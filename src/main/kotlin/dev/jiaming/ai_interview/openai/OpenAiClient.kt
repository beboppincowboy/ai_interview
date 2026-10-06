package dev.jiaming.ai_interview.openai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.StructuredGenerationClient
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.gemini.GeminiTransport
import dev.jiaming.ai_interview.gemini.jdkTransport
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.io.IOException
import java.net.URI
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * OpenAI chat behind the same JSON contract as Gemini, selected with AI_CHAT_PROVIDER=openai. OPENAI_MODEL picks the model
 * (gpt-4.1-mini by default). Reasoning models reject a custom temperature, so setting OPENAI_REASONING_EFFORT sends that
 * effort and leaves temperature out.
 * Failures reuse the GEMINI_* codes so retries, the repair attempt and the API's error codes behave exactly as with Gemini.
 * Embeddings stay on Gemini, so stored vectors keep their model and 1,024 dimensions.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["chat-provider"], havingValue = "openai")
class OpenAiClient(
    private val objectMapper: ObjectMapper,
    private val transport: GeminiTransport,
    private val meterRegistry: MeterRegistry,
    private val endpoint: String,
    private val apiKey: String?,
    private val temperature: Double,
    private val requestTimeout: Duration,
    private val maxOutputTokens: Int,
    private val model: String = DEFAULT_MODEL,
    private val reasoningEffort: String = ""
) : StructuredGenerationClient {
    @Autowired
    constructor(objectMapper: ObjectMapper, environment: Environment, meterRegistry: MeterRegistry) : this(
        objectMapper,
        jdkTransport(),
        meterRegistry,
        DEFAULT_ENDPOINT,
        environment.getProperty("app.openai.api-key", ""),
        environment.getProperty("app.openai.temperature", Double::class.javaObjectType, 0.2),
        Duration.ofSeconds(environment.getProperty("app.openai.request-timeout-seconds", Long::class.javaObjectType, 90L)),
        environment.getProperty("app.openai.max-output-tokens", Int::class.javaObjectType, 4096),
        environment.getProperty("app.openai.model", "").ifBlank { DEFAULT_MODEL },
        environment.getProperty("app.openai.reasoning-effort", "").trim()
    )

    override fun generateJson(prompt: String): String {
        if (apiKey.isNullOrBlank()) throw GeminiException(GeminiErrorCode.NOT_CONFIGURED, "OpenAI is not configured", false)
        val startedAt = System.nanoTime()
        try {
            val request = HttpRequest.newBuilder().uri(URI.create(endpoint)).timeout(requestTimeout)
                .header("Content-Type", "application/json").header("Authorization", "Bearer $apiKey")
                .POST(HttpRequest.BodyPublishers.ofString(requestBody(prompt))).build()
            val response = transport.send(request)
            if (response.statusCode !in 200..299) throw httpFailure(response.statusCode, response.body)
            return extractText(response.body).also { recordCall("success", startedAt) }
        } catch (exception: HttpTimeoutException) {
            recordCall("timeout", startedAt)
            throw GeminiException(GeminiErrorCode.TIMEOUT, "OpenAI request timed out", exception, true)
        } catch (exception: IOException) {
            recordCall("network", startedAt)
            throw GeminiException(GeminiErrorCode.UPSTREAM_ERROR, "OpenAI could not be reached", exception, true)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            recordCall("interrupted", startedAt)
            throw GeminiException(GeminiErrorCode.TIMEOUT, "OpenAI request was interrupted", exception, true)
        } catch (exception: GeminiException) {
            recordCall(exception.code.lowercase(Locale.ROOT).removePrefix("gemini_"), startedAt)
            throw exception
        }
    }

    // JSON mode makes the model return one JSON object; every prompt already asks for JSON, which this mode requires.
    private fun requestBody(prompt: String): String = objectMapper.writeValueAsString(linkedMapOf(
        "model" to model,
        "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        "response_format" to mapOf("type" to "json_object"),
        "max_completion_tokens" to maxOutputTokens
    ).apply { if (reasoningEffort.isEmpty()) put("temperature", temperature) else put("reasoning_effort", reasoningEffort) })

    private fun httpFailure(statusCode: Int, body: String): GeminiException {
        // OpenAI's error code only (e.g. rate_limit_exceeded, insufficient_quota); never the prompt or the full body.
        val reason = runCatching { objectMapper.readTree(body).path("error").path("code").asText("") }.getOrDefault("")
        log.warn("openai_request_rejected model={} status={} reason={}", model, statusCode, reason)
        // An exhausted balance does not recover on retry; a per-minute limit does.
        return if (statusCode == 429) GeminiException(GeminiErrorCode.RATE_LIMITED, "OpenAI rate limit exceeded", statusCode, reason != "insufficient_quota")
        else GeminiException(GeminiErrorCode.UPSTREAM_ERROR, "OpenAI request failed", statusCode, statusCode == 408 || statusCode >= 500)
    }

    private fun extractText(responseBody: String): String {
        val root = try { objectMapper.readTree(responseBody) }
        catch (_: JsonProcessingException) { throw GeminiException(GeminiErrorCode.UPSTREAM_ERROR, "OpenAI returned an unreadable response", true) }
        val choice = root.path("choices").path(0)
        if (choice.isMissingNode) throw GeminiException(GeminiErrorCode.EMPTY_RESPONSE, "OpenAI returned no choice", true)
        val message = choice.path("message")
        if (message.hasNonNull("refusal")) throw GeminiException(GeminiErrorCode.SAFETY, "OpenAI refused the request", false)
        when (choice.path("finish_reason").asText("")) {
            "stop" -> Unit
            "length" -> throw GeminiException(GeminiErrorCode.MAX_TOKENS, "OpenAI reached the output token limit", false)
            "content_filter" -> throw GeminiException(GeminiErrorCode.SAFETY, "OpenAI filtered the response", false)
            else -> throw GeminiException(GeminiErrorCode.UPSTREAM_ERROR, "OpenAI ended with an unsupported finish reason", false)
        }
        val text = message.path("content").asText("").trim()
        if (text.isEmpty()) throw GeminiException(GeminiErrorCode.EMPTY_RESPONSE, "OpenAI returned an empty response", true)
        return text
    }

    private fun recordCall(outcome: String, startedAt: Long) {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        meterRegistry.counter("ai.openai.calls", "outcome", outcome, "model", model).increment()
        meterRegistry.timer("ai.openai.duration", "outcome", outcome, "model", model).record(Duration.ofMillis(elapsed))
        log.info("openai_request_complete model={} outcome={} elapsedMs={}", model, outcome, elapsed)
    }

    private companion object {
        val log = LoggerFactory.getLogger(OpenAiClient::class.java)
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        const val DEFAULT_MODEL = "gpt-4.1-mini"
    }
}
