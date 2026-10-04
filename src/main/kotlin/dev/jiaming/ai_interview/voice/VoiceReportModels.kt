package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.sha256Hex
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import java.util.UUID

/** One scored answer. The transcript itself stays on the owning session row. */
data class VoiceAnswerReport(
    val questionId: UUID,
    val score: Int,
    val summary: String,
    val nextStep: String?,
    val strengths: List<String>,
    val gaps: List<String>,
    val betterAnswerOutline: List<String>,
    val followUpQuestion: String?,
    val incomplete: Boolean,
)

/** The completed, content-only report for one saved spoken interview. */
data class VoiceSessionReport(
    val selectedCount: Int,
    val answeredCount: Int,
    val overallScore: Int,
    val answers: List<VoiceAnswerReport>,
    val weakestQuestionIds: List<UUID>,
    val unansweredQuestionIds: List<UUID>,
)

/** Durable job checkpoint; [inputDigest] prevents reuse for a changed or unrelated saved answer. */
internal data class VoiceAnswerScoreCheckpoint(
    val voiceSessionId: UUID,
    val questionId: UUID,
    val inputDigest: String,
    val feedback: AnswerFeedbackResult,
)

internal fun voiceAnswerInputDigest(
    objectMapper: ObjectMapper,
    session: VoiceSessionView,
    question: VoiceQuestion,
    answer: VoiceAnswer,
): String {
    val encoded = try {
        objectMapper.writeValueAsString(
            listOf(session.id, question.id, question.text, question.category, question.expectedSignals, answer.answerText, answer.incomplete),
        )
    } catch (exception: JsonProcessingException) {
        throw IllegalStateException("Could not encode voice answer checkpoint input", exception)
    }
    return sha256Hex(encoded)
}

internal fun validVoiceFeedback(feedback: AnswerFeedbackResult): Boolean = feedback.score in 0..100 && feedback.summary.isNotBlank()

internal fun validVoiceAnswerCheckpoint(
    checkpoint: VoiceAnswerScoreCheckpoint,
    session: VoiceSessionView,
    question: VoiceQuestion,
    answer: VoiceAnswer,
    objectMapper: ObjectMapper,
): Boolean = checkpoint.voiceSessionId == session.id && checkpoint.questionId == question.id &&
    checkpoint.inputDigest == voiceAnswerInputDigest(objectMapper, session, question, answer) && validVoiceFeedback(checkpoint.feedback)
