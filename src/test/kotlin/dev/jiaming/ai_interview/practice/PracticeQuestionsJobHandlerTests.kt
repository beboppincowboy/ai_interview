package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachAnalysisInput
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
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
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any

class PracticeQuestionsJobHandlerTests {
    private val objectMapper = ObjectMapper().findAndRegisterModules()
    private val coach = Mockito.mock(AiResumeCoachService::class.java)
    private val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
    private val practice = Mockito.mock(PracticeService::class.java)
    private val jobStore = Mockito.mock(BackgroundJobStore::class.java)
    private val materialization = Mockito.mock(JobEffectMaterializationService::class.java)
    private val handler = PracticeQuestionsJobHandler(coach, resolver, practice)
    private val userId = UUID.randomUUID()
    private val lease = UUID.randomUUID()
    private val payload = PracticeQuestionsPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
    private val drafts = PracticeQuestionDrafts((1..3).map { PracticeQuestionDraft("Question $it?", "Reason $it", null, listOf("signal")) })
    private val saved = listOf(PracticeQuestionView(UUID.randomUUID(), 1, QuestionOrigin.AI, "Question 1?", "Reason 1", null, listOf("signal")))

    @Test
    fun generatesFromThePairThenCheckpointsBeforeSavingAndReturnsTheSavedQuestions() {
        val job = job(null)
        Mockito.`when`(resolver.resolveStrict(userId, payload.resumeId, payload.targetJobId)).thenReturn(ResolvedJobInputs(
            document(DocumentSourceType.RESUME, payload.resumeId), Optional.of(document(DocumentSourceType.JOB_DESCRIPTION, payload.targetJobId))
        ))
        Mockito.`when`(coach.generatePracticeQuestions(any<CoachAnalysisInput>())).thenReturn(drafts)
        Mockito.`when`(practice.questions(userId, payload.practiceSetId)).thenReturn(saved)

        val returned = handler.handle(payload, context(job))

        assertThat(returned).isEqualTo(objectMapper.valueToTree<JsonNode>(PracticeQuestionsResult(saved)))
        assertThat(returned.at("/questions/0/attempts").isArray).isTrue()
        Mockito.verify(jobStore).updateStage(job.id, lease, JobStage.GENERATING_QUESTIONS)
        val order = Mockito.inOrder(jobStore, materialization)
        order.verify(jobStore).checkpointResult(job.id, lease, objectMapper.valueToTree(drafts))
        order.verify(materialization).materializePracticeQuestions(job, lease, payload.practiceSetId, drafts.drafts)
    }

    @Test
    fun retryAfterCheckpointSkipsRetrievalAndTheModel() {
        val job = job(objectMapper.valueToTree(drafts))
        Mockito.`when`(practice.questions(userId, payload.practiceSetId)).thenReturn(saved)

        handler.handle(payload, context(job))

        Mockito.verifyNoInteractions(coach, resolver)
        Mockito.verify(materialization).materializePracticeQuestions(job, lease, payload.practiceSetId, drafts.drafts)
    }

    private fun document(type: DocumentSourceType, id: UUID) = ResolvedDocument(type, id, "hash-$id", "text", emptyList())

    private fun context(job: BackgroundJob) = JobExecutionContext(job, lease, jobStore, materialization, JobMetrics(SimpleMeterRegistry()), objectMapper)

    private fun job(checkpoint: JsonNode?) = BackgroundJob(
        UUID.randomUUID(), userId, JobType.PRACTICE_QUESTIONS, PracticeService.RESOURCE, payload.practiceSetId,
        JobStatus.PROCESSING, JobStage.QUEUED, objectMapper.valueToTree(payload), checkpoint,
        "fingerprint", 1, 3, null, null, null, Instant.now(), Instant.now(), Instant.now(), null, Instant.now(), null, lease,
        Instant.now().plusSeconds(300)
    )
}
