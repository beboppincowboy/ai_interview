package dev.jiaming.ai_interview.suggestions

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.coach.CoachPromptBuilder
import dev.jiaming.ai_interview.coach.CoachRagContextService
import dev.jiaming.ai_interview.coach.CoachResponseMapper
import dev.jiaming.ai_interview.coach.StructuredGenerationClient
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.experience.ExperienceService
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobDispatcher
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobHandlerRegistry
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobPayloadDecoder
import dev.jiaming.ai_interview.jobs.JobProcessor
import dev.jiaming.ai_interview.jobs.JobProperties
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.jobs.RequestFingerprintService
import dev.jiaming.ai_interview.rag.RagContextSnippet
import dev.jiaming.ai_interview.rag.RagDocumentIndexHandle
import dev.jiaming.ai_interview.rag.RagIndexingService
import dev.jiaming.ai_interview.rag.RagProperties
import dev.jiaming.ai_interview.rag.RagRetrievalService
import dev.jiaming.ai_interview.resume.ResumeLibraryService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import dev.jiaming.ai_interview.resume.ResumeStorageCleanupService
import dev.jiaming.ai_interview.resume.ResumeStorageService
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import dev.jiaming.ai_interview.targetjob.TargetJobService
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.Optional
import java.util.UUID

@Testcontainers
class SuggestionsIntegrationTests {
    @Test
    fun withoutOtherReadyResumesOrExperiencesGetIsUnavailableAndPostStartsNothing() {
        val resumeId = insertResume("Selected")
        val targetJobId = insertTargetJob()
        insertResume("Still processing", status = "PENDING")
        val otherUser = insertUser()
        insertResume("Foreign", userId = otherUser)
        insertExperience("Foreign project", userId = otherUser)

        val view = suggestions.get(resumeId, targetJobId)
        assertThat(view.sourcesAvailable).isFalse()
        assertThat(view.stale).isFalse()
        assertThat(view.result).isNull()
        assertThat(view.latestJob).isNull()

        assertThatThrownBy { suggestions.run(resumeId, targetJobId) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("NO_EXPERIENCE_SOURCES") }
        assertThat(count("background_jobs")).isZero()
        assertThat(count("experience_suggestions")).isZero()
    }

    @Test
    fun missingOrForeignInputsReturnTheirOwnNotFound() {
        val resumeId = insertResume("Selected")
        val targetJobId = insertTargetJob()
        val foreignResume = insertResume("Foreign", userId = insertUser())

        assertThatThrownBy { suggestions.get(foreignResume, targetJobId) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("RESUME_NOT_FOUND") }
        assertThatThrownBy { suggestions.run(resumeId, UUID.randomUUID()) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("TARGET_JOB_NOT_FOUND") }
    }

    @Test
    fun aQueuedFirstRunIsNotStaleAndDeleteImpactDoesNotCountItsPair() {
        val resumeId = insertResume("Selected")
        val targetJobId = insertTargetJob()
        val experienceId = insertExperience("Ledger rewrite")

        val first = suggestions.run(resumeId, targetJobId)
        val second = suggestions.run(resumeId, targetJobId)

        assertThat(second.jobId).isEqualTo(first.jobId)
        assertThat(second.reused).isTrue()
        val view = suggestions.get(resumeId, targetJobId)
        assertThat(view.sourcesAvailable).isTrue()
        assertThat(view.stale).isFalse()
        assertThat(view.result).isNull()
        assertThat(view.latestJob?.jobType).isEqualTo(JobType.EXPERIENCE_SUGGESTIONS)
        assertThat(view.latestJob?.status).isEqualTo(JobStatus.QUEUED)
        assertThat(library.deleteImpact(resumeId).suggestionSets).isZero()
        assertThat(targetJobs.deleteImpact(targetJobId).suggestionSets).isZero()
        assertThat(experiences.deleteImpact(local.localUserId(), experienceId).staleSuggestionSets).isZero()
    }

