package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.ai.AiProviderErrorCode
import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.resume.ResumeExtractionException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.kotlin.eq
import org.mockito.Mockito
import org.springframework.test.util.ReflectionTestUtils
import software.amazon.awssdk.services.sqs.model.Message
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class JobWorkerTests {
    private val queueService = Mockito.mock(JobQueueService::class.java)
    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val processor = Mockito.mock(JobProcessor::class.java)
    private val metrics = Mockito.mock(JobMetrics::class.java)
    private val properties = properties()
    private val worker = worker(properties, emptyList())
    private lateinit var heartbeatExecutor: ScheduledExecutorService

    @BeforeEach fun setUp() { heartbeatExecutor = Executors.newSingleThreadScheduledExecutor(); ReflectionTestUtils.setField(worker, "heartbeatExecutor", heartbeatExecutor) }
    @AfterEach fun tearDown() { heartbeatExecutor.shutdownNow() }

    @Test fun duplicateQueueMessageExecutesBusinessLogicOnlyOnce() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 1); val succeeded = job(id, JobType.JOB_FIT, JobStatus.SUCCEEDED, 1)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(queueService.receiveCount(message)).thenReturn(1)
        Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing), Optional.empty())
        Mockito.`when`(processor.process(eq(processing), any())).thenReturn(ObjectMapper().createObjectNode()); Mockito.`when`(jobStore.markSucceeded(eq(id), any(), any())).thenReturn(true); Mockito.`when`(jobStore.findById(id)).thenReturn(Optional.of(succeeded))
        worker.processMessage(message); worker.processMessage(message)
        Mockito.verify(processor, Mockito.times(1)).process(eq(processing), any()); Mockito.verify(queueService, Mockito.times(2)).delete(message)
    }

    @Test fun transientFailureSchedulesFreshRetryAndDeletesCurrentMessage() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 1)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(processor.process(eq(processing), any())).thenThrow(AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "timeout", true)); Mockito.`when`(jobStore.markRetrying(eq(id), any(), eq("GEMINI_UPSTREAM_ERROR"), eq("timeout"), eq(java.time.Duration.ofSeconds(15)))).thenReturn(true)
        worker.processMessage(message)
        Mockito.verify(queueService).delete(message); Mockito.verify(queueService, Mockito.never()).changeVisibility(message, 15)
    }

    @Test fun permanentExtractionFailureIsNotRetried() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.RESUME_EXTRACTION, JobStatus.PROCESSING, 1)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(processor.process(eq(processing), any())).thenThrow(ResumeExtractionException("Encrypted PDF")); Mockito.`when`(jobStore.markFailed(eq(id), any(), eq("RESUME_EXTRACTION_FAILED"), eq("Encrypted PDF"))).thenReturn(true)
        worker.processMessage(message)
        Mockito.verify(queueService).delete(message); Mockito.verify(jobStore, Mockito.never()).markRetrying(any(), any(), any(), any(), any())
    }

    @Test fun transientFailureStopsAfterThreeAttemptsAndMovesTransportMessageToDlq() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 3)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(processor.process(eq(processing), any())).thenThrow(AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "timeout", true)); Mockito.`when`(jobStore.findById(id)).thenReturn(Optional.of(processing)); Mockito.`when`(jobStore.markFailed(eq(id), any(), eq("RETRIES_EXHAUSTED_GEMINI_UPSTREAM_ERROR"), eq("timeout"))).thenReturn(true)
        worker.processMessage(message)
        Mockito.verify(queueService).sendDeadLetter(id); Mockito.verify(queueService).delete(message); Mockito.verify(queueService, Mockito.never()).changeVisibility(message, 0); Mockito.verify(jobStore, Mockito.never()).markRetrying(any(), any(), any(), any(), any())
    }

    @Test fun exhaustedTerminalMessageIsDeadLetteredAfterAWorkerRestart() {
        val id = UUID.randomUUID(); val message = message(); val failed = job(id, JobType.JOB_FIT, JobStatus.FAILED, 3, "RETRIES_EXHAUSTED_GEMINI_ERROR")
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.empty()); Mockito.`when`(jobStore.findById(id)).thenReturn(Optional.of(failed))
        worker.processMessage(message); Mockito.verify(queueService).sendDeadLetter(id); Mockito.verify(queueService).delete(message)
    }

    @Test fun failedDeadLetterPublishLeavesTheMainMessageForAnotherAttempt() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 3)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(processor.process(eq(processing), any())).thenThrow(AiProviderException(AiProviderErrorCode.UPSTREAM_ERROR, "timeout", true)); Mockito.`when`(jobStore.findById(id)).thenReturn(Optional.of(processing)); Mockito.`when`(jobStore.markFailed(eq(id), any(), eq("RETRIES_EXHAUSTED_GEMINI_UPSTREAM_ERROR"), eq("timeout"))).thenReturn(true); Mockito.`when`(queueService.receiveCount(message)).thenReturn(1); Mockito.doThrow(IllegalStateException("DLQ unavailable")).`when`(queueService).sendDeadLetter(id)
        worker.processMessage(message); Mockito.verify(queueService, Mockito.never()).delete(message); Mockito.verify(queueService).changeVisibility(message, 5)
    }

    @Test fun staleWorkerDoesNotDeleteMessageAfterLosingLease() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 1)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(processor.process(eq(processing), any())).thenReturn(ObjectMapper().createObjectNode()); Mockito.`when`(jobStore.markSucceeded(eq(id), any(), any())).thenReturn(false)
        worker.processMessage(message); Mockito.verify(queueService, Mockito.never()).delete(message)
    }

    @Test fun malformedMainQueueMessageIsLeftForSqsRedrive() {
        val message = message(); Mockito.`when`(queueService.parse(message)).thenThrow(IllegalArgumentException("bad JSON"))
        worker.processMessage(message); Mockito.verify(metrics).invalidMessage(); Mockito.verify(queueService).changeVisibility(message, 0); Mockito.verify(queueService, Mockito.never()).delete(message)
    }

    @Test fun terminalResumeFailureRunsCleanupHandler() {
        val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.RESUME_EXTRACTION, JobStatus.PROCESSING, 1); val handler = Mockito.mock(JobTerminalFailureHandler::class.java); Mockito.`when`(handler.supports(JobType.RESUME_EXTRACTION)).thenReturn(true); val cleanupWorker = worker(properties, listOf(handler))
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(properties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(processor.process(eq(processing), any())).thenThrow(ResumeExtractionException("Encrypted PDF")); Mockito.`when`(jobStore.markFailed(eq(id), any(), eq("RESUME_EXTRACTION_FAILED"), eq("Encrypted PDF"))).thenReturn(true)
        cleanupWorker.processMessage(message); Mockito.verify(handler).handle(processing, "RESUME_EXTRACTION_FAILED", "Encrypted PDF")
    }

    @Test fun heartbeatLeaseLossInterruptsProcessingWithoutRecordingFailure() {
        val heartbeatProperties = properties(1, 120); val heartbeatWorker = worker(heartbeatProperties, emptyList()); val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 1); val entered = CountDownLatch(1)
        Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(heartbeatProperties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(jobStore.extendLease(eq(id), any(), eq(heartbeatProperties.visibilityTimeout()))).thenReturn(false); Mockito.`when`(processor.process(eq(processing), any())).thenAnswer { entered.countDown(); try { CountDownLatch(1).await(); ObjectMapper().createObjectNode() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw RuntimeException("interrupted", e) } }
        val thread = Thread { heartbeatWorker.processMessage(message) }; thread.start(); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); thread.join(3_000)
        assertThat(thread.isAlive).isFalse(); Mockito.verify(jobStore, Mockito.never()).markRetrying(any(), any(), any(), any(), any()); Mockito.verify(jobStore, Mockito.never()).markFailed(any(), any(), any(), any()); Mockito.verify(queueService, Mockito.never()).delete(message)
    }

    @Test fun shutdownReleasesUnfinishedJobWithoutConsumingAttempt() {
        val shutdownProperties = properties(10, 1); val shutdownWorker = worker(shutdownProperties, emptyList()); val id = UUID.randomUUID(); val message = message(); val processing = job(id, JobType.JOB_FIT, JobStatus.PROCESSING, 1); val entered = CountDownLatch(1); val receives = AtomicInteger()
        Mockito.`when`(queueService.receive(anyInt())).thenAnswer { if (receives.getAndIncrement() == 0) listOf(message) else { Thread.sleep(10); emptyList() } }; Mockito.`when`(queueService.parse(message)).thenReturn(JobMessage(id)); Mockito.`when`(jobStore.claim(eq(id), any(), eq(shutdownProperties.visibilityTimeout()))).thenReturn(Optional.of(processing)); Mockito.`when`(jobStore.releaseForRedispatch(eq(id), any())).thenReturn(true); Mockito.`when`(processor.process(eq(processing), any())).thenAnswer { entered.countDown(); try { CountDownLatch(1).await(); ObjectMapper().createObjectNode() } catch (e: InterruptedException) { Thread.currentThread().interrupt(); throw RuntimeException("interrupted", e) } }
        shutdownWorker.start(); assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue(); shutdownWorker.stop()
        Mockito.verify(jobStore).releaseForRedispatch(eq(id), any()); Mockito.verify(queueService).delete(message); Mockito.verify(jobStore, Mockito.never()).markRetrying(any(), any(), any(), any(), any()); Mockito.verify(jobStore, Mockito.never()).markFailed(any(), any(), any(), any())
    }

    private fun worker(properties: JobProperties, handlers: List<JobTerminalFailureHandler>) = JobWorker(properties, queueService, jobStore, processor, JobFailureClassifier(), metrics, RuntimeModeProperties("all"), JobRetryDelayStrategy { it - 1 }, handlers).also { if (::heartbeatExecutor.isInitialized) ReflectionTestUtils.setField(it, "heartbeatExecutor", heartbeatExecutor) }
    private fun properties(heartbeatSeconds: Int = 60, shutdownGraceSeconds: Int = 120) = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, if (heartbeatSeconds == 60) 20 else 1, if (heartbeatSeconds == 60) 300 else 30, heartbeatSeconds, 3, 15, 5_000, 30_000, 3_600_000, shutdownGraceSeconds, 7)
    private fun message() = Message.builder().messageId(UUID.randomUUID().toString()).receiptHandle("receipt").body("{}").build()
    private fun job(id: UUID, type: JobType, status: JobStatus, attempts: Int, errorCode: String? = null): BackgroundJob { val now = Instant.now(); return BackgroundJob(id, UUID.randomUUID(), type, "resource", null, status, JobStage.QUEUED, ObjectMapper().createObjectNode(), null, "fingerprint", attempts, 3, errorCode, null, null, now, now, now, now, now, if (status.terminal()) now else null, UUID.randomUUID(), now.plusSeconds(300)) }
}
