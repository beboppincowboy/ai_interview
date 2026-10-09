package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

@Testcontainers
class ResumeLibraryIntegrationTests {
    @Test
    fun pasteCreatesReadyResumeAndNormalizedDuplicateReturnsExistingItem() {
        val raw = "  EXPERIENCE\r\nJava  Spring  \n".repeat(5)
        val created = library.paste(PasteResumeRequest("Backend", null, raw))
        val first = requireNotNull(created.body)
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(first.duplicate).isFalse()
        assertThat(first.resume.source).isEqualTo("PASTE")
        assertThat(first.resume.status).isEqualTo("READY")
        assertThat(library.get(UUID.fromString(first.resume.id)).text).isEqualTo(normalizer.normalize(raw))

        val duplicate = library.paste(PasteResumeRequest("Different label", null, normalizer.normalize(raw)))
        assertThat(duplicate.statusCode.value()).isEqualTo(200)
        assertThat(duplicate.body).isEqualTo(ResumeCreated(first.resume, true))
        assertThat(count("resumes")).isEqualTo(1)
    }

    @Test
    fun deletingPastedResumeWithNullStorageKeyRemovesTheOwnedRow() {
        val pasted = requireNotNull(library.paste(PasteResumeRequest("Backend", null, "Paste-only resume body ".repeat(8))).body).resume
        val id = UUID.fromString(pasted.id)
        assertThat(jdbc.queryForObject(
            "SELECT storage_key FROM ai_interview_app.resumes WHERE id = ?",
            String::class.java,
            id
        )).isNull()

        library.delete(id)

        assertThat(count("resumes")).isZero()
        assertThat(count("storage_cleanup")).isZero()
        assertThatThrownBy { library.get(id) }
            .isInstanceOf(ApiRequestException::class.java)
    }