    @Test
    fun addingAnExperienceAfterARunMakesItStaleAndRunningAgainClearsIt() {
        val resumeId = insertResume("Selected")
        val targetJobId = insertTargetJob()
        val ledger = insertExperience("Ledger rewrite")
        modelCites(ledger)

        val firstJob = finishRun(resumeId, targetJobId)
        val fresh = suggestions.get(resumeId, targetJobId)
        assertThat(fresh.stale).isFalse()
        assertThat(fresh.createdAt).isNotNull()
        assertThat(fresh.result!!.items.map { it.source }).containsExactly(SuggestionSource(SuggestionSourceType.EXPERIENCE, ledger, "Ledger rewrite"))
        assertThat(fresh.latestJob?.jobId).isEqualTo(firstJob)
        assertThat(jobs.findById(firstJob).orElseThrow().resultPayload).isEqualTo(mapper.valueToTree(fresh.result))

        insertExperience("Kafka pipeline")
        assertThat(suggestions.get(resumeId, targetJobId).stale).isTrue()

        finishRun(resumeId, targetJobId)
        assertThat(suggestions.get(resumeId, targetJobId).stale).isFalse()
        assertThat(count("experience_suggestions")).isEqualTo(1)
    }

    @Test
    fun anItemCitingAnUnprovidedSourceIsDroppedAndAnEmptyListIsStored() {
        val resumeId = insertResume("Selected")
        val targetJobId = insertTargetJob()
        insertExperience("Ledger rewrite")
        modelCites(UUID.randomUUID(), resumeId)

        finishRun(resumeId, targetJobId)

        val view = suggestions.get(resumeId, targetJobId)
        assertThat(view.result).isNotNull()
        assertThat(view.result!!.items).isEmpty()
        assertThat(view.stale).isFalse()
        assertThat(targetJobs.deleteImpact(targetJobId).suggestionSets).isEqualTo(1)
    }

    @Test
    fun deletingAnExperienceUsedByTwoPairsHidesItsItemsAndMarksBothStale() {
        val resumeId = insertResume("Selected")
        val firstTarget = insertTargetJob()
        val secondTarget = insertTargetJob()
        val ledger = insertExperience("Ledger rewrite")
        val kafka = insertExperience("Kafka pipeline")
        modelCites(ledger, kafka)
        finishRun(resumeId, firstTarget)
        finishRun(resumeId, secondTarget)

        assertThat(experiences.deleteImpact(local.localUserId(), ledger).staleSuggestionSets).isEqualTo(2)
        experiences.delete(local.localUserId(), ledger)

        for (target in listOf(firstTarget, secondTarget)) {
            val view = suggestions.get(resumeId, target)
            assertThat(view.stale).isTrue()
            assertThat(view.result!!.items.map { it.source.id }).containsExactly(kafka)
        }
        assertThatThrownBy { experiences.deleteImpact(local.localUserId(), ledger) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("EXPERIENCE_NOT_FOUND") }
    }

