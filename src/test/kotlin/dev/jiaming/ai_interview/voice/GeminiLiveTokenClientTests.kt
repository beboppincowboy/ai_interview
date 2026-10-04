package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import dev.jiaming.ai_interview.common.ApiRequestException

class GeminiLiveTokenClientTests {
    @Test
    fun `mints one v1beta token with server locked Live setup and deadline bounded expiry`() {
        val requestBody = AtomicReference<String>()
        val requestKey = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                requestBody.set(exchange.requestBody.bufferedReader().readText())
                requestKey.set(exchange.requestHeaders.getFirst("x-goog-api-key"))
                val body = """{"name":"auth_tokens/ephemeral-secret"}""".toByteArray()
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val properties = VoiceProperties(true, null, null, null, "server-only-key")
            val client = GeminiLiveTokenClient(
                ObjectMapper(), properties, URI("http://127.0.0.1:${server.address.port}"),
            )
            val question = VoiceQuestion(UUID.randomUUID(), "Tell me about a difficult tradeoff.", "Judgment", listOf("decision"))
            val deadline = Instant.now().plusSeconds(120)

            val token = client.mint(question, deadline)

            assertThat(token.token).isEqualTo("auth_tokens/ephemeral-secret")
            assertThat(token.model).isEqualTo("gemini-3.8-live")
            assertThat(token.apiVersion).isEqualTo("v1beta")
            assertThat(token.expiresAt).isBeforeOrEqualTo(deadline)
            assertThat(token.newSessionExpiresAt).isBeforeOrEqualTo(deadline)
            assertThat(token.newSessionExpiresAt).isBeforeOrEqualTo(token.expiresAt)
            assertThat(requestKey.get()).isEqualTo("server-only-key")

            val body = ObjectMapper().readTree(requestBody.get())
            assertThat(body["uses"].asInt()).isEqualTo(1)
            assertThat(body["bidiGenerateContentSetup"]["model"].asText()).isEqualTo("models/gemini-3.8-live")
            assertThat(body["bidiGenerateContentSetup"]["generationConfig"]["responseModalities"].map { it.asText() })
                .containsExactly("AUDIO")
            assertThat(body["bidiGenerateContentSetup"]["inputAudioTranscription"].isEmpty).isTrue()
            assertThat(body["bidiGenerateContentSetup"]["outputAudioTranscription"].isEmpty).isTrue()
            assertThat(body["bidiGenerateContentSetup"]["realtimeInputConfig"]["automaticActivityDetection"]["silenceDurationMs"].asInt())
                .isEqualTo(4500)
            val instruction = body["bidiGenerateContentSetup"]["systemInstruction"]["parts"][0]["text"].asText()
            assertThat(instruction).contains(question.text, "Do not introduce another question", "claim to move on")
            assertThat(body["bidiGenerateContentSetup"].has("tools")).isFalse()
            assertThat(body.toString()).doesNotContain("server-only-key", "caller-selected-model", "caller-selected-tools")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `maps rate limits to a sanitized error and does not retry`() {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            respond(exchange, 429, "provider-private-marker")
        }
        try {
            val error = runCatching { client(server).mint(question(), Instant.now().plusSeconds(60)) }.exceptionOrNull()

            assertThat(error).isInstanceOf(ApiRequestException::class.java)
            val apiError = error as ApiRequestException
            assertThat(apiError.status().value()).isEqualTo(429)
            assertThat(apiError.code()).isEqualTo("VOICE_TOKEN_RATE_LIMITED")
            assertThat(apiError.message).doesNotContain("provider-private-marker")
            assertThat(requests.get()).isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `does not follow provider redirects`() {
        val mintRequests = AtomicInteger()
        val redirectRequests = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1beta/auth_tokens") { exchange ->
                mintRequests.incrementAndGet()
                exchange.responseHeaders.add("Location", "/redirect-target")
                respond(exchange, 302, "redirect-marker")
            }
            createContext("/redirect-target") { exchange ->
                redirectRequests.incrementAndGet()
                respond(exchange, 200, """{"name":"auth_tokens/should-not-be-used"}""")
            }
            start()
        }
        try {
            val error = runCatching { client(server).mint(question(), Instant.now().plusSeconds(60)) }.exceptionOrNull()

            assertThat(error).isInstanceOf(ApiRequestException::class.java)
            assertThat((error as ApiRequestException).code()).isEqualTo("VOICE_TOKEN_UNAVAILABLE")
            assertThat(mintRequests.get()).isEqualTo(1)
            assertThat(redirectRequests.get()).isZero()
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `times out provider provisioning after five seconds`() {
        val requests = AtomicInteger()
        val server = server { exchange ->
            requests.incrementAndGet()
            Thread.sleep(6_000)
            respond(exchange, 200, """{"name":"auth_tokens/too-late"}""")
        }
        try {
            val startedAt = System.nanoTime()
            val error = runCatching { client(server).mint(question(), Instant.now().plusSeconds(60)) }.exceptionOrNull()
            val elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000

            assertThat(error).isInstanceOf(ApiRequestException::class.java)
            assertThat((error as ApiRequestException).code()).isEqualTo("VOICE_TOKEN_TIMEOUT")
            assertThat(elapsedMillis).isBetween(4_500L, 5_800L)
            assertThat(requests.get()).isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }

    private fun client(server: HttpServer) = GeminiLiveTokenClient(
        ObjectMapper(), VoiceProperties(true, null, null, null, "server-only-key"), URI("http://127.0.0.1:${server.address.port}"),
    )

    private fun question() = VoiceQuestion(UUID.randomUUID(), "Tell me about a difficult tradeoff.", null, emptyList())

    private fun server(handler: (com.sun.net.httpserver.HttpExchange) -> Unit) = HttpServer
        .create(InetSocketAddress("127.0.0.1", 0), 0)
        .apply {
            createContext("/") { handler(it) }
            start()
        }

    private fun respond(exchange: com.sun.net.httpserver.HttpExchange, status: Int, bodyText: String) {
        val body = bodyText.toByteArray()
        exchange.sendResponseHeaders(status, body.size.toLong())
        exchange.responseBody.use { it.write(body) }
    }
}
