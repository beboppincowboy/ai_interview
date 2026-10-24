package dev.jiaming.ai_interview.targetjob

import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RequestValidation
import java.util.function.Supplier
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** Exposes target-job CRUD and downstream deletion-impact routes. */
@RestController
@RequestMapping("/api/target-jobs")
class TargetJobController(
    private val targetJobService: TargetJobService,
    private val requestGuard: RedisRequestGuard,
) {
    /** Validates name/text and returns 201 for a new target job or 200 for normalized-text duplicate. */
    @PostMapping
    fun create(@RequestBody request: CreateTargetJobRequest): ResponseEntity<TargetJobCreated> {
        val name = RequestValidation.text("name", request.name, 1, 80)
        val text = RequestValidation.text("text", request.text, 100, 20_000)
        return requestGuard.withIdempotentHttpCache(
            "create-target-job",
            listOf(name, text),
            TargetJobCreated::class.java,
            Supplier {
                val created = targetJobService.create(name, text)
                ResponseEntity.status(if (created.duplicate) HttpStatus.OK else HttpStatus.CREATED)
                    .body(TargetJobCreated(created.targetJob, created.duplicate))
            },
        )
    }

    /** Lists the current user's target jobs newest first. */
    @GetMapping
    fun list(): TargetJobListResponse = targetJobService.list()

    /** Returns owner-scoped target-job detail or 404. */
    @GetMapping("/{targetJobId}")
    fun get(@PathVariable targetJobId: java.util.UUID): TargetJobDetail = targetJobService.get(targetJobId)

    /** Validates a 1–80 character name and renames the owned target job. */
    @PatchMapping("/{targetJobId}")
    fun rename(
        @PathVariable targetJobId: java.util.UUID,
        @RequestBody request: RenameTargetJobRequest,
    ): TargetJob = targetJobService.rename(targetJobId, RequestValidation.text("name", request.name, 1, 80))

    /** Counts supported downstream records; score and stale-suggestion fields are always zero by contract. */
    @GetMapping("/{targetJobId}/delete-impact")
    fun deleteImpact(@PathVariable targetJobId: java.util.UUID): TargetJobDeleteImpact =
        targetJobService.deleteImpact(targetJobId)

    /** Deletes the owned target job and returns 204. */
    @DeleteMapping("/{targetJobId}")
    fun delete(@PathVariable targetJobId: java.util.UUID): ResponseEntity<Void> {
        targetJobService.delete(targetJobId)
        return ResponseEntity.noContent().build()
    }
}

/** Creation input; controller requires name 1–80 chars and text 100–20,000 chars. */
data class CreateTargetJobRequest(val name: String?, val text: String?)
/** Rename input; controller requires a 1–80 character name. */
data class RenameTargetJobRequest(val name: String?)
/** Created or existing normalized-text match; duplicate true returns the unchanged existing target job. */
data class TargetJobCreated(val targetJob: TargetJobDetail, val duplicate: Boolean)
