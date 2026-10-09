package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.targetjob.TargetJobPersistenceService
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobDispatcher
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobProperties
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.jobs.RequestFingerprintService
import dev.jiaming.ai_interview.resume.ResumeLibraryService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import dev.jiaming.ai_interview.resume.ResumeStorageCleanupService
import dev.jiaming.ai_interview.resume.ResumeStorageService
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import dev.jiaming.ai_interview.targetjob.TargetJobService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class JobFitIntegrationTests {
    @Test
    fun aPairThatNeverRanReturnsAnEmptyViewAndMissingInputsReturnTheirOwnNotFound() {
        val resumeId = insertResume()
        val targetJobId = insertTargetJob()

        val view = fits.get(resumeId, targetJobId)
        assertThat(view.result).isNull()
        assertThat(view.createdAt).isNull()
        assertThat(view.latestJob).isNull()

        assertThatThrownBy { fits.get(UUID.randomUUID(), targetJobId) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("RESUME_NOT_FOUND") }
        assertThatThrownBy { fits.run(resumeId, UUID.randomUUID()) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("TARGET_JOB_NOT_FOUND") }
        assertThat(count("job_fits")).isZero()
    }

    @Test
    fun concurrentStartsShareOnePairAndItsRunningJob() {
        val resumeId = insertResume()
        val targetJobId = insertTargetJob()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val runs = (1..2).map { executor.submit(Callable { start.await(); fits.run(resumeId, targetJobId) }) }
            start.countDown()
            val accepted = runs.map { it.get(10, TimeUnit.SECONDS) }
            assertThat(accepted.map { it.jobId }.distinct()).hasSize(1)
            assertThat(accepted.map { it.reused }).containsExactlyInAnyOrder(false, true)
        } finally {
            executor.shutdownNow()
        }
        assertThat(count("job_fits")).isEqualTo(1)
        assertThat(fits.get(resumeId, targetJobId).latestJob?.status).isEqualTo(JobStatus.QUEUED)
    }

    @Test
    fun aRerunKeepsThePairAndReplacesItsResult() {
        val resumeId = insertResume()
        val targetJobId = insertTargetJob()
        val first = finishRun(resumeId, targetJobId, result(55))
        val firstFitId = fitId(resumeId, targetJobId)

        val second = finishRun(resumeId, targetJobId, result(81))

        assertThat(second).isNotEqualTo(first)
        assertThat(count("job_fits")).isEqualTo(1)
        assertThat(fitId(resumeId, targetJobId)).isEqualTo(firstFitId)
        val view = fits.get(resumeId, targetJobId)
        assertThat(view.result?.fitScore).isEqualTo(81)
        assertThat(view.createdAt).isNotNull()
        assertThat(view.latestJob?.jobId).isEqualTo(second)
    }

    @Test
    fun deleteImpactCountsOnlyFitsWithResultsAndDeletesCascadeThePair() {
        val resumeId = insertResume()
        val scoredJob = insertTargetJob()
        val pendingJob = insertTargetJob()
        finishRun(resumeId, scoredJob, result(70))
        fits.run(resumeId, pendingJob)

        assertThat(library.deleteImpact(resumeId).fits).isEqualTo(1)
        assertThat(targetJobs.deleteImpact(pendingJob).fits).isZero()
        assertThat(targetJobs.deleteImpact(scoredJob).fits).isEqualTo(1)

        transactions.execute { targetJobs.delete(scoredJob) }
        assertThat(count("job_fits")).isEqualTo(1)
        library.delete(resumeId)
        assertThat(count("job_fits")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    private fun finishRun(resumeId: UUID, targetJobId: UUID, result: JobFitResult): UUID {
        val jobId = fits.run(resumeId, targetJobId).jobId
        val lease = UUID.randomUUID()
        jdbc.update("UPDATE ai_interview_app.background_jobs SET status = 'PROCESSING', lease_token = ?, lease_expires_at = now() + interval '5 minutes' WHERE id = ?", lease, jobId)
        val job = jobs.findById(jobId).orElseThrow()
        materialization.materializeJobFit(job, lease, job.resourceId!!, mapper.valueToTree(result))
        jobs.markSucceeded(jobId, lease, mapper.valueToTree(result))
        return jobId
    }

    private fun result(score: Int) = JobFitResult(score, "Summary", listOf(MatchedRequirement("Kotlin", "Built services")), emptyList(), emptyList())

    private fun fitId(resumeId: UUID, targetJobId: UUID) = jdbc.queryForObject(
        "SELECT id FROM ai_interview_app.job_fits WHERE resume_id = ? AND target_job_id = ?", UUID::class.java, resumeId, targetJobId
    )

    private fun insertResume(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', 'PASTE', 'READY', 'Built Kotlin services.')",
            id, local.localUserId()
        )
        return id
    }

    private fun insertTargetJob(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.job_descriptions (id, user_id, name, title, raw_text, normalized_text, content_hash) VALUES (?, ?, 'Platform', 'Platform', 'text', 'text', ?)",
            id, local.localUserId(), "hash-$id"
        )
        return id
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    @BeforeEach fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.job_fits, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_job_fit_test").withUsername("ai_interview").withPassword("ai_interview")
        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var fits: JobFitService
        private lateinit var library: ResumeLibraryService
        private lateinit var targetJobs: TargetJobService
        private lateinit var jobs: BackgroundJobStore
        private lateinit var materialization: JobEffectMaterializationService

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            jobs = BackgroundJobStore(jdbc, mapper)
            val guard = RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties(
                "job-fit-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)
            ), mapper)
            val submissions = JobSubmissionService(
                jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(mapper), local, guard,
                PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), mapper
            )
            fits = JobFitService(jdbc, local, submissions, guard, jobs, mapper, transactions)
            val normalizer = ResumeTextNormalizer()
            val persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions,
                ResumeStorageCleanupService(jdbc, Mockito.mock(ResumeStorageService::class.java)), DeleteImpactService(jdbc), mapper, BackgroundJobStore(jdbc, mapper)
            )
            targetJobs = TargetJobService(jdbc, local, TargetJobPersistenceService(jdbc, normalizer, SectionAwareTextChunker(), ContentHasher()))
            materialization = JobEffectMaterializationService(jdbc, mapper)
        }
    }
}
