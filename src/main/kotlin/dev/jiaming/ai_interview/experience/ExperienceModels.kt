package dev.jiaming.ai_interview.experience

import java.time.Instant
import java.util.UUID

enum class ExperienceSource { FORM, LINKEDIN }

/** Request fields stay nullable so validation can return a field-specific 400 for missing values. */
data class ExperienceInput(
    val title: String?,
    val organization: String?,
    val startDate: String?,
    val endDate: String?,
    val description: String?
)

data class ExperienceBatchRequest(val items: List<ExperienceInput>?)
data class ExperienceSplitRequest(val text: String?)
data class ExperienceRenameRequest(val title: String?)

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
data class ExperienceBatchResult(val created: List<Experience>, val duplicates: List<ExperienceBatchDuplicate>)

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
