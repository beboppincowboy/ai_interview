package dev.jiaming.ai_interview.experience

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.isNull
import org.mockito.kotlin.whenever
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.http.HttpStatus
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID

class ExperienceIntegrationTests {
	private lateinit var userId: UUID

	@BeforeEach
	fun createUser() {
		Mockito.reset(jobSubmission)
		userId = UUID.randomUUID()
		jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", userId, "$userId@experience-test.example")
	}

	@Test
	fun normalizesSingleItemDuplicatesAndScopesThemToTheOwner() {
		val original = service.create(userId, input(title = "  Staff   Engineer ", description = " Built a reliable payment service. "))
		val duplicate = service.create(userId, input(title = "staff engineer", description = "built a reliable   payment service."))
		val anotherUser = UUID.randomUUID()
		jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", anotherUser, "$anotherUser@experience-test.example")
		val otherOwner = service.create(anotherUser, input(title = "staff engineer", description = "built a reliable   payment service."))

		assertThat(original.duplicate).isFalse()
		assertThat(original.experience.source).isEqualTo(ExperienceSource.FORM)
		assertThat(duplicate.duplicate).isTrue()
		assertThat(duplicate.experience.id).isEqualTo(original.experience.id)
		assertThat(otherOwner.duplicate).isFalse()
	}

	@Test
	fun renameRecomputesContentHashForFutureDuplicateDetection() {
		val original = service.create(userId, input(title = "Backend Engineer", description = "Built reliable platform services.")).experience

		val renamed = service.rename(userId, original.id, ExperienceRenameRequest("  Senior   Backend Engineer  "))
		val duplicate = service.create(userId, input(title = "senior backend engineer", description = "Built   reliable platform services."))

		assertThat(renamed.title).isEqualTo("Senior   Backend Engineer")
		assertThat(duplicate.duplicate).isTrue()
		assertThat(duplicate.experience.id).isEqualTo(original.id)
	}

	@Test
	fun renameHidesMissingAndForeignExperiencesBehindTheSameNotFound() {
		val foreignUser = UUID.randomUUID()
		jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", foreignUser, "$foreignUser@experience-test.example")
		val foreign = service.create(foreignUser, input()).experience

		assertThatThrownBy { service.rename(userId, UUID.randomUUID(), ExperienceRenameRequest("Renamed")) }
			.isInstanceOfSatisfying(ApiRequestException::class.java) {
				assertThat(it.status()).isEqualTo(HttpStatus.NOT_FOUND)
				assertThat(it.code()).isEqualTo("EXPERIENCE_NOT_FOUND")
			}
		assertThatThrownBy { service.rename(userId, foreign.id, ExperienceRenameRequest("Renamed")) }
			.isInstanceOfSatisfying(ApiRequestException::class.java) {
				assertThat(it.status()).isEqualTo(HttpStatus.NOT_FOUND)
				assertThat(it.code()).isEqualTo("EXPERIENCE_NOT_FOUND")
			}
	}

	@Test
	fun renameCollisionReturnsSanitizedConflictAndKeepsOriginalTitle() {
		val first = service.create(userId, input(title = "First role", description = "Shared role details.")).experience
		service.create(userId, input(title = "Existing role", description = "Shared role details."))

		assertThatThrownBy { service.rename(userId, first.id, ExperienceRenameRequest("Existing role")) }
			.isInstanceOfSatisfying(ApiRequestException::class.java) {
				assertThat(it.status()).isEqualTo(HttpStatus.CONFLICT)
				assertThat(it.code()).isEqualTo("EXPERIENCE_DUPLICATE")
				assertThat(it.message).isEqualTo("An experience with this title and description already exists")
				assertThat(it.message).doesNotContain("Existing role", first.id.toString())
			}

		assertThat(service.list(userId).items.single { it.id == first.id }.title).isEqualTo("First role")
	}

	@Test
	fun batchCreatesUniqueItemsAndReportsExistingOnesAsSkipped() {
		val existing = service.create(userId, input(title = "Existing role")).experience

		val result = service.batch(userId, ExperienceBatchRequest(listOf(
			input(title = " existing   role "),
			input(title = "First project"),
			input(title = "Second project")
		)))

		assertThat(result.created).hasSize(2).allSatisfy { assertThat(it.source).isEqualTo(ExperienceSource.LINKEDIN) }
		assertThat(result.duplicates).containsExactly(ExperienceBatchDuplicate("existing   role", existing.id, existing.title))
	}

