package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.Priority
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobFitPayload
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

class JobFitJobHandlerTests {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val coach = Mockito.mock(AiResumeCoachService::class.java)
    private val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val materialization = Mockito.mock(JobEffectMaterializationService::class.java)
    private val handler = JobFitJobHandler(coach, resolver)
    private val userId = UUID.randomUUID()
    private val lease = UUID.randomUUID()
    private val payload = JobFitPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val result = JobFitResult(68, "Good match.", listOf(MatchedRequirement("Kotlin", "Built Kotlin services")),
        listOf(MissingRequirement("Kafka", "Describe streaming work.")), listOf(FitFeedback(Priority.HIGH, "Lead with latency work.")))

    @Test
    fun matchesTheSavedPairThenCheckpointsBeforeMaterializing() {
        val job = job(null)
        Mockito.`when`(resolver.resolveStrict(userId, payload.resumeId, payload.targetJobId)).thenReturn(ResolvedJobInputs(
            document(DocumentSourceType.RESUME, payload.resumeId), Optional.of(document(DocumentSourceType.JOB_DESCRIPTION, payload.targetJobId))
        ))
        Mockito.`when`(coach.assessJobFit(any<CoachAnalysisInput>())).thenReturn(result)

        val returned = handler.handle(payload, context(job))

        assertThat(returned.get("fitScore").asInt()).isEqualTo(68)
        Mockito.verify(jobStore).updateStage(job.id, lease, JobStage.MATCHING_JOB)
        Mockito.verify(jobStore).checkpointResult(job.id, lease, objectMapper.valueToTree(result))
        Mockito.verify(materialization).materializeJobFit(job, lease, payload.fitId, objectMapper.valueToTree(result))
    }

    @Test
    fun retryAfterCheckpointSkipsRetrievalAndTheModel() {
        val job = job(objectMapper.valueToTree(result))

        handler.handle(payload, context(job))

        Mockito.verifyNoInteractions(coach, resolver)
        Mockito.verify(materialization).materializeJobFit(job, lease, payload.fitId, objectMapper.valueToTree(result))
    }

    private fun document(type: DocumentSourceType, id: UUID) = ResolvedDocument(type, id, "hash-$id", "text", emptyList())

    private fun context(job: BackgroundJob) = JobExecutionContext(job, lease, jobStore, materialization, JobMetrics(SimpleMeterRegistry()), objectMapper)

    private fun job(checkpoint: JsonNode?) = BackgroundJob(
        UUID.randomUUID(), userId, JobType.JOB_FIT, "job-fit", payload.fitId,
        JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.valueToTree(payload), checkpoint,
        "fingerprint", 1, 3, null, null, null, Instant.now(), Instant.now(), Instant.now(), null, Instant.now(), null, lease,
        Instant.now().plusSeconds(300)
    )
}
