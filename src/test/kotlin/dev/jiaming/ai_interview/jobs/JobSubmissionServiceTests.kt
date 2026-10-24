package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.data.redis.core.ValueOperations
import org.springframework.web.server.ResponseStatusException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class JobSubmissionServiceTests {

    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val dispatcher = Mockito.mock(JobDispatcher::class.java)
    private val localUserService = Mockito.mock(LocalUserService::class.java)
    private val metrics = Mockito.mock(JobMetrics::class.java)
    private val requestGuard = Mockito.mock(RedisRequestGuard::class.java)
    private val userId = UUID.randomUUID()
    private val properties = JobProperties(
        true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3,
        2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7
    )
    private val service = JobSubmissionService(
        jobStore,
        dispatcher,
        RequestFingerprintService(ObjectMapper()),
        localUserService,
        requestGuard,
        properties,
        RuntimeModeProperties("all"),
        metrics,
        ObjectMapper()
    )

    @Test
    fun reusesActiveJobWithTheSameFingerprint() {
        val existing = job(JobStatus.PROCESSING)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.RESUME_SCORE, "same"))
            .thenReturn(Optional.of(existing))

        val response = service.submit(
            JobType.RESUME_SCORE, "resume", null, mapOf("resume" to "same"), dedupKey = "same"
        )

        assertThat(response.reused).isTrue()
        assertThat(response.jobId).isEqualTo(existing.id)
        Mockito.verifyNoInteractions(dispatcher)
    }

    @Test
    fun createsAndDispatchesNewJobWhenNoReusableJobExists() {
        val created = job(JobStatus.QUEUED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.RESUME_SCORE, "new"))
            .thenReturn(Optional.empty())
        Mockito.`when`(
            jobStore.createIfAbsentWithInitialResult(eq(userId), eq(JobType.RESUME_SCORE), eq("resume"), eq(null), any(), eq("new"), eq(3), eq(null))
        ).thenReturn(Optional.of(created))

        val response = service.submit(
            JobType.RESUME_SCORE, "resume", null, mapOf("resume" to "new"), dedupKey = "new"
        )

        assertThat(response.reused).isFalse()
        Mockito.verify(dispatcher).dispatch(created.id)
        Mockito.verify(metrics).submitted(JobType.RESUME_SCORE)
    }

    @Test
    fun reusesWinningJobWhenConcurrentInsertDoesNotCreateARow() {
        val winner = job(JobStatus.QUEUED)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.RESUME_SCORE, "race"))
            .thenReturn(Optional.empty(), Optional.of(winner))
        Mockito.`when`(
            jobStore.createIfAbsentWithInitialResult(
                eq(userId), eq(JobType.RESUME_SCORE), eq("resume"), eq(null), any(), eq("race"), eq(3), eq(null)
            )
        ).thenReturn(Optional.empty())

        val response = service.submit(
            JobType.RESUME_SCORE, "resume", null, mapOf("resume" to "same"), dedupKey = "race"
        )

        assertThat(response.reused).isTrue()
        assertThat(response.jobId).isEqualTo(winner.id)
        Mockito.verifyNoInteractions(dispatcher)
    }

    @Test
    fun aiSubmissionsOfEveryJobTypeShareOneRateLimitBudget() {
        val counters = mutableMapOf<String, Long>()
        val redis = Mockito.mock(StringRedisTemplate::class.java)
        @Suppress("UNCHECKED_CAST") val values = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
        Mockito.`when`(redis.opsForValue()).thenReturn(values)
        Mockito.`when`(values.increment(any<String>())).thenAnswer { counters.merge(it.getArgument(0), 1L, Long::plus) }
        val guard = RedisRequestGuard(redis, RedisUsageProperties(RedisUsageProperties.RateLimit(true, 60, 12, 20), null), ObjectMapper())
        val limitedService = JobSubmissionService(jobStore, dispatcher, RequestFingerprintService(ObjectMapper()), localUserService,
            guard, properties, RuntimeModeProperties("all"), metrics, ObjectMapper())
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.createIfAbsentWithInitialResult(eq(userId), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), eq(3), eq(null)))
            .thenAnswer { Optional.of(job(JobStatus.QUEUED)) }

        repeat(12) { limitedService.submit(JobType.RESUME_SCORE, "resume", UUID.randomUUID(), mapOf("n" to it)) }

        assertThatThrownBy { limitedService.submit(JobType.ANSWER_FEEDBACK, "attempt", UUID.randomUUID(), mapOf("n" to 13)) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("429 TOO_MANY_REQUESTS")
        assertThat(counters).hasSize(1)
    }

    @Test
    fun submitFingerprintsOnJobTypeAndResourceAndLeavesResourcelessJobsUnfingerprinted() {
        val resourceId = UUID.randomUUID()
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.createIfAbsentWithInitialResult(eq(userId), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), eq(3), eq(null)))
            .thenAnswer { Optional.of(job(JobStatus.QUEUED)) }

        service.submit(JobType.RESUME_SCORE, "resume", resourceId, mapOf("text" to "first"))
        service.submit(JobType.RESUME_SCORE, "resume", resourceId, mapOf("text" to "second"))
        service.submit(JobType.ANSWER_FEEDBACK, null, null, mapOf("text" to "split"))

        val fingerprints = ArgumentCaptor.forClass(String::class.java)
        Mockito.verify(jobStore, Mockito.times(3)).createIfAbsentWithInitialResult(
            eq(userId), any(), anyOrNull(), anyOrNull(), any(), fingerprints.capture(), eq(3), eq(null)
        )
        assertThat(fingerprints.allValues[0]).isNotNull().isEqualTo(fingerprints.allValues[1])
        assertThat(fingerprints.allValues[2]).isNull()
        Mockito.verify(requestGuard, Mockito.times(3)).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
    }

    @Test
    fun everySubmissionPathChecksTheRateLimitBeforeTouchingTheStore() {
        Mockito.doThrow(ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).`when`(requestGuard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        val resourceId = UUID.randomUUID()

        assertThatThrownBy { service.submit(JobType.RESUME_SCORE, "resume", resourceId, mapOf("n" to 1)) }
            .hasMessageContaining("429 TOO_MANY_REQUESTS")
        assertThatThrownBy { service.submit(JobType.JOB_FIT, "job-fit", resourceId, mapOf("n" to 2), dedupKey = "explicit") }
            .hasMessageContaining("429 TOO_MANY_REQUESTS")
        assertThatThrownBy {
            service.submit(JobType.VOICE_REPORT, "voice-session", resourceId, mapOf("n" to 3), initialCheckpoint = ObjectMapper().createObjectNode())
        }.hasMessageContaining("429 TOO_MANY_REQUESTS")

        Mockito.verify(requestGuard, Mockito.times(3)).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        Mockito.verifyNoInteractions(jobStore, dispatcher)
    }

    @Test
    fun everySubmissionPathRefusesWorkerOnlyProcessesBeforeTheRateLimit() {
        val workerOnly = JobSubmissionService(jobStore, dispatcher, RequestFingerprintService(ObjectMapper()), localUserService,
            requestGuard, properties, RuntimeModeProperties("worker"), metrics, ObjectMapper())

        assertThatThrownBy { workerOnly.submit(JobType.RESUME_SCORE, "resume", UUID.randomUUID(), mapOf("n" to 1)) }
            .hasMessageContaining("503 SERVICE_UNAVAILABLE")
        assertThatThrownBy {
            workerOnly.submit(JobType.JOB_FIT, "job-fit", null, mapOf("n" to 2), dedupKey = "explicit", initialCheckpoint = ObjectMapper().createObjectNode())
        }.hasMessageContaining("503 SERVICE_UNAVAILABLE")

        Mockito.verifyNoInteractions(requestGuard, jobStore, dispatcher)
    }

    @Test
    fun initialCheckpointReachesTheStoreAfterTheRateLimit() {
        val created = job(JobStatus.QUEUED)
        val checkpoint = ObjectMapper().createObjectNode().put("answered", 1)
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.findReusable(userId, JobType.VOICE_REPORT, "voice")).thenReturn(Optional.empty())
        Mockito.`when`(
            jobStore.createIfAbsentWithInitialResult(eq(userId), eq(JobType.VOICE_REPORT), eq("voice-session"), anyOrNull(), any(), eq("voice"), eq(3), eq(checkpoint))
        ).thenReturn(Optional.of(created))

        val response = service.submit(
            JobType.VOICE_REPORT, "voice-session", UUID.randomUUID(), mapOf("n" to 1), dedupKey = "voice", initialCheckpoint = checkpoint
        )

        assertThat(response.jobId).isEqualTo(created.id)
        val order = Mockito.inOrder(requestGuard, jobStore)
        order.verify(requestGuard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        order.verify(jobStore).createIfAbsentWithInitialResult(
            eq(userId), eq(JobType.VOICE_REPORT), eq("voice-session"), anyOrNull(), any(), eq("voice"), eq(3), eq(checkpoint)
        )
    }

    @Test
    fun resumeExtractionIsNotChargedToTheAiRateLimit() {
        Mockito.`when`(localUserService.localUserId()).thenReturn(userId)
        Mockito.`when`(jobStore.createIfAbsentWithInitialResult(eq(userId), any(), anyOrNull(), anyOrNull(), any(), anyOrNull(), eq(3), eq(null)))
            .thenAnswer { Optional.of(job(JobStatus.QUEUED)) }

        service.submit(JobType.RESUME_EXTRACTION, "resume", UUID.randomUUID(), mapOf("n" to 1), dedupKey = "upload")

        Mockito.verifyNoInteractions(requestGuard)
    }

    private fun job(status: JobStatus): BackgroundJob {
        val now = Instant.now()
        return BackgroundJob(
            UUID.randomUUID(), userId, JobType.RESUME_SCORE, "resume", null, status, JobStage.QUEUED,
            ObjectMapper().createObjectNode(), null, if (status == JobStatus.QUEUED) "new" else "same", 0, 3,
            null, null, null, now, now, now, if (status == JobStatus.QUEUED) null else now, null,
            if (status == JobStatus.SUCCEEDED) now else null, null, null
        )
    }
}