	@Test
	fun splitReviewMarksOnlyOwnerDuplicatesAndPersistsNothing() {
		val matching = input(title = "Platform Engineer", description = "Built reliable distributed services.")
		val existing = service.create(userId, matching).experience
		val otherUser = UUID.randomUUID()
		jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", otherUser, "$otherUser@experience-test.example")
		service.create(otherUser, input(title = "Another owner's role", description = "Built reliable distributed services."))
		val before = jdbc.queryForObject("SELECT COUNT(*) FROM ai_interview_app.experiences WHERE user_id = ?", Long::class.java, userId)
		val split = ExperienceSplitResult(listOf(
			ExperienceSplitItem(matching.title!!, matching.organization, matching.startDate, matching.endDate, matching.description!!, null),
			ExperienceSplitItem("Another owner's role", null, null, null, "Built reliable distributed services.", null)
		))

		val reviewed = service.annotateDuplicates(userId, split)

		assertThat(reviewed.items[0].duplicateOf).isEqualTo(ExperienceDuplicate(existing.id, existing.title))
		assertThat(reviewed.items[1].duplicateOf).isNull()
		assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ai_interview_app.experiences WHERE user_id = ?", Long::class.java, userId)).isEqualTo(before)
	}

	@Test
	fun invalidBatchSizeAndFieldsNameTheProblemField() {
		assertThatThrownBy { service.batch(userId, ExperienceBatchRequest(List(31) { input("Role $it") })) }
			.isInstanceOf(ApiRequestException::class.java).hasMessageContaining("items")
		assertThatThrownBy { service.create(userId, input(title = "T".repeat(121))) }
			.isInstanceOf(ApiRequestException::class.java).hasMessageContaining("title")
		assertThatThrownBy { service.create(userId, input(startDate = "2023-1")) }
			.isInstanceOf(ApiRequestException::class.java).hasMessageContaining("startDate")
	}

	@Test
	fun splitSubmissionUsesNoResourceOrFingerprintSoEveryExplicitSplitGetsAJob() {
		val first = dev.jiaming.ai_interview.jobs.JobAcceptedResponse(UUID.randomUUID(), JobType.EXPERIENCE_SPLIT,
			dev.jiaming.ai_interview.jobs.JobStatus.QUEUED, dev.jiaming.ai_interview.jobs.JobStage.QUEUED, "/api/jobs/first", false)
		val second = first.copy(jobId = UUID.randomUUID(), statusUrl = "/api/jobs/second")
		whenever(jobSubmission.submit(eq(JobType.EXPERIENCE_SPLIT), isNull(), isNull(), any<Any>(), isNull(), isNull()))
			.thenReturn(first, second)

		val firstResult = service.split(ExperienceSplitRequest("First LinkedIn section with more than fifty characters for validation."))
		val secondResult = service.split(ExperienceSplitRequest("Second LinkedIn section with different details, also more than fifty chars."))

		assertThat(firstResult.jobId).isNotEqualTo(secondResult.jobId)
		Mockito.verify(jobSubmission, Mockito.times(2)).submit(eq(JobType.EXPERIENCE_SPLIT), isNull(), isNull(), any<Any>(), isNull(), isNull())
	}

	@Test
	fun jdbcReloadMapsResourceLessSplitJobsAndKeepsDistinctRequests() {
		val store = BackgroundJobStore(jdbc, ObjectMapper().findAndRegisterModules())
		val firstPayload = ExperienceSplitJobPayload(text = "First LinkedIn text with distinct content and enough detail.")
		val secondPayload = ExperienceSplitJobPayload(text = "Second LinkedIn text with other distinct content and enough detail.")
		val first = store.createIfAbsent(userId, JobType.EXPERIENCE_SPLIT, null, null,
			ObjectMapper().valueToTree(firstPayload), null, 3).orElseThrow()
		val second = store.createIfAbsent(userId, JobType.EXPERIENCE_SPLIT, null, null,
			ObjectMapper().valueToTree(secondPayload), null, 3).orElseThrow()
		val reloaded = store.findById(first.id).orElseThrow()

		assertThat(second.id).isNotEqualTo(first.id)
		assertThat(reloaded.jobType).isEqualTo(JobType.EXPERIENCE_SPLIT)
		assertThat(reloaded.resourceType).isNull()
		assertThat(reloaded.resourceId).isNull()
		assertThat(reloaded.requestFingerprint).isNull()
		assertThat(reloaded.requestPayload?.path("text")?.asText()).isEqualTo(firstPayload.text)
	}

	private fun input(
		title: String = "Backend Engineer",
		organization: String? = "Acme",
		startDate: String? = "2023-01",
		endDate: String? = null,
		description: String = "Built a dependable service."
	) = ExperienceInput(title, organization, startDate, endDate, description)

	companion object {
		private val postgres = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
			.withDatabaseName("ai_interview_experience_test").withUsername("ai_interview").withPassword("ai_interview")
		private lateinit var jdbc: JdbcTemplate
		private lateinit var service: ExperienceService
		private val jobSubmission = Mockito.mock(JobSubmissionService::class.java)

		@BeforeAll @JvmStatic
		fun setUp() {
			postgres.start()
			val source = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
			Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
			jdbc = JdbcTemplate(source)
			service = ExperienceService(jdbc, ContentHasher(), jobSubmission)
		}

		@AfterAll @JvmStatic fun tearDown() { postgres.stop() }
	}
}
