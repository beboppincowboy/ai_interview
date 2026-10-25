package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
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
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager

class JobFitSourceSnapshotServiceTests {
    private val resume = JobFitFrozenSource(JobFitSourceType.RESUME, UUID.randomUUID(), "Backend", "Built Kotlin services.")
    private val targetJob = JobFitFrozenSource(JobFitSourceType.TARGET_JOB, UUID.randomUUID(), "Platform", "Requires Kubernetes.")
    private val experience = JobFitFrozenSource(JobFitSourceType.EXPERIENCE, UUID.randomUUID(), "Cluster migration", "Deployed Kubernetes.")
    private val saved = listOf(resume, targetJob, experience).map(JobFitFrozenSource::manifestEntry)

    @Test
    fun legacyResultsHaveUnknownFreshness() {
        assertThat(jobFitFreshness(null, saved)).isEqualTo(JobFitFreshness.UNKNOWN)
    }

    @Test
    fun unchangedSourcesInAnyOrderAreCurrent() {
        assertThat(jobFitFreshness(saved, saved.reversed())).isEqualTo(JobFitFreshness.CURRENT)
    }

    @Test
    fun addedRenamedEditedOrDeletedSourcesAreOutdated() {
        val added = saved + JobFitFrozenSource(JobFitSourceType.EXPERIENCE, UUID.randomUUID(), "Talk", "Spoke at KubeCon.").manifestEntry()
        val renamed = listOf(resume, targetJob, experience.copy(sourceLabel = "Platform migration")).map(JobFitFrozenSource::manifestEntry)
        val edited = listOf(resume.copy(text = "Built Go services."), targetJob, experience).map(JobFitFrozenSource::manifestEntry)
        val deleted = listOf(resume, targetJob).map(JobFitFrozenSource::manifestEntry)

        listOf(added, renamed, edited, deleted).forEach { current ->
            assertThat(jobFitFreshness(saved, current)).isEqualTo(JobFitFreshness.OUTDATED)
        }
    }

    @Test
    fun theRevisionHashCoversTheLabelAndText() {
        assertThat(experience.contentHash).isNotEqualTo(experience.copy(sourceLabel = "Other").contentHash)
        assertThat(experience.contentHash).isNotEqualTo(experience.copy(text = "Other").contentHash)
        assertThat(experience.contentHash).isEqualTo(experience.copy().contentHash)
    }

    @Test
    fun aRetryRestoresTheCheckpointedSnapshotWithoutReadingSources() {
        val objectMapper = ObjectMapper().findAndRegisterModules()
        val jdbc = Mockito.mock(JdbcTemplate::class.java)
        val transactions = Mockito.mock(PlatformTransactionManager::class.java)
        val service = JobFitSourceSnapshotService(jdbc, transactions)
        val snapshot = JobFitSourceSnapshot("snapshot-1", Instant.parse("2026-10-25T00:00:00Z"), listOf(resume, targetJob, experience))
        val checkpoint = objectMapper.createObjectNode().set<JsonNode>(JobFitSourceSnapshotService.CHECKPOINT_FIELD, objectMapper.valueToTree(snapshot))

        val restored = service.frozenSources(context(objectMapper, checkpoint), resume.resourceId, targetJob.resourceId)

        assertThat(restored).isEqualTo(snapshot)
        Mockito.verifyNoInteractions(jdbc, transactions)
    }

    private fun context(objectMapper: ObjectMapper, checkpoint: JsonNode): JobExecutionContext {
        val lease = UUID.randomUUID()
        val job = BackgroundJob(
            UUID.randomUUID(), UUID.randomUUID(), JobType.JOB_FIT, "job-fit", UUID.randomUUID(),
            JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.createObjectNode(), checkpoint,
            "fingerprint", 2, 3, null, null, null, Instant.now(), Instant.now(), Instant.now(), null, Instant.now(), null, lease,
            Instant.now().plusSeconds(300),
        )
        return JobExecutionContext(
            job, lease, Mockito.mock(BackgroundJobStore::class.java), Mockito.mock(JobEffectMaterializationService::class.java),
            JobMetrics(SimpleMeterRegistry()), objectMapper,
        )
    }
}
