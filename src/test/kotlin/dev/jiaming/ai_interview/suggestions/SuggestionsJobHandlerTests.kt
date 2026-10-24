package dev.jiaming.ai_interview.suggestions

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

class SuggestionsJobHandlerTests {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val coach = Mockito.mock(AiResumeCoachService::class.java)
    private val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
    private val suggestions = Mockito.mock(SuggestionsService::class.java)
    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val materialization = Mockito.mock(JobEffectMaterializationService::class.java)
    private val handler = ExperienceSuggestionsJobHandler(coach, resolver, suggestions)
    private val userId = UUID.randomUUID()
    private val lease = UUID.randomUUID()
    private val payload = ExperienceSuggestionsPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val experience = SuggestionSource(SuggestionSourceType.EXPERIENCE, UUID.fromString("f0000000-0000-0000-0000-000000000000"), "Ledger rewrite")
    private val resume = SuggestionSource(SuggestionSourceType.RESUME, UUID.fromString("10000000-0000-0000-0000-000000000000"), "Platform resume")
    private val result = ExperienceSuggestionsResult(listOf(
        ExperienceSuggestionItem("Event-driven systems", experience, "Outbox pipeline", "The job asks for it.", "Add it under the Acme role.")
    ))

    @Test
    fun loadsSourcesThenCheckpointsTheResultWithItsSortedSourceSnapshotBeforeMaterializing() {
        val job = job(null)
        val inputs = ResolvedJobInputs(document(DocumentSourceType.RESUME, payload.resumeId), Optional.of(document(DocumentSourceType.JOB_DESCRIPTION, payload.targetJobId)))
        val sources = listOf(SuggestionSourceInput.Experience(experience, "Ledger rewrite\nBuilt it."),
            SuggestionSourceInput.Resume(resume, document(DocumentSourceType.RESUME, resume.id)))
        Mockito.`when`(resolver.resolveStrict(userId, payload.resumeId, payload.targetJobId)).thenReturn(inputs)
        Mockito.`when`(suggestions.promptSources(userId, payload.resumeId)).thenReturn(sources)
        Mockito.`when`(coach.suggestExperiences(inputs.resume(), inputs.targetJob().orElseThrow(), sources)).thenReturn(result)

        val returned = handler.handle(payload, context(job))

        val run = ExperienceSuggestionsRun(result, listOf(resume.id, experience.id))
        assertThat(returned).isEqualTo(objectMapper.valueToTree<JsonNode>(result))
        val stages = Mockito.inOrder(jobStore)
        stages.verify(jobStore).updateStage(job.id, lease, JobStage.RETRIEVING_EXPERIENCE)
        stages.verify(jobStore).updateStage(job.id, lease, JobStage.MATCHING_EXPERIENCE)
        Mockito.verify(jobStore).checkpointResult(job.id, lease, objectMapper.valueToTree(run))
        Mockito.verify(materialization).materializeExperienceSuggestions(job, lease, payload.suggestionsId, objectMapper.valueToTree(result), run.sourceIds)
    }

    @Test
    fun retryAfterCheckpointSkipsSourcesAndTheModel() {
        val run = ExperienceSuggestionsRun(result, listOf(experience.id))
        val job = job(objectMapper.valueToTree(run))

        handler.handle(payload, context(job))

        Mockito.verifyNoInteractions(coach, resolver, suggestions)
        Mockito.verify(materialization).materializeExperienceSuggestions(job, lease, payload.suggestionsId, objectMapper.valueToTree(result), run.sourceIds)
    }

    @Test
    fun noRemainingSourcesStoresNoMatchesWithoutCallingTheModel() {
        val job = job(null)
        Mockito.`when`(resolver.resolveStrict(userId, payload.resumeId, payload.targetJobId)).thenReturn(ResolvedJobInputs(
            document(DocumentSourceType.RESUME, payload.resumeId), Optional.of(document(DocumentSourceType.JOB_DESCRIPTION, payload.targetJobId))
        ))
        Mockito.`when`(suggestions.promptSources(userId, payload.resumeId)).thenReturn(emptyList())

        handler.handle(payload, context(job))

        Mockito.verify(coach, Mockito.never()).suggestExperiences(any(), any(), any())
        Mockito.verify(materialization).materializeExperienceSuggestions(job, lease, payload.suggestionsId,
            objectMapper.valueToTree(ExperienceSuggestionsResult(emptyList())), emptyList())
    }

    private fun document(type: DocumentSourceType, id: UUID) = ResolvedDocument(type, id, "hash-$id", "text", emptyList())

    private fun context(job: BackgroundJob) = JobExecutionContext(job, lease, jobStore, materialization, JobMetrics(SimpleMeterRegistry()), objectMapper)

    private fun job(checkpoint: JsonNode?) = BackgroundJob(
        UUID.randomUUID(), userId, JobType.EXPERIENCE_SUGGESTIONS, SuggestionsService.RESOURCE, payload.suggestionsId,
        JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.valueToTree(payload), checkpoint,
        "fingerprint", 1, 3, null, null, null, Instant.now(), Instant.now(), Instant.now(), null, Instant.now(), null, lease,
        Instant.now().plusSeconds(300)
    )
}
