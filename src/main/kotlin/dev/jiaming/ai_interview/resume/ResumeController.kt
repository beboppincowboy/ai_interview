package dev.jiaming.ai_interview.resume

import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.score.ResumeScoreService
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestPart
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

@RestController
@RequestMapping("/api/resumes")
class ResumeController(
    private val resumeJobSubmissionService: ResumeJobSubmissionService,
    private val resumeLibraryService: ResumeLibraryService,
    private val resumeScoreService: ResumeScoreService,
    private val requestGuard: RedisRequestGuard
) {
    @PostMapping(consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun upload(
        @RequestPart("file") file: MultipartFile,
        @RequestPart(value = "name", required = false) name: String?,
        @RequestPart(value = "jobTitle", required = false) jobTitle: String?
    ): ResponseEntity<ResumeCreated> = resumeJobSubmissionService.submit(file, name, jobTitle)

    @PostMapping("/paste", consumes = [MediaType.APPLICATION_JSON_VALUE])
    fun paste(@RequestBody request: PasteResumeRequest): ResponseEntity<ResumeCreated> = resumeLibraryService.paste(request)

    @GetMapping
    fun list(): ResumePage = resumeLibraryService.list()

    @GetMapping("/{resumeId}")
    fun get(@PathVariable resumeId: UUID): ResumeLibraryDetail = resumeLibraryService.get(resumeId)

    @PatchMapping("/{resumeId}", consumes = [MediaType.APPLICATION_JSON_VALUE])
    // A map, not a JsonNode: Spring Boot reads request bodies with Jackson 3, which cannot build the Jackson 2 tree type.
    fun patch(@PathVariable resumeId: UUID, @RequestBody body: Map<String, Any?>): ResumeLibraryItem = resumeLibraryService.patch(resumeId, body)

    @PostMapping("/{resumeId}/score")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun score(@PathVariable resumeId: UUID): JobAcceptedResponse =
        requestGuard.withIdempotentRetryCache("resume-score", resumeId, JobAcceptedResponse::class.java) {
            resumeScoreService.submit(resumeId)
        }

    @GetMapping("/{resumeId}/delete-impact")
    fun deleteImpact(@PathVariable resumeId: UUID) = resumeLibraryService.deleteImpact(resumeId)

    @DeleteMapping("/{resumeId}")
    fun delete(@PathVariable resumeId: UUID): ResponseEntity<Void> {
        resumeLibraryService.delete(resumeId)
        return ResponseEntity.noContent().build()
    }
}
