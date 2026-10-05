package dev.jiaming.ai_interview.common

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.containers.GenericContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.utility.DockerImageName

/**
 * Drives [RedisRequestGuard] against the Redis image Compose runs, so the reservation SETNX and the
 * store, renew and delete Lua scripts execute in Redis, and reservations expire on Redis's clock.
 */
@Testcontainers(disabledWithoutDocker = true)
class RedisRequestGuardIntegrationTests {
    // A fresh prefix per test keeps each test's keys apart on the shared container.
    private val prefix = "it:${UUID.randomUUID()}:"
    private val calls = AtomicInteger()
    private lateinit var connectionFactory: LettuceConnectionFactory
    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var guard: RedisRequestGuard

    @BeforeEach
    fun setUp() {
        connectionFactory = LettuceConnectionFactory(REDIS.host, REDIS.getMappedPort(6379)).apply {
            afterPropertiesSet()
            start()
        }
        redisTemplate = StringRedisTemplate(connectionFactory)
        guard = RedisRequestGuard(
            redisTemplate,
            RedisUsageProperties(
                prefix,
                RedisUsageProperties.RateLimit(true, 60, 2, 2),
                RedisUsageProperties.Idempotency(true, 86_400)
            ),
            JsonMapper.builder().findAndAddModules().build()
        )
        requestWithIdempotencyKey()
    }

    @AfterEach
    fun tearDown() {
        RequestContextHolder.resetRequestAttributes()
        connectionFactory.destroy()
    }

    @Test
    fun theSameKeyAndPayloadReplaysTheStoredResponseWithoutRunningTheWorkAgain() {
        val first = call { CachedResponse("first") }
        val replay = call { CachedResponse("second") }

        assertThat(replay).isEqualTo(first)
        assertThat(calls).hasValue(1)
        // The owner-checked store swapped the short reservation for the response, under the day-long TTL.
        val key = storedKey()
        assertThat(redisTemplate.opsForValue().get(key)).endsWith(""" {"value":"first"}""")
        assertThat(redisTemplate.getExpire(key, TimeUnit.SECONDS)).isBetween(86_000L, 86_400L)
    }

    @Test
    fun theSameKeyWhileTheFirstRequestRunsReturnsARetryable503() {
        val first = holdFirstRequest({ CachedResponse("first") }) { assertStillRunning() }

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(CachedResponse("first"))
        assertThat(call { CachedResponse("duplicate") }).isEqualTo(CachedResponse("first"))
        assertThat(calls).hasValue(1)
    }

