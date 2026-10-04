package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachFeedbackInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import org.springframework.stereotype.Component
import kotlin.math.roundToInt

/** Scores the immutable, reviewed transcript and writes its single report through the job effect fence. */
@Component
class VoiceReportJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver,
    private val voiceSessionService: VoiceSessionService,
    private val objectMapper: ObjectMapper,
) : JobHandler<VoiceReportPayload> {
    override fun type() = JobType.VOICE_REPORT
    override fun payloadType() = VoiceReportPayload::class.java

    override fun handle(payload: VoiceReportPayload, context: JobExecutionContext): JsonNode {
        val job = context.job()
        require(job.jobType == JobType.VOICE_REPORT && job.resourceType == VoiceReportPayload.RESOURCE &&
            job.resourceId == payload.voiceSessionId) { "Voice report payload does not match its job resource" }

        val session = voiceSessionService.getForReport(context.userId(), payload.voiceSessionId)
        require(session.status == VoiceSessionStatus.SAVED && session.transcript != null && session.savedAt != null) {
            "Voice report session ${payload.voiceSessionId} is not saved"
        }
        require(session.resumeId == payload.resumeId && session.targetJobId == payload.targetJobId) {
            "Voice report payload references do not match the saved session"
        }
        require(session.reportJobId == job.id) { "Voice report job ${job.id} is no longer current for session ${session.id}" }

        val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.targetJobId)
        val answersByQuestion = session.transcript.answers.associateBy { it.questionId }
        require(answersByQuestion.size == session.transcript.answers.size &&
            answersByQuestion.keys.all { id -> session.questions.any { it.id == id } }) {
            "Saved voice transcript does not match its question snapshot"
        }

        context.stage(JobStage.SCORING_ANSWER)
        val scored = mutableMapOf<java.util.UUID, VoiceAnswerReport>()
        session.questions.forEach { question ->
            val answer = answersByQuestion[question.id]?.takeIf { it.answerText.isNotBlank() } ?: return@forEach
            val checkpointField = voiceAnswerCheckpointField(question.id)
            val savedCheckpoint = runCatching {
                context.checkpoint(checkpointField, VoiceAnswerScoreCheckpoint::class.java)
            }.getOrNull()?.takeIf {
                validVoiceAnswerCheckpoint(it, session, question, answer, objectMapper)
            }
            val feedback = savedCheckpoint?.feedback ?: coachService.scorePracticeAnswer(
                CoachFeedbackInput(
                    documents.resume(), documents.jobDescription(), question.text, question.category,
                    question.expectedSignals, answer.answerText, answer.incomplete,
                ),
            ).also { result ->
                require(validVoiceFeedback(result)) { "Answer scorer returned an invalid score for question ${question.id}" }
                context.saveCheckpoint(
                    checkpointField,
                    VoiceAnswerScoreCheckpoint(session.id, question.id, voiceAnswerInputDigest(objectMapper, session, question, answer), result),
                )
            }
            scored[question.id] = feedback.asReport(question.id, answer.incomplete)
        }
        check(scored.isNotEmpty()) { "Saved voice session ${session.id} has no nonempty answers to score" }

        val answers = session.questions.mapNotNull { scored[it.id] }
        val order = session.questions.withIndex().associate { it.value.id to it.index }
        val report = VoiceSessionReport(
            selectedCount = session.questions.size,
            answeredCount = answers.size,
            overallScore = answers.map { it.score }.average().roundToInt(),
            answers = answers,
            weakestQuestionIds = answers.sortedWith(compareBy<VoiceAnswerReport> { it.score }.thenBy { order.getValue(it.questionId) })
                .take(3).map { it.questionId },
            unansweredQuestionIds = session.questions.map { it.id }.filterNot(scored::containsKey),
        )
        context.materializeVoiceReport(session.id, report)
        return context.toJson(report)
    }

    private fun dev.jiaming.ai_interview.practice.AnswerFeedbackResult.asReport(
        questionId: java.util.UUID,
        incomplete: Boolean,
    ) = VoiceAnswerReport(
        questionId, score, summary, nextStep, strengths, gaps, betterAnswerOutline, followUpQuestion, incomplete,
    )
}
