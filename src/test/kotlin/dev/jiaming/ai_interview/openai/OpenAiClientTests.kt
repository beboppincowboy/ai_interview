package dev.jiaming.ai_interview.openai

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.StructuredGenerationClient
import dev.jiaming.ai_interview.gemini.GeminiClient
import dev.jiaming.ai_interview.ai.AiProviderErrorCode
import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.ai.AiHttpTransportResponse
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
        val client = client { request -> captured.set(request); AiHttpTransportResponse(200, choice("stop", """{"ok":true}""")) }

        assertThat(client.generateJson("Return JSON.")).isEqualTo("""{"ok":true}""")

        val request = captured.get()
        assertThat(request.headers().firstValue("Authorization")).contains("Bearer sk-test")
        assertThat(request.uri().toString()).isEqualTo("https://api.openai.test/v1/chat/completions").doesNotContain("sk-test")
        val body = ObjectMapper().readTree(body(request))
        assertThat(body.path("model").asText()).isEqualTo("gpt-4.1-mini")
        assertThat(body.path("response_format").path("type").asText()).isEqualTo("json_object")
        assertThat(body.path("messages").path(0).path("content").asText()).isEqualTo("Return JSON.")
        assertThat(body.path("max_completion_tokens").asInt()).isEqualTo(2048)
    }

    @Test
    fun aPerMinuteLimitRetriesButAnExhaustedBalanceDoesNot() {
        assertFailure(429, """{"error":{"code":"rate_limit_exceeded"}}""", AiProviderErrorCode.RATE_LIMITED, retryable = true)
        assertFailure(429, """{"error":{"code":"insufficient_quota"}}""", AiProviderErrorCode.RATE_LIMITED, retryable = false)
        assertFailure(503, "{}", AiProviderErrorCode.UPSTREAM_ERROR, retryable = true)
        assertFailure(401, """{"error":{"code":"invalid_api_key"}}""", AiProviderErrorCode.UPSTREAM_ERROR, retryable = false)
    }

    @Test
    fun truncatedRefusedAndEmptyResponsesMapToTheSharedCodes() {
        assertResponse(choice("length", "{"), AiProviderErrorCode.MAX_TOKENS)
        assertResponse(choice("content_filter", ""), AiProviderErrorCode.SAFETY)
        assertResponse("""{"choices":[{"finish_reason":"stop","message":{"content":null,"refusal":"I can't help with that."}}]}""", AiProviderErrorCode.SAFETY)
        assertResponse(choice("stop", "  "), AiProviderErrorCode.EMPTY_RESPONSE)
        assertResponse("""{"choices":[]}""", AiProviderErrorCode.EMPTY_RESPONSE)
    }

    @Test
    fun transportFailuresAndUnreadableBodiesAreRetryable() {
        assertTransportFailure(AiProviderErrorCode.TIMEOUT) { throw HttpTimeoutException("slow") }
        assertTransportFailure(AiProviderErrorCode.UPSTREAM_ERROR) { throw IOException("connection reset") }
        assertTransportFailure(AiProviderErrorCode.UPSTREAM_ERROR) { AiHttpTransportResponse(200, "not json") }
        assertTransportFailure(AiProviderErrorCode.TIMEOUT) { throw InterruptedException() }
        assertThat(Thread.interrupted()).isTrue()
    }

    @Test
    fun aMissingKeyFailsWithoutCallingOpenAi() {
        val client = OpenAiClient(ObjectMapper(), { error("must not send") }, SimpleMeterRegistry(), ENDPOINT, "", 0.2, Duration.ofSeconds(5), 2048)
        assertThatThrownBy { client.generateJson("prompt") }
            .isInstanceOfSatisfying(AiProviderException::class.java) { assertThat(it.code()).isEqualTo(AiProviderErrorCode.NOT_CONFIGURED) }
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
        assertThatThrownBy { client { AiHttpTransportResponse(status, body) }.generateJson("prompt") }
            .isInstanceOfSatisfying(AiProviderException::class.java) {
                assertThat(it.code()).isEqualTo(code)
                assertThat(it.retryable()).isEqualTo(retryable)
            }
    }

    private fun assertTransportFailure(code: String, transport: (HttpRequest) -> AiHttpTransportResponse) {
        assertThatThrownBy { client(transport).generateJson("prompt") }
            .isInstanceOfSatisfying(AiProviderException::class.java) {
                assertThat(it.code()).isEqualTo(code)
                assertThat(it.retryable()).isTrue()
            }
    }

    private fun assertResponse(body: String, code: String) {
        assertThatThrownBy { client { AiHttpTransportResponse(200, body) }.generateJson("prompt") }
            .isInstanceOfSatisfying(AiProviderException::class.java) { assertThat(it.code()).isEqualTo(code) }
    }

    private fun client(transport: (HttpRequest) -> AiHttpTransportResponse) =
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