    @Test
    fun theSameKeyWithADifferentPayloadIsRejected() {
        call(listOf("resume-a")) { CachedResponse("saved") }

        assertThatThrownBy { call(listOf("resume-b")) { CachedResponse("should-not-run") } }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.CONFLICT)
                assertThat(it.reason).contains("Idempotency-Key")
            }
        assertThat(calls).hasValue(1)
        assertThat(redisTemplate.opsForValue().get(storedKey())).endsWith(""" {"value":"saved"}""")
    }

    @Test
    fun aFailedFirstRequestReleasesItsKeySoARetryRunsTheWork() {
        assertThatThrownBy { call { throw IllegalStateException("job insert failed") } }
            .isInstanceOf(IllegalStateException::class.java)
        assertThat(redisTemplate.keys("$prefix*")).isEmpty()

        assertThat(call { CachedResponse("retried") }).isEqualTo(CachedResponse("retried"))
        assertThat(calls).hasValue(2)
    }

    @Test
    fun theHeartbeatKeepsALongRequestsReservationPastItsInitialTtl() {
        guard.inFlightTtl = Duration.ofSeconds(1)
        guard.heartbeatInterval = Duration.ofMillis(200)

        val first = holdFirstRequest({ CachedResponse("first") }) {
            Thread.sleep(2_500) // Without renewal, Redis would have expired the reservation twice over.
            assertStillRunning()
        }

        assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(CachedResponse("first"))
        assertThat(call { CachedResponse("unexpected") }).isEqualTo(CachedResponse("first"))
        assertThat(calls).hasValue(1)
    }

    @Test
    fun aStaleSuccessCannotOverwriteItsSuccessorsStoredResponse() = staleOwnerCannotChangeItsSuccessor(fail = false)

    @Test
    fun aStaleFailureCannotDeleteItsSuccessorsStoredResponse() = staleOwnerCannotChangeItsSuccessor(fail = true)

    private fun staleOwnerCannotChangeItsSuccessor(fail: Boolean) {
        guard.inFlightTtl = Duration.ofMillis(500)
        guard.heartbeatInterval = Duration.ofSeconds(30) // No renewal before Redis expires the reservation.

        val stale = holdFirstRequest({
            if (fail) throw IllegalStateException("stale failure")
            CachedResponse("stale")
        }) {
            val key = storedKey()
            await().atMost(Duration.ofSeconds(5)).until { redisTemplate.hasKey(key) == false }
            assertThat(call { CachedResponse("successor") }).isEqualTo(CachedResponse("successor"))
        }

        if (fail) {
            assertThatThrownBy { stale.get(5, TimeUnit.SECONDS) }.hasCauseInstanceOf(IllegalStateException::class.java)
        } else {
            assertThat(stale.get(5, TimeUnit.SECONDS)).isEqualTo(CachedResponse("stale"))
        }
        assertThat(call { CachedResponse("unexpected") }).isEqualTo(CachedResponse("successor"))
        assertThat(calls).hasValue(2)
    }

    @Test
    fun aRenewOnlyExtendsTheReservationItsCallerOwns() {
        val key = "${prefix}reservation"
        val renew = { owner: String ->
            redisTemplate.execute(RedisRequestGuard.RENEW_IF_RESERVED_SCRIPT, listOf(key), owner, "60000")
        }
        redisTemplate.opsForValue().set(key, "${RedisRequestGuard.RESERVATION_MARKER}successor", Duration.ofSeconds(5))

        assertThat(renew("${RedisRequestGuard.RESERVATION_MARKER}stale")).isEqualTo(0L)
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isBetween(1L, 5_000L)
        assertThat(renew("${RedisRequestGuard.RESERVATION_MARKER}successor")).isEqualTo(1L)
        assertThat(redisTemplate.getExpire(key, TimeUnit.MILLISECONDS)).isGreaterThan(5_000L)
    }

    private fun call(payload: Any = listOf("resume"), work: () -> CachedResponse): CachedResponse =
        guard.withIdempotentRetryCache("assessment", payload, CachedResponse::class.java) {
            calls.incrementAndGet()
            work()
        }

    /** Runs a first request on another thread, calls [whileRunning] once its work has started, then lets it finish. */
    private fun holdFirstRequest(outcome: () -> CachedResponse, whileRunning: () -> Unit): Future<CachedResponse> {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<CachedResponse> {
            requestWithIdempotencyKey()
            call {
                started.countDown()
                check(release.await(10, TimeUnit.SECONDS))
                outcome()
            }
        }
        try {
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue()
            whileRunning()
        } finally {
            release.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
        return first
    }

    private fun assertStillRunning() {
        assertThatThrownBy { call { CachedResponse("duplicate") } }
            .isInstanceOfSatisfying(ResponseStatusException::class.java) {
                assertThat(it.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
            }
    }

    private fun storedKey(): String = redisTemplate.keys("$prefix*").single()

    private fun requestWithIdempotencyKey() {
        val request = MockHttpServletRequest().apply {
            remoteAddr = "203.0.113.10"
            addHeader("Idempotency-Key", "retry-key")
        }
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    data class CachedResponse @JsonCreator constructor(@param:JsonProperty("value") val value: String)

    companion object {
        @Container
        @JvmField
        val REDIS = GenericContainer<Nothing>(DockerImageName.parse("redis:7.4-alpine")).apply { addExposedPort(6379) }
    }
}
