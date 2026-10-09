package dev.jiaming.ai_interview.document

import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.targetjob.TargetJobPersistenceService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.Optional
import java.util.UUID

/** Loads the stored resume and optional target job a background job references; there is no text or latest-resume fallback. */
@Service
class DocumentReferenceResolver(
    private val resumePersistenceService: ResumePersistenceService,
    private val targetJobPersistenceService: TargetJobPersistenceService,
) {
    @Transactional(readOnly = true)
    fun resolveStrict(userId: UUID, resumeId: UUID?, targetJobId: UUID?): ResolvedJobInputs {
        if (resumeId == null) {
            throw ApiRequestException(
                HttpStatus.BAD_REQUEST,
                "RESUME_REFERENCE_REQUIRED",
                "The background job does not contain a resume reference",
            )
        }
        // The status is read only when the ready document is missing, to tell a missing resume from an unready one.
        val resume = resumePersistenceService.findReadyDocument(userId, resumeId).orElseThrow {
            if (resumePersistenceService.findProcessingStatus(userId, resumeId).isEmpty) {
                ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
            } else resumeNotReady()
        }
        val targetJob = if (targetJobId == null) Optional.empty() else Optional.of(
            targetJobPersistenceService.findDocument(userId, targetJobId).orElseThrow {
                ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
            }
        )
        return ResolvedJobInputs(resume, targetJob)
    }

    private fun resumeNotReady() = ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for analysis")
}
