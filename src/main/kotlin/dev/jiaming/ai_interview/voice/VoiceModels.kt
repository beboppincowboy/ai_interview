package dev.jiaming.ai_interview.voice

import java.time.Instant
import java.util.UUID

/** A spoken interview run. A draft holds only its question snapshot; Save adds the reviewed transcript (KTD5). */
data class VoiceSessionView(
    val id: UUID,
    val practiceSetId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
    val status: VoiceSessionStatus,
    val questions: List<VoiceQuestion>,
    val transcript: VoiceTranscript?,
    val submissionJobId: UUID?,
    val reportJobId: UUID?,
    val createdAt: Instant,
    val runDeadline: Instant,
    val draftExpiresAt: Instant,
    val savedAt: Instant?,
)

/** EXPIRED is an unsaved draft past its expiry; cleanup removes it. */
enum class VoiceSessionStatus { DRAFT, EXPIRED, SAVED }

/** A copy of one AI practice question taken when the run started; [id] is the practice question's ID. */
data class VoiceQuestion(val id: UUID, val text: String, val category: String?, val expectedSignals: List<String>)

/** The reviewed transcript, one entry per question the run reached. */
data class VoiceTranscript(val answers: List<VoiceAnswer>)

/** What the interviewer said for [questionId] and the candidate's corrected answer; [incomplete] marks a cut-off capture. */
data class VoiceAnswer(val questionId: UUID, val interviewerText: String, val answerText: String, val incomplete: Boolean)

/** [replayed] is true when an identical Save was already committed and this call returned it. */
data class VoiceSaveResult(val session: VoiceSessionView, val replayed: Boolean)

/** The report job's input. Its resource is the session; the transcript stays on the session row (R10). */
@JvmRecord
data class VoiceReportPayload(
    val payloadVersion: Int,
    val voiceSessionId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) {
    constructor(voiceSessionId: UUID, resumeId: UUID, targetJobId: UUID) : this(CURRENT_VERSION, voiceSessionId, resumeId, targetJobId)

    companion object {
        const val CURRENT_VERSION = 1
        const val RESOURCE = "voice-session"
    }
}