    @Test
    fun simultaneousIdenticalPastesSerializeOnOwnerAndKeepOneResume() {
        val text = "Backend engineer with tested Java and PostgreSQL services. ".repeat(3)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val first = executor.submit<ResumeCreated> {
                start.await()
                requireNotNull(library.paste(PasteResumeRequest("Backend", null, text)).body)
            }
            val second = executor.submit<ResumeCreated> {
                start.await()
                requireNotNull(library.paste(PasteResumeRequest("Backend", null, text)).body)
            }
            start.countDown()
            val a = first.get(15, TimeUnit.SECONDS)
            val b = second.get(15, TimeUnit.SECONDS)
            assertThat(setOf(a.resume.id, b.resume.id)).hasSize(1)
            assertThat(listOf(a.duplicate, b.duplicate).count { !it }).isEqualTo(1)
            assertThat(count("resumes")).isEqualTo(1)
            assertThat(count("resume_chunks")).isGreaterThan(0)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun ownerScopedReadsAndPatchDistinguishMissingTitleFromExplicitNull() {
        val saved = requireNotNull(library.paste(PasteResumeRequest("Backend", "Platform", "Resume body ".repeat(12))).body).resume
        val id = UUID.fromString(saved.id)

        val renamed = library.patch(id, mapOf("name" to "Backend 2026"))
        assertThat(renamed.name).isEqualTo("Backend 2026")
        assertThat(renamed.jobTitle).isEqualTo("Platform")
        // The controller passes the request body as a map; an explicit null in it must still clear the title.
        val cleared = library.patch(id, mapOf("jobTitle" to null))
        assertThat(cleared.name).isEqualTo("Backend 2026")
        assertThat(cleared.jobTitle).isNull()

        val otherUser = UUID.randomUUID()
        val otherResume = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", otherUser, "other-${otherUser}@example.test")
        jdbc.update(
            """INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status) VALUES (?, ?, 'Private', 'PASTE', 'READY')""",
            otherResume, otherUser
        )
        assertThatThrownBy { library.get(otherResume) }
            .isInstanceOf(ApiRequestException::class.java)
            .matches { (it as ApiRequestException).code() == "RESUME_NOT_FOUND" }
        assertThatThrownBy { library.patch(otherResume, mapOf("name" to "Changed")) }
            .isInstanceOf(ApiRequestException::class.java)
        assertThatThrownBy { library.deleteImpact(otherResume) }
            .isInstanceOf(ApiRequestException::class.java)
    }

    @Test
    fun listMapsPendingToProcessingAndAttachesItsExtractionJob() {
        val userId = local.localUserId()
        val resumeId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, storage_key, size_bytes, processing_status, name, source, file_hash) VALUES (?, ?, 'resume.pdf', 'resumes/key', 12, 'PENDING', 'Backend', 'UPLOAD', ?)""",
            resumeId, userId, "a".repeat(64)
        )
        jdbc.update(
            """INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, max_attempts) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'QUEUED', 'QUEUED', jsonb_build_object('resumeId', ?::text), 'pending-job', 4)""",
            jobId, userId, resumeId, resumeId
        )

        val resume = library.list().items.single()
        assertThat(resume.status).isEqualTo("PROCESSING")
        assertThat(resume.latestJob?.jobId).isEqualTo(jobId)
        assertThat(resume.latestJob?.maxAttempts).isEqualTo(4)
    }

    @Test
    fun duplicateExtractionCheckpointsBeforeDeferringTemporaryObjectCleanup() {
        val userId = local.localUserId()
        val existing = requireNotNull(library.paste(PasteResumeRequest("Saved", null, "Same resume text ".repeat(10))).body).resume
        val temporaryId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        val lease = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, storage_key, size_bytes, processing_status, name, source, file_hash) VALUES (?, ?, 'copy.txt', 'resumes/temporary', 12, 'PENDING', 'Copy', 'UPLOAD', ?)""",
            temporaryId, userId, "b".repeat(64)
        )
        jdbc.update(
            """INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, attempts, max_attempts, lease_token, lease_expires_at) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'PROCESSING', 'CHUNKING_TEXT', jsonb_build_object('resumeId', ?::text), 'duplicate-extraction', 1, 3, ?, now() + interval '5 minutes')""",
            jobId, userId, temporaryId, temporaryId, lease
        )
        val job = jobs.findById(jobId).orElseThrow()

        val result = transactions.execute {
            materialization.withOwnedLease(job, lease, Supplier {
                persistence.completeExtractionForJob(
                    temporaryId, "resumes/temporary", "Same resume text ".repeat(10),
                    normalizer.normalize("Same resume text ".repeat(10)), emptyList()
                ).also { jobs.checkpointResult(jobId, lease, ObjectMapper().valueToTree(it)) }
            })
        }

        assertThat(result.duplicateOf?.id).isEqualTo(existing.id)
        val savedJob = jobs.findById(jobId).orElseThrow()
        assertThat(savedJob.status).isEqualTo(JobStatus.PROCESSING)
        assertThat(savedJob.resultPayload?.get("resumeId")?.asText()).isEqualTo(temporaryId.toString())
        assertThat(savedJob.resultPayload?.get("duplicateOf")?.get("id")?.asText()).isEqualTo(existing.id)
        assertThat(count("storage_cleanup")).isEqualTo(1)
        assertThatThrownBy { library.get(temporaryId) }
            .isInstanceOf(ApiRequestException::class.java)
    }

    @Test
    fun deletingResumeWaitsForOwnedExtractionLeaseThenRemovesBothRows() {
        val userId = local.localUserId()
        val resumeId = UUID.randomUUID()
        val jobId = UUID.randomUUID()
        val lease = UUID.randomUUID()
        jdbc.update(
            """INSERT INTO ai_interview_app.resumes (id, user_id, original_filename, storage_key, size_bytes, processing_status, name, source, file_hash) VALUES (?, ?, 'resume.pdf', 'resumes/race', 10, 'PENDING', 'Race', 'UPLOAD', ?)""",
            resumeId, userId, "c".repeat(64)
        )
        jdbc.update(
            """INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, request_fingerprint, attempts, max_attempts, lease_token, lease_expires_at) VALUES (?, ?, 'RESUME_EXTRACTION', 'resume', ?, 'PROCESSING', 'EXTRACTING_TEXT', jsonb_build_object('resumeId', ?::text), 'lease-delete-race', 1, 3, ?, now() + interval '5 minutes')""",
            jobId, userId, resumeId, resumeId, lease
        )
        val job = jobs.findById(jobId).orElseThrow()
        val locked = CountDownLatch(1)
        val finishExtraction = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val extraction = executor.submit {
                transactions.execute {
                    materialization.withOwnedLease(job, lease, Supplier {
                        locked.countDown()
                        finishExtraction.await(10, TimeUnit.SECONDS)
                        persistence.completeExtractionForJob(
                            resumeId, "resumes/race", "A resume body for the lease race",
                            normalizer.normalize("A resume body for the lease race"), emptyList()
                        )
                    })
                }
            }
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue()
            val deletion = executor.submit { library.delete(resumeId) }
            Thread.sleep(100)
            assertThat(deletion.isDone).isFalse()
            finishExtraction.countDown()
            extraction.get(10, TimeUnit.SECONDS)
            deletion.get(10, TimeUnit.SECONDS)
            assertThat(count("resumes")).isZero()
            assertThat(count("background_jobs")).isZero()
        } finally {
            finishExtraction.countDown()
            executor.shutdownNow()
        }
    }

    @BeforeEach
    fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.storage_cleanup, ai_interview_app.background_jobs, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_resume_library_test").withUsername("ai_interview").withPassword("ai_interview")
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var persistence: ResumePersistenceService
        private lateinit var normalizer: ResumeTextNormalizer
        private lateinit var cleanup: ResumeStorageCleanupService
        private lateinit var library: ResumeLibraryService
        private lateinit var jobs: BackgroundJobStore
        private lateinit var materialization: JobEffectMaterializationService

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            normalizer = ResumeTextNormalizer()
            persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            val storage = Mockito.mock(ResumeStorageService::class.java)
            cleanup = ResumeStorageCleanupService(jdbc, storage)
            val properties = RedisUsageProperties(
                "resume-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20),
                RedisUsageProperties.Idempotency(false, 86_400)
            )
            val guard = RedisRequestGuard(StringRedisTemplate(), properties, ObjectMapper().findAndRegisterModules())
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions, cleanup, DeleteImpactService(jdbc), ObjectMapper().findAndRegisterModules(), BackgroundJobStore(jdbc, ObjectMapper().findAndRegisterModules())
            )
            val mapper = ObjectMapper().findAndRegisterModules()
            jobs = BackgroundJobStore(jdbc, mapper)
            materialization = JobEffectMaterializationService(jdbc, ObjectMapper().findAndRegisterModules())
        }

    }
}
