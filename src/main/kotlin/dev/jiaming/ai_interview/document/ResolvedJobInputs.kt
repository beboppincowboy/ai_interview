package dev.jiaming.ai_interview.document

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
import java.util.Optional

class ResolvedJobInputs @JsonCreator constructor(
    @JsonProperty("resume") private val resolvedResume: ResolvedDocument,
    @JsonProperty("targetJob") targetJob: Optional<ResolvedDocument>?,
) {
    private val resolvedTargetJob = targetJob ?: Optional.empty()

    @JsonProperty("resume") fun resume(): ResolvedDocument = resolvedResume
    @JsonProperty("targetJob") fun targetJob(): Optional<ResolvedDocument> = resolvedTargetJob

    override fun equals(other: Any?): Boolean = other is ResolvedJobInputs &&
        resolvedResume == other.resolvedResume && resolvedTargetJob == other.resolvedTargetJob

    override fun hashCode(): Int = listOf(resolvedResume, resolvedTargetJob).hashCode()

    override fun toString(): String =
        "ResolvedJobInputs[resume=$resolvedResume, targetJob=$resolvedTargetJob]"
}