    @Test
    fun deletingASourceResumeHidesItsItemsFromOtherPairsAndCountsMatchTheDelete() {
        val selected = insertResume("Selected")
        val other = insertResume("Platform resume")
        val targetJobId = insertTargetJob()
        val ledger = insertExperience("Ledger rewrite")
        modelCites(other, selected, ledger)
        finishRun(selected, targetJobId)
        finishRun(other, targetJobId)
        suggestions.run(other, insertTargetJob())

        val impact = library.deleteImpact(other)
        assertThat(impact.suggestionSets).isEqualTo(1)
        assertThat(impact.staleSuggestionSets).isEqualTo(1)
        assertThat(targetJobs.deleteImpact(targetJobId).suggestionSets).isEqualTo(2)

        library.delete(other)

        val view = suggestions.get(selected, targetJobId)
        assertThat(view.stale).isTrue()
        assertThat(view.result!!.items.map { it.source.id }).containsExactly(ledger)
        assertThat(count("experience_suggestions")).isEqualTo(1)
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ai_interview_app.background_jobs WHERE request_payload ->> 'resumeId' = ?", Int::class.java, other.toString()
        )).isZero()

        transactions.execute { targetJobs.delete(targetJobId) }
        assertThat(count("experience_suggestions")).isZero()
    }

    @Test
    fun anOverBudgetResumeIsNarrowedThroughRetrievalWhileExperiencesAndShortResumesGoInWhole() {
        val selected = insertResume("Selected")
        val shortText = "Short resume: built Kotlin payment services with PostgreSQL."
        insertResume("Short", text = shortText)
        val longTail = "UNRETRIEVED-TAIL-MARKER"
        val longText = (1..150).joinToString("\n") { "Line $it: maintained internal tooling and dashboards." } + "\n" + longTail
        val longResume = insertResume("Long", text = longText)
        val description = "Designed an outbox-based ledger. ".repeat(110).trim()
        insertExperience("Ledger rewrite", description = description)
        val targetJobId = insertTargetJob(text = "We need Kafka and event-driven systems experience.")
        val handle = RagDocumentIndexHandle(UUID.randomUUID(), 1L)
        whenever(indexing.ensureIndexed(any())).thenReturn(Optional.of(handle))
        whenever(retrieval.retrieve(any(), eq(handle), any())).thenReturn(listOf(RagContextSnippet(
            "vector-0", "Retrieved: ran the Kafka event pipeline.",
            mapOf("contextId" to "resume:retrieved:0", "sourceType" to "resume", "section" to "Experience", "chunkIndex" to 0), 0.9
        )))
        modelCites()

        finishRun(selected, targetJobId)

        val prompt = argumentCaptor<String>()
        Mockito.verify(client).generateJson(prompt.capture())
        assertThat(description.length).isGreaterThan(3_500)
        assertThat(longText.length).isGreaterThan(6_000)
        assertThat(prompt.firstValue).contains(description, shortText, "Retrieved: ran the Kafka event pipeline.").doesNotContain(longTail)
        Mockito.verify(indexing).ensureIndexed(argThat<ResolvedDocument> { resourceId() == longResume })
        Mockito.verify(indexing, Mockito.times(1)).ensureIndexed(any())
        Mockito.verify(retrieval, Mockito.atLeastOnce()).retrieve(argThat<String> { contains("Kafka") }, eq(handle), any())
    }

    @Test
    fun aSourceDeletedBeforeMaterializationIsNeverShownAndRefreshHasNoSources() {
        val resumeId = insertResume("Selected")
        val targetJobId = insertTargetJob()
        val ledger = insertExperience("Ledger rewrite")
        whenever(client.generateJson(any())).thenAnswer {
            jdbc.update("DELETE FROM ai_interview_app.experiences WHERE id = ?", ledger)
            modelJson(listOf(ledger))
        }

        finishRun(resumeId, targetJobId)

        val view = suggestions.get(resumeId, targetJobId)
        assertThat(view.result!!.items).isEmpty()
        assertThat(view.stale).isTrue()
        assertThat(view.sourcesAvailable).isFalse()
        assertThatThrownBy { suggestions.run(resumeId, targetJobId) }
            .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("NO_EXPERIENCE_SOURCES") }
    }

    private fun finishRun(resumeId: UUID, targetJobId: UUID): UUID {
        val jobId = suggestions.run(resumeId, targetJobId).jobId
        val lease = UUID.randomUUID()
        jdbc.update("UPDATE ai_interview_app.background_jobs SET status = 'PROCESSING', lease_token = ?, lease_expires_at = now() + interval '5 minutes' WHERE id = ?", lease, jobId)
        val result = processor.process(jobs.findById(jobId).orElseThrow(), lease)!!
        jobs.markSucceeded(jobId, lease, result)
        return jobId
    }

    private fun modelCites(vararg sourceIds: UUID) {
        whenever(client.generateJson(any())).thenReturn(modelJson(sourceIds.toList()))
    }

    private fun modelJson(sourceIds: List<UUID>) = mapper.writeValueAsString(mapOf("items" to sourceIds.map {
        mapOf("requirement" to "Event-driven systems", "sourceId" to it.toString(), "match" to "Built an outbox pipeline.",
            "whyItFits" to "The job asks for event-driven design.", "guidance" to "Add it under the most recent role.")
    }))

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", id, "$id@suggestions-test.example")
        return id
    }

    private fun insertResume(name: String, status: String = "READY", userId: UUID = local.localUserId(), text: String = "$name: built Kotlin services."): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, ?, 'PASTE', ?, ?)",
            id, userId, name, status, text
        )
        return id
    }

    private fun insertExperience(title: String, userId: UUID = local.localUserId(), description: String = "$title: built a dependable service."): UUID =
        jdbc.queryForObject(
            "INSERT INTO ai_interview_app.experiences (user_id, title, description, source, content_hash) VALUES (?, ?, ?, 'FORM', ?) RETURNING id",
            UUID::class.java, userId, title, description, "hash-${UUID.randomUUID()}"
        )!!

    private fun insertTargetJob(text: String = "Platform role requiring Kotlin."): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.job_descriptions (id, user_id, name, title, raw_text, normalized_text, content_hash) VALUES (?, ?, 'Platform', 'Platform', ?, ?, ?)",
            id, local.localUserId(), text, text, "hash-$id"
        )
        return id
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    @BeforeEach fun reset() {
        Mockito.reset(client, indexing, retrieval)
        jdbc.execute("TRUNCATE TABLE ai_interview_app.experience_suggestions, ai_interview_app.experiences, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_suggestions_test").withUsername("ai_interview").withPassword("ai_interview")
        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private val client = Mockito.mock(StructuredGenerationClient::class.java)
        private val indexing = Mockito.mock(RagIndexingService::class.java)
        private val retrieval = Mockito.mock(RagRetrievalService::class.java)
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var suggestions: SuggestionsService
        private lateinit var library: ResumeLibraryService
        private lateinit var targetJobs: TargetJobService
        private lateinit var experiences: ExperienceService
        private lateinit var jobs: BackgroundJobStore
        private lateinit var processor: JobProcessor

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            jobs = BackgroundJobStore(jdbc, mapper)
            val guard = RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties(
                "suggestions-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)
            ), mapper)
            val normalizer = ResumeTextNormalizer()
            val persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            val jobDescriptions = JobDescriptionPersistenceService(jdbc, normalizer, SectionAwareTextChunker(), ContentHasher())
            val resolver = DocumentReferenceResolver(persistence, jobDescriptions)
            val submissions = JobSubmissionService(
                jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(mapper), local, guard,
                PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), mapper
            )
            suggestions = SuggestionsService(jdbc, local, submissions, guard, jobs, mapper, transactions, persistence)
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions,
                ResumeStorageCleanupService(jdbc, Mockito.mock(ResumeStorageService::class.java)), DeleteImpactService(jdbc), mapper, BackgroundJobStore(jdbc, mapper)
            )
            targetJobs = TargetJobService(jdbc, local, jobDescriptions)
            experiences = ExperienceService(jdbc, ContentHasher(), submissions)
            val rag = CoachRagContextService(SectionAwareTextChunker(), indexing, retrieval, SimpleMeterRegistry(),
                RagProperties(1024, 8, "gemini-embedding-001", "section-block-v3"))
            val coach = AiResumeCoachService(client, rag, CoachPromptBuilder(), CoachResponseMapper(mapper), SimpleMeterRegistry())
            val handler = ExperienceSuggestionsJobHandler(coach, resolver, suggestions)
            val otherHandlers = JobType.entries.filter { it != JobType.EXPERIENCE_SUGGESTIONS }.map { type ->
                Mockito.mock(JobHandler::class.java).also { whenever(it.type()).thenReturn(type) }
            }
            processor = JobProcessor(
                JobPayloadDecoder(mapper), JobHandlerRegistry(otherHandlers + handler), jobs,
                JobEffectMaterializationService(jdbc, mapper),
                JobMetrics(SimpleMeterRegistry()), mapper
            )
        }
    }
}
