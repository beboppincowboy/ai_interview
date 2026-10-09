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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class PracticeControllerTests {
    private val service = Mockito.mock(PracticeService::class.java)
    private val requestGuard = RedisRequestGuard(
        Mockito.mock(StringRedisTemplate::class.java),
        RedisUsageProperties(RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)),
        ObjectMapper().findAndRegisterModules(),
    )
    private val mockMvc = standaloneSetup(PracticeController(service, requestGuard)).setControllerAdvice(ApiExceptionHandler()).build()
    private val resumeId = UUID.randomUUID()
    private val targetJobId = UUID.randomUUID()

    @Test
    fun aNewSetReturns201WithTheGeneratingJobAndAnExistingSetReturns200() {
        Mockito.`when`(service.create(resumeId, targetJobId))
            .thenReturn(PracticeSetCreation(set(PracticeSetStatus.GENERATING, emptyList()), true))
            .thenReturn(PracticeSetCreation(set(PracticeSetStatus.READY, listOf(question("AI"))), false))

        mockMvc.perform(create("PRACTICE"))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.mode").value("PRACTICE"))
            .andExpect(jsonPath("$.status").value("GENERATING"))
            .andExpect(jsonPath("$.questions").isEmpty)
            .andExpect(jsonPath("$.latestJob.jobType").value("PRACTICE_QUESTIONS"))
            .andExpect(jsonPath("$.latestJob.stage").value("GENERATING_QUESTIONS"))
            .andExpect(jsonPath("$.latestJob.maxAttempts").value(3))
        mockMvc.perform(create("PRACTICE"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("READY"))
            .andExpect(jsonPath("$.questions[0].order").value(1))
            .andExpect(jsonPath("$.questions[0].origin").value("AI"))
            .andExpect(jsonPath("$.questions[0].rationale").value("Reason"))
            .andExpect(jsonPath("$.questions[0].attempts").isEmpty)
    }

    @Test
    fun voiceModeAndMissingIdsAreInvalidRequestsThatNeverReachTheService() {
        mockMvc.perform(create("VOICE"))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("mode")))
        mockMvc.perform(post("/api/practice-sets").contentType(MediaType.APPLICATION_JSON).content("""{"mode":"PRACTICE","targetJobId":"$targetJobId"}"""))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("resumeId")))
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun serviceErrorsKeepTheirContractCodes() {
        val setId = UUID.randomUUID()
        Mockito.`when`(service.get(setId)).thenThrow(ApiRequestException(HttpStatus.NOT_FOUND, "PRACTICE_SET_NOT_FOUND", "Practice set was not found"))
        Mockito.`when`(service.retry(setId)).thenThrow(ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_FAILED", "Practice set did not fail"))
        Mockito.`when`(service.create(resumeId, targetJobId)).thenThrow(ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready"))

        mockMvc.perform(get("/api/practice-sets/$setId")).andExpect(status().isNotFound).andExpect(jsonPath("$.code").value("PRACTICE_SET_NOT_FOUND"))
        mockMvc.perform(post("/api/practice-sets/$setId/retry")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("PRACTICE_SET_NOT_FAILED"))
        mockMvc.perform(create("PRACTICE")).andExpect(status().isConflict).andExpect(jsonPath("$.code").value("RESUME_NOT_READY"))
    }

    @Test
    fun retryReturns202WithTheSet() {
        val set = set(PracticeSetStatus.GENERATING, emptyList())
        Mockito.`when`(service.retry(set.id)).thenReturn(set)

        mockMvc.perform(post("/api/practice-sets/${set.id}/retry"))
            .andExpect(status().isAccepted)
            .andExpect(jsonPath("$.id").value(set.id.toString()))
            .andExpect(jsonPath("$.status").value("GENERATING"))
    }

    @Test
    fun addingAQuestionTrimsItAndReturns201WithTheUserQuestion() {
        val setId = UUID.randomUUID()
        Mockito.`when`(service.addQuestion(setId, "Why did you choose Kafka?")).thenReturn(question("USER"))

        mockMvc.perform(addQuestion(setId, "  Why did you choose Kafka?  "))
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.origin").value("USER"))
            .andExpect(jsonPath("$.rationale").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.expectedSignals").isEmpty)
            .andExpect(jsonPath("$.attempts").isEmpty)
    }

    @Test
    fun questionTextOutsideTenToFiveHundredCharactersIsRejectedBeforeTheService() {
        val setId = UUID.randomUUID()
        mockMvc.perform(addQuestion(setId, "x".repeat(9)))
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
            .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("text")))
        mockMvc.perform(addQuestion(setId, "x".repeat(501))).andExpect(status().isBadRequest)
        Mockito.verifyNoInteractions(service)
    }

    @Test
    fun questionConflictsKeepTheirContractCodes() {
        val setId = UUID.randomUUID()
        Mockito.`when`(service.addQuestion(setId, "A question while generating"))
            .thenThrow(ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_READY", "Questions are still being generated"))
        Mockito.`when`(service.addQuestion(setId, "The eleventh question here"))
            .thenThrow(ApiRequestException(HttpStatus.CONFLICT, "QUESTION_LIMIT_REACHED", "Limit reached"))

        mockMvc.perform(addQuestion(setId, "A question while generating")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("PRACTICE_SET_NOT_READY"))
        mockMvc.perform(addQuestion(setId, "The eleventh question here")).andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("QUESTION_LIMIT_REACHED"))
    }

    private fun create(mode: String) = post("/api/practice-sets").contentType(MediaType.APPLICATION_JSON)
        .content("""{"resumeId":"$resumeId","targetJobId":"$targetJobId","mode":"$mode"}""")

    private fun addQuestion(setId: UUID, text: String) = post("/api/practice-sets/$setId/questions")
        .contentType(MediaType.APPLICATION_JSON).content("""{"text":"$text"}""")

    private fun question(origin: String) = if (origin == "AI") {
        PracticeQuestionView(UUID.randomUUID(), 1, "AI", "Why Kafka?", "Reason", "Technical depth", listOf("trade-offs"))
    } else {
        PracticeQuestionView(UUID.randomUUID(), 2, "USER", "Why did you choose Kafka?", null, null, emptyList())
    }

    private fun set(status: PracticeSetStatus, questions: List<PracticeQuestionView>) = PracticeSetView(
        UUID.randomUUID(), resumeId, targetJobId, "PRACTICE", status, questions,
        LatestJob(UUID.randomUUID(), JobType.PRACTICE_QUESTIONS, JobStatus.PROCESSING, JobStage.GENERATING_QUESTIONS, 1, 3, null),
        Instant.parse("2026-09-30T12:00:00Z"), Instant.parse("2026-09-30T12:00:00Z"),
    )
}
