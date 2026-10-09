package dev.jiaming.ai_interview.suggestions

import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobType
import org.springframework.stereotype.Component

@Component
class ExperienceSuggestionsJobHandler(
    private val coachService: AiResumeCoachService,
    private val documentResolver: DocumentReferenceResolver,
    private val suggestionsService: SuggestionsService,
) : JobHandler<ExperienceSuggestionsPayload> {
    override fun type() = JobType.EXPERIENCE_SUGGESTIONS
    override fun payloadType() = ExperienceSuggestionsPayload::class.java

    override fun handle(payload: ExperienceSuggestionsPayload, context: JobExecutionContext): JsonNode {
        var run = context.rootCheckpoint(ExperienceSuggestionsRun::class.java, "sourceIds")
        if (run == null) {
            context.stage(JobStage.RETRIEVING_EXPERIENCE)
            val documents = documentResolver.resolveStrict(context.userId(), payload.resumeId, payload.targetJobId)
            val targetJob = documents.targetJob().orElseThrow {
                IllegalStateException("Experience suggestions ${payload.suggestionsId} have no target job")
            }
            val sources = suggestionsService.promptSources(context.userId(), payload.resumeId)
            context.stage(JobStage.MATCHING_EXPERIENCE)
            val result = if (sources.isEmpty()) ExperienceSuggestionsResult(emptyList())
                else coachService.suggestExperiences(documents.resume(), targetJob, sources)
            run = ExperienceSuggestionsRun(result, sources.map { it.source.id }.sortedBy { it.toString() })
            context.saveRootCheckpoint(run, "experience-suggestions")
        }
        context.materializeExperienceSuggestions(payload.suggestionsId, run)
        return context.toJson(run.result)
    }
}
