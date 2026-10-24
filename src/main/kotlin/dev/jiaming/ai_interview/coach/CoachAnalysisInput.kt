package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional

/** Supplies a required resolved resume and optional target job; a null optional wrapper is normalized to empty. */
class CoachAnalysisInput(
    private val resumeValue: ResolvedDocument,
    targetJobValue: Optional<ResolvedDocument>?
) {
    private val targetJobValue = targetJobValue ?: Optional.empty()
    fun resume() = resumeValue
    fun targetJob() = targetJobValue

    override fun equals(other: Any?): Boolean = other is CoachAnalysisInput &&
        resumeValue == other.resumeValue && targetJobValue == other.targetJobValue

    override fun hashCode(): Int = listOf(resumeValue, targetJobValue).hashCode()

    override fun toString(): String =
        "CoachAnalysisInput[resume=$resumeValue, targetJob=$targetJobValue]"
}
