package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.jobs.LatestJob
import java.time.Instant
import java.util.UUID

/** Saved fit for a resume/target-job pair; result and createdAt (UTC) are null before a result is saved.
 * Freshness describes the saved result independently of latestJob; UNKNOWN does not assert that sources are unchanged.
 * The current service always returns UNKNOWN; source-change evaluation is not implemented yet.
 */
data class FitView(
    val resumeId: UUID,
    val targetJobId: UUID,
    val result: JobFitResult?,
    val createdAt: Instant?,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
    val freshness: JobFitFreshness = JobFitFreshness.UNKNOWN,
)
