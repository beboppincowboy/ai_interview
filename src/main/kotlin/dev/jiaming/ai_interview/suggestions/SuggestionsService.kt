package dev.jiaming.ai_interview.suggestions

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.lockOwnerShared
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

@Service
class SuggestionsService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobSubmissionService: JobSubmissionService,
    private val requestGuard: RedisRequestGuard,
    private val backgroundJobStore: BackgroundJobStore,
    private val objectMapper: ObjectMapper,
    private val transactionOperations: TransactionOperations,
    private val resumePersistenceService: ResumePersistenceService,
) {
    fun get(resumeId: UUID, targetJobId: UUID): SuggestionsView {
        val userId = localUserService.localUserId()
        requireOwnedInputs(userId, resumeId, targetJobId, lockRows = false, requireReady = false)
        val row = findSuggestions(userId, resumeId, targetJobId)
        val current = currentSources(userId, resumeId).associateBy { it.id }
        val stored = row?.resultPayload?.let { objectMapper.readValue(it, ExperienceSuggestionsResult::class.java) }
        // Items whose source was deleted, became the selected resume or belongs to someone else are hidden, not rewritten.
        val visible = stored?.let { result ->
            ExperienceSuggestionsResult(result.items.mapNotNull { item ->
                current[item.source.id]?.takeIf { it.type == item.source.type }?.let { item.copy(source = it) }
            })
        }
        val stale = stored != null && row.sourceIds.orEmpty().toSet() != current.keys
        val latestJob = row?.id?.let { id ->
            backgroundJobStore.findLatestForResource(userId, RESOURCE, id, listOf(JobType.EXPERIENCE_SUGGESTIONS))
                .map(LatestJob::from).orElse(null)
        }
        return SuggestionsView(resumeId, targetJobId, current.isNotEmpty(), stale, visible, row?.resultCreatedAt, latestJob)
    }

    fun run(resumeId: UUID, targetJobId: UUID): JobAcceptedResponse {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return jobSubmissionService.withIdempotency(
            IDEMPOTENCY_ACTION,
            PairRequest(resumeId, targetJobId),
            JobAcceptedResponse::class.java,
            Supplier {
                requireNotNull(transactionOperations.execute {
                    jdbcTemplate.lockOwnerShared(userId)
                    requireOwnedInputs(userId, resumeId, targetJobId, lockRows = true, requireReady = true)
                    if (currentSources(userId, resumeId).isEmpty()) {
                        throw ApiRequestException(HttpStatus.CONFLICT, "NO_EXPERIENCE_SOURCES", "Add another resume or an experience first")
                    }
                    val suggestionsId = upsertAndLockSuggestions(userId, resumeId, targetJobId)
                    val fingerprint = jobSubmissionService.fingerprint(JobType.EXPERIENCE_SUGGESTIONS.name, suggestionsId)
                    val reusable = jobSubmissionService.findReusable(JobType.EXPERIENCE_SUGGESTIONS, fingerprint)
                    if (reusable.isPresent) reusable.get() else {
                        requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
                        jobSubmissionService.createOrReuse(
                            JobType.EXPERIENCE_SUGGESTIONS,
                            RESOURCE,
                            suggestionsId,
                            ExperienceSuggestionsPayload(suggestionsId, resumeId, targetJobId),
                            fingerprint,
                        )
                    }
                })
            },
        )
    }

    /** Every other READY resume and every experience the owner has; the only definition of a current source. */
    fun currentSources(userId: UUID, resumeId: UUID): List<SuggestionSource> = jdbcTemplate.query(
        """
            SELECT 'RESUME' AS type, id, name FROM ai_interview_app.resumes
            WHERE user_id = ? AND id <> ? AND processing_status = 'READY'
            UNION ALL
            SELECT 'EXPERIENCE', id, title FROM ai_interview_app.experiences WHERE user_id = ?
        """.trimIndent(),
        RowMapper { rs, _ ->
            SuggestionSource(SuggestionSourceType.valueOf(rs.getString("type")), rs.getObject("id", UUID::class.java), rs.getString("name"))
        }, userId, resumeId, userId,
    )

    fun promptSources(userId: UUID, resumeId: UUID): List<SuggestionSourceInput> {
        val experiences = jdbcTemplate.query(
            "SELECT id, title, organization, start_date, end_date, description FROM ai_interview_app.experiences WHERE user_id = ?",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) to experienceText(rs) }, userId,
        ).toMap()
        return currentSources(userId, resumeId).mapNotNull { source ->
            when (source.type) {
                SuggestionSourceType.RESUME -> resumePersistenceService.findReadyDocument(userId, source.id)
                    .map<SuggestionSourceInput> { SuggestionSourceInput.Resume(source, it) }.orElse(null)
                SuggestionSourceType.EXPERIENCE -> experiences[source.id]?.let { SuggestionSourceInput.Experience(source, it) }
            }
        }
    }

    private fun experienceText(rs: ResultSet): String {
        val start = rs.getString("start_date")
        val end = rs.getString("end_date")
        val dates = if (start == null && end == null) null else "${start ?: "unknown"} to ${end ?: "present"}"
        return listOfNotNull(rs.getString("title"), rs.getString("organization"), dates, rs.getString("description")).joinToString("\n")
    }

    private fun requireOwnedInputs(userId: UUID, resumeId: UUID, targetJobId: UUID, lockRows: Boolean, requireReady: Boolean) {
        val lock = if (lockRows) " FOR KEY SHARE" else ""
        val resumeStatus = jdbcTemplate.query(
            "SELECT processing_status FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?$lock",
            RowMapper { rs, _ -> rs.getString("processing_status") }, resumeId, userId,
        ).firstOrNull() ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
        if (requireReady && resumeStatus != "READY") {
            throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for analysis")
        }
        val target = jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ?$lock",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, targetJobId, userId,
        ).firstOrNull()
        if (target == null) throw ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
    }

    private fun upsertAndLockSuggestions(userId: UUID, resumeId: UUID, targetJobId: UUID): UUID {
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.experience_suggestions (user_id, resume_id, target_job_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, resume_id, target_job_id) DO NOTHING
            """.trimIndent(), userId, resumeId, targetJobId,
        )
        return jdbcTemplate.query(
            """
                SELECT id FROM ai_interview_app.experience_suggestions
                WHERE user_id = ? AND resume_id = ? AND target_job_id = ?
                FOR UPDATE
            """.trimIndent(),
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, resumeId, targetJobId,
        ).firstOrNull() ?: throw IllegalStateException("Suggestions pair could not be created or loaded")
    }

    private fun findSuggestions(userId: UUID, resumeId: UUID, targetJobId: UUID) = jdbcTemplate.query(
        """
            SELECT id, result_payload::text AS result_payload, source_ids::text AS source_ids, result_created_at
            FROM ai_interview_app.experience_suggestions
            WHERE user_id = ? AND resume_id = ? AND target_job_id = ?
        """.trimIndent(),
        RowMapper { rs, _ ->
            SuggestionsRow(
                rs.getObject("id", UUID::class.java),
                rs.getString("result_payload"),
                rs.getString("source_ids")?.let { objectMapper.readValue(it, Array<UUID>::class.java).toList() },
                rs.getTimestamp("result_created_at")?.toInstant(),
            )
        }, userId, resumeId, targetJobId,
    ).firstOrNull()

    private data class SuggestionsRow(val id: UUID, val resultPayload: String?, val sourceIds: List<UUID>?, val resultCreatedAt: Instant?)
    private data class PairRequest(val resumeId: UUID, val targetJobId: UUID)

    companion object {
        const val RESOURCE = "experience-suggestions"
        private const val IDEMPOTENCY_ACTION = "experience-suggestions"
    }
}
