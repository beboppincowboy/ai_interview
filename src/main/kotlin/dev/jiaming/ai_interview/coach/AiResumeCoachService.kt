package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.experience.ExperienceSplitResult
import dev.jiaming.ai_interview.gemini.GeminiErrorCode
import dev.jiaming.ai_interview.gemini.GeminiException
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.practice.PracticeQuestionDrafts
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.score.ResumeScoreResult
import dev.jiaming.ai_interview.suggestions.ExperienceSuggestionsResult
import dev.jiaming.ai_interview.suggestions.SuggestionSourceInput
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.stereotype.Service

@Service
class AiResumeCoachService(
    private val generationClient: StructuredGenerationClient,
    private val ragContextService: CoachRagContextService,
    private val promptBuilder: CoachPromptBuilder,
    private val responseMapper: CoachResponseMapper,
    private val meterRegistry: MeterRegistry
) {
    fun assessJobFit(input: CoachAnalysisInput): dev.jiaming.ai_interview.fit.JobFitResult {
        val context = ragContextService.jobFitContext(input)
        return responseMapper.normalizeJobFit(generateStructured(promptBuilder.buildJobFitPrompt(context), JobFitResponse::class.java))
    }

    // ponytail: per-source budget only (KTD7); add a total prompt cap if users keep hundreds of sources.
    fun suggestExperiences(resume: ResolvedDocument, targetJob: ResolvedDocument, sources: List<SuggestionSourceInput>): ExperienceSuggestionsResult {
        val texts = sources.map { input ->
            input.source to when (input) {
                is SuggestionSourceInput.Resume -> ragContextService.suggestionSourceText(input.document, targetJob)
                is SuggestionSourceInput.Experience -> input.text
            }
        }
        val prompt = promptBuilder.buildExperienceSuggestionsPrompt(
            ragContextService.suggestionSourceText(resume, targetJob), targetJob.normalizedText(), texts
        )
        return generateStructured(prompt, ExperienceSuggestionsResponse::class.java) {
            responseMapper.normalizeExperienceSuggestions(it, sources.map(SuggestionSourceInput::source))
        }
    }

    fun generatePracticeQuestions(input: CoachAnalysisInput): PracticeQuestionDrafts {
        val context = ragContextService.practiceQuestionContext(input)
        return generateStructured(promptBuilder.buildPracticeQuestionPrompt(context), PracticeQuestionsResponse::class.java,
            responseMapper::normalizePracticeQuestions)
    }

    /** Scores a practice attempt against its question and the pair's resume and job description (KTD7). */
    fun scorePracticeAnswer(input: CoachFeedbackInput): AnswerFeedbackResult {
        val context = ragContextService.feedbackContext(input)
        return generateStructured(promptBuilder.buildPracticeFeedbackPrompt(input, context), AnswerFeedbackResponse::class.java,
            responseMapper::normalizeFeedback)
    }

    fun scoreResume(resumeText: String, jobTitle: String?): ResumeScoreResult = responseMapper.normalizeResumeScore(
        generateStructured(promptBuilder.buildResumeScorePrompt(resumeText, jobTitle), ResumeScoreDraftResponse::class.java),
        resumeText, jobTitle
    )

    fun splitExperience(text: String): ExperienceSplitResult = generateStructured(
        promptBuilder.buildExperienceSplitPrompt(text),
        ExperienceSplitResponse::class.java,
        responseMapper::normalizeExperienceSplit
    )

    private fun <T> generateStructured(prompt: String, responseType: Class<T>): T = generateStructured(prompt, responseType) { it }

    private fun <T, R> generateStructured(prompt: String, responseType: Class<T>, normalize: (T) -> R): R {
        val firstOutput = generationClient.generateJson(prompt)
        try { return normalize(responseMapper.parse(firstOutput, responseType)) }
        catch (firstFailure: GeminiException) {
            if (firstFailure.code != GeminiErrorCode.INVALID_RESPONSE) throw firstFailure
            meterRegistry.counter("ai.gemini.schema_repair", "outcome", "attempted").increment()
            val parseError = firstFailure.cause?.message ?: firstFailure.message
            val repairedOutput = generationClient.generateJson(promptBuilder.buildRepairPrompt(prompt, firstOutput, parseError))
            try {
                val repaired = normalize(responseMapper.parse(repairedOutput, responseType))
                meterRegistry.counter("ai.gemini.schema_repair", "outcome", "succeeded").increment()
                return repaired
            } catch (secondFailure: GeminiException) {
                meterRegistry.counter("ai.gemini.schema_repair", "outcome", "failed").increment()
                throw GeminiException(GeminiErrorCode.INVALID_RESPONSE, "Gemini response remained invalid after one schema repair", secondFailure, false)
            }
        }
    }
}
