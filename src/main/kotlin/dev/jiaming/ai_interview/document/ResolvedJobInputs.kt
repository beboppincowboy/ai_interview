package dev.jiaming.ai_interview.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Optional

/** Bundles a required stored resume and an optional stored target job resolved for one job input. */
class ResolvedJobInputs @JsonCreator constructor(
    @JsonProperty("resume") private val resolvedResume: ResolvedDocument,
    @JsonProperty("targetJob") targetJob: Optional<ResolvedDocument>?,
) {
    private val resolvedTargetJob = targetJob ?: Optional.empty()

    /** Returns the resolved resume. */
    @JsonProperty("resume") fun resume(): ResolvedDocument = resolvedResume
    /** Returns the resolved target job, or empty when the job did not reference one. */
    @JsonProperty("targetJob") fun targetJob(): Optional<ResolvedDocument> = resolvedTargetJob

    override fun equals(other: Any?): Boolean = other is ResolvedJobInputs &&
        resolvedResume == other.resolvedResume && resolvedTargetJob == other.resolvedTargetJob

    override fun hashCode(): Int = listOf(resolvedResume, resolvedTargetJob).hashCode()

    override fun toString(): String =
        "ResolvedJobInputs[resume=$resolvedResume, targetJob=$resolvedTargetJob]"
}
