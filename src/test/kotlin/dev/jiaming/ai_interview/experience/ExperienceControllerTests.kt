package dev.jiaming.ai_interview.experience

import dev.jiaming.ai_interview.common.ApiExceptionHandler
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.DeleteImpact
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.jobs.JobAcceptedResponse
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import com.fasterxml.jackson.databind.ObjectMapper
import org.mockito.ArgumentMatchers.anyString
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.springframework.data.redis.core.script.RedisScript
import org.assertj.core.api.Assertions.assertThat
import org.springframework.http.MediaType
import org.springframework.http.HttpStatus
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.mockito.kotlin.any as kotlinAny

class ExperienceControllerTests {
	private val service = Mockito.mock(ExperienceService::class.java)
	private val localUser = Mockito.mock(LocalUserService::class.java)
	private val userId = UUID.randomUUID()
	private val redis = ConcurrentHashMap<String, String>()
	private val redisTemplate = Mockito.mock(StringRedisTemplate::class.java)
	@Suppress("UNCHECKED_CAST")
	private val valueOperations = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
	private val guard = createGuard()
	private val mockMvc = standaloneSetup(ExperienceController(service, localUser, guard))
		.setControllerAdvice(ApiExceptionHandler()).build()

	@BeforeEach
	fun setUp() {
		redis.clear()
		Mockito.`when`(localUser.localUserId()).thenReturn(userId)
	}

	@AfterEach
	fun tearDown() = RequestContextHolder.resetRequestAttributes()

	@Test
	fun listsOnlyTheCurrentUsersExperiences() {
		val experience = experience()
		Mockito.`when`(localUser.localUserId()).thenReturn(userId)
		Mockito.`when`(service.list(userId)).thenReturn(ExperienceListResponse(listOf(experience)))

		mockMvc.perform(get("/api/experiences"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.items[0].id").value(experience.id.toString()))
			.andExpect(jsonPath("$.items[0].source").value("FORM"))
	}

	@Test
	fun patchesExperienceTitleForCurrentUser() {
		val original = experience()
		val renamed = original.copy(title = "Senior Backend Engineer")
		Mockito.`when`(service.rename(eq(userId), eq(original.id), eq(ExperienceRenameRequest("Senior Backend Engineer"))))
			.thenReturn(renamed)

		mockMvc.perform(patch("/api/experiences/${original.id}").contentType(MediaType.APPLICATION_JSON)
			.content("""{"title":"Senior Backend Engineer"}"""))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.id").value(original.id.toString()))
			.andExpect(jsonPath("$.title").value("Senior Backend Engineer"))

		Mockito.verify(service).rename(eq(userId), eq(original.id), eq(ExperienceRenameRequest("Senior Backend Engineer")))
	}

	@Test
	fun patchReturnsExperienceNotFoundForUnknownOrForeignIds() {
		val missingId = UUID.randomUUID()
		Mockito.`when`(service.rename(eq(userId), eq(missingId), any())).thenThrow(
			ApiRequestException(HttpStatus.NOT_FOUND, "EXPERIENCE_NOT_FOUND", "Experience not found")
		)

		mockMvc.perform(patch("/api/experiences/$missingId").contentType(MediaType.APPLICATION_JSON)
			.content("""{"title":"Updated title"}"""))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("EXPERIENCE_NOT_FOUND"))
	}

	@Test
	fun patchDuplicateContentReturnsSanitizedConflict() {
		val existing = experience()
		Mockito.`when`(service.rename(eq(userId), eq(existing.id), any())).thenThrow(
			ApiRequestException(HttpStatus.CONFLICT, "CONFLICT", "An experience with this title and description already exists")
		)

		mockMvc.perform(patch("/api/experiences/${existing.id}").contentType(MediaType.APPLICATION_JSON)
			.content("""{"title":"Sensitive original title"}"""))
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("CONFLICT"))
			.andExpect(jsonPath("$.message").value("An experience with this title and description already exists"))
	}

