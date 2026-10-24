package dev.jiaming.ai_interview.gemini

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.ai.AiHttpTransport
import dev.jiaming.ai_interview.ai.AiHttpTransportResponse
import dev.jiaming.ai_interview.ai.AiProviderErrorCode
import dev.jiaming.ai_interview.ai.AiProviderException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class GeminiClientTests {
    @Test fun sendsApiKeyInHeaderAndNotInUrl() {
        val captured = AtomicReference<HttpRequest>(); val client = client { request ->
            captured.set(request); AiHttpTransportResponse(200, response("STOP", "{\"ok\":true}")) }
        assertThat(client.generateJson("prompt")).isEqualTo("{\"ok\":true}")
        assertThat(captured.get().headers().firstValue("x-goog-api-key")).contains(API_KEY)
        assertThat(captured.get().uri().toString()).doesNotContain(API_KEY).doesNotContain("?key=")
    }

    @Test fun usesGeminiThreeThinkingLevelWithoutDeprecatedTemperature() {
        val captured = AtomicReference<HttpRequest>()
        val client = GeminiClient(ObjectMapper(), { request -> captured.set(request); AiHttpTransportResponse(200, response("STOP", "{\"ok\":true}")) }, SimpleMeterRegistry(), "https://example.test/v1beta/models/", API_KEY, "gemini-3.6-flash", 0.2, Duration.ofSeconds(5), 2_048, 0, "medium")
        client.generateJson("prompt")
        assertThat(captured.get().uri().toString()).contains("gemini-3.6-flash")
        assertThat(requestBody(captured.get())).contains("\"thinkingLevel\":\"medium\"").doesNotContain("temperature").doesNotContain("thinkingBudget")
    }

    @Test fun acceptsOnlyStopAndClassifiesFinishReasonsWithoutRetry() {
        assertFinishReason("SAFETY", AiProviderErrorCode.SAFETY); assertFinishReason("RECITATION", AiProviderErrorCode.RECITATION)
        assertFinishReason("MAX_TOKENS", AiProviderErrorCode.MAX_TOKENS); assertFinishReason("OTHER", AiProviderErrorCode.UPSTREAM_ERROR)
    }

    @Test fun emptyCandidateIsRetryableAndDoesNotExposeRawResponse() {
        val marker = "private-resume-output"; val client = client { AiHttpTransportResponse(200, "{\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"parts\":[]}}],\"debug\":\"$marker\"}") }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e ->
            assertThat(e.code()).isEqualTo(AiProviderErrorCode.EMPTY_RESPONSE); assertThat(e.retryable()).isTrue(); assertThat(e.message).doesNotContain(marker).doesNotContain(API_KEY) }
    }

    @Test fun safetyPromptBlockWithoutCandidateIsNotRetryable() {
        val client = client { AiHttpTransportResponse(200, "{\"promptFeedback\":{\"blockReason\":\"SAFETY\"}}") }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.SAFETY); assertThat(e.retryable()).isFalse() }
    }

    @Test fun maxTokensUsesOneTransportCallAndIsNotRetryable() {
        val calls = AtomicInteger(); val client = client { calls.incrementAndGet(); AiHttpTransportResponse(200, response("MAX_TOKENS", "partial")) }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.MAX_TOKENS); assertThat(e.retryable()).isFalse() }; assertThat(calls).hasValue(1)
    }

    @Test fun rateLimitUsesFixedRetryableCodeWithoutResponseBody() {
        val marker = "upstream-body-marker"; val client = client { AiHttpTransportResponse(429, marker) }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.RATE_LIMITED); assertThat(e.retryable()).isTrue(); assertThat(e.message).doesNotContain(marker).doesNotContain(API_KEY) }
    }

    @Test fun blankApiKeyIsNotConfiguredAndSkipsTheTransport() {
        val calls = AtomicInteger(); val client = clientWithKey("") { calls.incrementAndGet(); AiHttpTransportResponse(200, response("STOP", "{}")) }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.NOT_CONFIGURED); assertThat(e.retryable()).isFalse() }; assertThat(calls).hasValue(0)
    }

    @Test fun serverAndTimeoutStatusesAreRetryableUpstreamErrors() { for (status in listOf(408, 500, 503)) assertError(status, true) }
    @Test fun clientErrorsOtherThan429AreNotRetryable() { for (status in listOf(400, 403, 404)) assertError(status, false) }

    @Test fun transportTimeoutIsRetryable() {
        val client = client { throw HttpTimeoutException("timed out") }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.TIMEOUT); assertThat(e.retryable()).isTrue() }
    }

    @Test fun networkErrorIsRetryable() {
        val client = client { throw IOException("connection reset") }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.UPSTREAM_ERROR); assertThat(e.retryable()).isTrue() }
    }

    @Test fun unreadableResponseBodyIsRetryable() {
        val client = client { AiHttpTransportResponse(200, "<<not json>>") }
        assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.UPSTREAM_ERROR); assertThat(e.retryable()).isTrue() }
    }

    @Test fun stripsJsonCodeFenceFromModelOutput() { assertThat(client { AiHttpTransportResponse(200, stopResponse("```json\n{\"ok\":true}\n```")) }.generateJson("prompt")).isEqualTo("{\"ok\":true}") }
    @Test fun concatenatesMultipleTextParts() { assertThat(client { AiHttpTransportResponse(200, stopResponse("{\"a\":1,", "\"b\":2}")) }.generateJson("prompt")).isEqualTo("{\"a\":1,\n\"b\":2}") }

    private fun assertError(status: Int, retryable: Boolean) { val client = client { AiHttpTransportResponse(status, "body") }; assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(AiProviderErrorCode.UPSTREAM_ERROR); assertThat(e.retryable()).isEqualTo(retryable) } }
    private fun assertFinishReason(reason: String, code: String) { val client = client { AiHttpTransportResponse(200, response(reason, "raw-marker")) }; assertThatThrownBy { client.generateJson("prompt") }.isInstanceOfSatisfying(AiProviderException::class.java) { e -> assertThat(e.code()).isEqualTo(code); assertThat(e.retryable()).isFalse(); assertThat(e.message).doesNotContain("raw-marker").doesNotContain(API_KEY) } }
    private fun client(transport: AiHttpTransport) = clientWithKey(API_KEY, transport)
    private fun clientWithKey(apiKey: String, transport: AiHttpTransport) = GeminiClient(ObjectMapper(), transport, SimpleMeterRegistry(), "https://example.test/v1beta/models/", apiKey, "gemini-2.5-flash", 0.2, Duration.ofSeconds(5), 2_048, 0)

    private fun requestBody(request: HttpRequest): String {
        val body = StringBuilder(); val completed = CompletableFuture<Void>()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) { body.append(StandardCharsets.UTF_8.decode(item)) }
            override fun onError(throwable: Throwable) { completed.completeExceptionally(throwable) }
            override fun onComplete() { completed.complete(null) }
        }); completed.join(); return body.toString()
    }
    private fun response(reason: String, text: String) = "{\"candidates\":[{\"finishReason\":\"$reason\",\"content\":{\"parts\":[{\"text\":\"${text.replace("\"", "\\\"")}\"}]}}]}"
    private fun stopResponse(vararg texts: String): String = try {
        val parts = texts.map { mapOf("text" to it) }
        ObjectMapper().writeValueAsString(
            mapOf("candidates" to listOf(
                mapOf("finishReason" to "STOP", "content" to mapOf("parts" to parts))
            ))
        )
    } catch (e: JsonProcessingException) { throw IllegalStateException(e) }
    private companion object { const val API_KEY = "secret-key-marker" }
}
