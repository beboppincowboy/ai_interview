package dev.jiaming.ai_interview.suggestions

import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.jobs.LatestJob
import java.time.Instant
import java.util.UUID

/** Kind of saved resource included as a suggestion source. */
enum class SuggestionSourceType { RESUME, EXPERIENCE }

data class SuggestionSource(val type: SuggestionSourceType, val id: UUID, val name: String)

data class ExperienceSuggestionItem(
    val requirement: String,
    val source: SuggestionSource,
    val match: String,
    val whyItFits: String,
    val guidance: String,
)

data class ExperienceSuggestionsResult(val items: List<ExperienceSuggestionItem>)

/** What a run checkpoints and stores: the result plus the sorted IDs of every source it was given. */
data class ExperienceSuggestionsRun(val result: ExperienceSuggestionsResult, val sourceIds: List<UUID>)

data class SuggestionsView(
    val resumeId: UUID,
    val targetJobId: UUID,
    val sourcesAvailable: Boolean,
    val stale: Boolean,
    val result: ExperienceSuggestionsResult?,
    val createdAt: Instant?,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
)

/** A prompt source; the resume variant carries the full document and budgeting happens when coach text is selected. */
sealed interface SuggestionSourceInput {
    val source: SuggestionSource
    /** Another ready resume; its text is narrowed against the target job by the coach context builder. */
    data class Resume(override val source: SuggestionSource, val document: ResolvedDocument) : SuggestionSourceInput
    /** Saved experience text, supplied whole to the suggestion prompt. */
    data class Experience(override val source: SuggestionSource, val text: String) : SuggestionSourceInput
}

@JvmRecord
/** Versioned job references; source text is loaded from saved resources at execution time. */
data class ExperienceSuggestionsPayload(
    val payloadVersion: Int,
    val suggestionsId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) {
    constructor(suggestionsId: UUID, resumeId: UUID, targetJobId: UUID) : this(CURRENT_VERSION, suggestionsId, resumeId, targetJobId)

    companion object { const val CURRENT_VERSION = 1 }
}
