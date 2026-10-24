package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiExceptionHandler
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class AttemptControllerTests {
    private val service = Mockito.mock(PracticeService::class.java)
    private val requestGuard = RedisRequestGuard(
        Mockito.mock(StringRedisTemplate::class.java),
        RedisUsageProperties(RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)),
        ObjectMapper().findAndRegisterModules(),
    )
    private val mockMvc = standaloneSetup(AttemptController(service, requestGuard)).setControllerAdvice(ApiExceptionHandler()).build()
    private val setId = UUID.randomUUID()
    private val questionId = UUID.randomUUID()

    @Test
    fun submittingTrimsTheAnswerAndReturns201WithThePendingAttemptAndItsJob() {
        Mockito.`when`(service.submitAttempt(setId, questionId, "I added retries.")).thenReturn(attempt(AttemptStatus.PENDING))

        mockMvc.perform(submit("  I added retries.  "))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.number").value(2))
            .andExpect(jsonPath("$.text").value("I added retries."))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.feedback").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.scoreDelta").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.latestJob.jobType").value("ANSWER_FEEDBACK"))
            .andExpect(jsonPath("$.latestJob.stage").value("QUEUED"))
            .andExpect(jsonPath("$.createdAt").value("2026-09-30T12:00:00Z"))
    }

    @Test
    fun blankAndOverLongAnswersHaveTheirOwnCodesAndNeverReachTheService() {
        mockMvc.perform(submit("   ")).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_EMPTY"))
        mockMvc.perform(post("/api/practice-sets/$setId/questions/$questionId/attempts").contentType(MediaType.APPLICATION_JSON).content("{}"))
            .andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_EMPTY"))
        mockMvc.perform(submit("x".repeat(4_001))).andExpect(status().isBadRequest).andExpect(jsonPath("$.code").value("ANSWER_TOO_LONG"))
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun serviceErrorsKeepTheirContractCodes() {
        val attemptId = UUID.randomUUID()
        val failed = UUID.randomUUID()
        Mockito.`when`(service.submitAttempt(setId, questionId, "Missing"))
            .thenThrow(ApiRequestException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "Question was not found"))
        Mockito.`when`(service.submitAttempt(setId, questionId, "Same"))
            .thenThrow(ApiRequestException(HttpStatus.CONFLICT, "ANSWER_UNCHANGED", "Same answer"))
        Mockito.`when`(service.retryAttempt(attemptId)).thenThrow(ApiRequestException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt was not found"))
        Mockito.`when`(service.retryAttempt(failed)).thenThrow(ApiRequestException(HttpStatus.CONFLICT, "ATTEMPT_NOT_FAILED", "Attempt did not fail"))

        mockMvc.perform(submit("Missing")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("QUESTION_NOT_FOUND"))
        mockMvc.perform(submit("Same")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ANSWER_UNCHANGED"))
        mockMvc.perform(post("/api/attempts/$attemptId/retry")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FOUND"))
        mockMvc.perform(post("/api/attempts/$failed/retry")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("ATTEMPT_NOT_FAILED"))
    }

    @Test
    fun retryReturns202WithTheAttemptPendingAgain() {
        val attempt = attempt(AttemptStatus.PENDING)
        Mockito.`when`(service.retryAttempt(attempt.id)).thenReturn(attempt)

        mockMvc.perform(post("/api/attempts/${attempt.id}/retry"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value(attempt.id.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
    }

    private fun submit(text: String) = post("/api/practice-sets/$setId/questions/$questionId/attempts")
        .contentType(MediaType.APPLICATION_JSON).content("""{"text":"$text"}""")

    private fun attempt(status: AttemptStatus) = AttemptView(
        UUID.randomUUID(), 2, "I added retries.", status, null, null,
        LatestJob(UUID.randomUUID(), JobType.ANSWER_FEEDBACK, JobStatus.QUEUED, JobStage.QUEUED, 0, 3, null),
        Instant.parse("2026-09-30T12:00:00Z"),
    )
}
