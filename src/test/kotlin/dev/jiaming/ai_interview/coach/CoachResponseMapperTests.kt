package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.experience.ExperienceSplitResult
import dev.jiaming.ai_interview.experience.ExperienceSplitItem
import dev.jiaming.ai_interview.fit.FitFeedback
import dev.jiaming.ai_interview.fit.MatchedRequirement
import dev.jiaming.ai_interview.fit.MissingRequirement
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CoachResponseMapperTests {
	private val mapper = CoachResponseMapper(ObjectMapper())

	@Test
	fun normalizesExperienceSplitFieldsAndLeavesDuplicateChecksForTheOwnerScopedService() {
		val response = ExperienceSplitResponse(listOf(
			ExperienceSplitResponseItem("  Senior   Engineer ", " Acme ", "2021-03", null, " Built a reliable service. ")
		))

		val result: ExperienceSplitResult = mapper.normalizeExperienceSplit(response)

		assertThat(result.items).containsExactly(ExperienceSplitItem("Senior Engineer", "Acme", "2021-03", null,
			"Built a reliable service.", null))
	}

	@Test
	fun rejectsExperienceSplitItemsThatCannotBeSaved() {
		val response = ExperienceSplitResponse(listOf(
			ExperienceSplitResponseItem("Engineer", null, "2023-1", null, "Built a service.")
		))

		assertThatThrownBy { mapper.normalizeExperienceSplit(response) }
			.isInstanceOf(GeminiException::class.java)
			.hasMessageContaining("experience")
	}

    @Test
    fun normalizesResumeScoresFixRanksPrioritiesAndRewritePlaceholders() {
        val draft = ResumeScoreDraftResponse(
            130,
            AssessmentScores(130, -4, 64, 75, 71),
            "Strong backend depth; impact is under-quantified.",
            listOf(
                ResumeScoreFixDraft("Experience", "high", "Quantify the result."),
                ResumeScoreFixDraft("Skills", "invalid", "Group related tools.")
            ),
            listOf(ResumeScoreRewriteDraft("Experience", "- Improved latency.", "Cut latency by [X%] across [N] services."))
        )

        val normalized = mapper.normalizeResumeScore(draft, "EXPERIENCE\n- Improved latency.", "Backend Engineer")

        assertThat(normalized.overall).isEqualTo(100)
        assertThat(normalized.scores.technicalDepth).isEqualTo(100)
        assertThat(normalized.scores.impact).isZero()
        assertThat(normalized.fixes.map { it.rank }).containsExactly(1, 2)
        assertThat(normalized.fixes.map { it.priority }).containsExactly("HIGH", "MEDIUM")
        assertThat(normalized.rewrites.single().placeholders).containsExactly("[X%]", "[N]")
        assertThat(normalized.jobTitle).isEqualTo("Backend Engineer")
    }

	@Test
	fun rewritesKeepOnlyOriginalsCopiedFromTheResume() {
		val draft = ResumeScoreDraftResponse(70, null, null, null, listOf(
			ResumeScoreRewriteDraft("Experience", " - Built   the payment API. ", "Built the payment API serving [N] merchants."),
			ResumeScoreRewriteDraft("Experience", "Cut costs.", "Reduced costs by [X%]."),
			ResumeScoreRewriteDraft("Experience", "API. Cut", "Improved the API and cut costs."),
			ResumeScoreRewriteDraft("Experience", "  ", "An improved line with no source."),
			ResumeScoreRewriteDraft("Experience", "Led a team of 40.", "Led a team of [N]."),
			ResumeScoreRewriteDraft("Experience", "&lt;DataTable> migration", "Migrated [N] screens to <DataTable>."),
		))

		val rewrites = mapper.normalizeResumeScore(draft, "EXPERIENCE\n  - Built the payment\tAPI.  \nCut  costs.\n<DataTable> migration", null).rewrites

		assertThat(rewrites.map { it.original }).containsExactly("- Built   the payment API.", "Cut costs.", "<DataTable> migration")
	}

	@Test
	fun normalizesJobFitScoresPrioritiesAndMalformedItemsWithoutInventingLists() {
		val response = mapper.parse(
			"""{"fitScore":140,"summary":"  Strong match. ","matchedRequirements":[{"requirement":"Kotlin","evidence":"Built Kotlin services"},{"requirement":"No evidence"},null],"missingRequirements":[{"requirement":"Kafka","guidance":"Describe relevant work honestly."}],"feedback":[{"priority":"high","message":"Move the project up."},{"priority":"urgent","message":"Keep it concise."},{"message":"  "}]}""",
			JobFitResponse::class.java,
		)

		val normalized = mapper.normalizeJobFit(response)

		assertThat(normalized.fitScore).isEqualTo(100)
		assertThat(normalized.summary).isEqualTo("Strong match.")
		assertThat(normalized.matchedRequirements).containsExactly(MatchedRequirement("Kotlin", "Built Kotlin services"))
		assertThat(normalized.missingRequirements).containsExactly(MissingRequirement("Kafka", "Describe relevant work honestly."))
		assertThat(normalized.feedback).containsExactly(FitFeedback("HIGH", "Move the project up."), FitFeedback("MEDIUM", "Keep it concise."))
	}

	@Test
	fun missingJobFitFieldsNormalizeToSafeEmptyArrays() {
		val response = mapper.parse("{}", JobFitResponse::class.java)
		val normalized = mapper.normalizeJobFit(response)

		assertThat(normalized.fitScore).isZero()
		assertThat(normalized.summary).isNotBlank()
		assertThat(normalized.matchedRequirements).isEmpty()
		assertThat(normalized.missingRequirements).isEmpty()
		assertThat(normalized.feedback).isEmpty()
	}

	@Test
	fun fewerThanThreeUsablePracticeQuestionsIsAnInvalidResponseSoTheRepairPathRuns() {
		val response = mapper.parse(practiceQuestionsJson(2), PracticeQuestionsResponse::class.java)

		assertThatThrownBy { mapper.normalizePracticeQuestions(response) }
			.isInstanceOfSatisfying(GeminiException::class.java) { assertThat(it.code()).isEqualTo(GeminiErrorCode.INVALID_RESPONSE) }
	}

	@Test
	fun practiceQuestionsWithoutARationaleDoNotCountTowardTheMinimum() {
		val response = mapper.parse(
			"""{"questions":[{"questionText":"Q1?","rationale":"R1","expectedSignals":["S1"]},{"questionText":"Q2?","rationale":"R2","expectedSignals":["S2"]},{"questionText":"Q3?","rationale":"  ","expectedSignals":["S3"]}]}""",
			PracticeQuestionsResponse::class.java,
		)

		assertThatThrownBy { mapper.normalizePracticeQuestions(response) }.isInstanceOf(GeminiException::class.java)
	}

	@Test
	fun practiceQuestionsWithoutExpectedSignalsDoNotCountTowardTheMinimum() {
		val response = mapper.parse(
			"""{"questions":[{"questionText":"Q1?","rationale":"R1","expectedSignals":["S1"]},{"questionText":"Q2?","rationale":"R2","expectedSignals":["S2"]},{"questionText":"Q3?","rationale":"R3","expectedSignals":[" "]}]}""",
			PracticeQuestionsResponse::class.java,
		)

		assertThatThrownBy { mapper.normalizePracticeQuestions(response) }
			.isInstanceOfSatisfying(GeminiException::class.java) { assertThat(it.code()).isEqualTo(GeminiErrorCode.INVALID_RESPONSE) }
	}

	@Test
	fun twelvePracticeQuestionsAreTrimmedToEightEachWithARationale() {
		val response = mapper.parse(practiceQuestionsJson(12), PracticeQuestionsResponse::class.java)

		val drafts = mapper.normalizePracticeQuestions(response).drafts

		assertThat(drafts).hasSize(8)
		assertThat(drafts.map { it.text }).containsExactly("Question 1?", "Question 2?", "Question 3?", "Question 4?", "Question 5?", "Question 6?", "Question 7?", "Question 8?")
		assertThat(drafts).allSatisfy { assertThat(it.rationale).isNotBlank() }
		assertThat(drafts.first()).isEqualTo(dev.jiaming.ai_interview.practice.PracticeQuestionDraft("Question 1?", "Reason 1", "Technical depth", listOf("signal")))
	}

	private fun practiceQuestionsJson(count: Int) = (1..count).joinToString(",", """{"questions":[""", "]}") {
		""" {"category":" Technical depth ","questionText":" Question $it? ","rationale":" Reason $it ","expectedSignals":[" signal ",""]}"""
	}
}
