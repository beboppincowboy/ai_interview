package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import java.util.Optional
import org.springframework.stereotype.Component

@Component
class PracticeQuestionsJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver,
    private val practiceService: PracticeService,
) : JobHandler<PracticeQuestionsPayload> {
    override fun type() = JobType.PRACTICE_QUESTIONS
    override fun payloadType() = PracticeQuestionsPayload::class.java

    override fun handle(payload: PracticeQuestionsPayload, context: JobExecutionContext): JsonNode {
        context.stage(JobStage.GENERATING_QUESTIONS)
        val drafts = context.rootCheckpoint(PracticeQuestionDrafts::class.java, "drafts") ?: run {
            val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.targetJobId)
            val targetJob = documents.targetJob().orElseThrow {
                IllegalStateException("Practice set ${payload.practiceSetId} has no target job description")
            }
            coachService.generatePracticeQuestions(CoachAnalysisInput(documents.resume(), Optional.of(targetJob)))
                .also { context.saveRootCheckpoint(it, "practice-questions") }
        }
        context.materializePracticeQuestions(payload.practiceSetId, drafts.drafts)
        return context.toJson(PracticeQuestionsResult(practiceService.questions(context.userId(), payload.practiceSetId)))
    }
}
