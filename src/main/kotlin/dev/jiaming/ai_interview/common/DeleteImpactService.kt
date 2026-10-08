package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@JvmRecord
data class DeleteImpact(
    val scores: Int,
    val fits: Int,
    val suggestionSets: Int,
    val practiceSets: Int,
    val attempts: Int,
    val staleSuggestionSets: Int,
    val voiceSessions: Int = 0,
)

@Service
class DeleteImpactService(private val jdbcTemplate: JdbcTemplate) {
    fun forResume(userId: java.util.UUID, resumeId: java.util.UUID): DeleteImpact {
        val exists = jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?",
            { rs, _ -> rs.getObject("id", java.util.UUID::class.java) }, resumeId, userId
        ).isNotEmpty()
        if (!exists) throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")

        val scores = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.resume_scores WHERE resume_id = ? AND user_id = ?",
            Int::class.java, resumeId, userId
        ) ?: 0
        val fits = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.job_fits WHERE resume_id = ? AND user_id = ? AND result_payload IS NOT NULL",
            Int::class.java, resumeId, userId
        ) ?: 0
        val practiceSets = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.practice_sets WHERE resume_id = ? AND user_id = ?",
            Int::class.java, resumeId, userId
        ) ?: 0
        val suggestionSets = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.experience_suggestions WHERE resume_id = ? AND user_id = ? AND result_payload IS NOT NULL",
            Int::class.java, resumeId, userId
        ) ?: 0
        // Other pairs keep their suggestions; the ones built from this resume become stale.
        val staleSuggestionSets = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.experience_suggestions WHERE user_id = ? AND resume_id <> ? AND source_ids @> jsonb_build_array(?::text)",
            Int::class.java, userId, resumeId, resumeId.toString()
        ) ?: 0
        return DeleteImpact(scores, fits, suggestionSets, practiceSets, countPracticeAttempts(jdbcTemplate, userId, "resume_id", resumeId), staleSuggestionSets,
            countSavedVoiceSessions(jdbcTemplate, userId, "resume_id", resumeId))
    }
}

internal fun countSavedVoiceSessions(jdbcTemplate: JdbcTemplate, userId: java.util.UUID, pairColumn: String, id: java.util.UUID): Int {
    require(pairColumn == "resume_id" || pairColumn == "target_job_id") { "Unsupported pair column $pairColumn" }
    return jdbcTemplate.queryForObject(
        "SELECT count(*) FROM ai_interview_app.voice_sessions WHERE $pairColumn = ? AND user_id = ? AND saved_at IS NOT NULL",
        Int::class.java, id, userId,
    ) ?: 0
}

/** Attempts in the owner's practice sets whose [pairColumn] (`resume_id` or `target_job_id`) is [id]; deleting that side cascades to exactly these. */
internal fun countPracticeAttempts(jdbcTemplate: JdbcTemplate, userId: java.util.UUID, pairColumn: String, id: java.util.UUID): Int {
    require(pairColumn == "resume_id" || pairColumn == "target_job_id") { "Unsupported pair column $pairColumn" }
    return jdbcTemplate.queryForObject(
        """
            SELECT count(*)
            FROM ai_interview_app.answer_attempts a
            JOIN ai_interview_app.practice_questions q ON q.id = a.question_id AND q.user_id = a.user_id
            JOIN ai_interview_app.practice_sets s ON s.id = q.practice_set_id AND s.user_id = q.user_id
            WHERE s.$pairColumn = ? AND s.user_id = ?
        """.trimIndent(),
        Int::class.java, id, userId
    ) ?: 0
}
