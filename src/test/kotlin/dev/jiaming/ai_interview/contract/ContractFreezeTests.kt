package dev.jiaming.ai_interview.contract

import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.fit.FitView
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.practice.AttemptView
import dev.jiaming.ai_interview.practice.PracticeSetView
import dev.jiaming.ai_interview.resume.ResumeLibraryDetail
import dev.jiaming.ai_interview.resume.ResumeLibraryItem
import dev.jiaming.ai_interview.suggestions.SuggestionsView
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File
import java.util.UUID

class ContractFreezeTests {
	@Test fun jobStatusValuesAreFrozen() { assertThat(JobStatus.entries.map { it.name }).containsExactly("QUEUED", "PROCESSING", "RETRYING", "SUCCEEDED", "FAILED") }
	@Test fun jobTypeValuesAreFrozen() { assertThat(JobType.entries.map { it.name }).containsExactly("RESUME_EXTRACTION", "RESUME_SCORE", "ANSWER_FEEDBACK", "EXPERIENCE_SPLIT", "JOB_FIT", "EXPERIENCE_SUGGESTIONS", "PRACTICE_QUESTIONS", "VOICE_REPORT") }
	@Test fun jobStageValuesAreFrozen() { assertThat(JobStage.entries.map { it.name }).containsExactly("QUEUED", "READING_FILE", "EXTRACTING_TEXT", "NORMALIZING_TEXT", "CHUNKING_TEXT", "SCORING_RESUME", "GENERATING_QUESTIONS", "SPLITTING_EXPERIENCE", "SCORING_ANSWER", "MATCHING_JOB", "COMPLETED", "RETRIEVING_EXPERIENCE", "MATCHING_EXPERIENCE") }
	@Test fun assessmentScoreKeysAreFrozen() {
		val mapper = ObjectMapper()
		val fields = mapper.readTree(mapper.writeValueAsString(AssessmentScores(1, 2, 3, 4, 5))).fieldNames().asSequence().toList()
		assertThat(fields).containsExactly("technicalDepth", "impact", "clarity", "relevance", "ats")
	}
	@Test fun everyResourceViewNamesItsNewestJobLatestJob() {
		for (view in listOf(ResumeLibraryItem::class, ResumeLibraryDetail::class, FitView::class, SuggestionsView::class, PracticeSetView::class, AttemptView::class)) {
			val fields = view.java.declaredFields.map { it.name }
			assertThat(fields).describedAs(view.simpleName).contains("latestJob").doesNotContain("activeJob")
		}
	}
	@Test fun latestJobKeysAreFrozen() {
		val mapper = ObjectMapper()
		val job = LatestJob(UUID.randomUUID(), JobType.RESUME_SCORE, JobStatus.SUCCEEDED, JobStage.COMPLETED, 1, 3, null)
		val fields = mapper.readTree(mapper.writeValueAsString(job)).fieldNames().asSequence().toList()
		assertThat(fields).containsExactly("jobId", "jobType", "status", "stage", "attempts", "maxAttempts", "error")
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
	@Test fun documentedErrorCodesAreFrozen() {
		val section = File("docs/api/frontend-api-contract.md").readText().substringAfter("## 10. Error codes").substringBefore("\n## ")
		val documented = section.lines().filter { it.startsWith("| `") }
			.flatMap { row -> Regex("`([A-Z_<>a-z]+)`").findAll(row.split("|")[1]).map { it.groupValues[1] } }
		assertThat(documented).containsExactly(
			"INVALID_REQUEST", "ANSWER_EMPTY", "ANSWER_TOO_LONG", "TRANSCRIPT_TOO_LARGE", "RESUME_REFERENCE_REQUIRED",
			"NOT_FOUND", "RESUME_NOT_FOUND", "TARGET_JOB_NOT_FOUND", "EXPERIENCE_NOT_FOUND", "PRACTICE_SET_NOT_FOUND",
			"QUESTION_NOT_FOUND", "ATTEMPT_NOT_FOUND", "JOB_NOT_FOUND", "VOICE_SESSION_NOT_FOUND", "VOICE_QUESTION_NOT_FOUND",
			"CONFLICT", "EXPERIENCE_DUPLICATE", "RESUME_NOT_READY", "NO_EXPERIENCE_SOURCES", "PRACTICE_SET_NOT_READY",
			"PRACTICE_SET_NOT_FAILED", "QUESTION_LIMIT_REACHED", "ANSWER_UNCHANGED", "ATTEMPT_NOT_FAILED",
			"VOICE_SESSION_EXPIRED", "VOICE_SESSION_NOT_DRAFT", "VOICE_SESSION_ALREADY_SAVED", "VOICE_RUN_EXPIRED",
			"VOICE_TOKEN_BUDGET_EXHAUSTED", "VOICE_REPORT_NOT_RETRYABLE", "UPLOAD_TOO_LARGE", "UNSUPPORTED_FILE_TYPE",
			"UNPROCESSABLE_CONTENT", "RESUME_EXTRACTION_FAILED", "RATE_LIMITED", "VOICE_TOKEN_RATE_LIMITED",
			"INTERNAL_ERROR", "REQUEST_FAILED", "STORAGE_UNAVAILABLE", "VOICE_TOKEN_UNAVAILABLE",
			"GEMINI_NOT_CONFIGURED", "GEMINI_RATE_LIMITED", "GEMINI_TIMEOUT", "GEMINI_UPSTREAM_ERROR", "GEMINI_SAFETY",
			"GEMINI_RECITATION", "GEMINI_MAX_TOKENS", "GEMINI_EMPTY_RESPONSE", "GEMINI_INVALID_RESPONSE",
			"SERVICE_UNAVAILABLE", "RESUME_PARSER_BUSY", "VOICE_DISABLED", "VOICE_TOKEN_TIMEOUT",
			"PROCESSING_ERROR", "WORKER_LEASE_EXPIRED", "RETRIES_EXHAUSTED_<code>", "RETRIES_EXHAUSTED_DLQ",
			"RETRIES_EXHAUSTED_WORKER_LEASE_EXPIRED",
		)
	}
}
