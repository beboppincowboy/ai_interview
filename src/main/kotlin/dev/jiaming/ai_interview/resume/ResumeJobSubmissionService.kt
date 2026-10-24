package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RequestValidation
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations
import org.springframework.web.multipart.MultipartFile

@Service
class ResumeJobSubmissionService(
    private val validator: ResumeFileValidator,
    private val fileReader: ResumeFileReader,
    private val storageService: ResumeStorageService,
    private val persistenceService: ResumePersistenceService,
    private val libraryService: ResumeLibraryService,
    private val jobSubmissionService: JobSubmissionService,
    private val localUserService: LocalUserService,
    private val requestGuard: RedisRequestGuard,
    private val cleanupService: ResumeStorageCleanupService,
    private val transactionOperations: TransactionOperations
) {
    /** Validates and stores an upload, starts extraction, and returns 202; existing/racing duplicates return 200. */
    fun submit(file: MultipartFile, requestedName: String?, requestedJobTitle: String?): ResponseEntity<ResumeCreated> {
        jobSubmissionService.assertApiAvailable()
        validator.validate(file)
        val content = fileReader.read(file)
        val fileHash = sha256(content.bytes)
        val name = requestedName?.let { RequestValidation.text("name", it, 1, 80) } ?: defaultName(content.originalFilename)
        val jobTitle = requestedJobTitle?.let { RequestValidation.text("jobTitle", it, 0, 100).ifEmpty { null } }
        val fingerprintSource = UploadFingerprint(
            content.originalFilename, content.contentType, content.detectedContentType,
            content.sizeBytes, fileHash, name, jobTitle
        )
        return requestGuard.withIdempotentHttpCache("resume-upload", fingerprintSource, ResumeCreated::class.java) {
            requestGuard.assertUploadAllowed()
            libraryService.findByFileHash(fileHash)?.let { duplicate ->
                return@withIdempotentHttpCache ResponseEntity.ok(ResumeCreated(duplicate, true))
            }
            submitNewOrReuse(content, fileHash, name, jobTitle, fingerprintSource)
        }
    }

    private fun submitNewOrReuse(
        content: ResumeFileContent, fileHash: String, name: String, jobTitle: String?, fingerprintSource: UploadFingerprint
    ): ResponseEntity<ResumeCreated> {
        val fingerprint = jobSubmissionService.fingerprint("resume-upload", fingerprintSource)
        var storageKey: String? = null
        val outcome = try {
            storageKey = storageService.store(content)
            val committedStorageKey = requireNotNull(storageKey)
            requireNotNull(transactionOperations.execute {
                val userId = localUserService.localUserId()
                val resumeId = persistenceService.createPending(
                    userId, content.originalFilename, content.contentType, content.detectedContentType,
                    content.sizeBytes, committedStorageKey, name, jobTitle, fileHash
                )
                if (resumeId.isEmpty) {
                    val winner = libraryService.findByFileHash(fileHash)
                        ?: throw IllegalStateException("A matching resume won the upload race but could not be loaded")
                    persistenceService.enqueueStorageCleanup(committedStorageKey)
                    return@execute ResumeSubmissionOutcome(UUID.fromString(winner.id), true, committedStorageKey)
                }

                val createdResumeId = resumeId.get()
                val payload = ResumeExtractionJobPayload(
                    createdResumeId, committedStorageKey, content.originalFilename, content.contentType,
                    content.detectedContentType, content.sizeBytes, content.extension
                )
                val accepted = jobSubmissionService.submit(
                    JobType.RESUME_EXTRACTION, "resume", createdResumeId, payload, dedupKey = fingerprint
                )
                if (accepted.reused) {
                    persistenceService.enqueueStorageCleanup(committedStorageKey)
                    persistenceService.deletePending(createdResumeId)
                    val existingId = accepted.inputRefs.resumeId ?: createdResumeId
                    ResumeSubmissionOutcome(existingId, true, committedStorageKey)
                } else ResumeSubmissionOutcome(createdResumeId, false, null)
            })
        } catch (exception: RuntimeException) {
            storageKey?.let(cleanupService::scheduleAndDelete)
            throw exception
        }

        if (!outcome.storageKey.isNullOrBlank()) cleanupService.deleteAndAcknowledge(outcome.storageKey)
        val item = libraryService.get(outcome.resumeId).toItem()
        return ResponseEntity.status(if (outcome.duplicate) HttpStatus.OK else HttpStatus.ACCEPTED)
            .body(ResumeCreated(item, outcome.duplicate))
    }

    private fun defaultName(filename: String?): String = filename.orEmpty()
        .substringBeforeLast('.', filename.orEmpty()).trim().take(80).ifBlank { "Untitled resume" }

    private fun sha256(bytes: ByteArray): String = try {
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))
    } catch (exception: NoSuchAlgorithmException) {
        throw IllegalStateException("SHA-256 is unavailable", exception)
    }

    private data class UploadFingerprint(
        val originalFilename: String?, val contentType: String?, val detectedContentType: String?,
        val sizeBytes: Long, val fileHash: String, val name: String, val jobTitle: String?
    )
}
