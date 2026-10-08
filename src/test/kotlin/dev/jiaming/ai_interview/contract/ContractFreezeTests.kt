package dev.jiaming.ai_interview.contract

import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ContractFreezeTests {
	@Test fun jobStatusValuesAreFrozen() { assertThat(JobStatus.entries.map { it.name }).containsExactly("QUEUED", "PROCESSING", "RETRYING", "SUCCEEDED", "FAILED") }
	@Test fun jobTypeValuesAreFrozen() { assertThat(JobType.entries.map { it.name }).containsExactly("RESUME_EXTRACTION", "RESUME_SCORE", "ANSWER_FEEDBACK", "EXPERIENCE_SPLIT", "JOB_FIT", "EXPERIENCE_SUGGESTIONS", "PRACTICE_QUESTIONS", "VOICE_REPORT") }
	@Test fun jobStageValuesAreFrozen() { assertThat(JobStage.entries.map { it.name }).containsExactly("QUEUED", "READING_FILE", "EXTRACTING_TEXT", "NORMALIZING_TEXT", "CHUNKING_TEXT", "SCORING_RESUME", "GENERATING_QUESTIONS", "SPLITTING_EXPERIENCE", "SCORING_ANSWER", "MATCHING_JOB", "COMPLETED", "RETRIEVING_EXPERIENCE", "MATCHING_EXPERIENCE") }
	@Test fun assessmentScoreKeysAreFrozen() {
		val mapper = ObjectMapper()
		val fields = mapper.readTree(mapper.writeValueAsString(AssessmentScores(1, 2, 3, 4, 5))).fieldNames().asSequence().toList()
		assertThat(fields).containsExactly("technicalDepth", "impact", "clarity", "relevance", "ats")
	}
	@Test fun geminiErrorCodesTheClientMapsAreFrozen() {
		assertThat(GeminiErrorCode.NOT_CONFIGURED).isEqualTo("GEMINI_NOT_CONFIGURED")
		assertThat(GeminiErrorCode.RATE_LIMITED).isEqualTo("GEMINI_RATE_LIMITED")
		assertThat(GeminiErrorCode.TIMEOUT).isEqualTo("GEMINI_TIMEOUT")
		assertThat(GeminiErrorCode.UPSTREAM_ERROR).isEqualTo("GEMINI_UPSTREAM_ERROR")
		assertThat(GeminiErrorCode.SAFETY).isEqualTo("GEMINI_SAFETY")
		assertThat(GeminiErrorCode.RECITATION).isEqualTo("GEMINI_RECITATION")
		assertThat(GeminiErrorCode.MAX_TOKENS).isEqualTo("GEMINI_MAX_TOKENS")
		assertThat(GeminiErrorCode.EMPTY_RESPONSE).isEqualTo("GEMINI_EMPTY_RESPONSE")
		assertThat(GeminiErrorCode.INVALID_RESPONSE).isEqualTo("GEMINI_INVALID_RESPONSE")
	}
}
