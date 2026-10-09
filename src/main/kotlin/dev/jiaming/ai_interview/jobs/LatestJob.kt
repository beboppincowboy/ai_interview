package dev.jiaming.ai_interview.jobs

import java.util.UUID

/**
 * The newest background job for a resource, looked up with [BackgroundJobStore.findLatestForResource]. It can be in
 * any status, including SUCCEEDED and FAILED, so check [status] before showing progress. Null on a view only when no
 * job was ever submitted for the resource.
 */
@JvmRecord
data class LatestJob(
    val jobId: UUID, val jobType: JobType, val status: JobStatus, val stage: JobStage,
    val attempts: Int, val maxAttempts: Int, val error: JobErrorResponse?
) {
    companion object {
        @JvmStatic fun from(job: BackgroundJob) =
            LatestJob(job.id, job.jobType, job.status, job.stage, job.attempts, job.maxAttempts, JobErrorResponse.from(job))
    }
}
