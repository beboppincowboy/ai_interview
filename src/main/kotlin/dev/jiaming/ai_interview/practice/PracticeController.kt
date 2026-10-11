package dev.jiaming.ai_interview.practice

import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RequestValidation
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/practice-sets")
class PracticeController(
    private val practiceService: PracticeService,
    private val requestGuard: RedisRequestGuard,
) {
    @PostMapping
    fun create(@RequestBody request: CreatePracticeSetRequest): ResponseEntity<PracticeSetCreated> {
        if (request.mode != "PRACTICE") throw RequestValidation.invalid("mode must be PRACTICE")
        val resumeId = request.resumeId ?: throw RequestValidation.invalid("resumeId is required")
        val targetJobId = request.targetJobId ?: throw RequestValidation.invalid("targetJobId is required")
        return requestGuard.withIdempotentHttpCache("practice-set-create", listOf(resumeId, targetJobId), PracticeSetCreated::class.java) {
            val creation = practiceService.create(resumeId, targetJobId)
            ResponseEntity.status(if (creation.duplicate) HttpStatus.OK else HttpStatus.CREATED).body(creation)
        }
    }

    @GetMapping("/{setId}")
    fun get(@PathVariable setId: UUID): PracticeSetView = practiceService.get(setId)

    @PostMapping("/{setId}/retry")
    fun retry(@PathVariable setId: UUID): ResponseEntity<PracticeSetView> = ResponseEntity.status(HttpStatus.ACCEPTED).body(
        requestGuard.withIdempotentRetryCache("practice-set-retry", setId, PracticeSetView::class.java) { practiceService.retry(setId) }
    )

    @PostMapping("/{setId}/questions")
    fun addQuestion(@PathVariable setId: UUID, @RequestBody request: AddPracticeQuestionRequest): ResponseEntity<PracticeQuestionView> {
        val text = RequestValidation.text("text", request.text, 10, 500)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            requestGuard.withIdempotentRetryCache("practice-question-add", listOf(setId, text), PracticeQuestionView::class.java) {
                practiceService.addQuestion(setId, text)
            }
        )
    }
}
