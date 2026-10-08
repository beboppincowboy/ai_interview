package dev.jiaming.ai_interview.resume

import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
internal class ResumeStorageReconciler(
    private val persistenceService: ResumePersistenceService,
    private val storageService: ResumeStorageService,
    private val cleanupService: ResumeStorageCleanupService
) {
    @Scheduled(fixedDelayString = "\${app.storage.cleanup-interval-ms:300000}")
    fun deleteFailedResumeObjects() {
        for (failedJob in persistenceService.findUnappliedTerminalFailures(25)) {
            try {
                persistenceService.markFailed(failedJob.resumeId, failedJob.errorCode, failedJob.errorMessage)
            } catch (exception: RuntimeException) {
                log.warn("resume_terminal_failure_reconcile_failed resumeId={} reason={}", failedJob.resumeId, exception.message)
            }
        }
        for (failed in persistenceService.findFailedStorageObjects(25)) {
            try {
                storageService.delete(failed.storageKey)
                persistenceService.clearStorageKey(failed.resumeId, failed.storageKey)
                log.info("resume_failed_object_reconciled resumeId={}", failed.resumeId)
            } catch (exception: RuntimeException) {
                log.warn("resume_failed_object_reconcile_failed resumeId={} reason={}", failed.resumeId, exception.message)
            }
        }
        cleanupService.retryPending(25)
        try {
            cleanupService.sweepOrphans()
        } catch (exception: RuntimeException) {
            log.warn("resume_storage_orphan_sweep_failed reason={}", exception.message)
        }
    }

    private companion object { val log = LoggerFactory.getLogger(ResumeStorageReconciler::class.java) }
}
