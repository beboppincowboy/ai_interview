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

/** RAW, unescaped prompt-visible source text and display label; construction does not validate ownership or content.
 * [contentHash] is lowercase SHA-256 hex of UTF-8(label + NUL + text), computed without I/O; it is not the RAG index hash. */
data class JobFitFrozenSource(
    val sourceType: JobFitSourceType,
    val resourceId: UUID,
    val sourceLabel: String,
    val text: String,
) {
    @get:JsonIgnore
    val contentHash: String get() = sha256Hex("$sourceLabel\u0000$text")

    /** Builds the source identity/label/revision entry without text or I/O; invalid manifest fields throw IllegalArgumentException. */
    fun manifestEntry() = JobFitSourceManifestEntry(sourceType, resourceId, sourceLabel, contentHash)
}

/** Frozen source values; [capturedAt] is a UTC instant. Construction does not validate source membership or ordering.
 * [JobFitSourceSnapshotService.capture] supplies selected resume, selected target job, then all owner experiences ordered by UUID. */
data class JobFitSourceSnapshot(
    val snapshotId: String,
    val capturedAt: Instant,
    val sources: List<JobFitFrozenSource>,
) {
    /** Builds entries in source order without I/O; propagates manifest-field validation errors. */
    fun manifest(): List<JobFitSourceManifestEntry> = sources.map(JobFitFrozenSource::manifestEntry)
}

/** Pure comparison: null [savedManifest] gives UNKNOWN, equal entry sets give CURRENT, otherwise OUTDATED.
 * Compares identities, labels and hashes; ignores ordering and duplicate entries and performs no source reads. */
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

    /** Returns RAW source text from the job checkpoint without rereading live sources, or blocks on capture and checkpoint persistence.
     * Caller supplies the job's selected document IDs; restored checkpoints are trusted and not rebound to these arguments.
     * Propagates capture, checkpoint decoding, persistence and lease-loss errors; makes no provider calls. */
    fun frozenSources(context: JobExecutionContext, resumeId: UUID, targetJobId: UUID): JobFitSourceSnapshot =
        context.checkpoint(CHECKPOINT_FIELD, JobFitSourceSnapshot::class.java)
            ?: capture(context.userId(), resumeId, targetJobId).also { context.saveCheckpoint(CHECKPOINT_FIELD, it) }

    /** Reads all owner sources in a read-only repeatable-read transaction; returns RAW text, a new snapshot ID and UTC capture time.
     * Orders selected resume, selected target job, then experiences by UUID; does not persist the snapshot or call providers.
     * Throws ApiRequestException RESUME_NOT_FOUND (404), RESUME_NOT_READY (409), TARGET_JOB_NOT_FOUND (404), or propagates database errors. */
    fun capture(userId: UUID, resumeId: UUID, targetJobId: UUID): JobFitSourceSnapshot = requireNotNull(snapshotTransaction.execute {
        val resume = findResume(userId, resumeId) ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
        if (!resume.ready) throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for analysis")
        val targetJob = findTargetJob(userId, targetJobId)
            ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
        JobFitSourceSnapshot(UUID.randomUUID().toString(), Instant.now(), listOf(resume.source, targetJob) + findExperiences(userId))
    })

    /** Blocks on read-only repeatable-read database reads and builds current identity/label/hash entries without returning source text.
     * Omits an unready, missing or foreign selected document; includes all owner experiences. Propagates database/manifest validation errors. */
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
                // Mirrors ResumePersistenceService.findReadyDocument (READY, non-blank text); isBlank trims all whitespace, btrim only
                // spaces, which agree because ResumeTextNormalizer stores trimmed text.
                rs.getString("processing_status") == "READY" && !rs.getString("normalized_text").isNullOrBlank(),
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
