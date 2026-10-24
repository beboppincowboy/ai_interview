package dev.jiaming.ai_interview.fit

import dev.jiaming.ai_interview.coach.Priority

/** Target-job fit output with a score, summary, evidence, gaps, and prioritized feedback. */
data class JobFitResult(
    val fitScore: Int,
    val summary: String,
    val matchedRequirements: List<MatchedRequirement>,
    val missingRequirements: List<MissingRequirement>,
    val feedback: List<FitFeedback>,
)

/** A target-job requirement supported by evidence in the resume. */
data class MatchedRequirement(val requirement: String, val evidence: String)

/** A target-job requirement not found in the resume, with guidance. */
data class MissingRequirement(val requirement: String, val guidance: String)

/** Prioritized action for improving target-job fit. */
data class FitFeedback(val priority: Priority, val message: String)
