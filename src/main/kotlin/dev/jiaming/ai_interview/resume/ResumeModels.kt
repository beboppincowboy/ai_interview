package dev.jiaming.ai_interview.resume

import java.time.Instant
import java.util.UUID
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.score.ResumeScoreResult
import dev.jiaming.ai_interview.score.ResumeScoreSummary
import org.springframework.boot.context.properties.ConfigurationProperties

/** A failed extraction job associated with its resume and nullable error details. */
@JvmRecord
data class FailedResumeJob(val resumeId: UUID, val errorCode: String?, val errorMessage: String?)

/** Storage object left for cleanup after failed resume processing. */
@JvmRecord
data class FailedResumeStorage(val resumeId: UUID, val storageKey: String)

@JvmRecord
data class ResumeChunkResponse(val index: Int, val section: String, val content: String, val characterCount: Int)

/** Extraction-job input containing stored upload metadata; content type fields may be absent. */
@JvmRecord
data class ResumeExtractionJobPayload(
    val resumeId: UUID,
    val storageKey: String,
    val originalFilename: String?,
    val contentType: String?,
    val detectedContentType: String?,
    val sizeBytes: Long,
    val extension: String
)

/** Parser queue/resource limits; nonpositive values fall back to documented defaults. */
@ConfigurationProperties(prefix = "app.resume-extraction")
class ResumeExtractionProperties(
    queueCapacity: Int,
    timeoutSeconds: Int,
    maxParseChars: Int,
    maxPdfPages: Int,
    maxEmbeddedResources: Int
) {
    val queueCapacity = if (queueCapacity <= 0) 2 else queueCapacity
    val timeoutSeconds = if (timeoutSeconds <= 0) 20 else timeoutSeconds
    val maxParseChars = if (maxParseChars <= 0) 250_000 else maxParseChars
    val maxPdfPages = if (maxPdfPages <= 0) 50 else maxPdfPages
    val maxEmbeddedResources = if (maxEmbeddedResources <= 0) 20 else maxEmbeddedResources

}

@JvmRecord
data class ResumeFileContent(
    val originalFilename: String?,
    val contentType: String?,
    val sizeBytes: Long,
    val bytes: ByteArray,
    val detectedContentType: String?,
    val extension: String
)

/** Resume origin: uploaded file or pasted text. */
enum class ResumeSource { UPLOAD, PASTE }

/** Contract `Resume.status`; the stored `PENDING` is reported as PROCESSING. */
enum class ResumeStatus { PROCESSING, READY, FAILED }

@JvmRecord
data class ResumeLibraryItem(
    val id: String,
    val name: String,
    val jobTitle: String?,
    val source: ResumeSource,
    val originalFilename: String?,
    val status: ResumeStatus,
    val latestScore: ResumeScoreSummary?,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
    val createdAt: Instant,
    val updatedAt: Instant
)

@JvmRecord
data class ResumeLibraryDetail(
    val id: String,
    val name: String,
    val jobTitle: String?,
    val source: ResumeSource,
    val originalFilename: String?,
    val status: ResumeStatus,
    val latestScore: ResumeScoreSummary?,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
    val createdAt: Instant,
    val updatedAt: Instant,
    val text: String?,
    val score: ResumeScoreResult?
) {
    fun toItem() = ResumeLibraryItem(id, name, jobTitle, source, originalFilename, status, latestScore, latestJob, createdAt, updatedAt)
}

@JvmRecord
data class ResumePage(val items: List<ResumeLibraryItem>)

@JvmRecord
data class ResumeCreated(val resume: ResumeLibraryItem, val duplicate: Boolean)

@JvmRecord
data class DuplicateResume(val id: String, val name: String)

@JvmRecord
data class ResumeExtractionResult(val resumeId: String, val duplicateOf: DuplicateResume?)

@JvmRecord
data class PasteResumeRequest(val name: String, val jobTitle: String?, val text: String)

@JvmRecord
data class PastePersistenceResult(val resumeId: UUID, val duplicate: Boolean)

internal data class ResumeSubmissionOutcome(val resumeId: UUID, val duplicate: Boolean, val storageKey: String?)

@JvmRecord
data class TextChunk(val index: Int, val section: String, val content: String)
