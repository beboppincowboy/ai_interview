package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockMultipartFile
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class ResumeStorageCleanupIntegrationTests {
    @Test
    fun uploadTransactionRollbackRecordsCleanupBeforeRetryingObjectDeletion() {
        val objectKey = "resumes/rollback.txt"
        Mockito.`when`(storage.store(any())).thenReturn(objectKey)
        Mockito.doThrow(IllegalStateException("temporary S3 outage")).doNothing().`when`(storage).delete(objectKey)
        val jobs = mockedJobSubmission {
            throw IllegalStateException("job persistence failed")
        }
        val service = submissionService(jobs)
        val file = MockMultipartFile("file", "rollback.txt", "text/plain", "This is a resume upload body.".toByteArray())

        assertThatThrownBy { service.submit(file, "Rollback", null) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("job persistence failed")
        assertThat(count("resumes")).isZero()
        assertThat(count("background_jobs")).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", Int::class.java, objectKey)).isEqualTo(1)

        assertThat(cleanup.retryPending(25)).isEqualTo(1)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.storage_cleanup", Int::class.java)).isZero()
        Mockito.verify(storage, Mockito.times(2)).delete(objectKey)
    }

    @Test
    fun racingFileHashInsertReturnsWinnerAndDeletesOnlyTheLoserObject() {
        val keys = listOf("resumes/race-1.txt", "resumes/race-2.txt")
        val storeCalls = java.util.concurrent.atomic.AtomicInteger()
        val bothStored = CountDownLatch(2)
        Mockito.doAnswer {
            val key = keys[storeCalls.getAndIncrement()]
            bothStored.countDown()
            check(bothStored.await(10, TimeUnit.SECONDS))
            key
        }.`when`(storage).store(any())
        val jobs = mockedJobSubmission { invocation ->
            val userId = local.localUserId()
            val resumeId = invocation.getArgument<UUID>(2)
            val fingerprint = invocation.getArgument<String>(4)
            val jobId = UUID.randomUUID()
            jdbc.update(
                """INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, max_attempts) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED', jsonb_build_object('resumeId', ?::text), ?, 3)""",
                jobId, userId, resumeId, resumeId, fingerprint
            )
            JobAcceptedResponse(jobId, JobType.RESUME_EXTRACTION, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$jobId", false)
        }
        val bytes = "Identical file bytes for the race.".toByteArray()
        val file = MockMultipartFile("file", "resume.txt", "text/plain", bytes)
        val validator = Mockito.mock(ResumeFileValidator::class.java)
        val reader = Mockito.mock(ResumeFileReader::class.java)
        Mockito.doNothing().`when`(validator).validate(any())
        Mockito.`when`(reader.read(any())).thenReturn(
            ResumeFileContent("resume.txt", "text/plain", bytes.size.toLong(), bytes, "text/plain", "txt")
        )
        val service = submissionService(jobs, validator, reader)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<org.springframework.http.ResponseEntity<ResumeCreated>> { service.submit(file, "Backend", null) }
            val second = executor.submit<org.springframework.http.ResponseEntity<ResumeCreated>> { service.submit(file, "Backend", null) }
            val responses = listOf(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
            assertThat(responses.map { it.statusCode.value() }).containsExactlyInAnyOrder(200, 202)
            assertThat(responses.map { it.body!!.resume.id }.distinct()).hasSize(1)
            assertThat(count("resumes")).isEqualTo(1)
            assertThat(count("background_jobs")).isEqualTo(1)
            assertThat(count("storage_cleanup")).isZero()
            Mockito.verify(storage, Mockito.times(1)).delete(any())
            val deletedKey = Mockito.mockingDetails(storage).invocations
                .single { it.method.name == "delete" }.arguments.single() as String
            assertThat(deletedKey).isIn(keys)

            val replay = service.submit(file, "A different name", null)
            assertThat(replay.statusCode.value()).isEqualTo(200)
            Mockito.verify(storage, Mockito.times(2)).store(any())
            Mockito.verify(jobs, Mockito.times(1)).createOrReuse(
                eq(JobType.RESUME_EXTRACTION), eq("resume"), anyOrNull(), any(), eq("file-fingerprint")
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.storage_cleanup, ai_interview_app.background_jobs, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
        Mockito.reset(storage)
    }

    private fun submissionService(
        jobSubmission: JobSubmissionService,
        validator: ResumeFileValidator = ResumeFileValidator(),
        reader: ResumeFileReader = ResumeFileReader()
    ): ResumeJobSubmissionService = ResumeJobSubmissionService(
        validator, reader, storage, persistence, library, jobSubmission, local, guard, cleanup, transactions
    )

    private fun mockedJobSubmission(createJob: (org.mockito.invocation.InvocationOnMock) -> JobAcceptedResponse): JobSubmissionService {
        val service = Mockito.mock(JobSubmissionService::class.java)
        Mockito.`when`(service.fingerprint(Mockito.anyString(), any())).thenReturn("file-fingerprint")
        Mockito.doAnswer { createJob(it) }.`when`(service).createOrReuse(
            eq(JobType.RESUME_EXTRACTION), eq("resume"), anyOrNull(), any(), eq("file-fingerprint")
        )
        return service
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_resume_cleanup_test").withUsername("ai_interview").withPassword("ai_interview")
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var persistence: ResumePersistenceService
        private lateinit var normalizer: ResumeTextNormalizer
        private lateinit var storage: ResumeStorageService
        private lateinit var cleanup: ResumeStorageCleanupService
        private lateinit var library: ResumeLibraryService
        private lateinit var guard: RedisRequestGuard

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            normalizer = ResumeTextNormalizer()
            persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            storage = Mockito.mock(ResumeStorageService::class.java)
            cleanup = ResumeStorageCleanupService(jdbc, storage)
            val properties = RedisUsageProperties(
                "resume-cleanup-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20),
                RedisUsageProperties.Idempotency(false, 86_400)
            )
            guard = RedisRequestGuard(StringRedisTemplate(), properties, ObjectMapper().findAndRegisterModules())
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions, cleanup, DeleteImpactService(jdbc), ObjectMapper().findAndRegisterModules(), BackgroundJobStore(jdbc, ObjectMapper().findAndRegisterModules())
            )
        }
    }
}
