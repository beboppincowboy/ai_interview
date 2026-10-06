package dev.jiaming.ai_interview.openai

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.StructuredGenerationClient
import dev.jiaming.ai_interview.gemini.GeminiClient
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.gemini.GeminiTransportResponse
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.io.IOException
import java.net.http.HttpRequest
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Flow
import java.util.concurrent.atomic.AtomicReference

class OpenAiClientTests {
    @Test
    fun sendsTheBearerKeyModelAndJsonModeThenReturnsTheMessage() {
        val captured = AtomicReference<HttpRequest>()
        val client = client { request -> captured.set(request); GeminiTransportResponse(200, choice("stop", """{"ok":true}""")) }

        assertThat(client.generateJson("Return JSON.")).isEqualTo("""{"ok":true}""")

        val request = captured.get()
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer sk-test")
        assertThat(request.uri().toString()).isEqualTo("https://api.openai.test/v1/chat/completions").doesNotContain("sk-test")
        val body = ObjectMapper().readTree(body(request))
        assertThat(body.path("model").asText()).isEqualTo("gpt-4.1-mini")
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object")
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("Return JSON.")
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(2048)
        assertThat(body.path("temperature").asDouble()).isEqualTo(0.2)
        assertThat(body.has("reasoning_effort")).isFalse()
    }

    @Test
    fun aConfiguredReasoningModelGetsItsEffortAndNoTemperature() {
        val captured = AtomicReference<HttpRequest>()
        val client = OpenAiClient(
            ObjectMapper(), { request -> captured.set(request); GeminiTransportResponse(200, choice("stop", "{}")) },
            SimpleMeterRegistry(), ENDPOINT, "sk-test", 0.2, Duration.ofSeconds(5), 2048, "gpt-6-luna", "none"
        )

        client.generateJson("Return JSON.")

        val body = ObjectMapper().readTree(body(captured.get()))
        assertThat(body.path("model").asText()).isEqualTo("gpt-6-luna")
        assertThat(body.path("reasoning_effort").asText()).isEqualTo("none")
        assertThat(body.has("temperature")).isFalse()
    }

    @Test
    fun aPerMinuteLimitRetriesButAnExhaustedBalanceDoesNot() {
        assertFailure(429, """{"error":{"code":"rate_limit_exceeded"}}""", GeminiErrorCode.RATE_LIMITED, retryable = true)
        assertFailure(429, """{"error":{"code":"insufficient_quota"}}""", GeminiErrorCode.RATE_LIMITED, retryable = false)
        assertFailure(503, "{}", GeminiErrorCode.UPSTREAM_ERROR, retryable = true)
        assertFailure(401, """{"error":{"code":"invalid_api_key"}}""", GeminiErrorCode.UPSTREAM_ERROR, retryable = false)
    }

    @Test
    fun truncatedRefusedAndEmptyResponsesMapToTheSharedCodes() {
        assertResponse(choice("length", "{"), GeminiErrorCode.MAX_TOKENS)
        assertResponse(choice("content_filter", ""), GeminiErrorCode.SAFETY)
        assertResponse("""{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"I can't help with that."}}]}""", GeminiErrorCode.SAFETY)
        assertResponse(choice("stop", "  "), GeminiErrorCode.EMPTY_RESPONSE)
        assertResponse("""{"choices":[]}""", GeminiErrorCode.EMPTY_RESPONSE)
    }

    @Test
    fun transportFailuresAndUnreadableBodiesAreRetryable() {
        assertTransportFailure(GeminiErrorCode.TIMEOUT) { throw HttpTimeoutException("slow") }
        assertTransportFailure(GeminiErrorCode.UPSTREAM_ERROR) { throw IOException("connection reset") }
        assertTransportFailure(GeminiErrorCode.UPSTREAM_ERROR) { GeminiTransportResponse(200, "not json") }
        assertTransportFailure(GeminiErrorCode.TIMEOUT) { throw InterruptedException() }
        assertThat(Thread.interrupted()).isTrue()
    }

    @Test
    fun aMissingKeyFailsWithoutCallingOpenAi() {
        val client = OpenAiClient(ObjectMapper(), { error("must not send") }, SimpleMeterRegistry(), ENDPOINT, "", 0.2, Duration.ofSeconds(5), 2048)
        assertThatThrownBy { client.generateJson("prompt") }
            .isInstanceOfSatisfying(GeminiException::class.java) { assertThat(it.code()).isEqualTo(GeminiErrorCode.NOT_CONFIGURED) }
    }

    @Test
    fun theProviderSettingPicksExactlyOneChatClient() {
        val runner = ApplicationContextRunner()
            .withBean(ObjectMapper::class.java, { ObjectMapper() })
            .withBean(MeterRegistry::class.java, { SimpleMeterRegistry() })
            .withUserConfiguration(GeminiClient::class.java, OpenAiClient::class.java)

        runner.run { context -> assertThat(context.getBean(StructuredGenerationClient::class.java)).isInstanceOf(GeminiClient::class.java) }
        runner.withPropertyValues("app.ai.chat-provider=openai").run { context ->
            assertThat(context.getBean(StructuredGenerationClient::class.java)).isInstanceOf(OpenAiClient::class.java)
        }
    }

    private fun assertFailure(status: Int, body: String, code: String, retryable: Boolean) {
        assertThatThrownBy { client { GeminiTransportResponse(status, body) }.generateJson("prompt") }
            .isInstanceOfSatisfying(GeminiException::class.java) {
                assertThat(it.code()).isEqualTo(code)
                assertThat(it.retryable()).isEqualTo(retryable)
            }
    }

    private fun assertTransportFailure(code: String, transport: (HttpRequest) -> GeminiTransportResponse) {
        assertThatThrownBy { client(transport).generateJson("prompt") }
            .isInstanceOfSatisfying(GeminiException::class.java) {
                assertThat(it.code()).isEqualTo(code)
                assertThat(it.retryable()).isTrue()
            }
    }

    private fun assertResponse(body: String, code: String) {
        assertThatThrownBy { client { GeminiTransportResponse(200, body) }.generateJson("prompt") }
            .isInstanceOfSatisfying(GeminiException::class.java) { assertThat(it.code()).isEqualTo(code) }
    }

    private fun client(transport: (HttpRequest) -> GeminiTransportResponse) =
        OpenAiClient(ObjectMapper(), transport, SimpleMeterRegistry(), ENDPOINT, "sk-test", 0.2, Duration.ofSeconds(5), 2048)

    private fun choice(finishReason: String, content: String) = ObjectMapper().writeValueAsString(
        mapOf("choices" to listOf(mapOf("finish_reason" to finishReason, "message" to mapOf("role" to "assistant", "content" to content))))
    )

    private fun body(request: HttpRequest): String {
        val body = StringBuilder(); val completed = CompletableFuture<Void>()
        request.bodyPublisher().orElseThrow().subscribe(object : Flow.Subscriber<ByteBuffer> {
            override fun onSubscribe(subscription: Flow.Subscription) = subscription.request(Long.MAX_VALUE)
            override fun onNext(item: ByteBuffer) { body.append(StandardCharsets.UTF_8.decode(item)) }
            override fun onError(throwable: Throwable) { completed.completeExceptionally(throwable) }
            override fun onComplete() { completed.complete(null) }
        })
        completed.join()
        return body.toString()
    }

    private companion object {
        const val ENDPOINT = "https://api.openai.test/v1/chat/completions"
    }
}
