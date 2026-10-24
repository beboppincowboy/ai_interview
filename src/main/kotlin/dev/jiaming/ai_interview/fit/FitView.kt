package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.jobs.LatestJob
import java.time.Instant
import java.util.UUID

data class FitView(
    val resumeId: UUID,
    val targetJobId: UUID,
    val result: JobFitResult?,
    val createdAt: Instant?,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
)
