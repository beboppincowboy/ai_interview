package dev.jiaming.ai_interview.resume

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.score.ResumeScoreService
import org.springframework.data.redis.core.StringRedisTemplate
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.Mockito
import org.springframework.mock.web.MockMultipartFile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.http.MediaType
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq

class ResumeControllerTests {
	private val submissionService = Mockito.mock(ResumeJobSubmissionService::class.java)
	private val libraryService = Mockito.mock(ResumeLibraryService::class.java)
	private val scoreService = Mockito.mock(ResumeScoreService::class.java)
	private val guard = RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties("resume-controller-test:",
		RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)), ObjectMapper())
	private val mockMvc = standaloneSetup(ResumeController(submissionService, libraryService, scoreService, guard)).build()

	@Test
	fun uploadsResumeAndReturnsAcceptedJob() {
		val jobId = UUID.randomUUID()
		val item = ResumeLibraryItem(jobId.toString(), "resume", null, "UPLOAD", "resume.txt", "PROCESSING", null, null, Instant.now(), Instant.now())
		Mockito.`when`(submissionService.submit(any(), anyOrNull(), anyOrNull()))
			.thenReturn(ResponseEntity.status(HttpStatus.ACCEPTED).body(ResumeCreated(item, false)))
		val file = MockMultipartFile("file", "resume.txt", "text/plain", "SKILLS\nJava Spring Boot".toByteArray(StandardCharsets.UTF_8))
		mockMvc.perform(multipart("/api/resumes").file(file))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.duplicate").value(false))
			.andExpect(jsonPath("$.resume.id").value(jobId.toString()))
			.andExpect(jsonPath("$.resume.status").value("PROCESSING"))
	}

	@Test
	fun returnsExistingResumeForImmediateByteDuplicate() {
		val resumeId = UUID.randomUUID()
		val item = ResumeLibraryItem(resumeId.toString(), "Backend", null, "UPLOAD", "backend.pdf", "READY", null, null, Instant.now(), Instant.now())
		Mockito.`when`(submissionService.submit(any(), anyOrNull(), anyOrNull()))
			.thenReturn(ResponseEntity.ok(ResumeCreated(item, true)))
		val file = MockMultipartFile("file", "copy.pdf", "application/pdf", byteArrayOf(1, 2, 3))
		mockMvc.perform(multipart("/api/resumes").file(file))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.duplicate").value(true))
			.andExpect(jsonPath("$.resume.name").value("Backend"))
	}

	@Test
	fun acceptsPasteAndReturnsCreatedResume() {
		val item = ResumeLibraryItem(UUID.randomUUID().toString(), "Backend", null, "PASTE", null, "READY", null, null, Instant.now(), Instant.now())
		Mockito.`when`(libraryService.paste(any())).thenReturn(ResponseEntity.status(HttpStatus.CREATED).body(ResumeCreated(item, false)))
		mockMvc.perform(post("/api/resumes/paste").contentType(MediaType.APPLICATION_JSON)
			.content("""{"name":"Backend","jobTitle":null,"text":"${"x".repeat(100)}"}"""))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.resume.source").value("PASTE"))
	}

	@Test
	fun patchReadsTheBodyWithTheJacksonConverterTheApplicationUses() {
		val resumeId = UUID.randomUUID()
		val item = ResumeLibraryItem(resumeId.toString(), "Backend", null, "PASTE", null, "READY", null, null, Instant.now(), Instant.now())
		Mockito.`when`(libraryService.patch(eq(resumeId), any<Map<String, Any?>>())).thenReturn(item)

		mockMvc.perform(patch("/api/resumes/{id}", resumeId).contentType(MediaType.APPLICATION_JSON)
			.content("""{"name":"Backend","jobTitle":null}"""))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.name").value("Backend"))
		// An explicit null still reaches the service, so it clears the job title instead of leaving it unchanged.
		Mockito.verify(libraryService).patch(resumeId, mapOf("name" to "Backend", "jobTitle" to null))
	}

	@Test
	fun scoreStartsAResumeScoreJob() {
		val resumeId = UUID.randomUUID()
		val jobId = UUID.randomUUID()
		Mockito.`when`(scoreService.submit(resumeId)).thenReturn(JobAcceptedResponse(
			jobId, JobType.RESUME_SCORE, JobStatus.QUEUED, JobStage.QUEUED, "/api/jobs/$jobId", false, JobInputRefs(resumeId, null, null, null)
		))
		mockMvc.perform(post("/api/resumes/$resumeId/score").contentType(MediaType.APPLICATION_JSON).content("{}"))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("RESUME_SCORE"))
			.andExpect(jsonPath("$.inputRefs.resumeId").value(resumeId.toString()))
	}
}
