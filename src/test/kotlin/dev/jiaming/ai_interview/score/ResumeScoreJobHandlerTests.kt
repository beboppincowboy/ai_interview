package dev.jiaming.ai_interview.score

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.Priority
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobExecutionContext
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito

class ResumeScoreJobHandlerTests {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val coachService = Mockito.mock(AiResumeCoachService::class.java)
    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val materialization = Mockito.mock(JobEffectMaterializationService::class.java)
    private val handler = ResumeScoreJobHandler(coachService)

    @Test
    fun scoresTheResumeThenCheckpointsBeforeMaterialization() {
        val userId = UUID.randomUUID()
        val resumeId = UUID.randomUUID()
        val lease = UUID.randomUUID()
        val result = score("Backend Engineer")
        val payload = ResumeScorePayload(resumeId, "resume snapshot", "Backend Engineer")
        val job = job(userId, resumeId, lease, null)
        Mockito.`when`(coachService.scoreResume(payload.resumeText, payload.jobTitle)).thenReturn(result)
        val context = context(job, lease)

        val returned = handler.handle(payload, context)

        assertThat(returned?.get("overall")?.asInt()).isEqualTo(result.overall)
        Mockito.verify(jobStore).updateStage(job.id, lease, JobStage.SCORING_RESUME)
        Mockito.verify(jobStore).checkpointResult(job.id, lease, objectMapper.valueToTree(result))
        Mockito.verify(materialization).materializeResumeScore(job, lease, resumeId, result)
    }

    @Test
    fun retryAfterCheckpointSkipsTheModelAndMaterializesTheSavedScore() {
        val userId = UUID.randomUUID()
        val resumeId = UUID.randomUUID()
        val lease = UUID.randomUUID()
        val result = score(null)
        val payload = ResumeScorePayload(resumeId, "resume snapshot", null)
        val job = job(userId, resumeId, lease, objectMapper.valueToTree(result))
        val context = context(job, lease)

        val returned = handler.handle(payload, context)

        assertThat(returned?.get("overall")?.asInt()).isEqualTo(result.overall)
        Mockito.verifyNoInteractions(coachService)
        Mockito.verify(materialization).materializeResumeScore(job, lease, resumeId, result)
    }

    private fun context(job: BackgroundJob, lease: UUID) = JobExecutionContext(
        job, lease, jobStore, materialization, JobMetrics(SimpleMeterRegistry()), objectMapper
    )

    private fun job(userId: UUID, resumeId: UUID, lease: UUID, checkpoint: com.fasterxml.jackson.databind.JsonNode?) = BackgroundJob(
        UUID.randomUUID(), userId, JobType.RESUME_SCORE, "resume", resumeId,
        JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.valueToTree(ResumeScorePayload(resumeId, "resume", null)), checkpoint,
        "fingerprint", 1, 3, null, null, null, Instant.now(), Instant.now(), Instant.now(), null, Instant.now(), null, lease,
        Instant.now().plusSeconds(300)
    )

    private fun score(jobTitle: String?) = ResumeScoreResult(
        81, AssessmentScores(82, 80, 79, 83, 81), "Strong platform experience.",
        listOf(ResumeScoreFix(1, "Experience", Priority.HIGH, "Add measured outcomes.")), emptyList(), jobTitle, Instant.now()
    )
}
