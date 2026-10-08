package dev.jiaming.ai_interview.targetjob

import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.countPracticeAttempts
import dev.jiaming.ai_interview.common.countSavedVoiceSessions
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class TargetJobService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobDescriptionPersistenceService: JobDescriptionPersistenceService,
) {
    @Transactional
    fun create(name: String, text: String): TargetJobCreateResult {
        val userId = localUserService.localUserId()
        val saved = jobDescriptionPersistenceService.findOrCreateTargetJob(userId, name, text)
        val targetJob = findDetail(userId, saved.document.resourceId()) ?: throw notFound()
        return TargetJobCreateResult(targetJob, !saved.created)
    }

    fun list(): TargetJobListResponse {
        val userId = localUserService.localUserId()
        return TargetJobListResponse(jdbcTemplate.query(
            """
                SELECT id, name, created_at, updated_at
                FROM ai_interview_app.job_descriptions
                WHERE user_id = ?
                ORDER BY created_at DESC, id DESC
            """.trimIndent(),
            { rs, _ -> rs.toTargetJob() },
            userId,
        ))
    }

    fun get(targetJobId: UUID): TargetJobDetail {
        val userId = localUserService.localUserId()
        return findDetail(userId, targetJobId) ?: throw notFound()
    }

    @Transactional
    fun rename(targetJobId: UUID, name: String): TargetJob {
        val userId = localUserService.localUserId()
        val updated = jdbcTemplate.update(
            """
                UPDATE ai_interview_app.job_descriptions
                SET name = ?, updated_at = now()
                WHERE id = ? AND user_id = ?
            """.trimIndent(),
            name,
            targetJobId,
            userId,
        )
        if (updated != 1) throw notFound()
        return findSummary(userId, targetJobId) ?: throw notFound()
    }

    fun deleteImpact(targetJobId: UUID): TargetJobDeleteImpact {
        val userId = localUserService.localUserId()
        requireOwned(userId, targetJobId)
        val fits = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.job_fits WHERE target_job_id = ? AND user_id = ? AND result_payload IS NOT NULL",
            Int::class.java, targetJobId, userId
        ) ?: 0
        val practiceSets = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.practice_sets WHERE target_job_id = ? AND user_id = ?",
            Int::class.java, targetJobId, userId
        ) ?: 0
        val suggestionSets = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.experience_suggestions WHERE target_job_id = ? AND user_id = ? AND result_payload IS NOT NULL",
            Int::class.java, targetJobId, userId
        ) ?: 0
        val attempts = countPracticeAttempts(jdbcTemplate, userId, "target_job_id", targetJobId)
        return TargetJobDeleteImpact(fits = fits, suggestionSets = suggestionSets, practiceSets = practiceSets, attempts = attempts,
            voiceSessions = countSavedVoiceSessions(jdbcTemplate, userId, "target_job_id", targetJobId))
    }

    @Transactional
    fun delete(targetJobId: UUID) {
        val userId = localUserService.localUserId()
        // Match worker job-then-resource locking; stable ordering also keeps overlapping deletes consistent.
        val affectedJobIds = lockAffectedJobs(userId, targetJobId).toMutableSet()
        val ownedId = jdbcTemplate.query(
            """
                SELECT id
                FROM ai_interview_app.job_descriptions
                WHERE id = ? AND user_id = ?
                FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            targetJobId,
            userId,
        ).firstOrNull() ?: throw notFound()

        // A fit request can finish creating its job while this delete waits for the target row.
        // Re-read after taking the row lock so that job cannot outlive its pair.
        affectedJobIds += lockAffectedJobs(userId, targetJobId)

        if (affectedJobIds.isNotEmpty()) {
            val placeholders = affectedJobIds.joinToString { "?" }
            jdbcTemplate.update(
                "DELETE FROM ai_interview_app.background_jobs WHERE user_id = ? AND id IN ($placeholders)",
                userId,
                *affectedJobIds.toTypedArray(),
            )
        }
        val deleted = jdbcTemplate.update(
            "DELETE FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ?",
            ownedId,
            userId,
        )
        if (deleted != 1) throw notFound()
    }

    private fun lockAffectedJobs(userId: UUID, targetJobId: UUID): List<UUID> = jdbcTemplate.query(
        """
            SELECT id
            FROM ai_interview_app.background_jobs
            WHERE user_id = ?
              AND (
                  (resource_type = 'target-job' AND resource_id = ?)
                  OR request_payload ->> 'targetJobId' = ?
                  OR request_payload ->> 'jobDescriptionId' = ?
              )
            ORDER BY id ASC
            FOR UPDATE
        """.trimIndent(),
        { rs, _ -> rs.getObject("id", UUID::class.java) },
        userId, targetJobId, targetJobId.toString(), targetJobId.toString(),
    )

    private fun requireOwned(userId: UUID, targetJobId: UUID) {
        val exists = jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ?",
            { rs, _ -> rs.getObject("id", UUID::class.java) },
            targetJobId,
            userId,
        ).isNotEmpty()
        if (!exists) throw notFound()
    }

    private fun findSummary(userId: UUID, targetJobId: UUID) = jdbcTemplate.query(
        """
            SELECT id, name, created_at, updated_at
            FROM ai_interview_app.job_descriptions
            WHERE id = ? AND user_id = ?
        """.trimIndent(),
        { rs, _ -> rs.toTargetJob() },
        targetJobId,
        userId,
    ).firstOrNull()

    private fun findDetail(userId: UUID, targetJobId: UUID) = jdbcTemplate.query(
        """
            SELECT id, name, raw_text, created_at, updated_at
            FROM ai_interview_app.job_descriptions
            WHERE id = ? AND user_id = ?
        """.trimIndent(),
        { rs, _ ->
            TargetJobDetail(
                rs.getObject("id", UUID::class.java),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant(),
                rs.getString("raw_text"),
            )
        },
        targetJobId,
        userId,
    ).firstOrNull()

    private fun ResultSet.toTargetJob() = TargetJob(
        getObject("id", UUID::class.java),
        getString("name"),
        getTimestamp("created_at").toInstant(),
        getTimestamp("updated_at").toInstant(),
    )

    private fun notFound() = ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
}

data class TargetJobCreateResult(val targetJob: TargetJobDetail, val duplicate: Boolean)
data class TargetJobListResponse(val items: List<TargetJob>)
data class TargetJob(val id: UUID, val name: String, val createdAt: Instant, val updatedAt: Instant)
data class TargetJobDetail(val id: UUID, val name: String, val createdAt: Instant, val updatedAt: Instant, val text: String)
data class TargetJobDeleteImpact(
    val scores: Int = 0,
    val fits: Int = 0,
    val suggestionSets: Int = 0,
    val practiceSets: Int = 0,
    val attempts: Int = 0,
    val staleSuggestionSets: Int = 0,
    val voiceSessions: Int = 0,
)
