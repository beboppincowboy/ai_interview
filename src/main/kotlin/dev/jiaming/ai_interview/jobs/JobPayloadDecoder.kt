package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import dev.jiaming.ai_interview.experience.ExperienceSplitJobPayload
import dev.jiaming.ai_interview.practice.PracticeQuestionsPayload
import dev.jiaming.ai_interview.resume.ResumeExtractionJobPayload
import dev.jiaming.ai_interview.score.ResumeScorePayload
import dev.jiaming.ai_interview.suggestions.ExperienceSuggestionsPayload
import dev.jiaming.ai_interview.voice.VoiceReportPayload

@Component
class JobPayloadDecoder(private val objectMapper: ObjectMapper) {
    /** Only attempt-backed feedback exists; V18 deleted the resume-backed jobs of the removed interview flow. */
    fun feedback(job: BackgroundJob): AttemptFeedbackPayload {
        require(job.resourceType == AttemptFeedbackPayload.RESOURCE && isCurrent(job.requestPayload, AttemptFeedbackPayload.CURRENT_VERSION)) {
            "Invalid AttemptFeedbackPayload job payload"
        }
        return convert(job.requestPayload, AttemptFeedbackPayload::class.java)
    }

    fun resumeScore(job: BackgroundJob): ResumeScorePayload {
        val payload = convert(job.requestPayload, ResumeScorePayload::class.java)
        require(payload.payloadVersion == ResumeScorePayload.CURRENT_VERSION) { "Unsupported resume score payload version ${payload.payloadVersion}" }
        return payload
    }

    fun jobFit(job: BackgroundJob): JobFitPayload {
        if (!isCurrent(job.requestPayload, JobFitPayload.CURRENT_VERSION)) {
            throw IllegalArgumentException("Invalid ${JobFitPayload::class.java.simpleName} job payload")
        }
        return convert(job.requestPayload, JobFitPayload::class.java)
    }

    fun experienceSuggestions(job: BackgroundJob): ExperienceSuggestionsPayload {
        if (!isCurrent(job.requestPayload, ExperienceSuggestionsPayload.CURRENT_VERSION)) {
            throw IllegalArgumentException("Invalid ${ExperienceSuggestionsPayload::class.java.simpleName} job payload")
        }
        return convert(job.requestPayload, ExperienceSuggestionsPayload::class.java)
    }

    fun decode(job: BackgroundJob, payloadType: Class<*>): Any {
        val payload: Any = when (job.jobType) {
            JobType.RESUME_EXTRACTION -> convert(job.requestPayload, ResumeExtractionJobPayload::class.java)
            JobType.RESUME_SCORE -> resumeScore(job)
            JobType.ANSWER_FEEDBACK -> feedback(job)
            JobType.EXPERIENCE_SPLIT -> convert(job.requestPayload, ExperienceSplitJobPayload::class.java)
            JobType.JOB_FIT -> jobFit(job)
            JobType.EXPERIENCE_SUGGESTIONS -> experienceSuggestions(job)
            JobType.PRACTICE_QUESTIONS -> {
                require(isCurrent(job.requestPayload, PracticeQuestionsPayload.CURRENT_VERSION)) { "Invalid PracticeQuestionsPayload job payload" }
                convert(job.requestPayload, PracticeQuestionsPayload::class.java)
            }
            JobType.VOICE_REPORT -> {
                require(isCurrent(job.requestPayload, VoiceReportPayload.CURRENT_VERSION)) { "Invalid VoiceReportPayload job payload" }
                convert(job.requestPayload, VoiceReportPayload::class.java)
            }
        }
        require(payloadType.isInstance(payload)) { "Decoded payload for ${job.jobType} is not ${payloadType.simpleName}" }
        return payload
    }

    private fun isCurrent(payload: JsonNode?, version: Int) = payload != null && payload.path("payloadVersion").asInt(0) == version
    private fun <T> convert(node: JsonNode?, type: Class<T>): T = try {
        objectMapper.treeToValue(node, type)
    } catch (exception: JsonProcessingException) {
        throw IllegalArgumentException("Invalid ${type.simpleName} job payload", exception)
    }
}
