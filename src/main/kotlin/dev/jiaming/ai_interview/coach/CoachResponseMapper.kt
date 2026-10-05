package dev.jiaming.ai_interview.coach

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.experience.ExperienceSplitItem
import dev.jiaming.ai_interview.experience.ExperienceSplitResult
import dev.jiaming.ai_interview.score.ResumeScoreFix
import dev.jiaming.ai_interview.score.ResumeScoreResult
import dev.jiaming.ai_interview.score.ResumeScoreRewrite
import dev.jiaming.ai_interview.fit.FitFeedback
import dev.jiaming.ai_interview.fit.JobFitResult
import dev.jiaming.ai_interview.fit.MatchedRequirement
import dev.jiaming.ai_interview.fit.MissingRequirement
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.practice.PracticeQuestionDraft
import dev.jiaming.ai_interview.practice.PracticeQuestionDrafts
import dev.jiaming.ai_interview.suggestions.ExperienceSuggestionItem
import dev.jiaming.ai_interview.suggestions.ExperienceSuggestionsResult
import dev.jiaming.ai_interview.suggestions.SuggestionSource
import org.springframework.stereotype.Component
import java.io.IOException
import java.time.Instant
import java.util.Locale

@Component
class CoachResponseMapper(private val objectMapper: ObjectMapper) {
    fun <T> parse(json: String, responseType: Class<T>): T = try { objectMapper.readValue(json, responseType) }
    catch (exception: IOException) {
        throw GeminiException(GeminiErrorCode.INVALID_RESPONSE, "Gemini returned JSON that did not match the expected AI contract", exception, false)
    }

    fun normalizeExperienceSplit(response: ExperienceSplitResponse): ExperienceSplitResult {
        val items = response.items.orEmpty().filterNotNull().map { item ->
            val title = experienceText(item.title, "title", 1, 120)
            val description = experienceText(item.description, "description", 1, 4_000)
            val organization = item.organization?.let(::collapseWhitespace)?.takeIf(String::isNotEmpty)?.also {
                if (it.length > 120) invalidExperience("organization")
            }
            ExperienceSplitItem(
                title,
                organization,
                experienceMonth(item.startDate, "startDate"),
                experienceMonth(item.endDate, "endDate"),
                description,
                null
            )
        }
        return ExperienceSplitResult(items)
    }

    /** Keeps only rewrites whose original line was copied from [resumeText], so each one points at a real line. */
    fun normalizeResumeScore(response: ResumeScoreDraftResponse, resumeText: String, jobTitle: String?): ResumeScoreResult {
        val scores = response.scores ?: AssessmentScores(0, 0, 0, 0, 0)
        val normalizedScores = AssessmentScores(
            clampScore(scores.technicalDepth), clampScore(scores.impact), clampScore(scores.clarity),
            clampScore(scores.relevance), clampScore(scores.ats)
        )
        val fixes = response.fixes.orEmpty().filterNotNull().filter { !it.message.isNullOrBlank() }.take(6).mapIndexed { index, fix ->
            ResumeScoreFix(index + 1, fallback(fix.section, "Resume"), normalizePriority(fix.priority).uppercase(Locale.ROOT), fix.message!!.trim())
        }.ifEmpty {
            listOf(ResumeScoreFix(1, "Experience", "MEDIUM", "Add clear scope and measurable outcomes where you can support them."))
        }
        val resumeLines = resumeText.lineSequence().map(::collapseWhitespace).toSet()
        // The prompt sent "<" before a tag name as "&lt;", so the model may copy a line back in that form.
        val rewrites = response.rewrites.orEmpty().filterNotNull()
            .filter { !it.rewritten.isNullOrBlank() && !it.original.isNullOrBlank() }
            .map { it to it.original!!.replace("&lt;", "<").trim() }
            .filter { (_, original) -> collapseWhitespace(original) in resumeLines }
            .take(5).map { (rewrite, original) ->
                val rewritten = rewrite.rewritten!!.trim()
                ResumeScoreRewrite(
                    fallback(rewrite.section, "Experience"), original, rewritten,
                    PLACEHOLDER.findAll(rewritten).map { it.value }.distinct().toList()
                )
            }
        return ResumeScoreResult(
            response.overall?.let(::clampScore) ?: average(normalizedScores), normalizedScores,
            fallback(response.summary, "The resume was scored, but no summary was returned."),
            fixes, rewrites, jobTitle?.trim()?.ifBlank { null }, Instant.now()
        )
    }

    fun normalizeFeedback(response: AnswerFeedbackResponse): AnswerFeedbackResult = AnswerFeedbackResult(
        clampScore(response.score), fallback(response.summary, "The answer was scored, but no summary was returned."),
        fallback(response.nextStep, "Add clearer structure, technical detail, and measurable outcomes."), nonEmpty(response.strengths),
        nonEmpty(response.gaps), nonEmpty(response.betterAnswerOutline), response.followUpQuestion?.trim()?.ifEmpty { null }
    )

