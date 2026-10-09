package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RequestValidation
import dev.jiaming.ai_interview.common.lockOwnerExclusive
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.score.ResumeScoreResult
import dev.jiaming.ai_interview.score.ResumeScoreSummary
import java.sql.ResultSet
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

@Service
class ResumeLibraryService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val persistenceService: ResumePersistenceService,
    private val normalizer: ResumeTextNormalizer,
    private val requestGuard: RedisRequestGuard,
    private val transactionOperations: TransactionOperations,
    private val storageCleanupService: ResumeStorageCleanupService,
    private val deleteImpactService: DeleteImpactService,
    private val objectMapper: ObjectMapper,
    private val backgroundJobStore: BackgroundJobStore
) {
    fun list(): ResumePage {
        val userId = localUserService.localUserId()
        val items = jdbcTemplate.query(
            "$RESUME_SELECT WHERE r.user_id = ? ORDER BY r.created_at DESC, r.id DESC",
            RowMapper { rs, _ -> toItem(rs) }, userId
        )
        val jobs = backgroundJobStore.findLatestForResources(userId, RESOURCE, items.map { UUID.fromString(it.id) }, LIBRARY_JOB_TYPES)
        return ResumePage(items.map { it.copy(latestJob = jobs[UUID.fromString(it.id)]?.let(LatestJob::from)) })
    }

    fun get(resumeId: UUID): ResumeLibraryDetail {
        val userId = localUserService.localUserId()
        return jdbcTemplate.query(
            "$RESUME_SELECT WHERE r.user_id = ? AND r.id = ?",
            RowMapper { rs, _ -> toDetail(rs) }, userId, resumeId
        ).firstOrNull()?.copy(latestJob = latestJob(userId, resumeId)) ?: notFound()
    }

    fun findByFileHash(fileHash: String): ResumeLibraryItem? {
        val userId = localUserService.localUserId()
        return jdbcTemplate.query(
            "$RESUME_SELECT WHERE r.user_id = ? AND r.file_hash = ? ORDER BY r.created_at DESC LIMIT 1",
            RowMapper { rs, _ -> toItem(rs) }, userId, fileHash
        ).firstOrNull()?.run { copy(latestJob = latestJob(userId, UUID.fromString(id))) }
    }

    fun paste(request: PasteResumeRequest): ResponseEntity<ResumeCreated> {
        val name = RequestValidation.text("name", request.name, 1, 80)
        val jobTitle = request.jobTitle?.let { RequestValidation.text("jobTitle", it, 0, 100).ifEmpty { null } }
        val text = RequestValidation.text("text", request.text, 100, 50_000)
        val normalized = normalizer.normalize(text)
        val fingerprintSource = PasteFingerprint(name, jobTitle, normalized)
        return requestGuard.withIdempotentHttpCache(
            "resume-paste", fingerprintSource, ResumeCreated::class.java
        ) {
            val userId = localUserService.localUserId()
            val outcome = requireNotNull(transactionOperations.execute {
                persistenceService.createPaste(userId, name, jobTitle, text, normalized)
            })
            val response = ResumeCreated(getItem(outcome.resumeId), outcome.duplicate)
            ResponseEntity.status(if (outcome.duplicate) HttpStatus.OK else HttpStatus.CREATED).body(response)
        }
    }

    fun patch(resumeId: UUID, fields: Map<String, Any?>): ResumeLibraryItem = patch(resumeId, objectMapper.valueToTree<JsonNode>(fields))

    private fun patch(resumeId: UUID, body: JsonNode): ResumeLibraryItem {
        if (!body.isObject) throw RequestValidation.invalid("Request body must be an object")
        val unknown = body.fieldNames().asSequence().filterNot { it in setOf("name", "jobTitle") }.firstOrNull()
        if (unknown != null) throw RequestValidation.invalid("$unknown is not a supported resume field")

        val namePresent = body.has("name")
        val name = if (namePresent) {
            val value = body.get("name")
            if (!value.isTextual) throw RequestValidation.invalid("name must be text")
            RequestValidation.text("name", value.asText(), 1, 80)
        } else null
        val jobTitlePresent = body.has("jobTitle")
        val jobTitle = if (!jobTitlePresent || body.get("jobTitle").isNull) null else {
            val value = body.get("jobTitle")
            if (!value.isTextual) throw RequestValidation.invalid("jobTitle must be text or null")
            RequestValidation.text("jobTitle", value.asText(), 0, 100).ifEmpty { null }
        }

        val userId = localUserService.localUserId()
        val updated = requireNotNull(transactionOperations.execute {
            if (namePresent || jobTitlePresent) {
                val count = jdbcTemplate.update(
                    """
                        UPDATE ai_interview_app.resumes
                        SET name = CASE WHEN ? THEN ? ELSE name END,
                            job_title = CASE WHEN ? THEN ? ELSE job_title END,
                            updated_at = now()
                        WHERE id = ? AND user_id = ?
                        """.trimIndent(),
                    namePresent, name, jobTitlePresent, jobTitle, resumeId, userId
                )
                if (count != 1) notFound()
            } else if (!exists(userId, resumeId)) notFound()
            resumeId
        })
        return getItem(updated)
    }

    fun deleteImpact(resumeId: UUID) = deleteImpactService.forResume(localUserService.localUserId(), resumeId)

    fun delete(resumeId: UUID) {
        val userId = localUserService.localUserId()
        val storageKey = transactionOperations.execute {
            lockResourceJobs(userId, resumeId)
            jdbcTemplate.lockOwnerExclusive(userId)
            val ownedResume = jdbcTemplate.query(
                "SELECT id, storage_key FROM ai_interview_app.resumes WHERE id = ? AND user_id = ? FOR UPDATE",
                RowMapper { rs, _ -> OwnedResume(rs.getObject("id", UUID::class.java), rs.getString("storage_key")) },
                resumeId, userId
            ).firstOrNull() ?: notFound()
            val key = ownedResume.storageKey
            if (!key.isNullOrBlank()) persistenceService.enqueueStorageCleanup(key)
            jdbcTemplate.update(
                """
                    DELETE FROM ai_interview_app.background_jobs
                    WHERE user_id = ? AND (
                        (resource_type = 'resume' AND resource_id = ?)
                        OR request_payload ->> 'resumeId' = ?
                    )
                """.trimIndent(), userId, resumeId, resumeId.toString()
            )
            jdbcTemplate.update("DELETE FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?", resumeId, userId)
            key
        }
        if (!storageKey.isNullOrBlank()) storageCleanupService.deleteAndAcknowledge(storageKey)
    }

    private fun getItem(id: UUID): ResumeLibraryItem = get(id).toItem()

    private fun exists(userId: UUID, resumeId: UUID): Boolean = jdbcTemplate.query(
        "SELECT id FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?",
        RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, resumeId, userId
    ).isNotEmpty()

    private fun lockResourceJobs(userId: UUID, resumeId: UUID) {
        jdbcTemplate.query(
            """
                SELECT id FROM ai_interview_app.background_jobs
                WHERE user_id = ? AND (
                    (resource_type = 'resume' AND resource_id = ?)
                    OR request_payload ->> 'resumeId' = ?
                )
                ORDER BY id FOR UPDATE
                """.trimIndent(),
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, resumeId, resumeId.toString()
        )
    }

    // latestJob is filled in by the caller from the job store.
    private fun toItem(rs: ResultSet): ResumeLibraryItem {
        return ResumeLibraryItem(
            rs.getObject("id", UUID::class.java).toString(), rs.getString("name"), rs.getString("job_title"),
            rs.getString("source"), rs.getString("original_filename"), apiStatus(rs.getString("processing_status")),
            latestScore(rs), null, rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant()
        )
    }

    private fun toDetail(rs: ResultSet): ResumeLibraryDetail {
        val item = toItem(rs)
        val text = rs.getString("normalized_text")?.takeIf { item.status == "READY" }
        return ResumeLibraryDetail(
            item.id, item.name, item.jobTitle, item.source, item.originalFilename, item.status,
            item.latestScore, item.latestJob, item.createdAt, item.updatedAt, text,
            rs.getString("score_result")?.let { objectMapper.readValue(it, ResumeScoreResult::class.java) }
        )
    }

    // A score is stale once the resume's job title no longer matches the title it was scored with.
    private fun latestScore(rs: ResultSet): ResumeScoreSummary? {
        val scoredAt = rs.getTimestamp("score_scored_at") ?: return null
        return ResumeScoreSummary(rs.getInt("score_overall"), scoredAt.toInstant(), rs.getString("score_job_title") != rs.getString("job_title"))
    }

    private fun latestJob(userId: UUID, resumeId: UUID): LatestJob? =
        backgroundJobStore.findLatestForResource(userId, RESOURCE, resumeId, LIBRARY_JOB_TYPES).map(LatestJob::from).orElse(null)

    private fun apiStatus(status: String) = if (status == "PENDING") "PROCESSING" else status

    private fun notFound(): Nothing = throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")

    private data class PasteFingerprint(val name: String, val jobTitle: String?, val normalizedText: String)
    private data class OwnedResume(val id: UUID, val storageKey: String?)

    private companion object {
        const val RESOURCE = "resume"
        val LIBRARY_JOB_TYPES = listOf(JobType.RESUME_EXTRACTION, JobType.RESUME_SCORE)
        val RESUME_SELECT = """
            SELECT r.id, r.name, r.job_title, r.source, r.original_filename, r.processing_status,
                   r.normalized_text, r.created_at, r.updated_at,
                   s.overall AS score_overall, s.job_title AS score_job_title, s.scored_at AS score_scored_at,
                   s.result AS score_result
            FROM ai_interview_app.resumes r
            LEFT JOIN LATERAL (
                SELECT overall, job_title, scored_at, result::text AS result
                FROM ai_interview_app.resume_scores
                WHERE user_id = r.user_id AND resume_id = r.id
                ORDER BY scored_at DESC, id DESC LIMIT 1
            ) s ON TRUE
        """.trimIndent()
    }
}
