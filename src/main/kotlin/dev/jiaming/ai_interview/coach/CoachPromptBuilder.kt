package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.suggestions.SuggestionSource
import org.springframework.stereotype.Component

/**
 * Builds model prompts from the templates in `src/main/resources/prompts/`. Each template holds everything the model
 * reads before the input: role, steps, rules and output format. This class adds the `# Input` section, one tag per input.
 * A template line that starts with `[incomplete-capture] ` is kept, without that marker, only for an incomplete voice capture.
 */
@Component
class CoachPromptBuilder {
    private val experienceSplit = template("experience-split")
    private val resumeScore = template("resume-score")
    private val jobFit = template("job-fit")
    private val experienceSuggestions = template("experience-suggestions")
    private val practiceQuestions = template("practice-questions")
    private val practiceFeedback = template("practice-feedback")
    private val jsonRepair = template("json-repair")

    fun buildExperienceSplitPrompt(text: String): String = prompt(experienceSplit, listOf("linkedin_text" to text))

    fun buildResumeScorePrompt(resumeText: String, jobTitle: String?): String = prompt(
        resumeScore, listOf("job_title" to fallback(jobTitle, "Not provided"), "resume" to resumeText)
    )

    fun buildJobFitPrompt(context: CoachRagContext): String = prompt(jobFit, listOf("context" to context.context))

    fun buildExperienceSuggestionsPrompt(resumeText: String, jobDescription: String, sources: List<Pair<SuggestionSource, String>>): String = prompt(
        experienceSuggestions,
        listOf(
            "job_description" to jobDescription,
            "selected_resume" to resumeText,
            "sources" to sources.joinToString("\n\n") { (source, text) -> "[sourceId=${source.id} type=${source.type} name=${source.name}]\n$text" }
        )
    )

    fun buildPracticeQuestionPrompt(context: CoachRagContext): String = prompt(practiceQuestions, listOf("context" to context.context))

    /** Scores one practice attempt against its question and the pair's resume and job description. */
    fun buildPracticeFeedbackPrompt(input: CoachFeedbackInput, context: CoachRagContext): String = prompt(
        practiceFeedback.lines().mapNotNull { line ->
            if (!line.startsWith(INCOMPLETE_CAPTURE_MARKER)) line
            else if (input.incompleteCapture()) line.removePrefix(INCOMPLETE_CAPTURE_MARKER)
            else null
        }.joinToString("\n"),
        listOf(
            "question_category" to fallback(input.category(), "Interview"),
            "question" to fallback(input.questionText(), ""),
            "expected_signals" to input.expectedSignals().joinToString(", ").ifEmpty { "none listed" },
            "context" to context.context,
            "answer" to truncate(input.answerText(), ANSWER_PROMPT_LIMIT)
        ) + if (input.incompleteCapture()) listOf("capture_status" to "INCOMPLETE: the answer capture may have ended early") else emptyList()
    )

    fun buildRepairPrompt(originalPrompt: String, invalidOutput: String, parseError: String?): String = prompt(
        jsonRepair,
        listOf("original_request" to originalPrompt, "invalid_response" to invalidOutput,
            "parser_error" to fallback(parseError, "JSON did not match the schema"))
    )

    private fun prompt(template: String, inputs: List<Pair<String, String>>): String = buildString {
        appendLine(template.trimEnd()).appendLine()
        appendLine("# Input")
        // Only tag-shaped text is neutralized, so pasted text cannot open or close a delimiter while ordinary
        // characters such as "R&D" or "<10ms" reach the model unchanged and can be copied back exactly.
        // The repair prompt's original request was built here already, so it goes in as is.
        inputs.forEach { (tag, value) ->
            appendLine("<$tag>")
            appendLine(if (tag == "original_request") value else value.replace(TAG_LIKE, "&lt;"))
            appendLine("</$tag>")
        }
    }.trimEnd()

    private fun truncate(value: String?, limit: Int): String {
        val safe = fallback(value, "")
        return if (safe.length <= limit) safe else safe.substring(0, limit) + "\n[truncated]"
    }
    private fun fallback(value: String?, default: String) = if (value.isNullOrBlank()) default else value.trim()

    companion object {
        private val TAG_LIKE = Regex("<(?=\\s*/|[A-Za-z!?])")
        private const val ANSWER_PROMPT_LIMIT = 4_000
        private const val INCOMPLETE_CAPTURE_MARKER = "[incomplete-capture] "

        private fun template(name: String): String =
            CoachPromptBuilder::class.java.getResource("/prompts/$name.md")?.readText()
                ?: error("Missing prompt template prompts/$name.md")
    }
}
