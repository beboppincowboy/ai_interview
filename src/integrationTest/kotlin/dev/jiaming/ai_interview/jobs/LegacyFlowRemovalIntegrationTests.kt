package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.practice.AttemptStatus
import dev.jiaming.ai_interview.practice.PracticeService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/** V18 (U13): the legacy analysis and interview flow leaves the database; attempt-backed feedback stays. */
@Testcontainers
class LegacyFlowRemovalIntegrationTests {
    @Test
    fun v18RemovesLegacyJobsEffectsAndTablesButKeepsAttemptFeedbackAndRetry() = withDatabase { source ->
        flyway(source, "17").migrate()
        val jdbc = JdbcTemplate(source)
        val user = LocalUserService(jdbc).localUserId()
        val resumeId = insert(jdbc, "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', 'PASTE', 'READY', 'Built Kotlin services.')", user)
        val targetJobId = insert(jdbc, "INSERT INTO ai_interview_app.job_descriptions (id, user_id, name, title, raw_text, normalized_text, content_hash) VALUES (?, ?, 'Platform', 'Platform', 'text', 'text', 'hash')", user)

        // The legacy flow: an assessment, an interview session with a question, an answer and both embedding tables.
        val assessmentId = insert(jdbc, """
            INSERT INTO ai_interview_app.resume_assessments (id, user_id, resume_id, overall_score, technical_depth_score, impact_score,
                clarity_score, relevance_score, ats_score, model_name, prompt_name, input_hash)
            VALUES (?, ?, ?, 70, 70, 70, 70, 70, 70, 'gemini', 'rag-grounded', 'hash')
            """, user, resumeId)
        val sessionId = insert(jdbc, "INSERT INTO ai_interview_app.interview_sessions (id, user_id, resume_id, assessment_id) VALUES (?, ?, ?, ?)", user, resumeId, assessmentId)
        val legacyQuestionId = insert(jdbc, "INSERT INTO ai_interview_app.interview_questions (id, session_id, question_text, category, difficulty, order_index) VALUES (?, ?, 'Why Kotlin?', 'Core', 'Core', 0)", sessionId)
        val answerId = insert(jdbc, "INSERT INTO ai_interview_app.interview_answers (id, question_id, answer_text, score) VALUES (?, ?, 'Because.', 60)", legacyQuestionId)
        insert(jdbc, "INSERT INTO ai_interview_app.question_embeddings (id, question_id, content) VALUES (?, ?, 'Why Kotlin?')", legacyQuestionId)
        insert(jdbc, "INSERT INTO ai_interview_app.answer_embeddings (id, answer_id, content) VALUES (?, ?, 'Because.')", answerId)
        val analysisJob = insertJob(jdbc, user, "ANALYSIS", "resume", resumeId, "PARTIAL", "ASSESSING_RESUME")
        insertEffect(jdbc, analysisJob, "ASSESSMENT", assessmentId)
        insertEffect(jdbc, analysisJob, "QUESTIONS", sessionId)
        val legacyFeedbackJob = insertJob(jdbc, user, "ANSWER_FEEDBACK", "interview-answer", resumeId, "QUEUED", "QUEUED")
        insertEffect(jdbc, legacyFeedbackJob, "ANSWER_FEEDBACK", answerId)

        // The new flow: one scored attempt and one failed attempt on a practice question.
        val setId = insert(jdbc, "INSERT INTO ai_interview_app.practice_sets (id, user_id, resume_id, target_job_id) VALUES (?, ?, ?, ?)", user, resumeId, targetJobId)
        val questionId = insert(jdbc, "INSERT INTO ai_interview_app.practice_questions (id, practice_set_id, user_id, origin, order_index, text, rationale, expected_signals) VALUES (?, ?, ?, 'AI', 1, 'Question?', 'Reason', '[\"signal\"]'::jsonb)", setId, user)
        val scoredAttempt = insert(jdbc, """
            INSERT INTO ai_interview_app.answer_attempts (id, question_id, user_id, number, text, score, feedback)
            VALUES (?, ?, ?, 1, 'First answer', 74, '{"score":74,"summary":"Clear","nextStep":null,"strengths":[],"gaps":[],"betterAnswerOutline":[],"followUpQuestion":null}'::jsonb)
            """, questionId, user)
        val failedAttempt = insert(jdbc, "INSERT INTO ai_interview_app.answer_attempts (id, question_id, user_id, number, text) VALUES (?, ?, ?, 2, 'Second answer')", questionId, user)
        val scoredJob = insertJob(jdbc, user, "ANSWER_FEEDBACK", AttemptFeedbackPayload.RESOURCE, scoredAttempt, "SUCCEEDED", "COMPLETED")
        val scoredEffect = insertEffect(jdbc, scoredJob, "ANSWER_FEEDBACK", UUID.randomUUID())
        val failedJob = insertJob(jdbc, user, "ANSWER_FEEDBACK", AttemptFeedbackPayload.RESOURCE, failedAttempt, "FAILED", "SCORING_ANSWER")

        flyway(source, "18").migrate()

        assertThat(jdbc.queryForList("SELECT id FROM ai_interview_app.background_jobs", UUID::class.java)).containsExactlyInAnyOrder(scoredJob, failedJob)
        assertThat(jdbc.queryForList("SELECT resource_id FROM ai_interview_app.background_job_effects", UUID::class.java)).containsExactly(scoredEffect)
        LEGACY_TABLES.forEach { table ->
            assertThat(tableExists(jdbc, "ai_interview_app", table)).describedAs(table).isFalse()
            assertThat(tableExists(jdbc, "public", table)).describedAs("public.$table is preserved").isTrue()
        }
        assertThatThrownBy { insertEffect(jdbc, scoredJob, "ASSESSMENT", UUID.randomUUID()) }.isInstanceOf(DataIntegrityViolationException::class.java)
        assertThatThrownBy { insertEffect(jdbc, scoredJob, "QUESTIONS", UUID.randomUUID()) }.isInstanceOf(DataIntegrityViolationException::class.java)

        val practice = practiceService(source, jdbc)
        val attempts = practice.questions(user, setId).single().attempts
        assertThat(attempts.map { it.status }).containsExactly(AttemptStatus.SCORED, AttemptStatus.FAILED)
        assertThat(attempts.first().feedback!!.score).isEqualTo(74)
        val retried = practice.retryAttempt(failedAttempt)
        assertThat(retried.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(retried.activeJob!!.jobId).isNotEqualTo(failedJob)
        assertThat(retried.activeJob!!.status).isEqualTo(JobStatus.QUEUED)
        assertThatThrownBy { practice.retryAttempt(scoredAttempt) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("ATTEMPT_NOT_FAILED") }
    }

    @Test
    fun freshInitAndUpgradeFromV8BothValidateThroughV18AndKeepTheVectorStore() {
        withDatabase { source ->
            flyway(source, null).migrate()
            assertMigratedThroughV18(source)
        }
        withDatabase { source ->
            flyway(source, "8").migrate()
            flyway(source, null).migrate()
            assertMigratedThroughV18(source)
        }
    }

    private fun assertMigratedThroughV18(source: DriverManagerDataSource) {
        val flyway = flyway(source, null)
        flyway.validate()
        assertThat(flyway.info().applied().map { it.version.version }).startsWith(*(1..18).map(Int::toString).toTypedArray())
        val jdbc = JdbcTemplate(source)
        assertThat(jdbc.queryForObject("SELECT format_type(atttypid, atttypmod) FROM pg_attribute WHERE attrelid = 'public.vector_store'::regclass AND attname = 'embedding'", String::class.java))
            .isEqualTo("vector(1024)")
        assertThat(jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'public' AND indexname = 'idx_vector_store_embedding'", String::class.java))
            .contains("USING hnsw", "vector_cosine_ops")
        LEGACY_TABLES.forEach { assertThat(tableExists(jdbc, "ai_interview_app", it)).describedAs(it).isFalse() }
    }

    private fun practiceService(source: DriverManagerDataSource, jdbc: JdbcTemplate): PracticeService {
        val jobs = BackgroundJobStore(jdbc, MAPPER)
        val local = LocalUserService(jdbc)
        val guard = RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties(
            "legacy-removal-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)
        ), MAPPER)
        val submissions = JobSubmissionService(
            jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(MAPPER), local, guard,
            PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), MAPPER
        )
        return PracticeService(jdbc, local, submissions, guard, jobs, MAPPER, TransactionTemplate(DataSourceTransactionManager(source)))
    }

