package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.JsonNode
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
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import dev.jiaming.ai_interview.jobs.AttemptFeedbackPayload
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobDispatcher
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobProperties
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
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
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class AttemptIntegrationTests {
    @Test
    fun twoAttemptsScoring62Then74ShowAScoreDeltaOf12OnTheSecondOnly() {
        val set = readySet()
        val question = set.questions.first()

        val first = submit(set.id, question.id, "  I cut p99 latency with a cache.  ")
        assertThat(first.statusCode.value()).isEqualTo(201)
        val attempt = first.body!!
        assertThat(attempt.number).isEqualTo(1)
        assertThat(attempt.text).isEqualTo("I cut p99 latency with a cache.")
        assertThat(attempt.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(attempt.feedback).isNull()
        assertThat(attempt.scoreDelta).isNull()
        assertThat(attempt.activeJob!!.jobType).isEqualTo(JobType.ANSWER_FEEDBACK)
        assertThat(attempt.activeJob!!.status).isEqualTo(JobStatus.QUEUED)
        val job = jobs.findById(attempt.activeJob!!.jobId).orElseThrow()
        assertThat(job.resourceType).isEqualTo("attempt")
        assertThat(job.resourceId).isEqualTo(attempt.id)
        assertThat(JobInputRefs.from(job)).isEqualTo(JobInputRefs(set.resumeId, set.targetJobId, set.id, attempt.id))
        assertThat(job.requestPayload.toString()).doesNotContain("p99")
        Mockito.verify(guard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)

        score(attempt.id, 62)
        score(attempt.id, 99, jobId = job.id, alreadyScored = true)
        val second = submit(set.id, question.id, "I cut p99 latency by 40% with a read-through cache.").body!!
        assertThat(second.number).isEqualTo(2)
        score(second.id, 74)

        val attempts = practice.get(set.id).questions.first().attempts
        assertThat(attempts.map { it.number }).containsExactly(1, 2)
        assertThat(attempts.map { it.status }).containsOnly(AttemptStatus.SCORED)
        assertThat(attempts.map { it.feedback!!.score }).containsExactly(62, 74)
        assertThat(attempts.map { it.scoreDelta }).containsExactly(null, 12)
        assertThat(attempts.first().activeJob!!.status).isEqualTo(JobStatus.SUCCEEDED)
        val result = jobs.findById(second.activeJob!!.jobId).orElseThrow().resultPayload!!
        assertThat(mapper.treeToValue(result, AnswerFeedbackResult::class.java)).isEqualTo(attempts.last().feedback)
        assertThat(jdbc.queryForList("SELECT score FROM ai_interview_app.answer_attempts ORDER BY number", Int::class.java)).containsExactly(62, 74)
    }

    @Test
    fun aFailedAttemptBetweenTwoScoredOnesDoesNotBreakTheDeltaChain() {
        val set = readySet()
        val question = set.questions.first()
        val first = submit(set.id, question.id, "First answer").body!!
        score(first.id, 62)
        val failed = submit(set.id, question.id, "Second answer").body!!
        fail(failed.id)
        val third = submit(set.id, question.id, "Third answer").body!!
        score(third.id, 70)

        val attempts = practice.get(set.id).questions.first().attempts
        assertThat(attempts.map { it.status }).containsExactly(AttemptStatus.SCORED, AttemptStatus.FAILED, AttemptStatus.SCORED)
        assertThat(attempts.map { it.scoreDelta }).containsExactly(null, null, 8)
        assertThat(attempts[1].feedback).isNull()
        assertThat(attempts[1].text).isEqualTo("Second answer")
        assertThat(attempts[1].activeJob!!.error!!.code).isEqualTo("GEMINI_INVALID_RESPONSE")
    }

    @Test
    fun blankAndOverLongAnswersAreRejectedAndSaveNothing() {
        val set = readySet()
        val question = set.questions.first()

        expectCode("ANSWER_EMPTY") { submit(set.id, question.id, " \n\t ") }
        expectCode("ANSWER_TOO_LONG") { submit(set.id, question.id, "x".repeat(4_001)) }
        assertThat(count("answer_attempts")).isZero()
        assertThat(count("background_jobs")).isZero()
        Mockito.verify(guard, Mockito.never()).assertAiAllowed(anyString())

        assertThat(submit(set.id, question.id, "  " + "x".repeat(4_000) + "  ").body!!.text).hasSize(4_000)
    }

    @Test
    fun resubmittingTheLatestTextIsUnchangedEvenWhenThatAttemptFailed() {
        val set = readySet()
        val question = set.questions.first()
        val first = submit(set.id, question.id, "My answer").body!!

        expectCode("ANSWER_UNCHANGED") { submit(set.id, question.id, "   My answer   ") }
        fail(first.id)
        expectCode("ANSWER_UNCHANGED") { submit(set.id, question.id, "My answer ") }
        assertThat(count("answer_attempts")).isEqualTo(1)
        assertThat(count("background_jobs")).isEqualTo(1)

        assertThat(submit(set.id, question.id, "A better answer").body!!.number).isEqualTo(2)
        assertThat(submit(set.id, question.id, "My answer").body!!.number).isEqualTo(3)
    }

    @Test
    fun identicalTextOnTwoQuestionsCreatesTwoJobsThatArePendingAtOnce() {
        val set = readySet()
        val (first, second) = set.questions

        val one = submit(set.id, first.id, "The same answer").body!!
        val two = submit(set.id, second.id, "The same answer").body!!

        assertThat(one.activeJob!!.jobId).isNotEqualTo(two.activeJob!!.jobId)
        val questions = practice.get(set.id).questions
        assertThat(questions.take(2).map { it.attempts.single().status }).containsOnly(AttemptStatus.PENDING)
        assertThat(questions.take(2).map { it.attempts.single().activeJob!!.status }).containsOnly(JobStatus.QUEUED)
        assertThat(count("background_jobs")).isEqualTo(2)
        Mockito.verify(guard, Mockito.times(2)).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
    }

    @Test
    fun onlyAFailedAttemptCanBeRetriedAndItKeepsItsIdNumberAndText() {
        val set = readySet()
        val question = set.questions.first()
        submit(set.id, question.id, "An earlier answer")
        val attempt = submit(set.id, question.id, "The answer to retry").body!!
        expectCode("ATTEMPT_NOT_FAILED") { retry(attempt.id) }

        fail(attempt.id)
        Mockito.clearInvocations(guard)
        val retried = retry(attempt.id)

        assertThat(retried.statusCode.value()).isEqualTo(202)
        val body = retried.body!!
        assertThat(body.id).isEqualTo(attempt.id)
        assertThat(body.number).isEqualTo(2)
        assertThat(body.text).isEqualTo("The answer to retry")
        assertThat(body.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(body.activeJob!!.jobId).isNotEqualTo(attempt.activeJob!!.jobId)
        assertThat(body.activeJob!!.status).isEqualTo(JobStatus.QUEUED)
        Mockito.verify(guard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        expectCode("ATTEMPT_NOT_FAILED") { retry(attempt.id) }

        score(attempt.id, 80)
        expectCode("ATTEMPT_NOT_FAILED") { retry(attempt.id) }
        assertThat(count("answer_attempts")).isEqualTo(2)
    }

    @Test
    fun foreignAndUnknownSetsQuestionsAndAttemptsAreNotFound() {
        val other = insertUser()
        val foreignSet = insertSet(insertResume(other), insertTargetJob(other), other)
        val foreignQuestion = insertQuestion(foreignSet, 1, other)
        val foreignAttempt = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.answer_attempts (id, question_id, user_id, number, text) VALUES (?, ?, ?, 1, 'Theirs')",
            foreignAttempt, foreignQuestion, other,
        )
        val mine = readySet()
        val otherSetOfMine = readySet()

        for ((setId, questionId) in listOf(
            foreignSet to foreignQuestion,
            mine.id to foreignQuestion,
            otherSetOfMine.id to mine.questions.first().id,
            UUID.randomUUID() to mine.questions.first().id,
            mine.id to UUID.randomUUID(),
        )) expectCode("QUESTION_NOT_FOUND") { submit(setId, questionId, "An answer") }
        expectCode("ATTEMPT_NOT_FOUND") { retry(foreignAttempt) }
        expectCode("ATTEMPT_NOT_FOUND") { retry(UUID.randomUUID()) }
        assertThat(count("answer_attempts")).isEqualTo(1)
        assertThat(count("background_jobs")).isZero()
    }

    @Test
    fun replayingASuccessfulSubmissionReturnsTheSame201AttemptEvenAfterALaterAttempt() {
        val set = readySet()
        val question = set.questions.first()
        useIdempotencyKey("submit-a")
        val original = submit(set.id, question.id, "Answer A")

        val replay = submit(set.id, question.id, "Answer A")
        assertThat(replay.statusCode.value()).isEqualTo(201)
        assertThat(replay.body).isEqualTo(original.body)

        useIdempotencyKey("submit-b")
        assertThat(submit(set.id, question.id, "Answer B").body!!.number).isEqualTo(2)
        score(original.body!!.id, 70)
        useIdempotencyKey("submit-a")
        val lateReplay = submit(set.id, question.id, "Answer A")

        assertThat(lateReplay.statusCode.value()).isEqualTo(201)
        assertThat(lateReplay.body).isEqualTo(original.body)
        assertThat(lateReplay.body!!.status).isEqualTo(AttemptStatus.PENDING)
        assertThat(count("answer_attempts")).isEqualTo(2)
        assertThat(count("background_jobs")).isEqualTo(2)
        Mockito.verify(guard, Mockito.times(2)).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
    }

    @Test
    fun aJobCreationFailureRollsBackTheAttemptAndTheSameKeyThenSucceeds() {
        val set = readySet()
        val question = set.questions.first()
        useIdempotencyKey("flaky")
        Mockito.doThrow(IllegalStateException("job store unavailable")).doCallRealMethod()
            .`when`(jobs).createIfAbsentWithInitialResult(any(), any(), any(), any(), any(), any(), Mockito.anyInt(), anyOrNull())

        assertThatThrownBy { submit(set.id, question.id, "My answer") }.hasMessageContaining("job store unavailable")
        assertThat(count("answer_attempts")).isZero()
        assertThat(count("background_jobs")).isZero()

        val created = submit(set.id, question.id, "My answer")
        assertThat(created.statusCode.value()).isEqualTo(201)
        assertThat(created.body!!.number).isEqualTo(1)
        assertThat(count("answer_attempts")).isEqualTo(1)
        assertThat(count("background_jobs")).isEqualTo(1)
    }

    @Test
    fun twoConcurrentDifferentAnswersGetDistinctSequentialNumbersAndCorrectDeltas() {
        val set = readySet()
        val question = set.questions.first()
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        val submitted = try {
            val calls = listOf("Concurrent answer one", "Concurrent answer two").map { text ->
                executor.submit(Callable { start.await(); practice.submitAttempt(set.id, question.id, text) })
            }
            start.countDown()
            calls.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertThat(submitted.map { it.number }).containsExactlyInAnyOrder(1, 2)
        val byNumber = submitted.sortedBy { it.number }
        score(byNumber[0].id, 60)
        score(byNumber[1].id, 75)
        val attempts = practice.get(set.id).questions.first().attempts
        assertThat(attempts.map { it.id }).containsExactly(byNumber[0].id, byNumber[1].id)
        assertThat(attempts.map { it.scoreDelta }).containsExactly(null, 15)
    }

    @Test
    fun deleteImpactCountsAttemptsAndDeletingEitherSideCascadesThemWithTheirJobs() {
        val resumeId = insertResume()
        val firstJob = insertTargetJob()
        val secondJob = insertTargetJob()
        val first = readySet(resumeId, firstJob)
        val second = readySet(resumeId, secondJob)
        submit(first.id, first.questions[0].id, "One")
        submit(first.id, first.questions[0].id, "Two")
        submit(first.id, first.questions[1].id, "Three")
        submit(second.id, second.questions[0].id, "Four")

        assertThat(library.deleteImpact(resumeId).attempts).isEqualTo(4)
        assertThat(targetJobs.deleteImpact(firstJob).attempts).isEqualTo(3)
        assertThat(targetJobs.deleteImpact(secondJob).attempts).isEqualTo(1)

        transactions.execute { targetJobs.delete(firstJob) }
        assertThat(count("answer_attempts")).isEqualTo(1)
        assertThat(count("background_jobs")).isEqualTo(1)
        assertThat(library.deleteImpact(resumeId).attempts).isEqualTo(1)

        library.delete(resumeId)
        assertThat(count("answer_attempts")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    private fun submit(setId: UUID, questionId: UUID, text: String) =
        controller.submit(setId, questionId, SubmitAttemptRequest(text))

    private fun retry(attemptId: UUID) = controller.retry(attemptId)

    private fun readySet(resumeId: UUID = insertResume(), targetJobId: UUID = insertTargetJob()): PracticeSetView {
        val setId = insertSet(resumeId, targetJobId)
        (1..3).forEach { insertQuestion(setId, it) }
        return practice.get(setId)
    }

    // Plays the worker's part: claim the attempt's latest job, save the feedback under the lease, then succeed with it.
    private fun score(attemptId: UUID, score: Int, jobId: UUID = latestJob(attemptId), alreadyScored: Boolean = false) {
        val lease = claim(jobId)
        val feedback = AnswerFeedbackResult(score, "Summary $score", "Next step", listOf("Clear"), listOf("No metric"), listOf("Context"), null)
        materialization.materializeAttemptFeedback(jobs.findById(jobId).orElseThrow(), lease, attemptId, feedback)
        assertThat(jobs.markSucceeded(jobId, lease, mapper.valueToTree<JsonNode>(feedback))).isTrue()
        if (alreadyScored) assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM ai_interview_app.answer_attempts WHERE id = ? AND score = ?", Int::class.java, attemptId, score,
        )).isZero()
    }

    private fun fail(attemptId: UUID) {
        val jobId = latestJob(attemptId)
        assertThat(jobs.markFailed(jobId, claim(jobId), "GEMINI_INVALID_RESPONSE", "Gemini response remained invalid")).isTrue()
    }

    private fun claim(jobId: UUID): UUID {
        val lease = UUID.randomUUID()
        jdbc.update("UPDATE ai_interview_app.background_jobs SET status = 'PROCESSING', lease_token = ?, lease_expires_at = now() + interval '5 minutes' WHERE id = ?", lease, jobId)
        return lease
    }

    private fun latestJob(attemptId: UUID) = jobs.findLatestForResource(local.localUserId(), AttemptFeedbackPayload.RESOURCE, attemptId).orElseThrow().id

    private fun useIdempotencyKey(key: String) {
        val request = MockHttpServletRequest()
        request.addHeader("Idempotency-Key", key)
        RequestContextHolder.setRequestAttributes(ServletRequestAttributes(request))
    }

    private fun expectCode(code: String, call: ThrowingCallable) = assertThatThrownBy(call)
        .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo(code) }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", id, "$id@attempt.test")
        return id
    }

    private fun insertResume(user: UUID = local.localUserId()): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', 'PASTE', 'READY', 'Built Kotlin services.')",
            id, user,
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

    private fun insertSet(resumeId: UUID, targetJobId: UUID, user: UUID = local.localUserId()): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.practice_sets (id, user_id, resume_id, target_job_id) VALUES (?, ?, ?, ?)", id, user, resumeId, targetJobId)
        return id
    }

    private fun insertQuestion(setId: UUID, order: Int, user: UUID = local.localUserId()): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
                INSERT INTO ai_interview_app.practice_questions (id, practice_set_id, user_id, origin, order_index, text, rationale, expected_signals)
                VALUES (?, ?, ?, 'AI', ?, ?, 'Reason', '["signal"]'::jsonb)
            """.trimIndent(),
            id, setId, user, order, "Question $order?",
        )
        return id
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    @BeforeEach fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.answer_attempts, ai_interview_app.practice_questions, ai_interview_app.practice_sets, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
        redis.clear()
        Mockito.reset(jobs)
        Mockito.clearInvocations(guard)
    }

    @AfterEach fun clearRequest() = RequestContextHolder.resetRequestAttributes()

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_attempt_test").withUsername("ai_interview").withPassword("ai_interview")
        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private val redis = ConcurrentHashMap<String, String>()
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var guard: RedisRequestGuard
        private lateinit var practice: PracticeService
        private lateinit var controller: AttemptController
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
            jobs = Mockito.spy(BackgroundJobStore(jdbc, mapper))
            guard = Mockito.spy(RedisRequestGuard(inMemoryRedis(), RedisUsageProperties(
                "attempt-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(true, 86_400)
            ), mapper))
            val submissions = JobSubmissionService(
                jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(mapper), local, guard,
                PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), mapper
            )
            practice = PracticeService(jdbc, local, submissions, guard, jobs, mapper, transactions)
            controller = AttemptController(practice, guard)
            val normalizer = ResumeTextNormalizer()
            val persistence = ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher())
            library = ResumeLibraryService(
                jdbc, local, persistence, normalizer, guard, transactions,
                ResumeStorageCleanupService(jdbc, Mockito.mock(ResumeStorageService::class.java)), DeleteImpactService(jdbc), mapper
            )
            targetJobs = TargetJobService(jdbc, local, JobDescriptionPersistenceService(jdbc, normalizer, SectionAwareTextChunker(), ContentHasher()))
            materialization = JobEffectMaterializationService(jdbc, mapper)
        }

        @Suppress("UNCHECKED_CAST")
        private fun inMemoryRedis(): StringRedisTemplate {
            val template = Mockito.mock(StringRedisTemplate::class.java)
            val values = Mockito.mock(ValueOperations::class.java) as ValueOperations<String, String>
            Mockito.`when`(template.opsForValue()).thenReturn(values)
            Mockito.`when`(values.get(anyString())).thenAnswer { redis[it.getArgument(0)] }
            Mockito.`when`(values.setIfAbsent(anyString(), anyString(), any<Duration>()))
                .thenAnswer { redis.putIfAbsent(it.getArgument(0), it.getArgument(1)) == null }
            Mockito.doAnswer { redis[it.getArgument(0)] = it.getArgument(1); null }
                .`when`(values).set(anyString(), anyString(), any<Duration>())
            Mockito.`when`(template.delete(anyString())).thenAnswer { redis.remove(it.getArgument<String>(0)) != null }
            stubScriptExecution(template)
            return template
        }

        private fun stubScriptExecution(template: StringRedisTemplate) {
            val answer = org.mockito.stubbing.Answer<Long> {
                val script = it.getArgument<RedisScript<Long>>(0)
                val key = it.getArgument<List<String>>(1).single()
                val args = it.rawArguments[2] as Array<*>
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
            Mockito.doAnswer(answer).`when`(template).execute(
                any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>()
            )
            Mockito.doAnswer(answer).`when`(template).execute(
                any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>(), any<String>()
            )
            Mockito.doAnswer(answer).`when`(template).execute(
                any<RedisScript<Long>>(), Mockito.anyList<String>(), any<String>(), any<String>(), any<String>()
            )
        }
    }
}
