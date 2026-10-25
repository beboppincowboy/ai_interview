package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.coach.Priority
import com.fasterxml.jackson.annotation.JsonInclude as Jackson2JsonInclude

/** Target-job fit output; null agenticMetadata means a legacy result and is omitted from JSON.
 * Agentic results require a 0..100 score and exact legacy projections; violations throw [IllegalArgumentException].
 * Legacy scores retain historical parsing behavior; evidence may contain RAW candidate text.
 */
data class JobFitResult(
    val fitScore: Int,
    val summary: String,
    val matchedRequirements: List<MatchedRequirement>,
    val missingRequirements: List<MissingRequirement>,
    val feedback: List<FitFeedback>,
    @get:Jackson2JsonInclude(Jackson2JsonInclude.Include.NON_NULL)
    val agenticMetadata: JobFitEvidenceMetadata? = null,
) {
    init {
        agenticMetadata?.let { metadata ->
            require(fitScore in 0..100) { "Agentic fit score must be between 0 and 100" }
            require(matchedRequirements == metadata.legacyMatchedRequirements()) {
                "Legacy matched requirements must be derived from Supported assessments"
            }
            require(missingRequirements == metadata.legacyMissingRequirements()) {
                "Legacy missing requirements must be derived from non-Supported assessments"
            }
        }
    }

    companion object {
        /** Pure factory deriving compatibility lists from metadata; throws [IllegalArgumentException] for scores outside 0..100. */
        fun fromAgentic(
            fitScore: Int,
            summary: String,
            feedback: List<FitFeedback>,
            metadata: JobFitEvidenceMetadata,
        ) = JobFitResult(
            fitScore = fitScore,
            summary = summary,
            matchedRequirements = metadata.legacyMatchedRequirements(),
            missingRequirements = metadata.legacyMissingRequirements(),
            feedback = feedback,
            agenticMetadata = metadata,
        )
    }
}

/** A target-job requirement supported by candidate evidence. */
data class MatchedRequirement(val requirement: String, val evidence: String)

/** Compatibility projection for a requirement that is not fully supported, with guidance. */
data class MissingRequirement(val requirement: String, val guidance: String)

/** Prioritized action for improving target-job fit. */
data class FitFeedback(val priority: Priority, val message: String)
