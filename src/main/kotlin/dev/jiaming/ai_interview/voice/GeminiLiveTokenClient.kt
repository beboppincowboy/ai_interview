package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.time.Instant
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component

/** Mints one constrained Gemini Live credential. Its response is returned to the caller and never persisted. */
@Component
class GeminiLiveTokenClient(
    private val objectMapper: ObjectMapper,
    private val properties: VoiceProperties,
    @Value("\${app.voice.token-base-url:https://generativelanguage.googleapis.com}") baseUrl: String,
) {
    private val endpoint = URI.create("${baseUrl.trimEnd('/')}/${properties.apiVersion}/auth_tokens")
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(REQUEST_TIMEOUT)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    fun mint(question: VoiceQuestion, runDeadline: Instant): VoiceTokenResponse {
        val now = Instant.now()
        if (!runDeadline.isAfter(now)) throw runExpired()

        val expiresAt = minOf(now.plus(TOKEN_LIFETIME), runDeadline)
        val newSessionExpiresAt = minOf(now.plus(SESSION_START_WINDOW), runDeadline)
        val tokenRequest = TokenRequest(
            uses = 1,
            expireTime = expiresAt.toString(),
            newSessionExpireTime = newSessionExpiresAt.toString(),
            bidiGenerateContentSetup = LiveSetup(
                model = "models/${properties.model}",
                generationConfig = GenerationConfig(listOf("AUDIO")),
                systemInstruction = SystemInstruction(listOf(TextPart(instruction(question)))),
                inputAudioTranscription = emptyMap(),
                outputAudioTranscription = emptyMap(),
                realtimeInputConfig = RealtimeInputConfig(AutomaticActivityDetection(properties.silenceMs)),
            ),
        )
        val request = try {
            HttpRequest.newBuilder(endpoint)
                .timeout(REQUEST_TIMEOUT)
                .header("content-type", "application/json")
                .header("x-goog-api-key", properties.apiKey)
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(tokenRequest)))
                .build()
        } catch (_: Exception) {
            throw unavailable()
        }

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (_: HttpTimeoutException) {
            throw ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_TOKEN_TIMEOUT", "The interviewer connection timed out")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw unavailable()
        } catch (_: IOException) {
            throw unavailable()
        } catch (_: IllegalArgumentException) {
            throw unavailable()
        }

        if (response.statusCode() == 429) {
            throw ApiRequestException(HttpStatus.TOO_MANY_REQUESTS, "VOICE_TOKEN_RATE_LIMITED", "The interviewer is temporarily at its usage limit")
        }
        if (response.statusCode() !in 200..299) throw unavailable()

        val token = try {
            objectMapper.readTree(response.body())?.path("name")?.takeIf { it.isTextual }?.textValue()
                ?.takeIf { it.startsWith("auth_tokens/") }
        } catch (_: Exception) {
            null
        } ?: throw unavailable()

        return VoiceTokenResponse(token, properties.model, properties.apiVersion, expiresAt, newSessionExpiresAt)
    }

    private fun instruction(question: VoiceQuestion) = """
        You are the spoken interviewer in a mock interview. Read this canonical question aloud exactly once:
        ${question.text}
        After asking it, only briefly acknowledge or clarify the candidate's answer to this same question.
        Do not introduce another question, announce advancement, move to another question, or claim to move on.
        Do not answer the question or coach the candidate.
    """.trimIndent()

    private fun unavailable() =
        ApiRequestException(HttpStatus.BAD_GATEWAY, "VOICE_TOKEN_UNAVAILABLE", "The interviewer could not be reached")

    private fun runExpired() =
        ApiRequestException(HttpStatus.CONFLICT, "VOICE_RUN_EXPIRED", "The interview run has ended")

    private data class TokenRequest(
        val uses: Int,
        val expireTime: String,
        val newSessionExpireTime: String,
        val bidiGenerateContentSetup: LiveSetup,
    )

    private data class LiveSetup(
        val model: String,
        val generationConfig: GenerationConfig,
        val systemInstruction: SystemInstruction,
        val inputAudioTranscription: Map<String, String>,
        val outputAudioTranscription: Map<String, String>,
        val realtimeInputConfig: RealtimeInputConfig,
    )

    private data class GenerationConfig(val responseModalities: List<String>)
    private data class SystemInstruction(val parts: List<TextPart>)
    private data class TextPart(val text: String)
    private data class RealtimeInputConfig(val automaticActivityDetection: AutomaticActivityDetection)
    private data class AutomaticActivityDetection(val silenceDurationMs: Int)

    companion object {
        val REQUEST_TIMEOUT: Duration = Duration.ofSeconds(5)
        private val TOKEN_LIFETIME: Duration = Duration.ofMinutes(10)
        private val SESSION_START_WINDOW: Duration = Duration.ofSeconds(60)
    }
}
