package dev.jiaming.ai_interview.jobs

import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachFeedbackInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.practice.AttemptScoringInput
import dev.jiaming.ai_interview.practice.PracticeService
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import java.util.Optional
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.Mockito

class FeedbackJobHandlerTests {
	@Test
	fun attemptFeedbackScoresTheQuestionAgainstThePairAndCheckpointsBeforeSaving() {
		val coach = Mockito.mock(AiResumeCoachService::class.java)
		val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
		val practice = Mockito.mock(PracticeService::class.java)
		val payload = attemptPayload()
		val job = attemptJob(payload, null)
		val documents = ResolvedJobInputs(
			ResolvedDocument(DocumentSourceType.RESUME, payload.resumeId, "resume-hash", "resume", emptyList()),
			Optional.of(ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, payload.targetJobId, "jd-hash", "job", emptyList()))
		)
		val input = CoachFeedbackInput(documents.resume(), documents.targetJob(), "Why Kafka?", "Depth", listOf("trade-offs"), "Because ordering.")
		Mockito.`when`(practice.attemptForScoring(job.userId!!, payload.attemptId))
			.thenReturn(AttemptScoringInput("Because ordering.", "Why Kafka?", "Depth", listOf("trade-offs")))
		Mockito.`when`(resolver.resolveStrict(job.userId!!, payload.resumeId, payload.targetJobId)).thenReturn(documents)
		Mockito.`when`(coach.scorePracticeAnswer(input)).thenReturn(attemptFeedback)

		val returned = FeedbackJobHandler(coach, resolver, practice).handle(payload, attemptContext(job))

		assertThat(returned).isEqualTo(objectMapper.valueToTree<JsonNode>(attemptFeedback))
		Mockito.verify(jobStore).updateStage(job.id, lease, JobStage.SCORING_ANSWER)
		val order = Mockito.inOrder(coach, jobStore, materialization)
		order.verify(coach).scorePracticeAnswer(input)
		order.verify(jobStore).checkpointResult(job.id, lease, objectMapper.valueToTree(attemptFeedback))
		order.verify(materialization).materializeAttemptFeedback(job, lease, payload.attemptId, attemptFeedback)
	}

	@Test
	fun attemptRetryAfterTheCheckpointSkipsTheModelAndSavesTheCheckpointedFeedback() {
		val coach = Mockito.mock(AiResumeCoachService::class.java)
		val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
		val practice = Mockito.mock(PracticeService::class.java)
		val payload = attemptPayload()
		val job = attemptJob(payload, objectMapper.valueToTree(attemptFeedback))

		FeedbackJobHandler(coach, resolver, practice).handle(payload, attemptContext(job))

		Mockito.verifyNoInteractions(coach, resolver, practice)
		Mockito.verify(jobStore, Mockito.never()).checkpointResult(any(), any(), any())
		Mockito.verify(materialization).materializeAttemptFeedback(job, lease, payload.attemptId, attemptFeedback)
	}

	private val objectMapper = ObjectMapper().findAndRegisterModules()
	private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
	private val materialization = Mockito.mock(JobEffectMaterializationService::class.java)
	private val lease = UUID.randomUUID()
	private val attemptFeedback = AnswerFeedbackResult(74, "Clear.", "Add numbers.", listOf("Ownership"), listOf("No metric"), listOf("Context"), null)
	private fun attemptPayload() = AttemptFeedbackPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
	private fun attemptContext(job: BackgroundJob) = JobExecutionContext(job, lease, jobStore, materialization, JobMetrics(SimpleMeterRegistry()), objectMapper)
	private fun attemptJob(payload: AttemptFeedbackPayload, checkpoint: JsonNode?) = BackgroundJob(
		UUID.randomUUID(), UUID.randomUUID(), JobType.ANSWER_FEEDBACK, AttemptFeedbackPayload.RESOURCE, payload.attemptId,
		JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.valueToTree(payload), checkpoint, "fingerprint", 1, 3, null, null, null,
		Instant.now(), Instant.now(), Instant.now(), null, Instant.now(), null, lease, Instant.now().plusSeconds(300)
	)

}
