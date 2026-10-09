package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.targetjob.TargetJobPersistenceService
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobDispatcher
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobProperties
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.RequestFingerprintService
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
import org.assertj.core.api.ThrowableAssert.ThrowingCallable
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class PracticeIntegrationTests {
    @Test
    fun twoConcurrentCreatesForOnePairMakeOneSetWithOne201AndOne200AndOneAiCharge() {
        val resumeId = insertResume()
        val targetJobId = insertTargetJob()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val responses = try {
            val calls = (1..2).map { executor.submit(Callable { start.await(); controller.create(request(resumeId, targetJobId)) }) }
            start.countDown()
            calls.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(responses.map { it.statusCode.value() }).containsExactlyInAnyOrder(201, 200)
        assertThat(responses.map { it.body!!.id }.distinct()).hasSize(1)
        val created = responses.single { it.statusCode.value() == 201 }.body!!
        assertThat(created.status).isEqualTo(PracticeSetStatus.GENERATING)
        assertThat(created.questions).isEmpty()
        assertThat(created.latestJob?.status).isEqualTo(JobStatus.QUEUED)
        assertThat(count("practice_sets")).isEqualTo(1)
        assertThat(count("background_jobs")).isEqualTo(1)
        Mockito.verify(guard, Mockito.times(1)).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)

        val job = jobs.findById(created.latestJob!!.jobId).orElseThrow()
        assertThat(job.resourceType).isEqualTo(PracticeService.RESOURCE)
        assertThat(job.resourceId).isEqualTo(created.id)
        assertThat(JobInputRefs.from(job)).isEqualTo(JobInputRefs(resumeId, targetJobId, created.id, null))
    }

    @Test
    fun voiceModeNotReadyResumesAndForeignOrUnknownInputsCreateNothing() {
        val resumeId = insertResume()
        val targetJobId = insertTargetJob()
        val other = insertUser()

        expectCode("INVALID_REQUEST") { controller.create(CreatePracticeSetRequest(resumeId, targetJobId, "VOICE")) }
        expectCode("RESUME_NOT_READY") { practice.create(insertResume(status = "PENDING"), targetJobId) }
        expectCode("RESUME_NOT_FOUND") { practice.create(insertResume(user = other), targetJobId) }
        expectCode("RESUME_NOT_FOUND") { practice.create(UUID.randomUUID(), targetJobId) }
        expectCode("TARGET_JOB_NOT_FOUND") { practice.create(resumeId, insertTargetJob(user = other)) }
        assertThat(count("practice_sets")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    @Test
    fun unknownAndForeignSetsAreNotFoundForEveryOperation() {
        val other = insertUser()
        val foreignSet = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.practice_sets (id, user_id, resume_id, target_job_id) VALUES (?, ?, ?, ?)",
            foreignSet, other, insertResume(user = other), insertTargetJob(user = other),
        )

        for (setId in listOf(foreignSet, UUID.randomUUID())) {
            expectCode("PRACTICE_SET_NOT_FOUND") { practice.get(setId) }
            expectCode("PRACTICE_SET_NOT_FOUND") { practice.retry(setId) }
            expectCode("PRACTICE_SET_NOT_FOUND") { practice.addQuestion(setId, "A question of my own?") }
        }
        assertThat(count("practice_questions")).isZero()
    }

    @Test
    fun generatedQuestionsMakeTheSetReadyInOrderAndAreSavedOnlyOnce() {
        val set = createSet()
        val jobId = set.latestJob!!.jobId

        finishGeneration(set.id, 3)
        finishGeneration(set.id, 3, jobId = jobId, alreadyFinished = true)

        val ready = practice.get(set.id)
        assertThat(ready.status).isEqualTo(PracticeSetStatus.READY)
        assertThat(ready.latestJob?.status).isEqualTo(JobStatus.SUCCEEDED)
        assertThat(ready.questions.map { it.order }).containsExactly(1, 2, 3)
        assertThat(ready.questions.map { it.text }).containsExactly("Question 1?", "Question 2?", "Question 3?")
        assertThat(ready.questions).allSatisfy {
            assertThat(it.origin).isEqualTo("AI")
            assertThat(it.rationale).isNotBlank()
            assertThat(it.expectedSignals).containsExactly("signal")
            assertThat(it.attempts).isEmpty()
        }
        val result = jobs.findById(jobId).orElseThrow().resultPayload!!
        assertThat(mapper.treeToValue(result, PracticeQuestionsResult::class.java).questions).isEqualTo(ready.questions)

        val again = controller.create(request(set.resumeId, set.targetJobId))
        assertThat(again.statusCode.value()).isEqualTo(200)
        assertThat(again.body!!.id).isEqualTo(set.id)
        assertThat(count("background_jobs")).isEqualTo(1)
        expectCode("PRACTICE_SET_NOT_FAILED") { practice.retry(set.id) }
    }

    @Test
    fun aFailedGenerationIsFailedUntilRetryStartsANewJobAndUserQuestionsFollowTheAiOnes() {
        val set = createSet()
        val firstJob = set.latestJob!!.jobId
        expectCode("PRACTICE_SET_NOT_FAILED") { practice.retry(set.id) }
        expectCode("PRACTICE_SET_NOT_READY") { practice.addQuestion(set.id, "A question while generating?") }

        failGeneration(set.id)
        val failed = practice.get(set.id)
        assertThat(failed.status).isEqualTo(PracticeSetStatus.FAILED)
        assertThat(failed.latestJob?.status).isEqualTo(JobStatus.FAILED)
        assertThat(failed.latestJob?.error?.code).isEqualTo("GEMINI_INVALID_RESPONSE")
        val userQuestion = practice.addQuestion(set.id, "How do you test retries?")
        assertThat(userQuestion.origin).isEqualTo("USER")
        assertThat(userQuestion.order).isEqualTo(1)

        Mockito.clearInvocations(guard)
        val retried = practice.retry(set.id)
        assertThat(retried.status).isEqualTo(PracticeSetStatus.GENERATING)
        assertThat(retried.latestJob!!.jobId).isNotEqualTo(firstJob)
        assertThat(retried.latestJob!!.status).isEqualTo(JobStatus.QUEUED)
        Mockito.verify(guard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        expectCode("PRACTICE_SET_NOT_FAILED") { practice.retry(set.id) }

        finishGeneration(set.id, 3)
        val ready = practice.get(set.id)
        assertThat(ready.questions.map { it.origin to it.order })
            .containsExactly("AI" to 1, "AI" to 2, "AI" to 3, "USER" to 4)
        assertThat(ready.questions.last().id).isEqualTo(userQuestion.id)
        assertThat(practice.addQuestion(set.id, "What would you change next?").order).isEqualTo(5)
    }

    @Test
    fun theEleventhUserQuestionReachesTheLimit() {
        val set = readySet()
        repeat(10) { practice.addQuestion(set.id, "My own question number $it?") }

        expectCode("QUESTION_LIMIT_REACHED") { practice.addQuestion(set.id, "One question too many?") }
        assertThat(practice.get(set.id).questions.count { it.origin == "USER" }).isEqualTo(10)
    }

    @Test
    fun twoConcurrentAddsOnNineSavedQuestionsLeaveTenAndTheLoserReachesTheLimit() {
        val set = readySet()
        repeat(9) { practice.addQuestion(set.id, "My own question number $it?") }
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val outcomes = try {
            val calls = (1..2).map { index ->
                executor.submit(Callable { start.await(); practice.addQuestion(set.id, "Concurrent question $index?") })
            }
            start.countDown()
            calls.map { call ->
                try { call.get(10, TimeUnit.SECONDS).origin } catch (exception: ExecutionException) {
                    (exception.cause as ApiRequestException).code()
                }
            }
        } finally {
            executor.shutdownNow()
        }

        assertThat(outcomes).containsExactlyInAnyOrder("USER", "QUESTION_LIMIT_REACHED")
        val questions = practice.get(set.id).questions
        assertThat(questions.count { it.origin == "USER" }).isEqualTo(10)
        assertThat(questions.map { it.order }).containsExactlyElementsOf(1..13)
    }

    @Test
    fun deleteImpactCountsTheSetsAndDeletingEitherSideCascadesTheSetAndItsQuestions() {
        val resumeId = insertResume()
        val firstJob = insertTargetJob()
        val secondJob = insertTargetJob()
        val first = readySet(resumeId, firstJob)
        practice.addQuestion(first.id, "A question of my own?")
        createSet(resumeId, secondJob)

        assertThat(library.deleteImpact(resumeId).practiceSets).isEqualTo(2)
        assertThat(library.deleteImpact(resumeId).attempts).isZero()
        assertThat(targetJobs.deleteImpact(firstJob).practiceSets).isEqualTo(1)
        assertThat(targetJobs.deleteImpact(secondJob).practiceSets).isEqualTo(1)

        transactions.execute { targetJobs.delete(firstJob) }
        assertThat(count("practice_sets")).isEqualTo(1)
        assertThat(count("practice_questions")).isZero()
        assertThat(count("background_jobs")).isEqualTo(1)
        expectCode("PRACTICE_SET_NOT_FOUND") { practice.get(first.id) }

        library.delete(resumeId)
        assertThat(count("practice_sets")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    private fun createSet(resumeId: UUID = insertResume(), targetJobId: UUID = insertTargetJob()): PracticeSetView {
        val creation = practice.create(resumeId, targetJobId)
        assertThat(creation.created).isTrue()
        return creation.set
    }

    private fun readySet(resumeId: UUID = insertResume(), targetJobId: UUID = insertTargetJob()): PracticeSetView {
        val set = createSet(resumeId, targetJobId)
        finishGeneration(set.id, 3)
        return set
    }

    // Plays the worker's part: claim the latest job, save the drafts under the lease, then succeed with the handler's result.
    private fun finishGeneration(setId: UUID, questionCount: Int, jobId: UUID = practice.get(setId).latestJob!!.jobId, alreadyFinished: Boolean = false) {
        val lease = UUID.randomUUID()
        jdbc.update("UPDATE ai_interview_app.background_jobs SET status = 'PROCESSING', lease_token = ?, lease_expires_at = now() + interval '5 minutes' WHERE id = ?", lease, jobId)
        val job = jobs.findById(jobId).orElseThrow()
        val drafts = (1..questionCount).map { PracticeQuestionDraft("Question $it?", "Reason $it", "Depth", listOf("signal")) }
        materialization.materializePracticeQuestions(job, lease, setId, drafts)
        val result = mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(PracticeQuestionsResult(practice.questions(local.localUserId(), setId)))
        assertThat(jobs.markSucceeded(jobId, lease, result)).isTrue()
        if (alreadyFinished) assertThat(count("practice_questions")).isEqualTo(questionCount)
    }

    private fun failGeneration(setId: UUID) {
        val jobId = practice.get(setId).latestJob!!.jobId
        val lease = UUID.randomUUID()
        jdbc.update("UPDATE ai_interview_app.background_jobs SET status = 'PROCESSING', lease_token = ?, lease_expires_at = now() + interval '5 minutes' WHERE id = ?", lease, jobId)
        assertThat(jobs.markFailed(jobId, lease, "GEMINI_INVALID_RESPONSE", "Gemini response remained invalid")).isTrue()
    }

    private fun request(resumeId: UUID, targetJobId: UUID) = CreatePracticeSetRequest(resumeId, targetJobId, "PRACTICE")

    private fun expectCode(code: String, call: ThrowingCallable) = assertThatThrownBy(call)
        .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo(code) }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", id, "$id@practice.test")
        return id
    }

    private fun insertResume(user: UUID = local.localUserId(), status: String = "READY"): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', 'PASTE', ?, 'Built Kotlin services.')",
            id, user, status,
        )
        return id
    }

    private fun insertTargetJob(user: UUID = local.localUserId()): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.job_descriptions (id, user_id, name, title, raw_text, normalized_text, content_hash) VALUES (?, ?, 'Platform', 'Platform', 'text', 'text', ?)",
            id, user, "hash-$id",
        )
        return id
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    @BeforeEach fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.practice_questions, ai_interview_app.practice_sets, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
        Mockito.clearInvocations(guard)
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_practice_test").withUsername("ai_interview").withPassword("ai_interview")
        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var guard: RedisRequestGuard
        private lateinit var practice: PracticeService
        private lateinit var controller: PracticeController
        private lateinit var library: ResumeLibraryService
        private lateinit var targetJobs: TargetJobService
        private lateinit var jobs: BackgroundJobStore
        private lateinit var materialization: JobEffectMaterializationService

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            jobs = BackgroundJobStore(jdbc, mapper)
            guard = Mockito.spy(RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties(
                "practice-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)
            ), mapper))
            val submissions = JobSubmissionService(
                jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(mapper), local, guard,
                PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), mapper
            )
            practice = PracticeService(jdbc, local, submissions, guard, jobs, mapper, transactions)
            controller = PracticeController(practice, guard)
            val normalizer = ResumeTextNormalizer()
            val persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions,
                ResumeStorageCleanupService(jdbc, Mockito.mock(ResumeStorageService::class.java)), DeleteImpactService(jdbc), mapper, BackgroundJobStore(jdbc, mapper)
            )
            targetJobs = TargetJobService(jdbc, local, TargetJobPersistenceService(jdbc, normalizer, SectionAwareTextChunker(), ContentHasher()))
            materialization = JobEffectMaterializationService(jdbc, mapper)
        }
    }
}
