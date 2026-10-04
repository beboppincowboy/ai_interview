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
    fun submit(type: JobType, resourceType: String?, resourceId: UUID?, payload: Any): JobAcceptedResponse {
        assertApiAvailable()
        if (type != JobType.RESUME_EXTRACTION) requestGuard.assertAiAllowed(AI_JOB_ACTION)
        return createOrReuse(type, resourceType, resourceId, payload, resourceId?.let { fingerprint(type.name, it) })
    }
    fun fingerprint(action: String, source: Any): String = fingerprintService.fingerprint(action, source)
    fun assertApiAvailable() {
        if (!properties.enabled || !runtimeMode.apiEnabled()) throw ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "This process is running in worker-only mode and does not accept background jobs")
    }
    fun findReusable(type: JobType, fingerprint: String?): Optional<JobAcceptedResponse> = if (fingerprint == null) Optional.empty()
        else jobStore.findReusable(localUserService.localUserId(), type, fingerprint).map { JobAcceptedResponse.from(it, true) }
    fun createOrReuse(
        type: JobType,
        resourceType: String?,
        resourceId: UUID?,
        requestPayload: Any,
        fingerprint: String?,
    ): JobAcceptedResponse = createOrReuseWithInitialResult(type, resourceType, resourceId, requestPayload, fingerprint, null)

    fun createOrReuseWithInitialResult(
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
        val created = if (initialResultPayload == null) {
            jobStore.createIfAbsent(userId, type, resourceType, resourceId, request, fingerprint, properties.maxAttempts)
        } else {
            jobStore.createIfAbsentWithInitialResult(
                userId, type, resourceType, resourceId, request, fingerprint, properties.maxAttempts, initialResultPayload,
            )
        }
        if (created.isEmpty) return findReusable(type, fingerprint).orElseThrow { IllegalStateException("A matching active job won the submission race but could not be loaded") }
        val job = created.get()
        metrics.submitted(type)
        log.info("job_submitted jobId={} type={} userId={} resourceType={} resourceId={}", job.id, type, job.userId, resourceType, resourceId)
        dispatchAfterCommit(job.id)
        return JobAcceptedResponse.from(job, false)
    }
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
