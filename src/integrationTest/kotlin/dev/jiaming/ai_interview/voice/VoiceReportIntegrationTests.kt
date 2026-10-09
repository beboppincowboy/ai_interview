package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.common.sha256Hex
import dev.jiaming.ai_interview.coach.AiResumeCoachService
import dev.jiaming.ai_interview.document.DocumentChunk
import dev.jiaming.ai_interview.document.DocumentReferenceResolver
import dev.jiaming.ai_interview.document.DocumentSourceType
import dev.jiaming.ai_interview.document.ResolvedDocument
import dev.jiaming.ai_interview.document.ResolvedJobInputs
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobDispatcher
import dev.jiaming.ai_interview.jobs.JobEffectMaterializationService
import dev.jiaming.ai_interview.jobs.JobHandler
import dev.jiaming.ai_interview.jobs.JobHandlerRegistry
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobPayloadDecoder
import dev.jiaming.ai_interview.jobs.JobProcessor
import dev.jiaming.ai_interview.jobs.JobProperties
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.jobs.RequestFingerprintService
import dev.jiaming.ai_interview.practice.AnswerFeedbackResult
import dev.jiaming.ai_interview.resume.ResumeLibraryService
import dev.jiaming.ai_interview.resume.ResumePersistenceService
import dev.jiaming.ai_interview.resume.ResumeStorageCleanupService
import dev.jiaming.ai_interview.resume.ResumeTextNormalizer
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
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
import org.springframework.context.annotation.AnnotationConfigApplicationContext
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.annotation.EnableTransactionManagement
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.support.TransactionOperations
import org.springframework.transaction.support.TransactionCallback
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class VoiceReportIntegrationTests {
    @Test
    fun resumeDeletionAndReportRetryUseCompatibleJobThenOwnerLocks() {
        val session = savedSession(answerCount = 1)
        val lease = UUID.randomUUID()
        jobs.claim(session.reportJobId!!, lease, Duration.ofMinutes(2)).orElseThrow()
        assertThat(jobs.markFailed(session.reportJobId, lease, "SCORER_FAILED", "Retryable report")).isTrue()
        val deletionLockedJob = CountDownLatch(1)
        val deletionTransactions = object : TransactionOperations {
            override fun <T : Any?> execute(action: TransactionCallback<T>): T = transactions.execute { status ->
                // Pause the real Delete just after its first job lock, then let Retry reach that same lock.
                jdbc.queryForList("SELECT id FROM ai_interview_app.background_jobs WHERE id = ? FOR UPDATE", session.reportJobId)
                deletionLockedJob.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
                while (true) {
                    jdbc.execute("SELECT pg_stat_clear_snapshot()")
                    if (jdbc.queryForObject(
                        "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE '%resource_type =%' AND query LIKE '%ORDER BY id FOR UPDATE%'",
                        Int::class.java,
                    ) != 0) break
                    check(System.nanoTime() < deadline) { "Retry did not reach the locked report job" }
                    Thread.sleep(20)
                }
                action.doInTransaction(status)
            }
        }
        val library = ResumeLibraryService(
            jdbc, local, Mockito.mock(ResumePersistenceService::class.java), ResumeTextNormalizer(),
            Mockito.mock(RedisRequestGuard::class.java), deletionTransactions,
            Mockito.mock(ResumeStorageCleanupService::class.java), Mockito.mock(DeleteImpactService::class.java), mapper, BackgroundJobStore(jdbc, mapper)
        )
        val executor = Executors.newFixedThreadPool(2)
        try {
            val delete = executor.submit { library.delete(session.resumeId) }
            assertThat(deletionLockedJob.await(10, TimeUnit.SECONDS)).isTrue()
            val retry = executor.submit<Throwable?> { runCatching { voice.retryReport(session.id) }.exceptionOrNull() }

            delete.get(15, TimeUnit.SECONDS)
            assertThat(retry.get(15, TimeUnit.SECONDS)).isInstanceOfSatisfying(ApiRequestException::class.java) {
                assertThat(it.code()).isEqualTo("VOICE_SESSION_NOT_FOUND")
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.voice_sessions", Int::class.java)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.background_jobs", Int::class.java)).isZero()
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun aClaimedJobCannotScoreAnotherOwnersSavedSession() {
        val session = savedSession(answerCount = 1)
        val otherOwner = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", otherOwner, "$otherOwner@test.invalid")
        val foreignJob = jobs.createIfAbsent(
            otherOwner, JobType.VOICE_REPORT, VoiceReportPayload.RESOURCE, session.id,
            mapper.valueToTree(VoiceReportPayload(session.id, session.resumeId, session.targetJobId)), null, 3,
        ).orElseThrow()
        val lease = UUID.randomUUID()
        val claimed = jobs.claim(foreignJob.id, lease, Duration.ofMinutes(2)).orElseThrow()
        val coach = Mockito.mock(AiResumeCoachService::class.java)
        val resolver = Mockito.mock(DocumentReferenceResolver::class.java)

        assertThatThrownBy { processor(coach, resolver).process(claimed, lease) }
            .isInstanceOf(IllegalStateException::class.java).hasMessageContaining("job owner")

        Mockito.verifyNoInteractions(coach, resolver)
        assertThat(voice.get(session.id).report).isNull()
        assertThat(voice.get(session.id).transcript).isEqualTo(session.transcript)
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.background_job_effects", Int::class.java)).isZero()
    }

    @Test
    fun workerRestartReusesDurableAnswerCheckpointAndMaterializesOneGroundedPartialReport() {
        val session = savedSession(answerCount = 4)
        val scoringCalls = mutableListOf<String>()
        val coach = Mockito.mock(AiResumeCoachService::class.java)
        val resolver = resolverFor(session)
        val processor = processor(coach, resolver)
        val submitted = requireNotNull(jobs.findById(session.reportJobId!!).orElse(null))
        val firstLease = UUID.randomUUID()
        val firstAttempt = jobs.claim(submitted.id, firstLease, Duration.ofMinutes(2)).orElseThrow()
        Mockito.`when`(coach.scorePracticeAnswer(any())).thenAnswer { invocation ->
            val input = invocation.getArgument<dev.jiaming.ai_interview.coach.CoachFeedbackInput>(0)
            scoringCalls += input.answerText()!!
            if (input.answerText() == "Answer 2" && scoringCalls.count { it == "Answer 2" } == 1) error("temporary scorer failure")
            when (input.answerText()) {
                "Answer 1" -> feedback(20)
                "Answer 2" -> feedback(30)
                "Answer 3" -> feedback(30)
                else -> feedback(80)
            }
        }

        assertThatThrownBy { processor.process(firstAttempt, firstLease) }.hasMessageContaining("temporary scorer failure")
        assertThat(jobs.markRetrying(firstAttempt.id, firstLease, "TEMPORARY", "temporary scorer failure", Duration.ZERO)).isTrue()
        val secondLease = UUID.randomUUID()
        val secondAttempt = jobs.claim(submitted.id, secondLease, Duration.ofMinutes(2)).orElseThrow()

        val finalResult = requireNotNull(processor.process(secondAttempt, secondLease))
        assertThat(jobs.markSucceeded(secondAttempt.id, secondLease, finalResult)).isTrue()

        val report = reportNode(voice.get(session.id))
        assertThat(scoringCalls).containsExactly("Answer 1", "Answer 2", "Answer 2", "Answer 3", "Answer 4")
        assertThat(report.path("selectedCount").asInt()).isEqualTo(6)
        assertThat(report.path("answeredCount").asInt()).isEqualTo(4)
        assertThat(report.path("overallScore").asInt()).isEqualTo(40)
        assertThat(ids(report.path("weakestQuestionIds"))).containsExactlyElementsOf(session.questions.take(3).map { it.id })
        assertThat(ids(report.path("unansweredQuestionIds"))).containsExactlyElementsOf(session.questions.drop(4).map { it.id })
        assertThat(report.path("answers")[2].path("incomplete").asBoolean()).isTrue()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.answer_attempts", Int::class.java)).isZero()
        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.background_job_effects WHERE effect_type = 'VOICE_REPORT'", Int::class.java)).isEqualTo(1)
        assertThat(jobs.findById(submitted.id).orElseThrow().resultPayload).isEqualTo(finalResult)
        Mockito.verify(resolver, Mockito.times(2)).resolveStrict(eq(submitted.requireUserId()), eq(session.resumeId), eq(session.targetJobId))
    }

    @Test
    fun explicitRetryTransfersOnlyValidatedCheckpointsAndPreservesSaveIdentity() {
        val session = savedSession(answerCount = 2)
        val calls = mutableListOf<String>()
        val coach = Mockito.mock(AiResumeCoachService::class.java)
        Mockito.`when`(coach.scorePracticeAnswer(any())).thenAnswer { invocation ->
            val input = invocation.getArgument<dev.jiaming.ai_interview.coach.CoachFeedbackInput>(0)
            calls += input.answerText()!!
            if (input.answerText() == "Answer 2" && calls.count { it == "Answer 2" } == 1) error("terminal scorer failure")
            feedback(if (input.answerText() == "Answer 1") 75 else 45)
        }
        val processor = processor(coach, resolverFor(session))
        val originalSubmission = session.submissionJobId
        val failedJobId = session.reportJobId!!
        val firstLease = UUID.randomUUID()
        val firstAttempt = jobs.claim(failedJobId, firstLease, Duration.ofMinutes(2)).orElseThrow()

        assertThatThrownBy { processor.process(firstAttempt, firstLease) }.hasMessageContaining("terminal scorer failure")
        assertThat(jobs.markFailed(firstAttempt.id, firstLease, "SCORER_FAILED", "terminal scorer failure")).isTrue()
        jdbc.update(
            "UPDATE ai_interview_app.background_jobs SET result_payload = result_payload || jsonb_build_object(?, 'malformed') WHERE id = ?",
            "answer:${session.questions[1].id}", failedJobId,
        )
        val beforeRetry = voice.get(session.id)
        val transcriptBeforeRetry = beforeRetry.transcript

        voice.retryReport(session.id)

        val afterRetry = voice.get(session.id)
        val replacement = jobs.findById(afterRetry.reportJobId!!).orElseThrow()
        assertThat(afterRetry.submissionJobId).isEqualTo(originalSubmission)
        assertThat(afterRetry.reportJobId).isNotEqualTo(failedJobId)
        assertThat(afterRetry.transcript).isEqualTo(transcriptBeforeRetry)
        assertThat(replacement.status).isEqualTo(JobStatus.QUEUED)
        assertThat(replacement.errorCode).isNull()
        assertThat(replacement.lastError).isNull()
        assertThat(replacement.resultPayload!!.fieldNames().asSequence().toList()).containsExactly("answer:${session.questions[0].id}")
        assertThat(replacement.requestPayload.toString()).doesNotContain("Answer 1", "Answer 2", "signal-1")

        val retryLease = UUID.randomUUID()
        val claimed = jobs.claim(replacement.id, retryLease, Duration.ofMinutes(2)).orElseThrow()
        val result = requireNotNull(processor.process(claimed, retryLease))
        assertThat(jobs.markSucceeded(claimed.id, retryLease, result)).isTrue()

        assertThat(calls).containsExactly("Answer 1", "Answer 2", "Answer 2")
        assertThat(reportNode(voice.get(session.id)).path("overallScore").asInt()).isEqualTo(60)
        assertThat(voice.get(session.id).submissionJobId).isEqualTo(originalSubmission)
    }

    @Test
    fun concurrentExplicitRetriesCreateOnlyOneCurrentReplacementJob() {
        val session = savedSession(answerCount = 2)
        val coach = Mockito.mock(AiResumeCoachService::class.java)
        Mockito.`when`(coach.scorePracticeAnswer(any())).thenAnswer { invocation ->
            val input = invocation.getArgument<dev.jiaming.ai_interview.coach.CoachFeedbackInput>(0)
            if (input.answerText() == "Answer 2") error("terminal scorer failure")
            feedback(75)
        }
        val processor = processor(coach, resolverFor(session))
        val originalSubmission = session.submissionJobId
        val failedJobId = session.reportJobId!!
        val lease = UUID.randomUUID()
        val claimed = jobs.claim(failedJobId, lease, Duration.ofMinutes(2)).orElseThrow()
        assertThatThrownBy { processor.process(claimed, lease) }.hasMessageContaining("terminal scorer failure")
        assertThat(jobs.markFailed(claimed.id, lease, "SCORER_FAILED", "terminal scorer failure")).isTrue()

        val transcriptBeforeRetry = voice.get(session.id).transcript
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        try {
            val retries = List(2) {
                executor.submit<Result<VoiceSessionView>> {
                    ready.countDown()
                    check(start.await(10, TimeUnit.SECONDS))
                    runCatching { voice.retryReport(session.id) }
                }
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue()
            start.countDown()
            val outcomes = retries.map { it.get(10, TimeUnit.SECONDS) }

            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            assertThat(outcomes.count { it.isFailure }).isEqualTo(1)
            val current = voice.get(session.id)
            val replacement = jobs.findById(current.reportJobId!!).orElseThrow()
            assertThat(current.reportJobId).isNotEqualTo(failedJobId)
            assertThat(current.submissionJobId).isEqualTo(originalSubmission)
            assertThat(current.transcript).isEqualTo(transcriptBeforeRetry)
            assertThat(replacement.status).isEqualTo(JobStatus.QUEUED)
            assertThat(replacement.resultPayload!!.fieldNames().asSequence().toList())
                .containsExactly("answer:${session.questions[0].id}")
            assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM ai_interview_app.background_jobs WHERE job_type = 'VOICE_REPORT' AND resource_id = ?",
                Int::class.java,
                session.id,
            )).isEqualTo(2)
        } finally {
            start.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun deletingWhileScoringFencesTheLateCheckpointAndCannotResurrectTheSession() {
        val session = savedSession(answerCount = 1)
        val enteredScorer = CountDownLatch(1)
        val allowScorerToReturn = CountDownLatch(1)
        val coach = Mockito.mock(AiResumeCoachService::class.java)
        Mockito.`when`(coach.scorePracticeAnswer(any())).thenAnswer {
            enteredScorer.countDown()
            check(allowScorerToReturn.await(10, TimeUnit.SECONDS))
            feedback(70)
        }
        val processor = processor(coach, resolverFor(session))
        val lease = UUID.randomUUID()
        val job = jobs.claim(session.reportJobId!!, lease, Duration.ofMinutes(2)).orElseThrow()
        val executor = Executors.newSingleThreadExecutor()
        try {
            val worker = executor.submit<JsonNode> { requireNotNull(processor.process(job, lease)) }
            assertThat(enteredScorer.await(10, TimeUnit.SECONDS)).isTrue()

            voice.delete(session.id)
            allowScorerToReturn.countDown()

            assertThatThrownBy { worker.get(10, TimeUnit.SECONDS) }.hasCauseInstanceOf(dev.jiaming.ai_interview.jobs.JobLeaseLostException::class.java)
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, session.id)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.background_jobs WHERE id = ?", Int::class.java, job.id)).isZero()
            assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.background_job_effects WHERE job_id = ?", Int::class.java, job.id)).isZero()
        } finally {
            allowScorerToReturn.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun reportEffectIsIdempotentAndAStaleLeaseCannotCreateOne() {
        val session = savedSession(answerCount = 1)
        val coach = coach { feedback(70) }
        val processor = processor(coach, resolverFor(session))
        val lease = UUID.randomUUID()
        val job = jobs.claim(session.reportJobId!!, lease, Duration.ofMinutes(2)).orElseThrow()
        val result = requireNotNull(processor.process(job, lease))
        val report = mapper.treeToValue(result, VoiceSessionReport::class.java)
        val materialize = JobEffectMaterializationService::class.java.methods.single { it.name == "materializeVoiceReport" }

        materialize.invoke(materialization, job, lease, session.id, report.copy(overallScore = 0))
        assertThatThrownBy { materialize.invoke(materialization, job, UUID.randomUUID(), session.id, report.copy(overallScore = 0)) }
            .hasRootCauseInstanceOf(dev.jiaming.ai_interview.jobs.JobLeaseLostException::class.java)

        assertThat(jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.background_job_effects WHERE job_id = ? AND effect_type = 'VOICE_REPORT'", Int::class.java, job.id)).isEqualTo(1)
        assertThat(reportNode(voice.get(session.id))).isEqualTo(result)
    }

    @Test
    fun expiredVoiceJobPayloadCleanupRetainsStableReferencesAndRunsOnce() {
        val session = savedSession(answerCount = 1)
        val jobId = session.reportJobId!!
        jdbc.update(
            "UPDATE ai_interview_app.background_jobs SET status = 'FAILED', completed_at = now() - interval '8 days', request_payload = request_payload || '{\"candidateText\":\"PII_MARKER\"}'::jsonb, result_payload = '{\"checkpoint\":\"PII_MARKER\"}'::jsonb WHERE id = ?",
            jobId,
        )

        assertThat(jobs.clearExpiredPayloads(7)).isEqualTo(1)

        val cleaned = jobs.findById(jobId).orElseThrow()
        assertThat(cleaned.requestPayload!!.fieldNames().asSequence().toList())
            .containsExactlyInAnyOrder("payloadVersion", "voiceSessionId", "resumeId", "targetJobId")
        assertThat(cleaned.requestPayload.toString()).doesNotContain("PII_MARKER")
        assertThat(cleaned.resultPayload).isNull()
        assertThat(JobInputRefs.from(cleaned)).isEqualTo(
            JobInputRefs(session.resumeId, session.targetJobId, null, null, session.id),
        )
        assertThat(jobs.clearExpiredPayloads(7)).isZero()
    }

    private fun savedSession(answerCount: Int): VoiceSessionView {
        val user = local.localUserId()
        val resumeId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.resumes (id, user_id, name, source, processing_status, normalized_text) VALUES (?, ?, 'Backend', 'PASTE', 'READY', 'Built Kotlin services.')",
            resumeId, user,
        )
        val targetJobId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO ai_interview_app.job_descriptions (id, user_id, name, title, raw_text, normalized_text, content_hash) VALUES (?, ?, 'Platform', 'Platform', 'requirements', 'requirements', ?)",
            targetJobId, user, "hash-$targetJobId",
        )
        val practiceSetId = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.practice_sets (id, user_id, resume_id, target_job_id) VALUES (?, ?, ?, ?)", practiceSetId, user, resumeId, targetJobId)
        repeat(6) { index ->
            jdbc.update(
                "INSERT INTO ai_interview_app.practice_questions (practice_set_id, user_id, origin, order_index, text, rationale, category, expected_signals) VALUES (?, ?, 'AI', ?, ?, 'Reason', ?, ?::jsonb)",
                practiceSetId, user, index + 1, "Canonical question ${index + 1}?", "Category ${index + 1}", "[\"signal-${index + 1}\"]",
            )
        }
        val draft = voice.create(practiceSetId)
        val transcript = VoiceTranscript(draft.questions.take(answerCount).mapIndexed { index, question ->
            VoiceAnswer(question.id, "Interviewer asked", "Answer ${index + 1}", index == 2)
        })
        return voice.save(draft.id, transcript).session
    }

    private fun coach(score: (dev.jiaming.ai_interview.coach.CoachFeedbackInput) -> AnswerFeedbackResult): AiResumeCoachService =
        Mockito.mock(AiResumeCoachService::class.java).also { service ->
            Mockito.`when`(service.scorePracticeAnswer(any())).thenAnswer { invocation -> score(invocation.getArgument(0)) }
        }

    private fun resolverFor(session: VoiceSessionView): DocumentReferenceResolver {
        val resolver = Mockito.mock(DocumentReferenceResolver::class.java)
        val resume = ResolvedDocument(DocumentSourceType.RESUME, session.resumeId, "resume-hash", "resume evidence", listOf(DocumentChunk(0, "Projects", "Kotlin services", "resume:projects:0")))
        val description = ResolvedDocument(DocumentSourceType.JOB_DESCRIPTION, session.targetJobId, "job-hash", "target requirements", emptyList())
        Mockito.`when`(resolver.resolveStrict(eq(local.localUserId()), eq(session.resumeId), eq(session.targetJobId)))
            .thenReturn(ResolvedJobInputs(resume, Optional.of(description)))
        return resolver
    }

    private fun processor(coach: AiResumeCoachService, resolver: DocumentReferenceResolver): JobProcessor {
        val reportHandler = VoiceReportJobHandler(coach, resolver, voice, mapper)
        val otherHandlers = JobType.entries.filterNot { it == JobType.VOICE_REPORT }.map { type ->
            object : JobHandler<Any> {
                override fun type() = type
                override fun payloadType() = Any::class.java
                override fun handle(payload: Any, context: dev.jiaming.ai_interview.jobs.JobExecutionContext): JsonNode? = null
            }
        }
        return JobProcessor(
            JobPayloadDecoder(mapper), JobHandlerRegistry(otherHandlers + reportHandler), jobs, materialization,
            JobMetrics(SimpleMeterRegistry()), mapper,
        )
    }

    private fun reportNode(session: VoiceSessionView): JsonNode = mapper.valueToTree(session.report)

    private fun ids(node: JsonNode) = node.map { UUID.fromString(it.asText()) }

    private fun feedback(score: Int) = AnswerFeedbackResult(score, "Summary $score", "Next practice step", listOf("Strength"), listOf("Gap"), listOf("Context", "Action"), "Follow-up")

    @BeforeEach
    fun resetTables() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.voice_sessions, ai_interview_app.answer_attempts, ai_interview_app.practice_questions, ai_interview_app.practice_sets, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
    }

    companion object {
        @Container
        @JvmField
        val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_voice_report_test").withUsername("ai_interview").withPassword("ai_interview")

        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val properties = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        internal lateinit var dataSource: DriverManagerDataSource
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var jobs: BackgroundJobStore
        private lateinit var voice: VoiceSessionService
        private lateinit var materialization: JobEffectMaterializationService
        private lateinit var context: AnnotationConfigApplicationContext

        @BeforeAll
        @JvmStatic
        fun startDatabase() {
            dataSource = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(dataSource)
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(dataSource))
            local = LocalUserService(jdbc)
            jobs = BackgroundJobStore(jdbc, mapper)
            val guard = RedisRequestGuard(
                Mockito.mock(StringRedisTemplate::class.java),
                RedisUsageProperties("voice-report-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)),
                mapper,
            )
            val submission = JobSubmissionService(
                jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(mapper), local, guard,
                properties, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), mapper,
            )
            voice = VoiceSessionService(jdbc, local, submission, guard, mapper, transactions)
            context = AnnotationConfigApplicationContext(VoiceReportTestConfiguration::class.java)
            materialization = context.getBean(JobEffectMaterializationService::class.java)
            assertThat(JobEffectMaterializationService::class.java.isAssignableFrom(materialization.javaClass)).isTrue()
        }

        @AfterAll
        @JvmStatic
        fun closeContext() {
            if (::context.isInitialized) context.close()
        }
    }
}

@Configuration(proxyBeanMethods = true)
@EnableTransactionManagement
open class VoiceReportTestConfiguration {
    @Bean
    open fun dataSource() = VoiceReportIntegrationTests.run { dataSource }

    @Bean
    open fun jdbcTemplate(source: javax.sql.DataSource) = JdbcTemplate(source)

    @Bean
    open fun transactionManager(source: javax.sql.DataSource): PlatformTransactionManager = DataSourceTransactionManager(source)

    @Bean
    open fun objectMapper() = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()

    @Bean
    open fun jobEffectMaterializationService(jdbc: JdbcTemplate, mapper: ObjectMapper) = JobEffectMaterializationService(jdbc, mapper)
}
