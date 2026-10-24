package dev.jiaming.ai_interview.experience

import dev.jiaming.ai_interview.common.DeleteImpact
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** Exposes the authenticated user's experience library and delegates validation and persistence to [ExperienceService]. */
@RestController
@RequestMapping("/api/experiences")
class ExperienceController(
    private val experienceService: ExperienceService,
    private val localUserService: LocalUserService,
    private val requestGuard: RedisRequestGuard
) {
    /** Lists the current user's saved experiences. */
    @GetMapping
    fun list(): ExperienceListResponse = experienceService.list(localUserService.localUserId())

    /** Renames an experience; invalid titles and duplicate normalized content are rejected by the service. */
    @PatchMapping("/{experienceId}")
    fun rename(
        @PathVariable experienceId: UUID,
        @RequestBody request: ExperienceRenameRequest
    ): Experience = experienceService.rename(localUserService.localUserId(), experienceId, request)

    /** Previews deletion effects; only suggestion sets are counted for an experience. */
    @GetMapping("/{experienceId}/delete-impact")
    fun deleteImpact(@PathVariable experienceId: UUID): DeleteImpact =
        experienceService.deleteImpact(localUserService.localUserId(), experienceId)

    /** Deletes the owned experience and returns 204; a missing item returns 404. */
    @DeleteMapping("/{experienceId}")
    fun delete(@PathVariable experienceId: UUID): ResponseEntity<Void> {
        experienceService.delete(localUserService.localUserId(), experienceId)
        return ResponseEntity.noContent().build()
    }

    /** Creates an experience (201) or returns its existing duplicate (200), replaying a keyed request when cached. */
    @PostMapping
    fun create(@RequestBody input: ExperienceInput): ResponseEntity<ExperienceCreated> =
        requestGuard.withIdempotentHttpCache("experience-create", input, ExperienceCreated::class.java) {
            val response = experienceService.create(localUserService.localUserId(), input)
            ResponseEntity.status(if (response.duplicate) HttpStatus.OK else HttpStatus.CREATED).body(response)
        }

    /** Saves a reviewed batch and reports duplicates; idempotency applies only when the request has a key header. */
    @PostMapping("/batch")
    @ResponseStatus(HttpStatus.CREATED)
    fun batch(@RequestBody request: ExperienceBatchRequest): ExperienceBatchResult =
        requestGuard.withIdempotentRetryCache("experience-batch", request, ExperienceBatchResult::class.java) {
            experienceService.batch(localUserService.localUserId(), request)
        }

    /** Submits a new split job and returns 202; each request has no resource-based job deduplication. */
    @PostMapping("/linkedin-split")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun split(@RequestBody request: ExperienceSplitRequest): JobAcceptedResponse =
        requestGuard.withIdempotentRetryCache("experience-split", request, JobAcceptedResponse::class.java) {
            experienceService.split(request)
        }
}