    fun normalizeJobFit(response: JobFitResponse): JobFitResult = JobFitResult(
        fitScore = clampScore(response.fitScore ?: 0),
        summary = fallback(response.summary, "The fit was assessed, but no summary was returned."),
        matchedRequirements = response.matchedRequirements.orEmpty().filterNotNull()
            .filter { !it.requirement.isNullOrBlank() && !it.evidence.isNullOrBlank() }
            .take(12).map { MatchedRequirement(it.requirement!!.trim(), it.evidence!!.trim()) },
        missingRequirements = response.missingRequirements.orEmpty().filterNotNull()
            .filter { !it.requirement.isNullOrBlank() && !it.guidance.isNullOrBlank() }
            .take(12).map { MissingRequirement(it.requirement!!.trim(), it.guidance!!.trim()) },
        feedback = response.feedback.orEmpty().filterNotNull()
            .filter { !it.message.isNullOrBlank() }.take(8)
            .map { FitFeedback(normalizeFitPriority(it.priority), it.message!!.trim()) },
    )

    /**
     * Keeps 3 to 8 questions that have text, a rationale and at least one expected signal to score answers against.
     * Fewer than 3 is invalid, so the caller's repair attempt runs.
     */
    fun normalizePracticeQuestions(response: PracticeQuestionsResponse): PracticeQuestionDrafts {
        val drafts = response.questions.orEmpty().filterNotNull()
            .mapNotNull { question ->
                val signals = cleanStrings(question.expectedSignals)
                if (question.questionText.isNullOrBlank() || question.rationale.isNullOrBlank() || signals.isEmpty()) return@mapNotNull null
                PracticeQuestionDraft(question.questionText.trim(), question.rationale.trim(), question.category?.trim()?.ifEmpty { null }, signals)
            }
            .take(MAX_PRACTICE_QUESTIONS)
        if (drafts.size < MIN_PRACTICE_QUESTIONS) throw GeminiException(
            GeminiErrorCode.INVALID_RESPONSE,
            "Gemini returned ${drafts.size} practice questions with a rationale and expected signals; at least $MIN_PRACTICE_QUESTIONS are required",
            false
        )
        return PracticeQuestionDrafts(drafts)
    }

    // Items must cite a source the model was given; the source's type and name come from our data, not the model.
    fun normalizeExperienceSuggestions(response: ExperienceSuggestionsResponse, sources: List<SuggestionSource>): ExperienceSuggestionsResult {
        val provided = sources.associateBy { it.id.toString() }
        return ExperienceSuggestionsResult(response.items.orEmpty().filterNotNull().mapNotNull { item ->
            val source = provided[item.sourceId?.trim()?.lowercase(Locale.ROOT)] ?: return@mapNotNull null
            val fields = listOf(item.requirement, item.match, item.whyItFits, item.guidance).map { it?.trim().orEmpty() }
            if (fields.any(String::isEmpty)) null else ExperienceSuggestionItem(fields[0], source, fields[1], fields[2], fields[3])
        }.take(8))
    }

    private fun cleanStrings(values: List<String?>?): List<String> = values.orEmpty().filterNotNull().map(String::trim).filter(String::isNotBlank).take(6)
    private fun nonEmpty(values: List<String>?): List<String> = cleanStrings(values).ifEmpty { listOf("No specific evidence returned") }
    private fun normalizePriority(value: String?) = fallback(value, "medium").lowercase(Locale.ROOT).let { if (it in setOf("high", "medium", "low")) it else "medium" }
    private fun normalizeFitPriority(value: String?) = fallback(value, "MEDIUM").uppercase(Locale.ROOT).let { if (it in setOf("HIGH", "MEDIUM", "LOW")) it else "MEDIUM" }
    private fun average(scores: AssessmentScores) = Math.round((scores.technicalDepth + scores.impact + scores.clarity + scores.relevance + scores.ats) / 5.0f)
    private fun clampScore(value: Int) = value.coerceIn(0, 100)
    private fun experienceText(value: String?, field: String, min: Int, max: Int): String = value?.let(::collapseWhitespace)
        ?.takeIf { it.length in min..max } ?: invalidExperience(field)
    private fun collapseWhitespace(value: String) = value.trim().replace(WHITESPACE, " ")
    private fun experienceMonth(value: String?, field: String): String? {
        val month = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
        if (!Regex("\\d{4}-(0[1-9]|1[0-2])").matches(month)) invalidExperience(field)
        return month
    }
    private fun invalidExperience(field: String): Nothing = throw GeminiException(
        GeminiErrorCode.INVALID_RESPONSE,
        "Gemini returned an experience with an invalid $field field",
        false
    )
    private fun fallback(value: String?, default: String) = if (value.isNullOrBlank()) default else value.trim()

    private companion object {
        val PLACEHOLDER = Regex("""\[[^\]\r\n]+\]""")
        val WHITESPACE = Regex("\\s+")
        const val MIN_PRACTICE_QUESTIONS = 3
        const val MAX_PRACTICE_QUESTIONS = 8
    }
}