    private fun insert(jdbc: JdbcTemplate, sql: String, vararg arguments: Any): UUID =
        UUID.randomUUID().also { jdbc.update(sql.trimIndent(), it, *arguments) }

    private fun insertJob(jdbc: JdbcTemplate, user: UUID, type: String, resourceType: String, resourceId: UUID, status: String, stage: String): UUID =
        insert(jdbc, """
            INSERT INTO ai_interview_app.background_jobs (id, user_id, job_type, resource_type, resource_id, status, stage, request_payload, max_attempts)
            VALUES (?, ?, ?, ?, ?, ?, ?, '{}'::jsonb, 3)
            """, user, type, resourceType, resourceId, status, stage)

    private fun insertEffect(jdbc: JdbcTemplate, jobId: UUID, type: String, resourceId: UUID): UUID {
        jdbc.update("INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id) VALUES (?, ?, ?)", jobId, type, resourceId)
        return resourceId
    }

    private fun tableExists(jdbc: JdbcTemplate, schema: String, table: String) = jdbc.queryForObject(
        "SELECT EXISTS (SELECT 1 FROM information_schema.tables WHERE table_schema = ? AND table_name = ?)", Boolean::class.java, schema, table
    )!!

    private fun flyway(source: DriverManagerDataSource, target: String?): Flyway {
        val configuration = Flyway.configure().dataSource(source).locations("classpath:db/migration")
        if (target != null) configuration.target(target)
        return configuration.load()
    }

    private fun withDatabase(test: (DriverManagerDataSource) -> Unit) {
        val name = "u13_${UUID.randomUUID().toString().replace("-", "")}"
        admin("CREATE DATABASE \"$name\"")
        try {
            test(DriverManagerDataSource("${POSTGRES.jdbcUrl.substringBeforeLast('/')}/$name", POSTGRES.username, POSTGRES.password))
        } finally {
            admin("DROP DATABASE IF EXISTS \"$name\"")
        }
    }

    private fun admin(sql: String) = DriverManager.getConnection(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password).use { connection ->
        connection.createStatement().use { it.execute(sql) }
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_legacy_removal_test").withUsername("ai_interview").withPassword("ai_interview")
        private val MAPPER: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private val LEGACY_TABLES = listOf("question_embeddings", "answer_embeddings", "interview_answers", "interview_questions", "interview_sessions", "resume_assessments")
    }
}