	@Test
	fun returnsCreatedForNewFormItemAndOkForDuplicate() {
		val experience = experience()
		Mockito.`when`(localUser.localUserId()).thenReturn(userId)
		Mockito.`when`(service.create(eq(userId), any())).thenReturn(ExperienceCreated(experience, false))

		mockMvc.perform(post("/api/experiences").contentType(MediaType.APPLICATION_JSON).content(inputJson()))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.duplicate").value(false))

		Mockito.`when`(service.create(eq(userId), any())).thenReturn(ExperienceCreated(experience, true))
		mockMvc.perform(post("/api/experiences").contentType(MediaType.APPLICATION_JSON).content(inputJson()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.duplicate").value(true))
	}

	@Test
	fun formPostReplaysOriginalStatusAndBodyAndRejectsChangedBody() {
		val created = ExperienceCreated(experience(), false)
		Mockito.`when`(service.create(eq(userId), any())).thenReturn(created)
		val path = "/api/experiences"
		val key = "experience-create-key"

		mockMvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(inputJson()))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.experience.id").value(created.experience.id.toString()))
		mockMvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(inputJson()))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.experience.id").value(created.experience.id.toString()))
		mockMvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
			.content(inputJson(title = "Different request")))
			.andExpect(status().isConflict)
			.andExpect(jsonPath("$.code").value("CONFLICT"))

