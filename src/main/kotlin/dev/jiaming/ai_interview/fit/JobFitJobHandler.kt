package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobFitPayload
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import org.springframework.stereotype.Component

@Component
class JobFitJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver,
) : JobHandler<JobFitPayload> {
    override fun type() = JobType.JOB_FIT
    override fun payloadType() = JobFitPayload::class.java

    override fun handle(payload: JobFitPayload, context: JobExecutionContext): JsonNode {
        context.stage(JobStage.MATCHING_JOB)
        var result = context.rootCheckpoint(JobFitResult::class.java, "fitScore")
        if (result == null) {
            val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.targetJobId)
            val targetJob = documents.targetJob().orElseThrow {
                IllegalStateException("Job fit ${payload.fitId} has no target job description")
            }
            result = coachService.assessJobFit(CoachAnalysisInput(documents.resume(), java.util.Optional.of(targetJob)))
            context.saveRootCheckpoint(result, "job-fit")
        }
        context.materializeJobFit(payload.fitId, result)
        return context.toJson(result)
    }
}
