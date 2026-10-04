package dev.jiaming.ai_interview.common

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException

@Service
class RedisRequestGuard(
    private val redisTemplate: StringRedisTemplate,
    private val properties: RedisUsageProperties,
    private val objectMapper: ObjectMapper
) {
    internal var inFlightTtl: Duration = Duration.ofMinutes(2)
    internal var heartbeatInterval: Duration = Duration.ofSeconds(40)

    fun assertAiAllowed(action: String) = assertAllowed(action, properties.rateLimit.aiLimit)
    fun assertUploadAllowed() = assertAllowed("resume-upload", properties.rateLimit.uploadLimit)

    fun <T> withIdempotentRetryCache(
        action: String,
        requestFingerprintSource: Any?,
        responseType: Class<T>,
        work: Supplier<T>
    ): T {
        if (!properties.idempotency.enabled) return work.get()
        val idempotencyKey = idempotencyKey() ?: return work.get()
        val requestFingerprint = fingerprint(action, requestFingerprintSource)
        val redisKey = key("idem:%s:%s:%s".format(action, clientId(), sha256Hex(idempotencyKey)))
        val ttl = Duration.ofSeconds(properties.idempotency.ttlSeconds.toLong())
        val reservationValue = "$requestFingerprint $RESERVATION_MARKER${UUID.randomUUID()}"

        try {
            val cached = reserveOrReplay(action, redisKey, requestFingerprint, reservationValue, responseType)
            if (cached != null) return cached
        } catch (exception: ResponseStatusException) {
            throw exception
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_cache_unavailable action={} reason={}", action, exception.message)
            return work.get()
        }

        val heartbeat = startHeartbeat(action, redisKey, reservationValue)
        val response = try {
            work.get()
        } catch (failure: Throwable) {
            release(action, redisKey, reservationValue)
            throw failure
        } finally {
            heartbeat.cancel(false)
        }
        storeResponse(action, redisKey, reservationValue, requestFingerprint, response, ttl)
        return response
    }

    /** Like [withIdempotentRetryCache], but a replay also returns the first response's HTTP status. */
    fun <T : Any> withIdempotentHttpCache(
        action: String,
        requestFingerprintSource: Any?,
        bodyType: Class<T>,
        work: Supplier<ResponseEntity<T>>
    ): ResponseEntity<T> {
        var fresh: ResponseEntity<T>? = null
        val cached = withIdempotentRetryCache(action, requestFingerprintSource, CachedHttpResponse::class.java) {
            val response = work.get().also { fresh = it }
            CachedHttpResponse(response.statusCode.value(), objectMapper.valueToTree(response.body))
        }
        return fresh ?: ResponseEntity.status(cached.status).body(cached.body?.let { objectMapper.treeToValue(it, bodyType) })
    }

    private fun assertAllowed(action: String, limit: Int) {
        if (!properties.rateLimit.enabled) return
        val bucket = Instant.now().epochSecond / properties.rateLimit.windowSeconds
        val redisKey = key("rate:%s:%s:%d".format(action, clientId(), bucket))
        try {
            val count = redisTemplate.opsForValue().increment(redisKey)
            if (count != null && count == 1L) {
                redisTemplate.expire(redisKey, Duration.ofSeconds(properties.rateLimit.windowSeconds * 2L))
            }
            if (count != null && count > limit) {
                throw ResponseStatusException(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Too many $action requests. Try again in about ${properties.rateLimit.windowSeconds} seconds."
                )
            }
        } catch (exception: ResponseStatusException) {
            throw exception
        } catch (exception: RuntimeException) {
            log.warn("redis_rate_limit_unavailable action={} reason={}", action, exception.message)
        }
    }

    // The key holds the request fingerprint while the first request runs, then "<fingerprint> <response JSON>" once it
    // finishes, so a response is never stored without the fingerprint that replays it. A same-key replay returns that
    // response, or a retryable 503 while the first request still runs.
    private fun <T> reserveOrReplay(
        action: String,
        redisKey: String,
        requestFingerprint: String,
        reservationValue: String,
        responseType: Class<T>
    ): T? {
        repeat(RESERVE_ATTEMPTS) {
            if (redisTemplate.opsForValue().setIfAbsent(redisKey, reservationValue, inFlightTtl) == true) return null
            // Released between the two reads: try to reserve it again rather than run unreserved.
            val stored = redisTemplate.opsForValue().get(redisKey) ?: return@repeat
            if (stored.substringBefore(' ') != requestFingerprint) {
                throw ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different $action request.")
            }
            val responseJson = stored.substringAfter(' ', "")
            if (responseJson.isEmpty() || responseJson.startsWith(RESERVATION_MARKER)) throw stillRunning(action)
            try {
                return objectMapper.readValue(responseJson, responseType)
            } catch (exception: JsonProcessingException) {
                log.warn("redis_idempotency_cache_decode_failed action={} reason={}", action, exception.message)
                if (deleteIfValueMatches(redisKey, stored)) return@repeat
            }
        }
        throw stillRunning(action)
    }

    private fun stillRunning(action: String) = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE, "The first $action request with this Idempotency-Key is still running. Try again shortly."
    )

    // A failed request leaves nothing to replay, so its retry may run the work again.
    private fun release(action: String, redisKey: String, reservationValue: String) {
        try {
            deleteIfValueMatches(redisKey, reservationValue)
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_release_failed action={} reason={}", action, exception.message)
        }
    }

    // A failed store releases the key, so it never reports "still running" for a day; a retry runs the work again instead.
    private fun storeResponse(
        action: String,
        redisKey: String,
        reservationValue: String,
        fingerprint: String,
        response: Any?,
        ttl: Duration
    ) {
        try {
            val cached = "$fingerprint ${objectMapper.writeValueAsString(response)}"
            redisTemplate.execute(STORE_IF_RESERVED_SCRIPT, listOf(redisKey), reservationValue, cached, ttl.toMillis().toString())
        } catch (exception: JsonProcessingException) {
            log.warn("redis_idempotency_cache_encode_failed action={} reason={}", action, exception.message)
            release(action, redisKey, reservationValue)
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_cache_store_failed action={} reason={}", action, exception.message)
            release(action, redisKey, reservationValue)
        }
    }

    private fun startHeartbeat(action: String, redisKey: String, reservationValue: String): ScheduledFuture<*> =
        heartbeatExecutor.scheduleAtFixedRate(
            { renewReservation(action, redisKey, reservationValue) },
            heartbeatInterval.toMillis(), heartbeatInterval.toMillis(), TimeUnit.MILLISECONDS
        )

    private fun renewReservation(action: String, redisKey: String, reservationValue: String) {
        try {
            redisTemplate.execute(
                RENEW_IF_RESERVED_SCRIPT,
                listOf(redisKey),
                reservationValue,
                inFlightTtl.toMillis().toString()
            )
        } catch (exception: RuntimeException) {
            log.warn("redis_idempotency_renew_failed action={} reason={}", action, exception.message)
        }
    }

    private fun deleteIfValueMatches(redisKey: String, value: String): Boolean =
        redisTemplate.execute(DELETE_IF_MATCHES_SCRIPT, listOf(redisKey), value) == 1L

    private fun clientId(): String {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes
        val remoteAddress = attributes?.request?.remoteAddr
        return if (!remoteAddress.isNullOrBlank()) sanitize(remoteAddress) else "local"
    }

    private fun idempotencyKey(): String? {
        val attributes = RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes ?: return null
        val value = attributes.request.getHeader("Idempotency-Key")
        return value?.trim()?.takeIf(String::isNotBlank)
    }

    private fun sanitize(value: String) = value.replace(UNSAFE_KEY_CHARACTERS, "_")

    private fun fingerprint(action: String, requestFingerprintSource: Any?): String = try {
        sha256Hex(objectMapper.writeValueAsString(java.util.List.of(action, requestFingerprintSource)))
    } catch (exception: JsonProcessingException) {
        sha256Hex("$action:$requestFingerprintSource")
    }

    private fun key(suffix: String) = properties.keyPrefix + suffix

    @JvmRecord
    private data class CachedHttpResponse(val status: Int, val body: JsonNode?)

    private companion object {
        val log = LoggerFactory.getLogger(RedisRequestGuard::class.java)
        val UNSAFE_KEY_CHARACTERS = Regex("[^A-Za-z0-9._:-]")
        // ponytail: one thread serializes renewals; use a bounded pool if heartbeat lag approaches the two-minute lease.
        val heartbeatExecutor = ScheduledThreadPoolExecutor(1) { task ->
            Thread(task, "redis-idempotency-heartbeat").apply { isDaemon = true }
        }.apply { removeOnCancelPolicy = true }
        const val RESERVATION_MARKER = "owner:"
        val STORE_IF_RESERVED_SCRIPT = DefaultRedisScript<Long>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                redis.call('SET', KEYS[1], ARGV[2], 'PX', ARGV[3])
                return 1
            end
            return 0
        """.trimIndent(), Long::class.javaObjectType)
        val RENEW_IF_RESERVED_SCRIPT = DefaultRedisScript<Long>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            end
            return 0
        """.trimIndent(), Long::class.javaObjectType)
        val DELETE_IF_MATCHES_SCRIPT = DefaultRedisScript<Long>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
        """.trimIndent(), Long::class.javaObjectType)
        const val RESERVE_ATTEMPTS = 3
    }
}
