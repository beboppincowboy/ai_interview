package dev.jiaming.ai_interview.history

import dev.jiaming.ai_interview.common.LocalUserService
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

@Testcontainers
class HistoryIntegrationTests {
    @Test
    fun voiceHistoryIncludesOnlyOwnedSavedRunsAndRetainsReportsAfterJobRetention() {
        val resume = resume(owner, "Backend")
        val job = targetJob(owner, "Acme")
        val set = practiceSet(owner, resume, job)
        val draft = voice(owner, set, resume, job, saved = false)
        val pending = voice(owner, set, resume, job, saved = true)
        val failed = voice(owner, set, resume, job, saved = true, status = "FAILED")
        val complete = voice(owner, set, resume, job, saved = true, report = """{"overallScore":0}""")
        jdbc.update("DELETE FROM ai_interview_app.background_jobs WHERE id = (SELECT report_job_id FROM ai_interview_app.voice_sessions WHERE id = ?)", complete)
        val foreign = id()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id,email) VALUES (?,?)", foreign, "$foreign@history.test")
        val fr = resume(foreign, "Private")
        val fj = targetJob(foreign, "Private job")
        voice(foreign, practiceSet(foreign, fr, fj), fr, fj, saved = true)

        val rows = jacksonObjectMapper().findAndRegisterModules().valueToTree<com.fasterxml.jackson.databind.JsonNode>(history(owner))["voiceSessions"]
        assertThat(rows).isNotNull()
        assertThat(rows.map { UUID.fromString(it["id"].asText()) }).containsExactlyInAnyOrder(pending, failed, complete).doesNotContain(draft)
        assertThat(rows.first { it["id"].asText() == pending.toString() }["reportStatus"].asText()).isEqualTo("QUEUED")
        assertThat(rows.first { it["id"].asText() == failed.toString() }["reportStatus"].asText()).isEqualTo("FAILED")
        val done = rows.first { it["id"].asText() == complete.toString() }
        assertThat(dev.jiaming.ai_interview.common.DeleteImpactService(jdbc).forResume(owner, resume).voiceSessions).isEqualTo(3)
        assertThat(dev.jiaming.ai_interview.common.countSavedVoiceSessions(jdbc, owner, "target_job_id", job)).isEqualTo(3)
        assertThat(done["reportStatus"].asText()).isEqualTo("SUCCEEDED")
        assertThat(done["overallScore"].asInt()).isZero()
        assertThat(done["selectedCount"].asInt()).isEqualTo(2)
        assertThat(done["answeredCount"].asInt()).isEqualTo(1)
        assertThat(done["resumeName"].asText()).isEqualTo("Backend")
        assertThat(done["targetJobName"].asText()).isEqualTo("Acme")
    }

    private fun voice(user: UUID, set: UUID, resume: UUID, job: UUID, saved: Boolean, status: String = "QUEUED", report: String? = null): UUID {
        val session = id()
        val reportJob = id()
        if (saved) jdbc.update(
            "INSERT INTO ai_interview_app.background_jobs (id,user_id,job_type,resource_type,resource_id,status,stage,request_payload,max_attempts) VALUES (?,?,'VOICE_REPORT','voice-session',?,?, 'QUEUED','{}'::jsonb,3)",
            reportJob, user, session, status,
        )
        jdbc.update(
            """
                INSERT INTO ai_interview_app.voice_sessions (id,user_id,practice_set_id,resume_id,target_job_id,questions,run_deadline,draft_expires_at,transcript,transcript_hash,saved_at,submission_job_id,report_job_id,report)
                VALUES (?,?,?,?,?,'[{"id":"q1"},{"id":"q2"}]'::jsonb,now()+interval '20 minutes',now()+interval '24 hours',?::jsonb,?,CASE WHEN ? THEN now() END,?,?,?::jsonb)
            """.trimIndent(),
            session,user,set,resume,job,if (saved) """{"answers":[{"answerText":"An answer"},{"answerText":"  "}]}""" else null,
            if (saved) "hash" else null,saved,if (saved) reportJob else null,if (saved) reportJob else null,report,
        )
        return session
    }

    @Test
    fun aNewUserGetsThreeEmptyArrays() {
        assertThat(history(owner)).isEqualTo(HistoryResponse(emptyList(), emptyList(), emptyList()))
    }

    @Test
    fun trendsListSavedResultsOldestFirstAndSkipUnscoredAttempts() {
        val resume = resume(owner, "Backend")
        score(owner, resume, 72, "2026-09-30T11:00:00Z")
        score(owner, resume, 64, "2026-09-30T10:00:00Z")
        val job = targetJob(owner, "Acme")
        fit(owner, resume, job, 68)
        fit(owner, resume(owner, "Unrun"), job, null)
        val set = practiceSet(owner, resume, job)
        val user = question(owner, set, "USER", 1, "Your question")
        val ai = question(owner, set, "AI", 1, "Why Kafka?")
        attempt(owner, ai, 1, 58)
        attempt(owner, ai, 2, null)
        attempt(owner, ai, 3, 74)

        val history = history(owner)

        assertThat(history.resumes.first { it.id == resume }.scores.map { it.overall }).containsExactly(64, 72)
        assertThat(history.resumes.first { it.name == "Unrun" }.scores).isEmpty()
        assertThat(history.targetJobs.single().fits.map { it.resumeName to it.fitScore }).containsExactly("Backend" to 68)
        val practice = history.practiceSets.single()
        assertThat(practice.resumeName to practice.targetJobName).isEqualTo("Backend" to "Acme")
        assertThat(practice.questions.map { it.id }).containsExactly(ai, user)
        assertThat(practice.questions.map { it.scores }).containsExactly(listOf(58, 74), emptyList())
    }

    @Test
    fun anotherOwnersScoresPairsAndAttemptsAreAbsent() {
        val foreign = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", foreign, "$foreign@history.test")
        val resume = resume(foreign, "Private")
        score(foreign, resume, 90, "2026-09-30T10:00:00Z")
        val job = targetJob(foreign, "Private job")
        fit(foreign, resume, job, 80)
        attempt(foreign, question(foreign, practiceSet(foreign, resume, job), "AI", 1, "Private?"), 1, 70)

        assertThat(history(owner)).isEqualTo(HistoryResponse(emptyList(), emptyList(), emptyList()))
        assertThat(history(foreign).practiceSets.single().questions.single().scores).containsExactly(70)
    }

    private fun history(userId: UUID): HistoryResponse {
        val users = Mockito.mock(LocalUserService::class.java)
        Mockito.`when`(users.localUserId()).thenReturn(userId)
        return HistoryService(jdbc, users).history()
    }

    private fun resume(userId: UUID, name: String) = id().also {
        jdbc.update("INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, ?, 'PASTE', 'READY', 'text')", it, userId, name)
    }

    private fun score(userId: UUID, resumeId: UUID, overall: Int, at: String) = jdbc.update(
        "INSERT INTO ai_interview_app.resume_scores (id, user_id, resume_id, overall, result, scored_at) VALUES (?, ?, ?, ?, '{}'::jsonb, ?::timestamptz)",
        id(), userId, resumeId, overall, at
    )

    private fun targetJob(userId: UUID, name: String) = id().also {
        jdbc.update("INSERT INTO ai_interview_app.job_descriptions (id, user_id, name, title, raw_text, normalized_text, content_hash) VALUES (?, ?, ?, ?, 'text', 'text', ?)", it, userId, name, name, "hash-$it")
    }

    private fun fit(userId: UUID, resumeId: UUID, targetJobId: UUID, fitScore: Int?) = jdbc.update(
        "INSERT INTO ai_interview_app.job_fits (user_id, resume_id, target_job_id, result_payload, result_created_at) VALUES (?, ?, ?, ?::jsonb, CASE WHEN ? THEN now() END)",
        userId, resumeId, targetJobId, fitScore?.let { """{"fitScore":$it}""" }, fitScore != null
    )

    private fun practiceSet(userId: UUID, resumeId: UUID, targetJobId: UUID) = id().also {
        jdbc.update("INSERT INTO ai_interview_app.practice_sets (id, user_id, resume_id, target_job_id) VALUES (?, ?, ?, ?)", it, userId, resumeId, targetJobId)
    }

    private fun question(userId: UUID, setId: UUID, origin: String, order: Int, text: String) = id().also {
        jdbc.update("INSERT INTO ai_interview_app.practice_questions (id, practice_set_id, user_id, origin, order_index, text) VALUES (?, ?, ?, ?, ?, ?)", it, setId, userId, origin, order, text)
    }

    private fun attempt(userId: UUID, questionId: UUID, number: Int, score: Int?) = jdbc.update(
        "INSERT INTO ai_interview_app.answer_attempts (question_id, user_id, number, text, feedback, score) VALUES (?, ?, ?, ?, ?::jsonb, ?)",
        questionId, userId, number, "Answer $number", score?.let { """{"score":$it}""" }, score
    )

    private fun id() = UUID.randomUUID()

    @BeforeEach fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.answer_attempts, ai_interview_app.practice_questions, ai_interview_app.practice_sets, ai_interview_app.job_fits, ai_interview_app.resume_scores, ai_interview_app.job_descriptions, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
        owner = LocalUserService(jdbc).localUserId()
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_history_test").withUsername("ai_interview").withPassword("ai_interview")
        private lateinit var jdbc: JdbcTemplate
        private lateinit var owner: UUID

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
        }
    }
}
