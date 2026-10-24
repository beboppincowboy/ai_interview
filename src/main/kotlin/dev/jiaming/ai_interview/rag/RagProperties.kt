package dev.jiaming.ai_interview.rag

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

/** Configures retrieval budgets; nonpositive limits use defaults and model/schema names use nonblank defaults. */
@ConfigurationProperties(prefix = "app.rag")
class RagProperties @ConstructorBinding constructor(
    embeddingDimensions: Int,
    resumeCandidateTopK: Int,
    jobDescriptionCandidateTopK: Int,
    assessmentMaxSnippets: Int,
    questionMaxSnippets: Int,
    feedbackMaxSnippets: Int,
    assessmentMinTargetJobSnippets: Int,
    questionMinTargetJobSnippets: Int,
    feedbackMinTargetJobSnippets: Int,
    maxSnippetsPerSection: Int,
    rrfK: Int,
    embeddingModel: String?,
    chunkSchema: String?
) {
    private val dimensions = embeddingDimensions.takeIf { it > 0 } ?: 1024
    private val resumeTopK = resumeCandidateTopK.takeIf { it > 0 } ?: 6
    private val jdTopK = jobDescriptionCandidateTopK.takeIf { it > 0 } ?: 4
    private val assessmentBudget = assessmentMaxSnippets.takeIf { it > 0 } ?: 14
    private val questionBudget = questionMaxSnippets.takeIf { it > 0 } ?: 16
    private val feedbackBudget = feedbackMaxSnippets.takeIf { it > 0 } ?: 10
    private val assessmentMinTargetJobSnippets = assessmentMinTargetJobSnippets.coerceAtLeast(0)
    private val questionMinTargetJobSnippets = questionMinTargetJobSnippets.coerceAtLeast(0)
    private val feedbackMinTargetJobSnippets = feedbackMinTargetJobSnippets.coerceAtLeast(0)
    private val maxSection = maxSnippetsPerSection.takeIf { it > 0 } ?: 3
    private val reciprocalRankK = rrfK.takeIf { it > 0 } ?: 15
    private val model = embeddingModel?.takeIf { it.isNotBlank() }?.trim() ?: "gemini-embedding-001"
    private val schema = chunkSchema?.takeIf { it.isNotBlank() }?.trim() ?: "section-block-v3"

    constructor(embeddingDimensions: Int, embeddingModel: String?, chunkSchema: String?) :
        this(embeddingDimensions, 6, 4, 14, 16, 10, 3, 3, 2, 3, 15, embeddingModel, chunkSchema)

    /** Returns vector dimension count, defaulting to 1024 when configured nonpositive. */
    fun embeddingDimensions() = dimensions
    /** Returns resume candidate top-K, defaulting to 6 when configured nonpositive. */
    fun resumeCandidateTopK() = resumeTopK
    /** Returns target-job candidate top-K, defaulting to 4 when configured nonpositive. */
    fun jobDescriptionCandidateTopK() = jdTopK
    /** Returns the assessment snippet cap, defaulting to 14. */
    fun assessmentMaxSnippets() = assessmentBudget
    /** Returns the question-context snippet cap, defaulting to 16. */
    fun questionMaxSnippets() = questionBudget
    /** Returns the feedback-context snippet cap, defaulting to 10. */
    fun feedbackMaxSnippets() = feedbackBudget
    /** Returns the assessment minimum target-job snippets, clamped to zero or greater. */
    fun assessmentMinTargetJobSnippets() = assessmentMinTargetJobSnippets
    /** Returns the question minimum target-job snippets, clamped to zero or greater. */
    fun questionMinTargetJobSnippets() = questionMinTargetJobSnippets
    /** Returns the feedback minimum target-job snippets, clamped to zero or greater. */
    fun feedbackMinTargetJobSnippets() = feedbackMinTargetJobSnippets
    /** Returns the per-section snippet cap, defaulting to 3. */
    fun maxSnippetsPerSection() = maxSection
    /** Returns the reciprocal-rank-fusion constant, defaulting to 15. */
    fun rrfK() = reciprocalRankK
    /** Returns the nonblank embedding model name, defaulting to `gemini-embedding-001`. */
    fun embeddingModel() = model
    /** Returns the nonblank chunk schema name, defaulting to `section-block-v3`. */
    fun chunkSchema() = schema
}
