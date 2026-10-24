package dev.jiaming.ai_interview.gemini

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.ai.AiHttpTransport
import dev.jiaming.ai_interview.ai.AiProviderErrorCode
import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.ai.jdkTransport
import dev.jiaming.ai_interview.coach.StructuredGenerationClient
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Structured Gemini JSON client using the provider-neutral transport and `GEMINI_*` wire error codes. */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["chat-provider"], havingValue = "gemini", matchIfMissing = true)
class GeminiClient(
    private val objectMapper: ObjectMapper,
    private val transport: AiHttpTransport,
    private val meterRegistry: MeterRegistry,
    baseUrl: String,
    private val apiKey: String?,
    private val model: String,
    private val temperature: Double,
    private val requestTimeout: Duration,
    private val maxOutputTokens: Int,
    private val thinkingBudget: Int,
    private val thinkingLevel: String
) : StructuredGenerationClient {
    private val baseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

    @Autowired
    constructor(objectMapper: ObjectMapper, environment: Environment, meterRegistry: MeterRegistry) : this(
        objectMapper,
        jdkTransport(),
        meterRegistry,
        DEFAULT_BASE_URL,
        environment.getProperty("spring.ai.google.genai.api-key", ""),
        environment.getProperty("spring.ai.google.genai.chat.options.model", "gemini-3.6-flash"),
        environment.getProperty("spring.ai.google.genai.chat.options.temperature", Double::class.javaObjectType, 0.2),
        Duration.ofSeconds(environment.getProperty("app.gemini.request-timeout-seconds", Long::class.javaObjectType, 90L)),
        environment.getProperty("app.gemini.max-output-tokens", Int::class.javaObjectType, 4096),
        environment.getProperty("app.gemini.thinking-budget", Int::class.javaObjectType, 0),
        environment.getProperty("app.gemini.thinking-level", "medium")
    )

    internal constructor(
        objectMapper: ObjectMapper, transport: AiHttpTransport, meterRegistry: MeterRegistry,
        baseUrl: String, apiKey: String?, model: String, temperature: Double, requestTimeout: Duration,
        maxOutputTokens: Int, thinkingBudget: Int
    ) : this(objectMapper, transport, meterRegistry, baseUrl, apiKey, model, temperature, requestTimeout,
        maxOutputTokens, thinkingBudget, "medium")

    override fun generateJson(prompt: String): String {
        if (apiKey.isNullOrBlank()) throw failure(AiProviderErrorCode.NOT_CONFIGURED, "Gemini is not configured", null, false)
        val startedAt = System.nanoTime()
        log.info("gemini_request_start model={} timeoutSeconds={}", model, requestTimeout.seconds)
        try {
            val request = HttpRequest.newBuilder().uri(URI.create(endpoint())).timeout(requestTimeout)
                .header("Content-Type", "application/json").header("x-goog-api-key", apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt))).build()
            val response = transport.send(request)
            if (response.statusCode !in 200..299) {
                // Google's error status (e.g. UNAVAILABLE, RESOURCE_EXHAUSTED) only; never the prompt or the full body.
                val reason = runCatching { objectMapper.readTree(response.body).path("error").path("status").asText("") }.getOrDefault("")
                log.warn("gemini_request_rejected model={} status={} reason={}", model, response.statusCode, reason)
                throw httpFailure(response.statusCode)
            }
            val result = extractText(response.body)
            recordCall("success", startedAt)
            return result
        } catch (exception: HttpTimeoutException) {
            recordCall("timeout", startedAt)
            throw failure(AiProviderErrorCode.TIMEOUT, "Gemini request timed out", exception, true)
        } catch (exception: IOException) {
            recordCall("network", startedAt)
            throw failure(AiProviderErrorCode.UPSTREAM_ERROR, "Gemini could not be reached", exception, true)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            recordCall("interrupted", startedAt)
            throw failure(AiProviderErrorCode.TIMEOUT, "Gemini request was interrupted", exception, true)
        } catch (exception: AiProviderException) {
            recordCall(metricReason(exception.code), startedAt)
            throw exception
        }
    }

    private fun requestBody(prompt: String): String = objectMapper.writeValueAsString(mapOf(
        "contents" to listOf(mapOf("role" to "user", "parts" to listOf(mapOf("text" to prompt)))),
        "generationConfig" to generationConfig()
    ))

    private fun generationConfig(): Map<String, Any> = linkedMapOf<String, Any>(
        "responseMimeType" to "application/json", "maxOutputTokens" to maxOutputTokens
    ).apply {
        if (model.startsWith("gemini-3.")) put("thinkingConfig", mapOf("thinkingLevel" to thinkingLevel))
        else put("temperature", temperature)
        if (model.startsWith("gemini-2.5")) put("thinkingConfig", mapOf("thinkingBudget" to thinkingBudget))
    }

    private fun endpoint() = baseUrl + URLEncoder.encode(model, StandardCharsets.UTF_8) + ":generateContent"

    private fun extractText(responseBody: String): String {
        val root = try { objectMapper.readTree(responseBody) }
        catch (_: JsonProcessingException) { throw failure(AiProviderErrorCode.UPSTREAM_ERROR, "Gemini returned an unreadable response", null, true) }
        val candidate = root.path("candidates").path(0)
        if (candidate.isMissingNode) {
            val blockReason = root.path("promptFeedback").path("blockReason").asText("").trim().uppercase(Locale.ROOT)
            if (blockReason.isNotEmpty()) throw finishReasonFailure(blockReason)
            throw failure(AiProviderErrorCode.EMPTY_RESPONSE, "Gemini returned no candidate", null, true)
        }
        val finishReason = candidate.path("finishReason").asText("").trim().uppercase(Locale.ROOT)
        if (finishReason != "STOP") throw finishReasonFailure(finishReason)
        val parts = candidate.path("content").path("parts")
        val text = buildString {
            if (parts.isArray) for (part: JsonNode in parts) {
                val value = part.path("text").asText("")
                if (value.isNotBlank()) { if (isNotEmpty()) append('\n'); append(value) }
            }
        }
        if (text.isEmpty()) throw failure(AiProviderErrorCode.EMPTY_RESPONSE, "Gemini returned an empty candidate", null, true)
        return stripJsonFence(text)
    }

    private fun finishReasonFailure(reason: String) = when (reason) {
        "SAFETY" -> failure(AiProviderErrorCode.SAFETY, "Gemini blocked the response for safety", null, false)
        "RECITATION" -> failure(AiProviderErrorCode.RECITATION, "Gemini blocked the response for recitation", null, false)
        "MAX_TOKENS" -> failure(AiProviderErrorCode.MAX_TOKENS, "Gemini reached the output token limit", null, false)
        else -> failure(AiProviderErrorCode.UPSTREAM_ERROR, "Gemini ended with an unsupported finish reason", null, false)
    }

    private fun httpFailure(statusCode: Int): AiProviderException = if (statusCode == 429)
        AiProviderException(AiProviderErrorCode.RATE_LIMITED, "Gemini rate limit exceeded", statusCode, true)
    else AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "Gemini request failed", statusCode, statusCode == 408 || statusCode >= 500)

    private fun failure(code: String, message: String, cause: Throwable?, retryable: Boolean) =
        AiProviderException(code, message, cause, retryable)

    private fun recordCall(outcome: String, startedAt: Long) {
        val elapsed = elapsedMillis(startedAt)
        meterRegistry.counter("ai.gemini.calls", "outcome", outcome, "model", model).increment()
        meterRegistry.timer("ai.gemini.duration", "outcome", outcome, "model", model).record(Duration.ofMillis(elapsed))
        log.info("gemini_request_complete model={} outcome={} elapsedMs={}", model, outcome, elapsed)
    }

    private fun metricReason(code: String?) = code?.lowercase(Locale.ROOT)?.replace("gemini_", "") ?: "unknown"
    private fun stripJsonFence(value: String): String = value.trim().let {
        if (it.startsWith("```")) it.replaceFirst(Regex("^```(?:json)?\\s*"), "").replaceFirst(Regex("\\s*```$"), "").trim() else it
    }
    private fun elapsedMillis(startedAt: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

    companion object {
        private val log = LoggerFactory.getLogger(GeminiClient::class.java)
        private const val DEFAULT_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models/"
    }
}
