package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.lockOwnerShared
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobFitPayload
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.util.UUID
import java.util.function.Supplier
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

@Service
class JobFitService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobSubmissionService: JobSubmissionService,
    private val requestGuard: RedisRequestGuard,
    private val backgroundJobStore: BackgroundJobStore,
    private val objectMapper: ObjectMapper,
    private val transactionOperations: TransactionOperations,
) {
    fun get(resumeId: UUID, targetJobId: UUID): FitView {
        val userId = localUserService.localUserId()
        requireOwnedInputs(userId, resumeId, targetJobId, lockRows = false, requireReady = false)
        val row = findFit(userId, resumeId, targetJobId).firstOrNull()
        val latestJob = row?.id?.let { fitId ->
            backgroundJobStore.findLatestForResource(userId, FIT_RESOURCE, fitId, listOf(JobType.JOB_FIT))
                .map(LatestJob::from).orElse(null)
        }
        return FitView(resumeId, targetJobId, row?.resultPayload?.let { objectMapper.readValue(it, JobFitResult::class.java) }, row?.resultCreatedAt, latestJob)
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
                    val fitId = upsertAndLockFit(userId, resumeId, targetJobId)
                    val fingerprint = jobSubmissionService.fingerprint(JobType.JOB_FIT.name, fitId)
                    val reusable = jobSubmissionService.findReusable(JobType.JOB_FIT, fingerprint)
                    if (reusable.isPresent) reusable.get() else {
                        requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
                        jobSubmissionService.createOrReuse(
                            JobType.JOB_FIT,
                            FIT_RESOURCE,
                            fitId,
                            JobFitPayload(fitId, resumeId, targetJobId),
                            fingerprint,
                        )
                    }
                })
            },
        )
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

    private fun upsertAndLockFit(userId: UUID, resumeId: UUID, targetJobId: UUID): UUID {
        jdbcTemplate.update(
            """
                INSERT INTO ai_interview_app.job_fits (user_id, resume_id, target_job_id)
                VALUES (?, ?, ?)
                ON CONFLICT (user_id, resume_id, target_job_id) DO NOTHING
            """.trimIndent(), userId, resumeId, targetJobId,
        )
        return jdbcTemplate.query(
            """
                SELECT id FROM ai_interview_app.job_fits
                WHERE user_id = ? AND resume_id = ? AND target_job_id = ?
                FOR UPDATE
            """.trimIndent(),
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, resumeId, targetJobId,
        ).firstOrNull() ?: throw IllegalStateException("Fit pair could not be created or loaded")
    }

    private fun findFit(userId: UUID, resumeId: UUID, targetJobId: UUID) = jdbcTemplate.query(
        """
            SELECT id, result_payload::text AS result_payload, result_created_at
            FROM ai_interview_app.job_fits
            WHERE user_id = ? AND resume_id = ? AND target_job_id = ?
        """.trimIndent(),
        RowMapper { rs, _ ->
            FitRow(
                rs.getObject("id", UUID::class.java),
                rs.getString("result_payload"),
                rs.getTimestamp("result_created_at")?.toInstant(),
            )
        }, userId, resumeId, targetJobId,
    )

    private data class FitRow(val id: UUID, val resultPayload: String?, val resultCreatedAt: java.time.Instant?)
    private data class PairRequest(val resumeId: UUID, val targetJobId: UUID)

    companion object {
        const val FIT_RESOURCE = "job-fit"
        private const val IDEMPOTENCY_ACTION = "job-fit"
    }
}
