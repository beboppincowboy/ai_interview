package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import dev.jiaming.ai_interview.common.ApiErrorResponse
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.RequestValidation
import java.util.UUID
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/voice-sessions")
class VoiceController(
    private val voiceSessionService: VoiceSessionService,
    private val tokenClient: GeminiLiveTokenClient,
    private val properties: VoiceProperties,
) {
    @PostMapping
    fun create(@RequestBody request: CreateVoiceSessionRequest): ResponseEntity<VoiceSessionView> {
        requireEnabled()
        val practiceSetId = request.practiceSetId ?: throw RequestValidation.invalid("practiceSetId is required")
        return ResponseEntity.status(HttpStatus.CREATED).body(voiceSessionService.create(practiceSetId))
    }

    @GetMapping("/{sessionId}")
    fun get(@PathVariable sessionId: UUID): VoiceSessionView = voiceSessionService.get(sessionId)

    /** Reservations commit before the provider call, so timeouts and upstream failures spend one bounded slot. */
    @PostMapping("/{sessionId}/tokens")
    fun mint(
        @PathVariable sessionId: UUID,
        @RequestBody request: VoiceTokenRequest,
    ): ResponseEntity<Any> = try {
        requireEnabled()
        val questionId = request.questionId ?: throw RequestValidation.invalid("questionId is required")
        val reservation = voiceSessionService.reserveToken(sessionId, questionId)
        ResponseEntity.ok().cacheControl(CacheControl.noStore()).body<Any>(
            tokenClient.mint(reservation.question, reservation.runDeadline),
        )
    } catch (exception: ApiRequestException) {
        ResponseEntity.status(exception.status())
            .cacheControl(CacheControl.noStore())
            .body<Any>(ApiErrorResponse(exception.code(), exception.message))
    }

    @PostMapping("/{sessionId}/save")
    fun save(
        @PathVariable sessionId: UUID,
        @RequestBody request: SaveVoiceSessionRequest,
    ): VoiceSaveResult {
        val answers = request.answers ?: throw RequestValidation.invalid("answers is required")
        return voiceSessionService.save(
            sessionId,
            VoiceTranscript(answers.map { VoiceAnswer(it.questionId, it.interviewerText, it.answerText, it.incomplete) }),
        )
    }

    @PostMapping("/{sessionId}/report/retry")
    fun retryReport(@PathVariable sessionId: UUID): ResponseEntity<VoiceSessionView> =
        ResponseEntity.accepted().body(voiceSessionService.retryReport(sessionId))

    @DeleteMapping("/{sessionId}/draft")
    fun discardDraft(@PathVariable sessionId: UUID): ResponseEntity<Void> {
        voiceSessionService.discard(sessionId)
        return ResponseEntity.noContent().build()
    }

    @DeleteMapping("/{sessionId}")
    fun delete(@PathVariable sessionId: UUID): ResponseEntity<Void> {
        voiceSessionService.delete(sessionId)
        return ResponseEntity.noContent().build()
    }

    private fun requireEnabled() {
        if (!properties.enabled) {
            throw ApiRequestException(HttpStatus.SERVICE_UNAVAILABLE, "VOICE_DISABLED", "Spoken interviews are disabled")
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class CreateVoiceSessionRequest(val practiceSetId: UUID?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class VoiceTokenRequest(val questionId: UUID?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SaveVoiceSessionRequest(val answers: List<SaveVoiceAnswerRequest>?)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SaveVoiceAnswerRequest(
    val questionId: UUID,
    val interviewerText: String,
    val answerText: String,
    val incomplete: Boolean = false,
)
