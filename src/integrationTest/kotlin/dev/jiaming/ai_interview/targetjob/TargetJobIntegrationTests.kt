package dev.jiaming.ai_interview.targetjob

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobController
import dev.jiaming.ai_interview.jobs.JobStatusReaderConfiguration
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.MigrationVersion
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

class TargetJobIntegrationTests {
    @Test
    fun v11BackfillsExistingTitlesAndKeepsCreatedTimes() {
        val rows = jdbcTemplate.query(
            "SELECT id, name, created_at, updated_at FROM ai_interview_app.job_descriptions WHERE id IN (?, ?) ORDER BY name",
            { rs, _ -> listOf(rs.getObject("id", UUID::class.java), rs.getString("name"), rs.getTimestamp("created_at"), rs.getTimestamp("updated_at")) },
            legacyNamedId,
            legacyUntitledId,
        )

        assertThat(rows.map { it[1] }).containsExactly("Platform Engineer", "Untitled job")
        rows.forEach { assertThat(it[2]).isEqualTo(it[3]) }
    }

    @Test
    fun normalizedDuplicatesAreOwnerScopedAndConcurrentCreatesConverge() {
        val text = "Build reliable Kotlin services with PostgreSQL. ".repeat(3)
        val first = transactions.execute { service.create("First name", text) }
        val duplicate = transactions.execute { service.create("Ignored duplicate name", "  ${text.replace(" ", "  ")}\n") }
        assertThat(first.duplicate).isFalse()
        assertThat(duplicate.duplicate).isTrue()
        assertThat(duplicate.targetJob.id).isEqualTo(first.targetJob.id)
        assertThat(duplicate.targetJob.name).isEqualTo("First name")

        val otherUserId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", otherUserId, "$otherUserId@target-job.test")
        val otherUsers = Mockito.mock(LocalUserService::class.java)
        Mockito.`when`(otherUsers.localUserId()).thenReturn(otherUserId)
        val otherUserService = TargetJobService(jdbcTemplate, otherUsers, persistence)
        val foreign = transactions.execute { otherUserService.create("Other owner's copy", text) }
        assertThat(foreign.duplicate).isFalse()
        assertThat(foreign.targetJob.id).isNotEqualTo(first.targetJob.id)
        assertThat(service.list().items.map { it.id }).contains(first.targetJob.id).doesNotContain(foreign.targetJob.id)
        assertThat(service.get(first.targetJob.id).text).isEqualTo(text)

        val concurrentText = "Concurrent target job content with a stable normalized hash. ".repeat(3)
        val executor = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)
        try {
            val calls = (1..8).map { index -> executor.submit<TargetJobCreateResult> {
                check(start.await(5, TimeUnit.SECONDS))
                transactions.execute { service.create("Candidate $index", concurrentText) }
            } }
            start.countDown()
            val results = calls.map { it.get(15, TimeUnit.SECONDS) }
            assertThat(results.map { it.targetJob.id }.distinct()).hasSize(1)
            assertThat(results.count { !it.duplicate }).isEqualTo(1)
            assertThat(results.count { it.duplicate }).isEqualTo(7)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun renameChangesOnlyNameAndDeleteCancelsOwnedJobs() {
        val text = "Persist the original target job text and keep it immutable. ".repeat(3)
        val created = transactions.execute { service.create("Original", text) }
        val renamed = transactions.execute { service.rename(created.targetJob.id, "Renamed") }
        assertThat(renamed.name).isEqualTo("Renamed")
        assertThat(service.get(created.targetJob.id).text).isEqualTo(text)
        assertThat(service.deleteImpact(created.targetJob.id)).isEqualTo(TargetJobDeleteImpact())

        val jobId = UUID.randomUUID()
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.background_jobs
                    (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, max_attempts)
                VALUES (?, ?, 'RESUME_SCORE', 'resume', ?, 'QUEUED', 'QUEUED', ?::jsonb, 3)
            """.trimIndent(),
            jobId,
            userId,
            UUID.randomUUID(),
            """{"jobDescriptionId":"${created.targetJob.id}"}""",
        )
        val directJobId = UUID.randomUUID()
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.background_jobs
                    (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, max_attempts)
                VALUES (?, ?, 'JOB_FIT', 'target-job', ?, 'QUEUED', 'QUEUED', '{}'::jsonb, 3)
            """.trimIndent(),
            directJobId,
            userId,
            created.targetJob.id,
        )
        transactions.execute { service.delete(created.targetJob.id) }

        listOf(jobId, directJobId).forEach { id ->
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_interview_app.background_jobs WHERE id = ?", Int::class.java, id)).isZero()
        }
        val jobs = JobController(
            JobStatusReaderConfiguration().localJobStatusReader(BackgroundJobStore(jdbcTemplate, ObjectMapper())),
            LocalUserService(jdbcTemplate),
        )
        listOf(jobId, directJobId).forEach { id ->
            assertThatThrownBy { jobs.status(id) }
                .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("JOB_NOT_FOUND") }
        }
        assertThatThrownBy { service.get(created.targetJob.id) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("TARGET_JOB_NOT_FOUND") }
    }

    @Test
    fun foreignAndUnknownIdsReturnTheSameNotFoundResponse() {
        val otherUserId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", otherUserId, "$otherUserId@target-job.test")
        val foreignUsers = Mockito.mock(LocalUserService::class.java)
        Mockito.`when`(foreignUsers.localUserId()).thenReturn(otherUserId)
        val foreignService = TargetJobService(jdbcTemplate, foreignUsers, persistence)
        val foreign = transactions.execute { foreignService.create("Private", "Private target job text. ".repeat(5)) }

        assertNotFound { service.get(foreign.targetJob.id) }
        assertNotFound { service.get(UUID.randomUUID()) }
        assertNotFound { service.rename(foreign.targetJob.id, "Nope") }
        assertNotFound { service.deleteImpact(foreign.targetJob.id) }
        assertNotFound { transactions.execute { service.delete(foreign.targetJob.id) } }
    }

    private fun assertNotFound(action: () -> Unit) {
        assertThatThrownBy(action)
            .isInstanceOfSatisfying(ApiRequestException::class.java) {
                assertThat(it.status()).isEqualTo(HttpStatus.NOT_FOUND)
                assertThat(it.code()).isEqualTo("TARGET_JOB_NOT_FOUND")
            }
    }

    companion object {
        private val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_target_jobs_test")
            .withUsername("ai_interview")
            .withPassword("ai_interview")
        private lateinit var jdbcTemplate: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var service: TargetJobService
        private lateinit var persistence: TargetJobPersistenceService
        private lateinit var userId: UUID
        private lateinit var legacyNamedId: UUID
        private lateinit var legacyUntitledId: UUID

        @BeforeAll
        @JvmStatic
        fun startDatabase() {
            postgres.start()
            val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            jdbcTemplate = JdbcTemplate(dataSource)
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("9"))
                .load()
                .migrate()

            userId = UUID.nameUUIDFromBytes("local@ai-interview.dev".toByteArray())
            jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", userId, "local@ai-interview.dev")
            legacyNamedId = UUID.randomUUID()
            legacyUntitledId = UUID.randomUUID()
            val createdAt = java.sql.Timestamp.from(java.time.Instant.parse("2025-01-02T03:04:05Z"))
            jdbcTemplate.update(
                """
                    INSERT INTO ai_interview_app.job_descriptions (id, user_id, title, raw_text, normalized_text, content_hash, created_at)
                    VALUES (?, ?, '  Platform Engineer  ', 'legacy', 'legacy', 'legacy-hash-1', ?),
                           (?, ?, '   ', 'legacy', 'legacy', 'legacy-hash-2', ?)
                """.trimIndent(),
                legacyNamedId, userId, createdAt, legacyUntitledId, userId, createdAt,
            )
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()

            val localUsers = LocalUserService(jdbcTemplate)
            persistence = TargetJobPersistenceService(jdbcTemplate, ResumeTextNormalizer(), SectionAwareTextChunker(), ContentHasher())
            service = TargetJobService(jdbcTemplate, localUsers, persistence)
            transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
        }

        @AfterAll
        @JvmStatic
        fun stopDatabase() {
            postgres.stop()
        }
    }
}
