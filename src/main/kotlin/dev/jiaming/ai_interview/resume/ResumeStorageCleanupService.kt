package dev.jiaming.ai_interview.resume

import java.time.Duration
import java.time.Instant
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

@Service
class ResumeStorageCleanupService(
    private val jdbcTemplate: JdbcTemplate,
    private val storageService: ResumeStorageService
) {
    /**
     * Runs after the caller's resource transaction has settled, so this insert auto-commits before the S3 delete.
     * The delete is tried even when the intent cannot be recorded; if both fail, [sweepOrphans] removes the object later.
     */
    fun scheduleAndDelete(storageKey: String) {
        val recorded = try {
            record(storageKey)
            true
        } catch (exception: RuntimeException) {
            log.warn("resume_storage_cleanup_schedule_failed storageKey={} reason={}", storageKey, exception.message)
            false
        }
        try {
            if (!deleteAndAcknowledge(storageKey) && !recorded) {
                log.error("resume_storage_cleanup_unrecorded storageKey={} the orphan sweep will remove it", storageKey)
            }
        } catch (exception: RuntimeException) {
            // The object is gone; the intent row stays and the next retry deletes it again harmlessly.
            log.warn("resume_storage_cleanup_acknowledge_failed storageKey={} reason={}", storageKey, exception.message)
        }
    }

    private fun record(storageKey: String) {
        jdbcTemplate.update(
            "INSERT INTO ai_interview_app.storage_cleanup (storage_key) VALUES (?) ON CONFLICT (storage_key) DO NOTHING",
            storageKey
        )
    }

    /** Idempotent S3 deletion runs outside the JDBC transaction; the durable intent is acknowledged only on success. */
    fun deleteAndAcknowledge(storageKey: String): Boolean {
        try {
            storageService.delete(storageKey)
        } catch (exception: RuntimeException) {
            log.warn("resume_storage_cleanup_delete_failed storageKey={} reason={}", storageKey, exception.message)
            return false
        }
        jdbcTemplate.update("DELETE FROM ai_interview_app.storage_cleanup WHERE storage_key = ?", storageKey)
        return true
    }

    fun retryPending(limit: Int): Int {
        val keys = jdbcTemplate.query(
            "SELECT storage_key FROM ai_interview_app.storage_cleanup ORDER BY created_at LIMIT ?",
            { rs, _ -> rs.getString("storage_key") }, limit
        )
        return keys.count { deleteAndAcknowledge(it) }
    }

    /**
     * Deletes stored resume objects older than [ORPHAN_GRACE] that no resume row references, one page per call.
     * The grace period covers uploads whose resume row is not committed yet. A failed reference lookup throws before
     * anything is deleted.
     */
    fun sweepOrphans(now: Instant = Instant.now()): Int {
        val page = storageService.listObjects(sweepCursor, SWEEP_PAGE_SIZE)
        sweepCursor = if (page.truncated) page.objects.lastOrNull()?.key else null
        val candidates = page.objects.filter { it.lastModified.isBefore(now.minus(ORPHAN_GRACE)) }.map { it.key }
        if (candidates.isEmpty()) return 0
        val referenced = jdbcTemplate.queryForList(
            "SELECT storage_key FROM ai_interview_app.resumes WHERE storage_key IN (${candidates.joinToString { "?" }})",
            String::class.java, *candidates.toTypedArray()
        ).toSet()
        return candidates.filterNot(referenced::contains).count { key ->
            try {
                storageService.delete(key)
                log.info("resume_storage_orphan_deleted storageKey={}", key)
                true
            } catch (exception: RuntimeException) {
                log.warn("resume_storage_orphan_delete_failed storageKey={} reason={}", key, exception.message)
                false
            }
        }
    }

    // ponytail: the cursor lives in memory, so each process restart begins a fresh pass; persist it if buckets grow large.
    @Volatile private var sweepCursor: String? = null

    private companion object {
        const val SWEEP_PAGE_SIZE = 100
        val ORPHAN_GRACE: Duration = Duration.ofHours(24)
        val log = LoggerFactory.getLogger(ResumeStorageCleanupService::class.java)
    }
}
