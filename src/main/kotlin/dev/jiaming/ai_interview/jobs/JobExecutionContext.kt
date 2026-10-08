package dev.jiaming.ai_interview.jobs

import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.function.Supplier
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import org.slf4j.LoggerFactory
import dev.jiaming.ai_interview.fit.JobFitResult
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.practice.PracticeQuestionDraft
import dev.jiaming.ai_interview.score.ResumeScoreResult
import dev.jiaming.ai_interview.suggestions.ExperienceSuggestionsRun
import dev.jiaming.ai_interview.voice.VoiceSessionReport

class JobExecutionContext internal constructor(
    private val job: BackgroundJob, private val leaseToken: UUID, private val jobStore: BackgroundJobStore,
    private val materializationService: JobEffectMaterializationService, private val metrics: JobMetrics,
    private val objectMapper: ObjectMapper
) {
    private val checkpointState: ObjectNode = (job.resultPayload as? ObjectNode)?.deepCopy() ?: objectMapper.createObjectNode()
    private var activeStage: JobStage? = null
    private var stageStartedAt: Instant? = null

    fun job() = job
    fun userId(): UUID = job.requireUserId()
    fun stage(stage: JobStage) {
        finishActiveStage()
        jobStore.updateStage(job.id, leaseToken, stage)
        activeStage = stage
        stageStartedAt = Instant.now()
        log.info("job_stage_changed jobId={} type={} stage={} attempt={}", job.id, job.jobType, stage, job.attempts)
    }
    fun <T> checkpoint(field: String, type: Class<T>): T? = if (!checkpointState.hasNonNull(field)) null else convert(checkpointState.get(field), type)
    fun <T> rootCheckpoint(type: Class<T>, requiredField: String): T? {
        val result = job.resultPayload
        if (result == null || !result.isObject || !result.has(requiredField)) return null
        return convert(result, type)
    }
    fun saveCheckpoint(field: String, value: Any) {
        checkpointState.set<JsonNode>(field, objectMapper.valueToTree(value))
        jobStore.checkpointResult(job.id, leaseToken, checkpointState.deepCopy())
        log.info("job_checkpoint_saved jobId={} type={} checkpoint={}", job.id, job.jobType, field)
    }
    fun saveRootCheckpoint(value: Any, checkpointName: String) {
        jobStore.checkpointResult(job.id, leaseToken, objectMapper.valueToTree(value))
        log.info("job_checkpoint_saved jobId={} type={} checkpoint={}", job.id, job.jobType, checkpointName)
    }
    fun materializeResumeScore(resumeId: UUID, response: ResumeScoreResult) = materializationService.materializeResumeScore(job, leaseToken, resumeId, response)
    fun materializeJobFit(fitId: UUID, response: JobFitResult) = materializationService.materializeJobFit(job, leaseToken, fitId, objectMapper.valueToTree(response))
    fun materializePracticeQuestions(practiceSetId: UUID, drafts: List<PracticeQuestionDraft>) = materializationService.materializePracticeQuestions(job, leaseToken, practiceSetId, drafts)
    fun materializeExperienceSuggestions(suggestionsId: UUID, run: ExperienceSuggestionsRun) =
        materializationService.materializeExperienceSuggestions(job, leaseToken, suggestionsId, objectMapper.valueToTree(run.result), run.sourceIds)
    fun materializeAttemptFeedback(attemptId: UUID, feedback: AnswerFeedbackResult) = materializationService.materializeAttemptFeedback(job, leaseToken, attemptId, feedback)
    fun materializeVoiceReport(sessionId: UUID, report: VoiceSessionReport) = materializationService.materializeVoiceReport(job, leaseToken, sessionId, report)
    fun <T> withOwnedLease(work: Supplier<T>): T = materializationService.withOwnedLease(job, leaseToken, work)
    fun toJson(value: Any): JsonNode = objectMapper.valueToTree(value)
    internal fun finish() { finishActiveStage() }
    private fun <T> convert(node: JsonNode, type: Class<T>): T = try {
        objectMapper.treeToValue(node, type)
    } catch (exception: JsonProcessingException) {
        throw IllegalArgumentException("Invalid ${type.simpleName} checkpoint", exception)
    }
    private fun finishActiveStage() {
        val stage = activeStage ?: return
        val startedAt = stageStartedAt ?: return
        metrics.stageDuration(job.jobType, stage, Duration.between(startedAt, Instant.now()))
        activeStage = null
        stageStartedAt = null
    }
    companion object { private val log = LoggerFactory.getLogger(JobExecutionContext::class.java) }
}
