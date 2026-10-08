package dev.jiaming.ai_interview.jobs

import java.sql.ResultSet
import java.sql.SQLException
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class BackgroundJobStore(private val jdbcTemplate: JdbcTemplate, private val objectMapper: ObjectMapper) {
    @Transactional
    fun createIfAbsent(userId: UUID, jobType: JobType, resourceType: String?, resourceId: UUID?,
                       requestPayload: JsonNode?, requestFingerprint: String?, maxAttempts: Int): Optional<BackgroundJob> =
        createIfAbsentWithInitialResult(userId, jobType, resourceType, resourceId, requestPayload, requestFingerprint, maxAttempts, null)

    /** Creates a fresh retry job with previously validated answer checkpoints already durable. */
    @Transactional
    fun createIfAbsentWithInitialResult(userId: UUID, jobType: JobType, resourceType: String?, resourceId: UUID?,
                                        requestPayload: JsonNode?, requestFingerprint: String?, maxAttempts: Int,
                                        initialResultPayload: JsonNode?): Optional<BackgroundJob> {
        val jobId = UUID.randomUUID()
        val insertedIds = jdbcTemplate.query("""
            INSERT INTO ai_interview_app.background_jobs (
                id, user_id, job_type, resource_type, resource_id, status, stage,
                request_payload, result_payload, request_fingerprint, max_attempts, run_after
            ) VALUES (?, ?, ?, ?, ?, 'QUEUED', 'QUEUED', ?::jsonb, ?::jsonb, ?, ?, now())
            ON CONFLICT DO NOTHING RETURNING id
            """, { rs, _ -> rs.getObject("id", UUID::class.java) }, jobId, userId, jobType.name,
            resourceType, resourceId, json(requestPayload), initialResultPayload?.let(::json), requestFingerprint, maxAttempts)
        return Optional.ofNullable(insertedIds.firstOrNull()?.let { findById(it).orElse(null) })
    }

    fun findReusable(userId: UUID, jobType: JobType, requestFingerprint: String): Optional<BackgroundJob> = queryOne("""
        SELECT $JOB_COLUMNS FROM ai_interview_app.background_jobs
        WHERE user_id = ? AND job_type = ? AND request_fingerprint = ? AND status IN ('QUEUED', 'PROCESSING', 'RETRYING')
        ORDER BY created_at DESC LIMIT 1
        """, userId, jobType.name, requestFingerprint)
    fun findLatestForResource(userId: UUID, resourceType: String, resourceId: UUID, jobTypes: Collection<JobType> = emptyList()): Optional<BackgroundJob> {
        val typeFilter = if (jobTypes.isEmpty()) "" else "AND job_type IN (${jobTypes.joinToString { "'${it.name}'" }})"
        return queryOne("""
            SELECT $JOB_COLUMNS FROM ai_interview_app.background_jobs
            WHERE user_id = ? AND resource_type = ? AND resource_id = ? $typeFilter
            ORDER BY created_at DESC LIMIT 1
            """, userId, resourceType, resourceId)
    }
    /** [findLatestForResource] for many resources of one type in a single query, keyed by resource ID. */
    fun findLatestForResources(userId: UUID, resourceType: String, resourceIds: Collection<UUID>, jobTypes: Collection<JobType> = emptyList()): Map<UUID, BackgroundJob> {
        if (resourceIds.isEmpty()) return emptyMap()
        val typeFilter = if (jobTypes.isEmpty()) "" else "AND job_type IN (${jobTypes.joinToString { "'${it.name}'" }})"
        return jdbcTemplate.query("""
            SELECT DISTINCT ON (resource_id) $JOB_COLUMNS FROM ai_interview_app.background_jobs
            WHERE user_id = ? AND resource_type = ? AND resource_id IN (${resourceIds.joinToString { "?" }}) $typeFilter
            ORDER BY resource_id, created_at DESC
            """, { rs, _ -> mapJob(rs) }, userId, resourceType, *resourceIds.toTypedArray()).associateBy { it.resourceId!! }
    }
    fun deleteByResources(resourceType: String, resourceIds: Collection<UUID>): Int {
        if (resourceIds.isEmpty()) return 0
        return jdbcTemplate.update("DELETE FROM ai_interview_app.background_jobs WHERE resource_type = ? AND resource_id IN (${resourceIds.joinToString { "?" }})",
            resourceType, *resourceIds.toTypedArray())
    }
    fun findForUser(jobId: UUID, userId: UUID): Optional<BackgroundJob> = queryOne("SELECT $JOB_COLUMNS FROM ai_interview_app.background_jobs WHERE id = ? AND user_id = ?", jobId, userId)
    fun findById(jobId: UUID): Optional<BackgroundJob> = queryOne("SELECT $JOB_COLUMNS FROM ai_interview_app.background_jobs WHERE id = ?", jobId)
    fun findUndispatched(limit: Int): List<UUID> = jdbcTemplate.query("""
        SELECT id FROM ai_interview_app.background_jobs
        WHERE enqueued_at IS NULL AND status IN ('QUEUED', 'RETRYING') AND run_after <= now()
        ORDER BY created_at LIMIT ?
        """, { rs, _ -> rs.getObject("id", UUID::class.java) }, limit)
    fun markEnqueued(jobId: UUID) {
        jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET enqueued_at = now(), updated_at = now() WHERE id = ? AND status IN ('QUEUED', 'RETRYING')", jobId)
    }

    @Transactional
    fun claim(jobId: UUID, leaseToken: UUID, leaseDuration: Duration): Optional<BackgroundJob> = Optional.ofNullable(jdbcTemplate.query("""
        UPDATE ai_interview_app.background_jobs
        SET status = 'PROCESSING', attempts = attempts + 1, started_at = COALESCE(started_at, now()),
            lease_token = ?, lease_expires_at = now() + (? * interval '1 second'), last_error = NULL,
            error_code = NULL, retryable = NULL, updated_at = now()
        WHERE id = ? AND attempts < max_attempts
          AND ((status IN ('QUEUED', 'RETRYING') AND run_after <= now()) OR (status = 'PROCESSING' AND lease_expires_at < now()))
        RETURNING $JOB_COLUMNS
        """, { rs, _ -> mapJob(rs) }, leaseToken, leaseDuration.seconds, jobId).firstOrNull())

    fun extendLease(jobId: UUID, leaseToken: UUID, leaseDuration: Duration): Boolean = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() + (? * interval '1 second'), updated_at = now()
        WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
        """, leaseDuration.seconds, jobId, leaseToken) == 1
    fun updateStage(jobId: UUID, leaseToken: UUID, stage: JobStage) {
        val updated = jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET stage = ?, updated_at = now() WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?", stage.name, jobId, leaseToken)
        assertLeaseOwned(jobId, updated)
    }
    fun checkpointResult(jobId: UUID, leaseToken: UUID, resultPayload: JsonNode?) {
        val updated = jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET result_payload = ?::jsonb, updated_at = now() WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?", json(resultPayload), jobId, leaseToken)
        assertLeaseOwned(jobId, updated)
    }
    fun markSucceeded(jobId: UUID, leaseToken: UUID, resultPayload: JsonNode?) = markTerminal(jobId, leaseToken, JobStatus.SUCCEEDED, resultPayload, null, null, false)
    fun markFailed(jobId: UUID, leaseToken: UUID, errorCode: String, errorMessage: String?) =
        markTerminal(jobId, leaseToken, JobStatus.FAILED, null, errorCode, errorMessage, false)
    fun markRetrying(jobId: UUID, leaseToken: UUID, errorCode: String, errorMessage: String?, delay: Duration): Boolean = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET status = 'RETRYING', last_error = ?, error_code = ?, retryable = true,
            run_after = now() + (? * interval '1 second'), enqueued_at = NULL, lease_token = NULL, lease_expires_at = NULL, updated_at = now()
        WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
        """, truncate(errorMessage), errorCode, delay.seconds, jobId, leaseToken) == 1
    fun releaseForRedispatch(jobId: UUID, leaseToken: UUID): Boolean = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET status = 'RETRYING', attempts = GREATEST(0, attempts - 1), run_after = now(),
            enqueued_at = NULL, lease_token = NULL, lease_expires_at = NULL, updated_at = now()
        WHERE id = ? AND status = 'PROCESSING' AND lease_token = ?
        """, jobId, leaseToken) == 1
    fun prepareDlqRecovery(jobId: UUID): Boolean = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET status = CASE WHEN status = 'PROCESSING' THEN 'RETRYING' ELSE status END,
            run_after = now(), enqueued_at = NULL,
            lease_token = CASE WHEN status = 'PROCESSING' THEN NULL ELSE lease_token END,
            lease_expires_at = CASE WHEN status = 'PROCESSING' THEN NULL ELSE lease_expires_at END, updated_at = now()
        WHERE id = ? AND attempts < max_attempts AND (status IN ('QUEUED', 'RETRYING') OR (status = 'PROCESSING' AND lease_expires_at < now()))
        """, jobId) == 1
    fun markExhaustedFromDlq(jobId: UUID): Boolean = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET status = 'FAILED',
            last_error = 'SQS moved the job message to the dead-letter queue after retries were exhausted',
            error_code = 'RETRIES_EXHAUSTED_DLQ', retryable = false, completed_at = now(), lease_token = NULL, lease_expires_at = NULL, updated_at = now()
        WHERE id = ? AND attempts >= max_attempts AND status IN ('QUEUED', 'PROCESSING', 'RETRYING') AND (status <> 'PROCESSING' OR lease_expires_at < now())
        """, jobId) == 1
    fun reapExpiredLeases(): Int = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET status = CASE WHEN attempts >= max_attempts THEN 'FAILED' ELSE 'RETRYING' END,
            last_error = 'Worker lease expired before the job completed',
            error_code = CASE WHEN attempts >= max_attempts THEN 'RETRIES_EXHAUSTED_WORKER_LEASE_EXPIRED' ELSE 'WORKER_LEASE_EXPIRED' END,
            retryable = CASE WHEN attempts >= max_attempts THEN false ELSE true END, run_after = now(),
            enqueued_at = CASE WHEN attempts >= max_attempts THEN enqueued_at ELSE NULL END,
            completed_at = CASE WHEN attempts >= max_attempts THEN now() ELSE completed_at END,
            lease_token = NULL, lease_expires_at = NULL, updated_at = now()
        WHERE status = 'PROCESSING' AND lease_expires_at < now()
        """)
    fun clearExpiredPayloads(retentionDays: Int): Int = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET request_payload = jsonb_strip_nulls(jsonb_build_object(
            'payloadVersion', request_payload -> 'payloadVersion',
            'resumeId', COALESCE(request_payload -> 'resumeId', CASE WHEN resource_type IN ($RESUME_RESOURCE_TYPES) THEN to_jsonb(resource_id) END),
            'jobDescriptionId', request_payload -> 'jobDescriptionId', 'targetJobId', request_payload -> 'targetJobId',
            'practiceSetId', request_payload -> 'practiceSetId', 'attemptId', request_payload -> 'attemptId',
            'voiceSessionId', request_payload -> 'voiceSessionId')), result_payload = NULL, updated_at = now()
        WHERE status IN ('SUCCEEDED', 'FAILED') AND completed_at < now() - (? * interval '1 day')
          AND (result_payload IS NOT NULL OR (request_payload - ARRAY['payloadVersion', 'resumeId', 'jobDescriptionId', 'targetJobId', 'practiceSetId', 'attemptId', 'voiceSessionId']) <> '{}'::jsonb)
        """, retentionDays)

    private fun markTerminal(jobId: UUID, leaseToken: UUID, status: JobStatus, resultPayload: JsonNode?, errorCode: String?, errorMessage: String?, retryable: Boolean): Boolean = jdbcTemplate.update("""
        UPDATE ai_interview_app.background_jobs SET status = ?, stage = CASE WHEN ? = 'SUCCEEDED' THEN 'COMPLETED' ELSE stage END,
            result_payload = COALESCE(?::jsonb, result_payload), last_error = ?, error_code = ?, retryable = ?, completed_at = now(),
            lease_token = NULL, lease_expires_at = NULL, updated_at = now()
        WHERE id = ? AND lease_token = ?
        """, status.name, status.name, resultPayload?.let(::json), truncate(errorMessage), errorCode, retryable, jobId, leaseToken) == 1
    private fun assertLeaseOwned(jobId: UUID, updatedRows: Int) { if (updatedRows != 1) throw JobLeaseLostException(jobId) }
    private fun queryOne(sql: String, vararg arguments: Any): Optional<BackgroundJob> = Optional.ofNullable(jdbcTemplate.query(sql, { rs, _ -> mapJob(rs) }, *arguments).firstOrNull())
    private fun mapJob(rs: ResultSet): BackgroundJob = BackgroundJob(
        rs.getObject("id", UUID::class.java), rs.getObject("user_id", UUID::class.java), JobType.valueOf(rs.getString("job_type")),
        rs.getString("resource_type"), rs.getObject("resource_id", UUID::class.java), JobStatus.valueOf(rs.getString("status")),
        JobStage.valueOf(rs.getString("stage")), parseJson(rs.getString("request_payload")), parseJson(rs.getString("result_payload")),
        rs.getString("request_fingerprint"), rs.getInt("attempts"), rs.getInt("max_attempts"), rs.getString("error_code"),
        rs.getString("last_error"), rs.getObject("retryable") as Boolean?, instant(rs, "run_after")!!, instant(rs, "created_at")!!,
        instant(rs, "updated_at")!!, instant(rs, "enqueued_at"), instant(rs, "started_at"), instant(rs, "completed_at"),
        rs.getObject("lease_token", UUID::class.java), instant(rs, "lease_expires_at"))
    private fun parseJson(value: String?): JsonNode? {
        if (value == null) return null
        return try { objectMapper.readTree(value) } catch (exception: JsonProcessingException) { throw SQLException("Could not parse background job JSON", exception) }
    }
    private fun json(value: JsonNode?) = value?.toString() ?: JsonNodeFactory.instance.objectNode().toString()
    private fun instant(rs: ResultSet, column: String): Instant? = rs.getTimestamp(column)?.toInstant()
    private fun truncate(value: String?) = value?.take(4_000)

    companion object {
        private val RESUME_RESOURCE_TYPES = JobInputRefs.RESUME_RESOURCE_TYPES.joinToString { "'$it'" }
        private val JOB_COLUMNS = """
            id, user_id, job_type, resource_type, resource_id, status, stage,
            request_payload::text AS request_payload, result_payload::text AS result_payload,
            request_fingerprint, attempts, max_attempts, error_code, last_error, retryable,
            run_after, created_at, updated_at, enqueued_at, started_at, completed_at, lease_token, lease_expires_at
            """.trimIndent()
    }
}
