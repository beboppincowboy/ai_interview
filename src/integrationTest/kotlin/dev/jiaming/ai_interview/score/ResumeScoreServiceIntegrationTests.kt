package dev.jiaming.ai_interview.score

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.lockOwnerExclusive
import dev.jiaming.ai_interview.common.lockOwnerShared
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.resume.ResumeLibraryService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import dev.jiaming.ai_interview.resume.ResumeStorageCleanupService
import dev.jiaming.ai_interview.resume.ResumeStorageService
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class ResumeScoreServiceIntegrationTests {
    @Test
    fun scoreSubmissionSnapshotsAnOwnedReadyResume() {
        val resumeId = insertResume("READY", "Backend Engineer", "Built a payment API serving 20 services.")

        val response = transactions.execute { scores.submit(resumeId) }

        assertThat(response?.jobType).isEqualTo(JobType.RESUME_SCORE)
        val payload = argumentCaptor<ResumeScorePayload>()
        Mockito.verify(jobSubmission).submit(eq(JobType.RESUME_SCORE), eq("resume"), eq(resumeId), payload.capture())
        assertThat(payload.firstValue).isEqualTo(ResumeScorePayload(resumeId, "Built a payment API serving 20 services.", "Backend Engineer"))
    }

    @Test
    fun nonReadyAndForeignResumesCannotStartScoreJobs() {
        val processing = insertResume("PENDING", null, null)
        assertThatThrownBy { scores.submit(processing) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { error ->
                assertThat(error.status()).isEqualTo(HttpStatus.CONFLICT)
                assertThat(error.code()).isEqualTo("RESUME_NOT_READY")
            }

        val ownerId = UUID.randomUUID()
        val foreign = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", ownerId, "foreign-$ownerId@example.test")
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Private', 'PASTE', 'READY', 'private text')",
            foreign, ownerId
        )
        assertThatThrownBy { scores.submit(foreign) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { error ->
                assertThat(error.status()).isEqualTo(HttpStatus.NOT_FOUND)
                assertThat(error.code()).isEqualTo("RESUME_NOT_FOUND")
            }
        Mockito.verifyNoInteractions(jobSubmission)
    }

    @Test
    fun latestScoreGoesStaleWhenTheJobTitleChangesAndARescoreClearsIt() {
        val resumeId = insertResume("READY", "Backend Engineer", "Built a payment API.")
        materialize(resumeId, score(70, "Backend Engineer", "2026-09-30T10:00:00Z"))

        val first = library.get(resumeId)
        assertThat(first.latestScore?.overall).isEqualTo(70)
        assertThat(first.latestScore?.stale).isFalse()
        assertThat(first.score?.overall).isEqualTo(70)
        assertThat(first.activeJob?.jobType).isEqualTo(JobType.RESUME_SCORE)

        library.patch(resumeId, mapOf("jobTitle" to null))
        assertThat(library.get(resumeId).latestScore?.stale).isTrue()

        materialize(resumeId, score(84, null, "2026-09-30T11:00:00Z"))
        val rescored = library.get(resumeId)
        assertThat(rescored.latestScore?.overall).isEqualTo(84)
        assertThat(rescored.latestScore?.stale).isFalse()
        assertThat(rescored.score?.rewrites?.single()?.placeholders).containsExactly("[X%]")
        assertThat(count("resume_scores")).isEqualTo(2)
        assertThat(library.list().items.single().latestScore?.overall).isEqualTo(84)
    }

    @Test
    fun retriedMaterializationWritesOneScoreAndDeleteRemovesScores() {
        val resumeId = insertResume("READY", null, "Built a payment API.")
        val (job, lease) = processingJob(resumeId)
        val result = score(66, null, "2026-09-30T10:00:00Z")

        materialization.materializeResumeScore(job, lease, resumeId, result)
        materialization.materializeResumeScore(job, lease, resumeId, result)

        assertThat(count("resume_scores")).isEqualTo(1)
        assertThat(library.deleteImpact(resumeId).scores).isEqualTo(1)
        library.delete(resumeId)
        assertThat(count("resume_scores")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    @Test
    fun scoreSubmissionLocksTheOwnerBeforeTheResumeLikeDeletionDoes() {
        val resumeId = insertResume("READY", null, "Built a payment API.")
        val insideSubmit = CountDownLatch(1)
        val releaseSubmit = CountDownLatch(1)
        Mockito.`when`(jobSubmission.submit(eq(JobType.RESUME_SCORE), eq("resume"), any(), any())).thenAnswer {
            insideSubmit.countDown()
            releaseSubmit.await(10, TimeUnit.SECONDS)
            null
        }
        val executor = Executors.newSingleThreadExecutor()
        try {
            transactions.execute {
                // Resume deletion's order: the owner row first, then the resume row.
                jdbc.queryForList("SELECT id FROM ai_interview_app.app_users WHERE id = ? FOR UPDATE", local.localUserId())
                val submit = executor.submit { transactions.execute { scores.submit(resumeId) } }
                insideSubmit.await(500, TimeUnit.MILLISECONDS)
                assertThat(jdbc.queryForList("SELECT id FROM ai_interview_app.resumes WHERE id = ? FOR UPDATE NOWAIT", resumeId)).hasSize(1)
                releaseSubmit.countDown()
                submit
            }
        } finally {
            releaseSubmit.countDown()
            executor.shutdown()
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun submissionsShareTheOwnerLockButADeleteExcludesThem() {
        val owner = local.localUserId()
        val other = JdbcTemplate(DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password))
        fun otherCanLock(lock: String) =
            runCatching { other.queryForList("SELECT id FROM ai_interview_app.app_users WHERE id = ? $lock NOWAIT", owner) }.isSuccess

        transactions.execute {
            jdbc.lockOwnerShared(owner)
            assertThat(otherCanLock("FOR KEY SHARE")).isTrue()
            assertThat(otherCanLock("FOR UPDATE")).isFalse()
        }
        transactions.execute {
            jdbc.lockOwnerExclusive(owner)
            assertThat(otherCanLock("FOR KEY SHARE")).isFalse()
        }
        assertThatThrownBy { jdbc.lockOwnerShared(UUID.randomUUID()) }.isInstanceOf(IllegalStateException::class.java)
    }

    private fun materialize(resumeId: UUID, result: ResumeScoreResult) {
        val (job, lease) = processingJob(resumeId)
        materialization.materializeResumeScore(job, lease, resumeId, result)
        jobs.markSucceeded(job.id, lease, mapper.valueToTree(result))
    }

    private fun processingJob(resumeId: UUID): Pair<dev.jiaming.ai_interview.jobs.BackgroundJob, UUID> {
        val jobId = UUID.randomUUID()
        val lease = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, attempts, max_attempts, lease_token, lease_expires_at, created_at) VALUES (?, ?, 'RESUME_SCORE', 'resume', ?, 'PROCESSING', 'SCORING_RESUME', jsonb_build_object('resumeId', ?::text), 1, 3, ?, now() + interval '5 minutes', clock_timestamp())""",
            jobId, local.localUserId(), resumeId, resumeId, lease
        )
        return jobs.findById(jobId).orElseThrow() to lease
    }

    private fun score(overall: Int, jobTitle: String?, scoredAt: String) = ResumeScoreResult(
        overall, AssessmentScores(overall, overall, overall, overall, overall), "Summary",
        listOf(ResumeScoreFix(1, "Experience", "HIGH", "Quantify it.")),
        listOf(ResumeScoreRewrite("Experience", "Did it.", "Cut cost by [X%].", listOf("[X%]"))),
        jobTitle, Instant.parse(scoredAt)
    )

    private fun insertResume(status: String, jobTitle: String?, text: String?): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, job_title, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', ?, 'PASTE', ?, ?)",
            id, local.localUserId(), jobTitle, status, text
        )
        return id
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    @BeforeEach fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.resume_scores, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
        Mockito.reset(jobSubmission)
        Mockito.`when`(jobSubmission.submit(eq(JobType.RESUME_SCORE), eq("resume"), any(), any())).thenAnswer { invocation ->
            val jobId = UUID.randomUUID()
            JobAcceptedResponse(jobId, JobType.RESUME_SCORE, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$jobId", false,
                JobInputRefs(invocation.getArgument(2), null, null, null))
        }
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_resume_score_test").withUsername("ai_interview").withPassword("ai_interview")
        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var scores: ResumeScoreService
        private lateinit var library: ResumeLibraryService
        private lateinit var jobs: BackgroundJobStore
        private lateinit var materialization: JobEffectMaterializationService
        private lateinit var jobSubmission: JobSubmissionService

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            jobSubmission = Mockito.mock(JobSubmissionService::class.java)
            scores = ResumeScoreService(jdbc, local, jobSubmission)
            val normalizer = ResumeTextNormalizer()
            val persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            val guard = RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties(
                "resume-score-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)
            ), mapper)
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions,
                ResumeStorageCleanupService(jdbc, Mockito.mock(ResumeStorageService::class.java)), DeleteImpactService(jdbc), mapper
            )
            jobs = BackgroundJobStore(jdbc, mapper)
            materialization = JobEffectMaterializationService(jdbc, mapper)
        }
    }
}
