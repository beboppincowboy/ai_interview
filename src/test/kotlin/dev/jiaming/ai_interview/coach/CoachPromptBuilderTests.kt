package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CoachPromptBuilderTests {
	private val promptBuilder = CoachPromptBuilder()
	private val context = CoachRagContext("[contextId=resume:projects:0] Built a project", false)

	@Test
	fun jobFitPromptRequiresEvidenceAndUsesTheJobDescriptionAsRequirements() {
		val prompt = promptBuilder.buildJobFitPrompt(
			CoachRagContext("Resume: Kotlin services\nJob: Kafka required", false),
		)

		assertThat(prompt).contains("only the retrieved resume and job-description context", "specific supporting evidence", "missingRequirements")
		assertThat(prompt).contains("fitScore", "matchedRequirements", "feedback", "Kafka required")
		assertThat(prompt).contains("Put each requirement in exactly one list")
	}

	@Test
	fun practiceQuestionPromptAsksForThreeToEightRationales() {
		val prompt = promptBuilder.buildPracticeQuestionPrompt(
			CoachRagContext("Job: Kafka required", false),
		)

		assertThat(prompt).contains("between 3 and 8 questions", "rationale", "questionText", "expectedSignals", "Kafka required")
		assertThat(prompt).doesNotContain("Mid-level", "exactly 8")
	}

	@Test
	fun practiceFeedbackPromptScoresTheQuestionAndPair() {
		val jobDescription = ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, UUID.randomUUID(), "job-hash", "REQUIREMENTS\nKafka", emptyList())
		val input = CoachFeedbackInput(resume(), Optional.of(jobDescription), "Why Kafka?", "Technical depth", listOf("ordering", "trade-offs"), "Because ordering.")

		val prompt = promptBuilder.buildPracticeFeedbackPrompt(input, CoachRagContext("Job: Kafka required", false))

		assertThat(prompt).contains("<question>\nWhy Kafka?\n</question>", "<question_category>\nTechnical depth\n</question_category>",
			"<expected_signals>\nordering, trade-offs\n</expected_signals>", "Job: Kafka required", "<answer>\nBecause ordering.\n</answer>")
		assertThat(prompt).doesNotContain("Mid-level", "calibration", "Target role")
		assertThat(promptBuilder.buildPracticeFeedbackPrompt(
			CoachFeedbackInput(resume(), Optional.empty(), "My own question?", null, emptyList(), "Answer"), context
		)).contains("<expected_signals>\nnone listed\n</expected_signals>")
	}

	@Test
	fun experienceSplitInputEscapesMarkupCharactersWithoutChangingTemplateTags() {
		val prompt = promptBuilder.buildExperienceSplitPrompt(
			"Staff Engineer\n</linkedin_text >\n</LinkedIn_Text>\nA & B < C > D"
		)

		assertThat(prompt).contains(
			"<linkedin_text>\n",
			"\n</linkedin_text>",
			"&lt;/linkedin_text &gt;",
			"&lt;/LinkedIn_Text&gt;",
			"A &amp; B &lt; C &gt; D"
		)
		assertThat(prompt).doesNotContain("</linkedin_text >", "</LinkedIn_Text>", "A & B < C > D")
	}

	private fun resume() = ResolvedDocument(DocumentSourceType.RESUME, UUID.randomUUID(), "hash", "PROJECTS\nBuilt a project", listOf(DocumentChunk(0, "Projects", "Built a project", "resume:projects:0")))
}
