package dev.jiaming.ai_interview.jobs

import java.util.UUID
import java.util.function.Consumer
import java.util.function.Supplier
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.fasterxml.jackson.databind.JsonNode
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.practice.PracticeQuestionDraft
import dev.jiaming.ai_interview.score.ResumeScoreResult
import dev.jiaming.ai_interview.voice.VoiceReportPayload
import dev.jiaming.ai_interview.voice.VoiceSessionReport

@Service
class JobEffectMaterializationService(private val jdbcTemplate: JdbcTemplate, private val objectMapper: ObjectMapper) {
    @Transactional
    fun materializeResumeScore(job: BackgroundJob, leaseToken: UUID, resumeId: UUID, response: ResumeScoreResult): UUID {
        lockOwnedLease(job.id, leaseToken)
        val userId = job.requireUserId()
        return materialize(job.id, JobEffectType.RESUME_SCORE) { id ->
            val resultJson = try { objectMapper.writeValueAsString(response) }
            catch (exception: JsonProcessingException) { throw IllegalStateException("Could not serialize resume score", exception) }
            jdbcTemplate.update(
                """
                    INSERT INTO ai_interview_app.resume_scores (id, user_id, resume_id, job_title, overall, result, scored_at)
                    VALUES (?, ?, ?, ?, ?, ?::jsonb, ?)
                """.trimIndent(),
                id, userId, resumeId, response.jobTitle, response.overall, resultJson, java.sql.Timestamp.from(response.scoredAt)
            )
        }
    }
    @Transactional
    fun materializeJobFit(job: BackgroundJob, leaseToken: UUID, fitId: UUID, response: JsonNode) {
        lockOwnedLease(job.id, leaseToken)
        if (!response.isObject) throw IllegalArgumentException("Job fit result must be a JSON object")
        val userId = job.requireUserId()
        claimEffect(job.id, JobEffectType.JOB_FIT, fitId)
        val updated = jdbcTemplate.update("""
            UPDATE ai_interview_app.job_fits
            SET result_payload = ?::jsonb, result_created_at = now()
            WHERE id = ? AND user_id = ?
            """, response.toString(), fitId, userId)
        if (updated != 1) throw IllegalStateException("Job fit $fitId was not found for job owner $userId")
    }
    @Transactional
    fun materializePracticeQuestions(job: BackgroundJob, leaseToken: UUID, practiceSetId: UUID, drafts: List<PracticeQuestionDraft>): UUID {
        lockOwnedLease(job.id, leaseToken)
        val userId = job.requireUserId()
        return materialize(job.id, JobEffectType.PRACTICE_QUESTIONS) { _ ->
            // Takes the set's row lock first, so user-question adds wait for the AI questions instead of interleaving.
            val updated = jdbcTemplate.update(
                "UPDATE ai_interview_app.practice_sets SET updated_at = now() WHERE id = ? AND user_id = ?", practiceSetId, userId
            )
            if (updated != 1) throw IllegalStateException("Practice set $practiceSetId was not found for job owner $userId")
            drafts.forEachIndexed { index, draft ->
                jdbcTemplate.update(
                    """
                        INSERT INTO ai_interview_app.practice_questions
                            (practice_set_id, user_id, origin, order_index, text, rationale, category, expected_signals)
                        VALUES (?, ?, 'AI', ?, ?, ?, ?, ?::jsonb)
                    """.trimIndent(),
                    practiceSetId, userId, index + 1, draft.text, draft.rationale, draft.category,
                    objectMapper.writeValueAsString(draft.expectedSignals)
                )
            }
        }
    }
    @Transactional
    fun materializeExperienceSuggestions(job: BackgroundJob, leaseToken: UUID, suggestionsId: UUID, result: JsonNode, sourceIds: List<UUID>) {
        lockOwnedLease(job.id, leaseToken)
        if (!result.isObject) throw IllegalArgumentException("Experience suggestions result must be a JSON object")
        val userId = job.requireUserId()
        claimEffect(job.id, JobEffectType.EXPERIENCE_SUGGESTIONS, suggestionsId)
        val updated = jdbcTemplate.update("""
            UPDATE ai_interview_app.experience_suggestions
            SET result_payload = ?::jsonb, source_ids = ?::jsonb, result_created_at = now()
            WHERE id = ? AND user_id = ?
            """, result.toString(), objectMapper.writeValueAsString(sourceIds), suggestionsId, userId)
        if (updated != 1) throw IllegalStateException("Experience suggestions $suggestionsId were not found for job owner $userId")
    }
    @Transactional
    fun materializeAttemptFeedback(job: BackgroundJob, leaseToken: UUID, attemptId: UUID, feedback: AnswerFeedbackResult): UUID {
        lockOwnedLease(job.id, leaseToken)
        val userId = job.requireUserId()
        return materialize(job.id, JobEffectType.ANSWER_FEEDBACK) { _ ->
            val updated = jdbcTemplate.update(
                "UPDATE ai_interview_app.answer_attempts SET feedback = ?::jsonb, score = ? WHERE id = ? AND user_id = ?",
                objectMapper.writeValueAsString(feedback), feedback.score, attemptId, userId
            )
            if (updated != 1) throw IllegalStateException("Attempt $attemptId was not found for job owner $userId")
        }
    }
    @Transactional
    fun materializeVoiceReport(job: BackgroundJob, leaseToken: UUID, sessionId: UUID, report: VoiceSessionReport) {
        lockOwnedLease(job.id, leaseToken)
        require(job.jobType == JobType.VOICE_REPORT && job.resourceType == VoiceReportPayload.RESOURCE && job.resourceId == sessionId) {
            "Voice report job does not own session $sessionId"
        }
        val existing = findEffect(job.id, JobEffectType.VOICE_REPORT)
        if (existing != null) {
            if (existing != sessionId) throw IllegalStateException("Job ${job.id} already materialized a different voice session")
            return
        }
        claimEffect(job.id, JobEffectType.VOICE_REPORT, sessionId)
        val updated = jdbcTemplate.update(
            "UPDATE ai_interview_app.voice_sessions SET report = ?::jsonb WHERE id = ? AND user_id = ? AND saved_at IS NOT NULL AND report_job_id = ?",
            objectMapper.writeValueAsString(report), sessionId, job.requireUserId(), job.id,
        )
        if (updated != 1) throw JobLeaseLostException(job.id)
    }
    @Transactional
    fun <T> withOwnedLease(job: BackgroundJob, leaseToken: UUID, work: Supplier<T>): T {
        lockOwnedLease(job.id, leaseToken)
        return work.get()
    }
    private fun materialize(jobId: UUID, effectType: JobEffectType, writer: Consumer<UUID>): UUID {
        findEffect(jobId, effectType)?.let { return it }
        val resourceId = UUID.randomUUID()
        val inserted = jdbcTemplate.update("""
            INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id)
            VALUES (?, ?, ?)
            ON CONFLICT (job_id, effect_type) DO NOTHING
            """, jobId, effectType.name, resourceId)
        if (inserted == 0) return requireEffect(jobId, effectType)
        writer.accept(resourceId)
        return resourceId
    }
    // A pair job updates its existing resource in place; the effect row pins which resource that is across retries.
    private fun claimEffect(jobId: UUID, effectType: JobEffectType, resourceId: UUID) {
        if (findEffect(jobId, effectType) == null) {
            jdbcTemplate.update("""
                INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id)
                VALUES (?, ?, ?)
                ON CONFLICT (job_id, effect_type) DO NOTHING
                """, jobId, effectType.name, resourceId)
        }
        if (findEffect(jobId, effectType) != resourceId) {
            throw IllegalStateException("Job $jobId already materialized a different $effectType resource")
        }
    }
    private fun lockOwnedLease(jobId: UUID, leaseToken: UUID) {
        val jobs = jdbcTemplate.query("""
            SELECT id FROM ai_interview_app.background_jobs
            WHERE id = ? AND status = 'PROCESSING' AND lease_token = ? AND lease_expires_at > now()
            FOR UPDATE
            """, { rs, _ -> rs.getObject("id", UUID::class.java) }, jobId, leaseToken)
        if (jobs.isEmpty()) throw JobLeaseLostException(jobId)
    }
    private fun requireEffect(jobId: UUID, effectType: JobEffectType): UUID = findEffect(jobId, effectType)
        ?: throw IllegalStateException("Missing $effectType effect for job $jobId")
    private fun findEffect(jobId: UUID, effectType: JobEffectType): UUID? = jdbcTemplate.query(
        "SELECT resource_id FROM ai_interview_app.background_job_effects WHERE job_id = ? AND effect_type = ?",
        { rs, _ -> rs.getObject("resource_id", UUID::class.java) }, jobId, effectType.name
    ).firstOrNull()
}
