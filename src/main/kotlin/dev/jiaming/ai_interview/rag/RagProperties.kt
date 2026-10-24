package dev.jiaming.ai_interview.rag

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.bind.ConstructorBinding

@ConfigurationProperties(prefix = "app.rag")
class RagProperties @ConstructorBinding constructor(
    embeddingDimensions: Int,
    defaultTopK: Int,
    resumeCandidateTopK: Int,
    jobDescriptionCandidateTopK: Int,
    assessmentMaxSnippets: Int,
    questionMaxSnippets: Int,
    feedbackMaxSnippets: Int,
    assessmentMinJobDescriptionSnippets: Int,
    questionMinJobDescriptionSnippets: Int,
    feedbackMinJobDescriptionSnippets: Int,
    maxSnippetsPerSection: Int,
    rrfK: Int,
    embeddingModel: String?,
    chunkSchema: String?
) {
    private val dimensions = embeddingDimensions.takeIf { it > 0 } ?: 1024
    private val topK = defaultTopK.takeIf { it > 0 } ?: 8
    private val resumeTopK = resumeCandidateTopK.takeIf { it > 0 } ?: 6
    private val jdTopK = jobDescriptionCandidateTopK.takeIf { it > 0 } ?: 4
    private val assessmentBudget = assessmentMaxSnippets.takeIf { it > 0 } ?: 14
    private val questionBudget = questionMaxSnippets.takeIf { it > 0 } ?: 16
    private val feedbackBudget = feedbackMaxSnippets.takeIf { it > 0 } ?: 10
    private val assessmentJdMinimum = assessmentMinJobDescriptionSnippets.coerceAtLeast(0)
    private val questionJdMinimum = questionMinJobDescriptionSnippets.coerceAtLeast(0)
    private val feedbackJdMinimum = feedbackMinJobDescriptionSnippets.coerceAtLeast(0)
    private val maxSection = maxSnippetsPerSection.takeIf { it > 0 } ?: 3
    private val reciprocalRankK = rrfK.takeIf { it > 0 } ?: 15
    private val model = embeddingModel?.takeIf { it.isNotBlank() }?.trim() ?: "gemini-embedding-001"
    private val schema = chunkSchema?.takeIf { it.isNotBlank() }?.trim() ?: "section-block-v3"

    constructor(embeddingDimensions: Int, defaultTopK: Int, embeddingModel: String?, chunkSchema: String?) :
        this(embeddingDimensions, defaultTopK, 6, 4, 14, 16, 10, 3, 3, 2, 3, 15, embeddingModel, chunkSchema)

    fun embeddingDimensions() = dimensions
    fun defaultTopK() = topK
    fun resumeCandidateTopK() = resumeTopK
    fun jobDescriptionCandidateTopK() = jdTopK
    fun assessmentMaxSnippets() = assessmentBudget
    fun questionMaxSnippets() = questionBudget
    fun feedbackMaxSnippets() = feedbackBudget
    fun assessmentMinJobDescriptionSnippets() = assessmentJdMinimum
    fun questionMinJobDescriptionSnippets() = questionJdMinimum
    fun feedbackMinJobDescriptionSnippets() = feedbackJdMinimum
    fun maxSnippetsPerSection() = maxSection
    fun rrfK() = reciprocalRankK
    fun embeddingModel() = model
    fun chunkSchema() = schema
}
