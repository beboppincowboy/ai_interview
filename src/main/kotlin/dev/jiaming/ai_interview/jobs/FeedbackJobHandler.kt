package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachFeedbackInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.practice.PracticeService

@Component
class FeedbackJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver,
    private val practiceService: PracticeService
) : JobHandler<AttemptFeedbackPayload> {
    override fun type() = JobType.ANSWER_FEEDBACK
    override fun payloadType() = AttemptFeedbackPayload::class.java

    override fun handle(payload: AttemptFeedbackPayload, context: JobExecutionContext): JsonNode {
        context.stage(JobStage.SCORING_ANSWER)
        val feedback = context.rootCheckpoint(AnswerFeedbackResult::class.java, "score") ?: run {
            val attempt = practiceService.attemptForScoring(context.userId(), payload.attemptId)
                ?: throw IllegalStateException("Attempt ${payload.attemptId} was not found for its job owner")
            val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.targetJobId)
            coachService.scorePracticeAnswer(CoachFeedbackInput(documents.resume(), documents.targetJob(),
                attempt.questionText, attempt.category, attempt.expectedSignals, attempt.text))
                .also { context.saveRootCheckpoint(it, "answer-feedback") }
        }
        context.materializeAttemptFeedback(payload.attemptId, feedback)
        return context.toJson(feedback)
    }
}
