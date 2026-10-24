package dev.jiaming.ai_interview.experience

import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.DeleteImpact
import dev.jiaming.ai_interview.common.RequestValidation
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.sql.ResultSet
import java.util.Locale
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class ExperienceService(
    private val jdbcTemplate: JdbcTemplate,
    private val contentHasher: ContentHasher,
    private val jobSubmissionService: JobSubmissionService
) {
    fun list(userId: UUID): ExperienceListResponse = ExperienceListResponse(jdbcTemplate.query(
        "SELECT * FROM ai_interview_app.experiences WHERE user_id = ? ORDER BY created_at DESC, id",
        experienceRowMapper,
        userId
    ))

    /** Renaming re-keys duplicate detection; 409 EXPERIENCE_DUPLICATE if the new title + description matches another experience. */
    @Transactional
    fun rename(userId: UUID, experienceId: UUID, request: ExperienceRenameRequest): Experience {
        val title = RequestValidation.text("title", request.title, 1, 120)
        val current = jdbcTemplate.query(
            "SELECT id, title, organization, start_date, end_date, description, source, created_at FROM ai_interview_app.experiences WHERE user_id = ? AND id = ? FOR UPDATE",
            experienceRowMapper,
            userId,
            experienceId
        ).firstOrNull() ?: throw experienceNotFound()
        val hash = contentHash(title, current.description)
        findByHash(userId, hash)?.let { if (it.id != experienceId) throw duplicateExperienceConflict() }
        return try {
            jdbcTemplate.query(
                "UPDATE ai_interview_app.experiences SET title = ?, content_hash = ? WHERE user_id = ? AND id = ? RETURNING id, title, organization, start_date, end_date, description, source, created_at",
                experienceRowMapper,
                title,
                hash,
                userId,
                experienceId
            ).firstOrNull() ?: throw experienceNotFound()
        } catch (exception: DuplicateKeyException) {
            throw duplicateExperienceConflict()
        }
    }

    // Only suggestion sets that used the experience are affected; they are kept and read as stale.
    fun deleteImpact(userId: UUID, experienceId: UUID): DeleteImpact {
        jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.experiences WHERE user_id = ? AND id = ?",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, experienceId
        ).firstOrNull() ?: throw experienceNotFound()
        val stale = jdbcTemplate.queryForObject(
            "SELECT count(*) FROM ai_interview_app.experience_suggestions WHERE user_id = ? AND source_ids @> jsonb_build_array(?::text)",
            Int::class.java, userId, experienceId.toString()
        ) ?: 0
        return DeleteImpact(0, 0, 0, 0, 0, stale)
    }

    fun delete(userId: UUID, experienceId: UUID) {
        val deleted = jdbcTemplate.update("DELETE FROM ai_interview_app.experiences WHERE user_id = ? AND id = ?", userId, experienceId)
        if (deleted != 1) throw experienceNotFound()
    }

    @Transactional
    fun create(userId: UUID, input: ExperienceInput): ExperienceCreated {
        val draft = validate(input)
        val hash = contentHash(draft.title, draft.description)
        val inserted = insert(userId, draft, ExperienceSource.FORM, hash)
        if (inserted != null) return ExperienceCreated(inserted, false)
        val existing = findByHash(userId, hash) ?: throw IllegalStateException("Experience duplicate could not be loaded after insert conflict")
        return ExperienceCreated(existing, true)
    }

    /** Saves 1–30 reviewed items as source LINKEDIN; items matching a saved experience go to `duplicates`, not stored. */
    @Transactional
    fun batch(userId: UUID, request: ExperienceBatchRequest): ExperienceBatchResult {
        val inputs = request.items
        if (inputs == null || inputs.size !in 1..30) {
            throw RequestValidation.invalid("items must contain 1 to 30 experiences")
        }
        val created = mutableListOf<Experience>()
        val duplicates = mutableListOf<ExperienceBatchDuplicate>()
        for (input in inputs) {
            val draft = validate(input)
            val hash = contentHash(draft.title, draft.description)
            val inserted = insert(userId, draft, ExperienceSource.LINKEDIN, hash)
            if (inserted != null) {
                created += inserted
            } else {
                val existing = findByHash(userId, hash)
                    ?: throw IllegalStateException("Experience duplicate could not be loaded after insert conflict")
                duplicates += ExperienceBatchDuplicate(draft.title, existing.id, existing.title)
            }
        }
        return ExperienceBatchResult(created, duplicates)
    }

    fun split(request: ExperienceSplitRequest): JobAcceptedResponse {
        val text = RequestValidation.text("text", request.text, 50, 20_000)
        // A null resource deliberately bypasses active-job reuse; each explicit split is a new review job.
        return jobSubmissionService.submit(
            JobType.EXPERIENCE_SPLIT,
            null,
            null,
            ExperienceSplitJobPayload(text = text)
        )
    }

    fun annotateDuplicates(userId: UUID, result: ExperienceSplitResult): ExperienceSplitResult {
        val hashes = result.items.map { contentHash(it.title, it.description) }.distinct()
        val existingByHash = findByHashes(userId, hashes)
        return ExperienceSplitResult(result.items.map { item ->
            val existing = existingByHash[contentHash(item.title, item.description)]
            item.copy(duplicateOf = existing?.let { ExperienceDuplicate(it.id, it.title) })
        })
    }

    private fun validate(input: ExperienceInput): ExperienceDraft {
        val title = RequestValidation.text("title", input.title, 1, 120)
        val organization = RequestValidation.text("organization", input.organization, 0, 120).ifBlank { null }
        val startDate = RequestValidation.month("startDate", input.startDate)
        val endDate = RequestValidation.month("endDate", input.endDate)
        val description = RequestValidation.text("description", input.description, 1, 4_000)
        return ExperienceDraft(title, organization, startDate, endDate, description)
    }

    private fun insert(userId: UUID, draft: ExperienceDraft, source: ExperienceSource, hash: String): Experience? =
        jdbcTemplate.query(
            """
                INSERT INTO ai_interview_app.experiences
                    (user_id, title, organization, start_date, end_date, description, source, content_hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (user_id, content_hash) DO NOTHING
                RETURNING id, title, organization, start_date, end_date, description, source, created_at
            """.trimIndent(),
            experienceRowMapper,
            userId,
            draft.title,
            draft.organization,
            draft.startDate,
            draft.endDate,
            draft.description,
            source.name,
            hash
        ).firstOrNull()

    private fun findByHash(userId: UUID, hash: String): Experience? = jdbcTemplate.query(
        "SELECT id, title, organization, start_date, end_date, description, source, created_at FROM ai_interview_app.experiences WHERE user_id = ? AND content_hash = ?",
        experienceRowMapper,
        userId,
        hash
    ).firstOrNull()

    private fun findByHashes(userId: UUID, hashes: List<String>): Map<String, Experience> {
        if (hashes.isEmpty()) return emptyMap()
        val placeholders = hashes.joinToString(",") { "?" }
        val args: Array<Any?> = arrayOf<Any?>(userId, *hashes.toTypedArray())
        return jdbcTemplate.query(
            "SELECT id, title, organization, start_date, end_date, description, source, created_at, content_hash FROM ai_interview_app.experiences WHERE user_id = ? AND content_hash IN ($placeholders)",
            RowMapper { rs, _ -> contentHash(rs) to mapExperience(rs) },
            *args
        ).toMap()
    }

    private fun contentHash(resultSet: ResultSet): String = resultSet.getString("content_hash")

    private fun contentHash(title: String, description: String): String = contentHasher.sha256(
        "${normalize(title)}|${normalize(description)}"
    )

    private fun experienceNotFound() = ApiRequestException(HttpStatus.NOT_FOUND, "EXPERIENCE_NOT_FOUND", "Experience not found")

    private fun duplicateExperienceConflict() = ApiRequestException(
        HttpStatus.CONFLICT,
        "EXPERIENCE_DUPLICATE",
        "An experience with this title and description already exists"
    )

    private fun normalize(value: String) = value.trim().replace(WHITESPACE, " ").lowercase(Locale.ROOT)

    private data class ExperienceDraft(
        val title: String,
        val organization: String?,
        val startDate: String?,
        val endDate: String?,
        val description: String
    )

    private companion object {
        val WHITESPACE = Regex("\\s+")
        val experienceRowMapper = RowMapper { rs, _ -> mapExperience(rs) }

        fun mapExperience(rs: ResultSet) = Experience(
            rs.getObject("id", UUID::class.java),
            rs.getString("title"),
            rs.getString("organization"),
            rs.getString("start_date"),
            rs.getString("end_date"),
            rs.getString("description"),
            ExperienceSource.valueOf(rs.getString("source")),
            rs.getTimestamp("created_at").toInstant()
        )
    }
}
