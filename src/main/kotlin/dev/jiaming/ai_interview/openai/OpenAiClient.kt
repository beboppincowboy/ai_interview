package dev.jiaming.ai_interview.openai

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.StructuredGenerationClient
import dev.jiaming.ai_interview.ai.AiProviderErrorCode
import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.ai.AiHttpTransport
import dev.jiaming.ai_interview.ai.jdkTransport
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
 * OpenAI chat on gpt-4.1-mini only, behind the same JSON contract as Gemini, selected with AI_CHAT_PROVIDER=openai.
 * Failures reuse the GEMINI_* codes so retries, the repair attempt and the API's error codes behave exactly as with Gemini.
 * Embeddings stay on Gemini, so stored vectors keep their model and 1,024 dimensions.
 */
@Component
@ConditionalOnProperty(prefix = "app.ai", name = ["chat-provider"], havingValue = "openai")
class OpenAiClient(
    private val objectMapper: ObjectMapper,
    private val transport: AiHttpTransport,
    private val meterRegistry: MeterRegistry,
    private val endpoint: String,
    private val apiKey: String?,
    private val temperature: Double,
    private val requestTimeout: Duration,
    private val maxOutputTokens: Int
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
        environment.getProperty("app.openai.max-output-tokens", Int::class.javaObjectType, 4096)
    )

    override fun generateJson(prompt: String): String {
        if (apiKey.isNullOrBlank()) throw AiProviderException(AiProviderErrorCode.NOT_CONFIGURED, "OpenAI is not configured", false)
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
            throw AiProviderException(AiProviderErrorCode.TIMEOUT, "OpenAI request timed out", exception, true)
        } catch (exception: IOException) {
            recordCall("network", startedAt)
            throw AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "OpenAI could not be reached", exception, true)
        } catch (exception: InterruptedException) {
            Thread.currentThread().interrupt()
            recordCall("interrupted", startedAt)
            throw AiProviderException(AiProviderErrorCode.TIMEOUT, "OpenAI request was interrupted", exception, true)
        } catch (exception: AiProviderException) {
            recordCall(exception.code.lowercase(Locale.ROOT).removePrefix("gemini_"), startedAt)
            throw exception
        }
    }

    // JSON mode makes the model return one JSON object; every prompt already asks for JSON, which this mode requires.
    private fun requestBody(prompt: String): String = objectMapper.writeValueAsString(mapOf(
        "model" to MODEL,
        "messages" to listOf(mapOf("role" to "user", "content" to prompt)),
        "response_format" to mapOf("type" to "json_object"),
        "temperature" to temperature,
        "max_completion_tokens" to maxOutputTokens
    ))

    private fun httpFailure(statusCode: Int, body: String): AiProviderException {
        // OpenAI's error code only (e.g. rate_limit_exceeded, insufficient_quota); never the prompt or the full body.
        val reason = runCatching { objectMapper.readTree(body).path("error").path("code").asText("") }.getOrDefault("")
        log.warn("openai_request_rejected model={} status={} reason={}", MODEL, statusCode, reason)
        // An exhausted balance does not recover on retry; a per-minute limit does.
        return if (statusCode == 429) AiProviderException(AiProviderErrorCode.RATE_LIMITED, "OpenAI rate limit exceeded", statusCode, reason != "insufficient_quota")
        else AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "OpenAI request failed", statusCode, statusCode == 408 || statusCode >= 500)
    }

    private fun extractText(responseBody: String): String {
        val root = try { objectMapper.readTree(responseBody) }
        catch (_: JsonProcessingException) { throw AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "OpenAI returned an unreadable response", true) }
        val choice = root.path("choices").path(0)
        if (choice.isMissingNode) throw AiProviderException(AiProviderErrorCode.EMPTY_RESPONSE, "OpenAI returned no choice", true)
        val message = choice.path("message")
        if (message.hasNonNull("refusal")) throw AiProviderException(AiProviderErrorCode.SAFETY, "OpenAI refused the request", false)
        when (choice.path("finish_reason").asText("")) {
            "stop" -> Unit
            "length" -> throw AiProviderException(AiProviderErrorCode.MAX_TOKENS, "OpenAI reached the output token limit", false)
            "content_filter" -> throw AiProviderException(AiProviderErrorCode.SAFETY, "OpenAI filtered the response", false)
            else -> throw AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "OpenAI ended with an unsupported finish reason", false)
        }
        val text = message.path("content").asText("").trim()
        if (text.isEmpty()) throw AiProviderException(AiProviderErrorCode.EMPTY_RESPONSE, "OpenAI returned an empty response", true)
        return text
    }

    private fun recordCall(outcome: String, startedAt: Long) {
        val elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
        meterRegistry.counter("ai.openai.calls", "outcome", outcome, "model", MODEL).increment()
        meterRegistry.timer("ai.openai.duration", "outcome", outcome, "model", MODEL).record(Duration.ofMillis(elapsed))
        log.info("openai_request_complete model={} outcome={} elapsedMs={}", MODEL, outcome, elapsed)
    }

    private companion object {
        val log = LoggerFactory.getLogger(OpenAiClient::class.java)
        const val DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions"
        // Fixed, not configurable: the OpenAI account is lent on the condition that only this model runs on it.
        const val MODEL = "gpt-4.1-mini"
    }
}
