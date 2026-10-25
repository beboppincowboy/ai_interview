package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.annotation.JsonIgnore
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.sha256Hex
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.suggestions.experiencePromptText
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate

/** One prompt-visible source as frozen for a run; [contentHash] is a revision hash of label and text, not the RAG index hash. */
data class JobFitFrozenSource(
    val sourceType: JobFitSourceType,
    val resourceId: UUID,
    val sourceLabel: String,
    val text: String,
) {
    @get:JsonIgnore
    val contentHash: String get() = sha256Hex("$sourceLabel\u0000$text")

    fun manifestEntry() = JobFitSourceManifestEntry(sourceType, resourceId, sourceLabel, contentHash)
}

/** Owner-scoped sources frozen at a run's first attempt: one resume, one target job and every experience, sorted by type then ID. */
data class JobFitSourceSnapshot(
    val snapshotId: String,
    val capturedAt: Instant,
    val sources: List<JobFitFrozenSource>,
) {
    fun manifest(): List<JobFitSourceManifestEntry> = sources.map(JobFitFrozenSource::manifestEntry)
}

/** UNKNOWN for a legacy result (null [savedManifest]); otherwise CURRENT only when both manifests hold the same entries in any order. */
fun jobFitFreshness(savedManifest: List<JobFitSourceManifestEntry>?, currentManifest: List<JobFitSourceManifestEntry>): JobFitFreshness = when {
    savedManifest == null -> JobFitFreshness.UNKNOWN
    savedManifest.toSet() == currentManifest.toSet() -> JobFitFreshness.CURRENT
    else -> JobFitFreshness.OUTDATED
}

/** Captures Job Fit sources in one repeatable-read transaction and computes their current manifest for freshness. */
@Service
class JobFitSourceSnapshotService(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val snapshotTransaction = TransactionTemplate(transactionManager).apply {
        isolationLevel = TransactionDefinition.ISOLATION_REPEATABLE_READ
        isReadOnly = true
    }

    /** Restores the job's checkpointed snapshot, or captures and checkpoints one before any provider call. Capture errors match [capture]. */
    fun frozenSources(context: JobExecutionContext, resumeId: UUID, targetJobId: UUID): JobFitSourceSnapshot =
        context.checkpoint(CHECKPOINT_FIELD, JobFitSourceSnapshot::class.java)
            ?: capture(context.userId(), resumeId, targetJobId).also { context.saveCheckpoint(CHECKPOINT_FIELD, it) }

    /** Throws ApiRequestException RESUME_NOT_FOUND (404), RESUME_NOT_READY (409) or TARGET_JOB_NOT_FOUND (404), as the document resolver does. */
    fun capture(userId: UUID, resumeId: UUID, targetJobId: UUID): JobFitSourceSnapshot = requireNotNull(snapshotTransaction.execute {
        val resume = findResume(userId, resumeId) ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
        if (!resume.ready) throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for analysis")
        val targetJob = findTargetJob(userId, targetJobId)
            ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
        JobFitSourceSnapshot(UUID.randomUUID().toString(), Instant.now(), listOf(resume.source, targetJob) + findExperiences(userId))
    })

    /** Manifest of the owner's sources now; an unready, missing or foreign resume/target job is simply absent, which reads as OUTDATED. */
    fun currentManifest(userId: UUID, resumeId: UUID, targetJobId: UUID): List<JobFitSourceManifestEntry> = requireNotNull(snapshotTransaction.execute {
        listOfNotNull(findResume(userId, resumeId)?.takeIf { it.ready }?.source, findTargetJob(userId, targetJobId))
            .plus(findExperiences(userId))
            .map(JobFitFrozenSource::manifestEntry)
    })

    private fun findResume(userId: UUID, resumeId: UUID) = jdbcTemplate.query(
        "SELECT id, name, processing_status, normalized_text FROM ai_interview_app.resumes WHERE id = ? AND user_id = ?",
        RowMapper { rs, _ ->
            ResumeRow(
                JobFitFrozenSource(JobFitSourceType.RESUME, resumeId, rs.getString("name"), rs.getString("normalized_text").orEmpty()),
                rs.getString("processing_status") == "READY" && rs.getString("normalized_text") != null,
            )
        }, resumeId, userId,
    ).firstOrNull()

    private fun findTargetJob(userId: UUID, targetJobId: UUID) = jdbcTemplate.query(
        "SELECT name, normalized_text FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ?",
        RowMapper { rs, _ -> JobFitFrozenSource(JobFitSourceType.TARGET_JOB, targetJobId, rs.getString("name"), rs.getString("normalized_text").orEmpty()) },
        targetJobId, userId,
    ).firstOrNull()

    // ponytail: one unpaged query over the owner's experiences; page it inside the same transaction if catalogs grow large.
    private fun findExperiences(userId: UUID): List<JobFitFrozenSource> = jdbcTemplate.query(
        """
            SELECT id, title, organization, start_date, end_date, description
            FROM ai_interview_app.experiences WHERE user_id = ? ORDER BY id
        """.trimIndent(),
        RowMapper { rs, _ ->
            JobFitFrozenSource(JobFitSourceType.EXPERIENCE, rs.getObject("id", UUID::class.java), rs.getString("title"), experiencePromptText(rs))
        }, userId,
    )

    private data class ResumeRow(val source: JobFitFrozenSource, val ready: Boolean)

    companion object {
        /** Nested job checkpoint field; it never satisfies the legacy root-result checkpoint. */
        const val CHECKPOINT_FIELD = "sourceSnapshot"
    }
}
