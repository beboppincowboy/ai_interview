package dev.jiaming.ai_interview.experience

import java.time.Instant
import java.util.UUID

/** Source category for a saved experience. */
enum class ExperienceSource { FORM, LINKEDIN }

/** Request fields stay nullable so validation can return a field-specific 400 for missing values. */
data class ExperienceInput(
    val title: String?,
    val organization: String?,
    val startDate: String?,
    val endDate: String?,
    val description: String?
)

/** Batch input; the service requires 1–30 items. */
data class ExperienceBatchRequest(val items: List<ExperienceInput>?)
/** Split input; the service requires 50–20,000 trimmed characters. */
data class ExperienceSplitRequest(val text: String?)
/** Rename input; the service requires a 1–120 character title. */
data class ExperienceRenameRequest(val title: String?)

/** Saved experience; organization and dates may be null; title, description, source, and creation time are present. */
data class Experience(
    val id: UUID,
    val title: String,
    val organization: String?,
    val startDate: String?,
    val endDate: String?,
    val description: String,
    val source: ExperienceSource,
    val createdAt: Instant
)

data class ExperienceListResponse(val items: List<Experience>)
/**
 * [duplicate] is true when the user already has an experience with the same title and description (whitespace-collapsed,
 * case-insensitive): [experience] is that saved one, unchanged; organization and dates of the request are ignored.
 */
data class ExperienceCreated(val experience: Experience, val duplicate: Boolean)
/** A submitted batch item ([title] as submitted) that matched the saved experience [existingId] and was not stored. */
data class ExperienceBatchDuplicate(val title: String, val existingId: UUID, val existingTitle: String)
/** Batch outcome; duplicate inputs are reported separately and are not stored. */
data class ExperienceBatchResult(val created: List<Experience>, val duplicates: List<ExperienceBatchDuplicate>)

/** Versioned split-job input; version defaults to [CURRENT_VERSION]. */
data class ExperienceSplitJobPayload(
    val payloadVersion: Int = CURRENT_VERSION,
    val text: String
) {
    companion object { const val CURRENT_VERSION = 1 }
}

data class ExperienceDuplicate(val id: UUID, val title: String)
data class ExperienceSplitItem(
    val title: String,
    val organization: String?,
    val startDate: String?,
    val endDate: String?,
    val description: String,
    val duplicateOf: ExperienceDuplicate?
)
data class ExperienceSplitResult(val items: List<ExperienceSplitItem>)
