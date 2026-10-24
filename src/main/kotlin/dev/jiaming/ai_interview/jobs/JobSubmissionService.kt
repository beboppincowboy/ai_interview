package dev.jiaming.ai_interview.jobs

import java.util.Optional
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.server.ResponseStatusException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RuntimeModeProperties

@Service
class JobSubmissionService(
    private val jobStore: BackgroundJobStore, private val dispatcher: JobDispatcher,
    private val fingerprintService: RequestFingerprintService, private val localUserService: LocalUserService,
    private val requestGuard: RedisRequestGuard, private val properties: JobProperties,
    private val runtimeMode: RuntimeModeProperties, private val metrics: JobMetrics, private val objectMapper: ObjectMapper
) {
    /**
     * The only way to submit a background job. It always checks, in this order and before any write:
     * [assertApiAvailable] (503 in worker-only mode), then the shared AI rate limit
     * ([RedisRequestGuard.assertAiAllowed] with [AI_JOB_ACTION], 429 when exceeded; [JobType.RESUME_EXTRACTION] is
     * exempt because uploads have their own limit). The rate limit is charged even when an active job is reused.
     *
     * Then it returns the user's active (QUEUED/PROCESSING/RETRYING) job of [type] with the same [dedupKey] with
     * `reused = true`, or creates one and dispatches it after the surrounding transaction commits (at once if there is
     * none). Call it inside the caller's transaction when the job must commit with other writes.
     *
     * @param dedupKey defaults to a fingerprint of [type] and [resourceId] only, so a resubmit with a changed [payload]
     *   gets the old active job back. A null [resourceId] makes the default null, and a null key never reuses.
     *   Pass another key (from [fingerprint]) to dedupe on something else.
     * @param initialResult the job's starting result checkpoint, e.g. completed work carried over from a failed job.
     */
    fun submit(
        type: JobType,
        resourceType: String?,
        resourceId: UUID?,
        payload: Any,
        dedupKey: String? = resourceId?.let { fingerprint(type.name, it) },
        initialResult: JsonNode? = null,
    ): JobAcceptedResponse {
        assertApiAvailable()
        if (type != JobType.RESUME_EXTRACTION) requestGuard.assertAiAllowed(AI_JOB_ACTION)
        return createOrReuse(type, resourceType, resourceId, payload, dedupKey, initialResult)
    }
    fun fingerprint(action: String, source: Any): String = fingerprintService.fingerprint(action, source)
    /** Throws 503 when this process runs in worker-only mode; [submit] calls it too, so calling it first only fails fast. */
    fun assertApiAvailable() {
        if (!properties.enabled || !runtimeMode.apiEnabled()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "This process is running in worker-only mode and does not accept background jobs")
    }
    /** The user's active job of [type] with this dedup key, if any; a read only, it submits nothing. */
    fun findReusable(type: JobType, fingerprint: String?): Optional<JobAcceptedResponse> = if (fingerprint == null) Optional.empty()
        else jobStore.findReusable(localUserService.localUserId(), type, fingerprint).map { JobAcceptedResponse.from(it, true) }

    private fun createOrReuse(
        type: JobType,
        resourceType: String?,
        resourceId: UUID?,
        requestPayload: Any,
        fingerprint: String?,
        initialResultPayload: JsonNode?,
    ): JobAcceptedResponse {
        val existing = findReusable(type, fingerprint)
        if (existing.isPresent) return existing.get()
        val userId = localUserService.localUserId()
        val request = objectMapper.valueToTree<JsonNode>(requestPayload)
        val created = jobStore.createIfAbsentWithInitialResult(
            userId, type, resourceType, resourceId, request, fingerprint, properties.maxAttempts, initialResultPayload,
        )
        if (created.isEmpty) return findReusable(type, fingerprint).orElseThrow { IllegalStateException("A matching active job won the submission race but could not be loaded") }
        val job = created.get()
        metrics.submitted(type)
        log.info("job_submitted jobId={} type={} userId={} resourceType={} resourceId={}", job.id, type, job.userId, resourceType, resourceId)
        dispatchAfterCommit(job.id)
        return JobAcceptedResponse.from(job, false)
    }
    /** See [RedisRequestGuard.withIdempotentRetryCache]: acts only when the request has an `Idempotency-Key` header. */
    fun <T> withIdempotency(action: String, fingerprintSource: Any, type: Class<T>, work: java.util.function.Supplier<T>): T =
        requestGuard.withIdempotentRetryCache(action, fingerprintSource, type, work)
    private fun dispatchAfterCommit(jobId: UUID) {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) { tryDispatch(jobId); return }
        TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
            override fun afterCommit() = tryDispatch(jobId)
        })
    }
    private fun tryDispatch(jobId: UUID) { try { dispatcher.dispatch(jobId) } catch (exception: RuntimeException) { log.warn("job_initial_dispatch_failed jobId={} reason={}", jobId, exception.message) } }
    companion object {
        const val AI_JOB_ACTION = "ai-job"
        private val log = LoggerFactory.getLogger(JobSubmissionService::class.java)
    }
}