		Mockito.verify(service, Mockito.times(1)).create(eq(userId), any())
	}

	@Test
	fun formPostKeepsDuplicateResponseAtOk() {
		Mockito.`when`(service.create(eq(userId), any())).thenReturn(ExperienceCreated(experience(), true))

		mockMvc.perform(post("/api/experiences").header("Idempotency-Key", "duplicate-key")
			.contentType(MediaType.APPLICATION_JSON).content(inputJson()))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.duplicate").value(true))
	}

	@Test
	fun batchSavesReviewedLinkedInItemsAndSplitReturnsAcceptedJob() {
		val accepted = JobAcceptedResponse(UUID.randomUUID(), JobType.EXPERIENCE_SPLIT, JobStatus.QUEUED,
			JobStage.QUEUED, "/api/jobs/example", false)
		Mockito.`when`(localUser.localUserId()).thenReturn(userId)
		Mockito.`when`(service.batch(eq(userId), any())).thenReturn(ExperienceBatchResult(emptyList(), emptyList()))
		Mockito.`when`(service.split(any())).thenReturn(accepted)

		mockMvc.perform(post("/api/experiences/batch").contentType(MediaType.APPLICATION_JSON)
			.content("""{"items":[${inputJson()}]}"""))
			.andExpect(status().isCreated)
			.andExpect(jsonPath("$.created").isArray)

		mockMvc.perform(post("/api/experiences/linkedin-split").contentType(MediaType.APPLICATION_JSON)
			.content("""{"text":"A sufficiently long LinkedIn experience section for an asynchronous split."}"""))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobType").value("EXPERIENCE_SPLIT"))
	}

	@Test
	fun batchPostReplaysAndRejectsChangedBody() {
		Mockito.`when`(service.batch(eq(userId), any())).thenReturn(ExperienceBatchResult(emptyList(), emptyList()))
		val path = "/api/experiences/batch"
		val key = "experience-batch-key"
		val body = """{"items":[${inputJson()}]}"""

		mockMvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated)
		mockMvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isCreated)
		mockMvc.perform(post(path).header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON)
			.content("""{"items":[${inputJson(title = "Different request")}] }"""))
			.andExpect(status().isConflict)

		Mockito.verify(service, Mockito.times(1)).batch(eq(userId), any())
	}

	@Test
	fun splitPostReplaysOneJobButANewKeyCreatesAnother() {
		val first = JobAcceptedResponse(UUID.randomUUID(), JobType.EXPERIENCE_SPLIT, JobStatus.QUEUED,
			JobStage.QUEUED, "/api/jobs/first", false)
		val second = first.copy(jobId = UUID.randomUUID(), statusUrl = "/api/jobs/second")
		Mockito.`when`(service.split(any())).thenReturn(first, second)
		val path = "/api/experiences/linkedin-split"
		val body = """{"text":"A sufficiently long LinkedIn experience section for an asynchronous split."}"""

		mockMvc.perform(post(path).header("Idempotency-Key", "same-split").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobId").value(first.jobId.toString()))
		mockMvc.perform(post(path).header("Idempotency-Key", "same-split").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobId").value(first.jobId.toString()))
		mockMvc.perform(post(path).header("Idempotency-Key", "same-split").contentType(MediaType.APPLICATION_JSON)
			.content("""{"text":"A different LinkedIn experience section that is also long enough to split."}"""))
			.andExpect(status().isConflict)
		mockMvc.perform(post(path).header("Idempotency-Key", "new-split").contentType(MediaType.APPLICATION_JSON).content(body))
			.andExpect(status().isAccepted)
			.andExpect(jsonPath("$.jobId").value(second.jobId.toString()))

		Mockito.verify(service, Mockito.times(2)).split(any())
	}

	@Test
	fun deleteImpactReportsStaleSuggestionSetsAndDeleteReturnsNoContent() {
		val id = UUID.randomUUID()
		Mockito.`when`(service.deleteImpact(userId, id)).thenReturn(DeleteImpact(0, 0, 0, 0, 0, 2))

		mockMvc.perform(get("/api/experiences/$id/delete-impact"))
			.andExpect(status().isOk)
			.andExpect(jsonPath("$.staleSuggestionSets").value(2))
			.andExpect(jsonPath("$.suggestionSets").value(0))
		mockMvc.perform(delete("/api/experiences/$id")).andExpect(status().isNoContent)

		Mockito.verify(service).delete(userId, id)
	}

	@Test
	fun deleteOfAnUnknownOrForeignExperienceIsNotFound() {
		val id = UUID.randomUUID()
		Mockito.`when`(service.delete(userId, id)).thenThrow(ApiRequestException(HttpStatus.NOT_FOUND, "EXPERIENCE_NOT_FOUND", "Experience not found"))

		mockMvc.perform(delete("/api/experiences/$id"))
			.andExpect(status().isNotFound)
			.andExpect(jsonPath("$.code").value("EXPERIENCE_NOT_FOUND"))
	}

	@Test
	fun invalidExperienceUsesFieldSpecificBadRequest() {
		Mockito.`when`(localUser.localUserId()).thenReturn(userId)
		Mockito.`when`(service.create(eq(userId), any())).thenThrow(
			dev.jiaming.ai_interview.common.RequestValidation.invalid("startDate must be a month in YYYY-MM format")
		)

		val response = mockMvc.perform(post("/api/experiences").contentType(MediaType.APPLICATION_JSON).content(inputJson(startDate = "2023-1")))
			.andExpect(status().isBadRequest)
			.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
			.andReturn().response.contentAsString
		assertThat(response).contains("startDate")
	}

	private fun inputJson(startDate: String? = "2023-01", title: String = "Backend Engineer") = """{"title":"$title","organization":"Acme","startDate":${if (startDate == null) "null" else "\"$startDate\""},"endDate":null,"description":"Built a dependable service."}"""
	private fun experience() = Experience(UUID.randomUUID(), "Backend Engineer", "Acme", "2023-01", null,
		"Built a dependable service.", ExperienceSource.FORM, Instant.parse("2026-09-30T12:00:00Z"))

	private fun createGuard(): RedisRequestGuard {
		Mockito.`when`(redisTemplate.opsForValue()).thenReturn(valueOperations)
		Mockito.`when`(valueOperations.get(anyString())).thenAnswer { redis[it.getArgument(0)] }
		Mockito.`when`(valueOperations.setIfAbsent(anyString(), kotlinAny(), kotlinAny<Duration>()))
			.thenAnswer { redis.putIfAbsent(it.getArgument(0), it.getArgument(1)) == null }
		Mockito.doAnswer {
			redis[it.getArgument(0)] = it.getArgument(1)
			null
		}.`when`(valueOperations).set(anyString(), kotlinAny(), kotlinAny<Duration>())
		stubScriptExecution()
		return RedisRequestGuard(
			redisTemplate,
			RedisUsageProperties(
				RedisUsageProperties.RateLimit(true, 60, 12, 20),
				RedisUsageProperties.Idempotency(true, 86_400)
			),
			ObjectMapper().findAndRegisterModules()
		)
	}

	private fun stubScriptExecution() {
		val answer = org.mockito.stubbing.Answer<Long> {
			val script = it.getArgument<RedisScript<Long>>(0)
			val key = it.getArgument<List<String>>(1).single()
			val args = it.rawArguments[2] as Array<Any>
			if (redis[key] != args[0]) 0L
			else when {
				script.scriptAsString.contains("'PX'") -> {
					redis[key] = args[1] as String
					1L
				}
				script.scriptAsString.contains("PEXPIRE") -> 1L
				script.scriptAsString.contains("'DEL'") -> if (redis.remove(key) != null) 1L else 0L
				else -> error("Unexpected Redis script")
			}
		}
		Mockito.doAnswer(answer).`when`(redisTemplate).execute(
			any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>()
		)
		Mockito.doAnswer(answer).`when`(redisTemplate).execute(
			any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>(), any<String>()
		)
		Mockito.doAnswer(answer).`when`(redisTemplate).execute(
			any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>(), any<String>(), any<String>()
		)
	}
}
