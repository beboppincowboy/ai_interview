package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import com.sun.net.httpserver.HttpServer
import dev.jiaming.ai_interview.common.ApiErrorResponse
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ContentHasher
import dev.jiaming.ai_interview.common.DeleteImpactService
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RedisUsageProperties
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.interview.JobDescriptionPersistenceService
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobDispatcher
import dev.jiaming.ai_interview.jobs.JobEffectType
import dev.jiaming.ai_interview.jobs.JobInputRefs
import dev.jiaming.ai_interview.jobs.JobMetrics
import dev.jiaming.ai_interview.jobs.JobPayloadDecoder
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
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.net.InetSocketAddress
import java.net.URI
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Testcontainers
class VoiceSessionIntegrationTests {
    @Test
    fun twoRunsOnOneSetSnapshotTheFirstSixAiQuestionsIndependentlyAndLeaveTextPracticeUnchanged() {
        val set = readySet(aiQuestions = 8, userQuestions = 1)
        val first = voice.create(set.id)
        jdbc.update("UPDATE ai_interview_app.practice_questions SET text = 'Edited question?' WHERE id = ?", set.aiQuestions.first())
        val second = voice.create(set.id)

        assertThat(first.id).isNotEqualTo(second.id)
        assertThat(first.status).isEqualTo(VoiceSessionStatus.DRAFT)
        assertThat(first.practiceSetId).isEqualTo(set.id)
        assertThat(first.resumeId to first.targetJobId).isEqualTo(set.resumeId to set.targetJobId)
        assertThat(first.questions.map { it.id }).containsExactlyElementsOf(set.aiQuestions.take(6))
        assertThat(first.questions.map { it.text }).containsExactly("Question 1?", "Question 2?", "Question 3?", "Question 4?", "Question 5?", "Question 6?")
        assertThat(first.questions.first().category).isEqualTo("Depth")
        assertThat(first.questions.first().expectedSignals).containsExactly("signal")
        assertThat(voice.get(first.id)).isEqualTo(first)
        assertThat(second.questions.first().text).isEqualTo("Edited question?")
        assertThat(first.transcript).isNull()
        assertThat(first.submissionJobId).isNull()
        assertThat(Duration.between(first.createdAt, first.runDeadline)).isEqualTo(Duration.ofMinutes(20))
        assertThat(Duration.between(first.createdAt, first.draftExpiresAt)).isEqualTo(Duration.ofHours(24))
        assertThat(voice.create(readySet(aiQuestions = 3, userQuestions = 2).id).questions).hasSize(3)

        assertThat(count("practice_sets")).isEqualTo(2)
        assertThat(count("practice_questions")).isEqualTo(14)
        assertThat(count("answer_attempts")).isZero()
        assertThat(count("background_jobs")).isZero()
        Mockito.verify(guard, Mockito.never()).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
    }

    @Test
    fun tokenMintReservationsAreOwnedCanonicalBoundedAndAtomicUnderConcurrency() {
        val session = voice.create(readySet().id)
        val question = session.questions.first()

        expectCode("VOICE_QUESTION_NOT_FOUND") { voice.reserveToken(session.id, UUID.randomUUID()) }
        assertThat(jdbc.queryForObject("SELECT token_mint_count FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, session.id))
            .isZero()

        val results = concurrently(16) { runCatching { voice.reserveToken(session.id, question.id) } }
        val accepted = results.mapNotNull { it.getOrNull() }
        val rejected = results.mapNotNull { it.exceptionOrNull() as? ApiRequestException }
        assertThat(accepted).hasSize(12).allSatisfy {
            assertThat(it.question).isEqualTo(question)
            assertThat(it.runDeadline).isEqualTo(session.runDeadline)
        }
        assertThat(rejected).hasSize(4).allSatisfy { assertThat(it.code()).isEqualTo("VOICE_TOKEN_BUDGET_EXHAUSTED") }
        assertThat(jdbc.queryForObject("SELECT token_mint_count FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, session.id))
            .isEqualTo(12)

        val expired = voice.create(readySet().id)
        age(expired.id, Duration.ofMinutes(21))
        expectCode("VOICE_RUN_EXPIRED") { voice.reserveToken(expired.id, expired.questions.first().id) }
        assertThat(jdbc.queryForObject("SELECT token_mint_count FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, expired.id))
            .isZero()

        val saved = voice.create(readySet().id)
        voice.save(saved.id, transcript(saved, "A reviewed answer"))
        expectCode("VOICE_SESSION_NOT_DRAFT") { voice.reserveToken(saved.id, saved.questions.first().id) }
        assertThat(jdbc.queryForObject("SELECT token_mint_count FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, saved.id))
            .isZero()

        val foreignOwner = insertUser()
        val foreignSession = insertSession(readySet(user = foreignOwner), foreignOwner)
        expectCode("VOICE_SESSION_NOT_FOUND") { voice.reserveToken(foreignSession, UUID.randomUUID()) }
        assertThat(jdbc.queryForObject("SELECT token_mint_count FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, foreignSession))
            .isZero()
    }

    @Test
    fun providerFailureAfterTheCommittedReservationConsumesOneMintSlot() {
        val session = voice.create(readySet().id)
        val properties = VoiceProperties(true, null, null, null, "server-only-key")
        val providerCalls = java.util.concurrent.atomic.AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                providerCalls.incrementAndGet()
                exchange.requestBody.close()
                val body = "provider-secret-marker".toByteArray()
                exchange.sendResponseHeaders(503, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            }
            start()
        }
        try {
            val tokenClient = GeminiLiveTokenClient(mapper, properties, URI("http://127.0.0.1:${server.address.port}"))
            val controller = VoiceController(voice, tokenClient, properties)
            val response = controller.mint(session.id, VoiceTokenRequest(session.questions.first().id))

            assertThat(response.statusCode.value()).isEqualTo(502)
            assertThat(response.headers.getFirst("Cache-Control")).contains("no-store")
            assertThat(response.body).isInstanceOf(ApiErrorResponse::class.java)
            assertThat((response.body as ApiErrorResponse).code).isEqualTo("VOICE_TOKEN_UNAVAILABLE")
            assertThat(response.body.toString()).doesNotContain("provider-secret-marker", "server-only-key")
            assertThat(providerCalls.get()).isEqualTo(1)
            assertThat(jdbc.queryForObject("SELECT token_mint_count FROM ai_interview_app.voice_sessions WHERE id = ?", Int::class.java, session.id))
                .isEqualTo(1)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun notReadyUnknownAndForeignSetsStartNothingAndUnknownOrForeignSessionsAreNotFound() {
        val failedWithOwnQuestion = insertSet(insertResume(), insertTargetJob())
        insertQuestion(failedWithOwnQuestion, 1, origin = "USER")
        val other = insertUser()
        val foreignSet = readySet(user = other)

        expectCode("PRACTICE_SET_NOT_READY") { voice.create(failedWithOwnQuestion) }
        expectCode("PRACTICE_SET_NOT_FOUND") { voice.create(UUID.randomUUID()) }
        expectCode("PRACTICE_SET_NOT_FOUND") { voice.create(foreignSet.id) }
        assertThat(count("voice_sessions")).isZero()

        val foreignSession = insertSession(foreignSet, other)
        for (sessionId in listOf(foreignSession, UUID.randomUUID())) {
            val reviewed = VoiceTranscript(listOf(VoiceAnswer(foreignSet.aiQuestions.first(), "Asked", "Answered", false)))
            expectCode("VOICE_SESSION_NOT_FOUND") { voice.get(sessionId) }
            expectCode("VOICE_SESSION_NOT_FOUND") { voice.save(sessionId, reviewed) }
            expectCode("VOICE_SESSION_NOT_FOUND") { voice.discard(sessionId) }
            expectCode("VOICE_SESSION_NOT_FOUND") { voice.delete(sessionId) }
        }
        assertThat(count("voice_sessions")).isEqualTo(1)
        assertThat(count("background_jobs")).isZero()
    }

    @Test
    fun saveKeepsTheReviewedTranscriptInCanonicalOrderAndStartsItsReportJobInTheSameTransaction() {
        val set = readySet(aiQuestions = 3)
        val session = voice.create(set.id)
        val (q1, q2) = session.questions
        val reviewed = VoiceTranscript(listOf(
            VoiceAnswer(q2.id, "  Tell me about ${q2.text}  ", "  Second answer, cut off  ", true),
            VoiceAnswer(q1.id, "Tell me about ${q1.text}", "First answer", false),
        ))

        val saved = voice.save(session.id, reviewed)

        assertThat(saved.replayed).isFalse()
        val view = saved.session
        assertThat(view.status).isEqualTo(VoiceSessionStatus.SAVED)
        assertThat(view.savedAt).isNotNull()
        assertThat(view.transcript!!.answers).containsExactly(
            VoiceAnswer(q1.id, "Tell me about ${q1.text}", "First answer", false),
            VoiceAnswer(q2.id, "Tell me about ${q2.text}", "Second answer, cut off", true),
        )
        assertThat(view.reportJobId).isEqualTo(view.submissionJobId)
        assertThat(voice.get(session.id)).isEqualTo(view)

        val job = jobs.findById(view.submissionJobId!!).orElseThrow()
        assertThat(job.jobType).isEqualTo(JobType.VOICE_REPORT)
        assertThat(job.status).isEqualTo(JobStatus.QUEUED)
        assertThat(job.resourceType to job.resourceId).isEqualTo(VoiceReportPayload.RESOURCE to session.id)
        assertThat(JobInputRefs.from(job)).isEqualTo(JobInputRefs(set.resumeId, set.targetJobId, null, null, session.id))
        assertThat(JobPayloadDecoder(mapper).decode(job, VoiceReportPayload::class.java))
            .isEqualTo(VoiceReportPayload(session.id, set.resumeId, set.targetJobId))
        assertThat(job.requestPayload.toString()).doesNotContain("First answer", "Second answer")
        Mockito.verify(guard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        assertThat(count("answer_attempts")).isZero()

        // Every registered effect type, VOICE_REPORT included, passes the effect constraint.
        JobEffectType.entries.forEach {
            jdbc.update("INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id) VALUES (?, ?, ?)", job.id, it.name, session.id)
        }
    }

    @Test
    fun aFailureAfterTheJobInsertRollsBackTheSaveAndKeepsTheDraft() {
        val session = voice.create(readySet().id)
        val reviewed = transcript(session, "An answer")
        Mockito.doAnswer { it.callRealMethod(); throw IllegalStateException("connection lost before commit") }
            .`when`(jobs).createIfAbsentWithInitialResult(any(), any(), any(), any(), any(), any(), Mockito.anyInt(), anyOrNull())

        assertThatThrownBy { voice.save(session.id, reviewed) }.hasMessageContaining("connection lost before commit")
        assertThat(voice.get(session.id)).isEqualTo(session)
        assertThat(count("background_jobs")).isZero()

        Mockito.reset(jobs)
        assertThat(voice.save(session.id, reviewed).replayed).isFalse()
        assertThat(count("background_jobs")).isEqualTo(1)
    }

    @Test
    fun concurrentIdenticalSavesAndALaterReplayKeepOneTranscriptAndTheOriginalSubmission() {
        val session = voice.create(readySet().id)
        val reviewed = transcript(session, "First answer", "Second answer")

        val results = concurrently(2) { voice.save(session.id, reviewed) }

        assertThat(results.map { it.replayed }).containsExactlyInAnyOrder(false, true)
        val jobId = results.map { it.session.submissionJobId }.distinct().single()!!
        assertThat(count("background_jobs")).isEqualTo(1)
        jdbc.update("UPDATE ai_interview_app.background_jobs SET status = 'SUCCEEDED', stage = 'COMPLETED', completed_at = now() WHERE id = ?", jobId)

        // Order and surrounding whitespace are normalized away, so this is the same reviewed transcript.
        val replay = voice.save(session.id, VoiceTranscript(reviewed.answers.reversed().map { it.copy(answerText = " ${it.answerText} ") }))
        assertThat(replay.replayed).isTrue()
        assertThat(replay.session.submissionJobId).isEqualTo(jobId)
        assertThat(replay.session.transcript).isEqualTo(reviewed)
        assertThat(count("background_jobs")).isEqualTo(1)
        Mockito.verify(guard, Mockito.times(1)).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)

        expectCode("VOICE_SESSION_ALREADY_SAVED") { voice.save(session.id, transcript(session, "First answer", "A changed answer")) }
        assertThat(voice.get(session.id).transcript).isEqualTo(reviewed)
        assertThat(count("background_jobs")).isEqualTo(1)
    }

    @Test
    fun aSaveAfterTheRunDeadlineCommitsButASaveAfterDraftExpiryIsRejected() {
        val late = voice.create(readySet().id)
        age(late.id, Duration.ofMinutes(21))
        assertThat(voice.save(late.id, transcript(late, "Finished after the run ended")).session.status).isEqualTo(VoiceSessionStatus.SAVED)

        val expired = voice.create(readySet().id)
        age(expired.id, Duration.ofHours(24))
        assertThat(voice.get(expired.id).status).isEqualTo(VoiceSessionStatus.EXPIRED)
        expectCode("VOICE_SESSION_EXPIRED") { voice.save(expired.id, transcript(expired, "Too late")) }
        assertThat(voice.get(expired.id).transcript).isNull()
        assertThat(count("background_jobs")).isEqualTo(1)

        // A committed Save outlives the draft window and still replays.
        age(late.id, Duration.ofHours(25))
        val replay = voice.save(late.id, transcript(late, "Finished after the run ended"))
        assertThat(replay.replayed).isTrue()
        assertThat(replay.session.status).isEqualTo(VoiceSessionStatus.SAVED)
    }

    @Test
    fun limitsAndForeignOrDuplicateQuestionsFailWithoutTruncationOrPartialWrites() {
        val set = readySet(aiQuestions = 6, userQuestions = 1)
        val session = voice.create(set.id)
        val first = session.questions.first().id
        fun answer(questionId: UUID, text: String, asked: String = "Asked") = VoiceAnswer(questionId, asked, text, false)

        expectCode("ANSWER_TOO_LONG") { voice.save(session.id, VoiceTranscript(listOf(answer(first, "a".repeat(4_001))))) }
        expectCode("ANSWER_EMPTY") { voice.save(session.id, VoiceTranscript(listOf(answer(first, "   ")))) }
        expectCode("ANSWER_EMPTY") { voice.save(session.id, VoiceTranscript(emptyList())) }
        expectCode("INVALID_REQUEST") { voice.save(session.id, VoiceTranscript(listOf(answer(first, "One"), answer(first, "Two")))) }
        expectCode("INVALID_REQUEST") { voice.save(session.id, VoiceTranscript(listOf(answer(set.userQuestions.single(), "Mine")))) }
        expectCode("INVALID_REQUEST") { voice.save(session.id, VoiceTranscript(listOf(answer(UUID.randomUUID(), "Unknown")))) }
        expectCode("TRANSCRIPT_TOO_LARGE") { voice.save(session.id, VoiceTranscript(listOf(answer(first, "Short", asked = "x".repeat(70_000))))) }
        // Six answers within 4,000 characters each, but 3-byte characters put the transcript over 64 KiB.
        expectCode("TRANSCRIPT_TOO_LARGE") { voice.save(session.id, VoiceTranscript(session.questions.map { answer(it.id, "€".repeat(4_000)) })) }
        assertThat(voice.get(session.id)).isEqualTo(session)
        assertThat(count("background_jobs")).isZero()

        val saved = voice.save(session.id, VoiceTranscript(listOf(answer(first, "a".repeat(4_000)))))
        assertThat(saved.session.transcript!!.answers.single().answerText).hasSize(4_000)
    }

    @Test
    fun discardRemovesOnlyDraftsAndCleanupRemovesOnlyExpiredDrafts() {
        val set = readySet()
        val discarded = voice.create(set.id)
        voice.discard(discarded.id)
        expectCode("VOICE_SESSION_NOT_FOUND") { voice.get(discarded.id) }

        val saved = voice.create(set.id)
        voice.save(saved.id, transcript(saved, "Kept"))
        expectCode("VOICE_SESSION_ALREADY_SAVED") { voice.discard(saved.id) }
        assertThat(voice.get(saved.id).status).isEqualTo(VoiceSessionStatus.SAVED)

        val expired = voice.create(set.id)
        val fresh = voice.create(set.id)
        age(expired.id, Duration.ofHours(25))
        age(saved.id, Duration.ofHours(25))
        voice.removeExpiredDrafts()

        expectCode("VOICE_SESSION_NOT_FOUND") { voice.get(expired.id) }
        assertThat(voice.get(fresh.id).status).isEqualTo(VoiceSessionStatus.DRAFT)
        assertThat(voice.get(saved.id).transcript!!.answers.single().answerText).isEqualTo("Kept")
        assertThat(count("background_jobs")).isEqualTo(1)
    }

    @Test
    fun aDiscardWaitingOnASaveCannotEraseTheCommittedTranscript() {
        val session = voice.create(readySet().id)

        val (save, discard) = raceWithPausedSave(session.id, transcript(session, "Committed")) { voice.discard(session.id) }

        assertThat(save.getOrThrow().replayed).isFalse()
        assertThat((discard.exceptionOrNull() as ApiRequestException).code()).isEqualTo("VOICE_SESSION_ALREADY_SAVED")
        assertThat(voice.get(session.id).transcript!!.answers.single().answerText).isEqualTo("Committed")
        assertThat(count("background_jobs")).isEqualTo(1)
    }

    @Test
    fun aSaveCommittedWhileSessionDeleteWaitsIsCaughtByTheSecondJobScan() {
        val session = voice.create(readySet().id)

        val (save, delete) = raceWithPausedSave(session.id, transcript(session, "Committed")) { voice.delete(session.id) }

        assertThat(save.isSuccess).isTrue()
        assertThat(delete.isSuccess).isTrue()
        assertThat(count("voice_sessions")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    @Test
    fun aSaveCommittedWhileTargetJobDeleteWaitsLeavesNoReportJobBehind() {
        val set = readySet()
        val session = voice.create(set.id)

        val (save, delete) = raceWithPausedSave(session.id, transcript(session, "Committed")) { transactions.execute { targetJobs.delete(set.targetJobId) } }

        assertThat(save.isSuccess).isTrue()
        assertThat(delete.isSuccess).isTrue()
        assertThat(count("voice_sessions")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    @Test
    fun deletingASavedSessionRemovesItsJobsCheckpointsAndEffectsOnly() {
        val set = readySet()
        val saved = voice.create(set.id)
        val jobId = voice.save(saved.id, transcript(saved, "Answer")).session.submissionJobId!!
        jdbc.update("UPDATE ai_interview_app.background_jobs SET result_payload = '{\"checkpoints\":[]}'::jsonb WHERE id = ?", jobId)
        jdbc.update("INSERT INTO ai_interview_app.background_job_effects (job_id, effect_type, resource_id) VALUES (?, 'VOICE_REPORT', ?)", jobId, saved.id)
        val other = voice.create(set.id)
        val otherJob = voice.save(other.id, transcript(other, "Other answer")).session.submissionJobId!!

        voice.delete(saved.id)

        expectCode("VOICE_SESSION_NOT_FOUND") { voice.get(saved.id) }
        assertThat(jdbc.queryForList("SELECT id FROM ai_interview_app.background_jobs", UUID::class.java)).containsExactly(otherJob)
        assertThat(count("background_job_effects")).isZero()
        assertThat(voice.get(other.id).status).isEqualTo(VoiceSessionStatus.SAVED)
        assertThat(count("practice_sets")).isEqualTo(1)
    }

    @Test
    fun deletingTheSourceTargetJobOrResumeRemovesItsVoiceSessionsAndTheirReportJobs() {
        val resumeId = insertResume()
        val first = readySet(resumeId = resumeId)
        val second = readySet(resumeId = resumeId)
        val onFirst = voice.create(first.id)
        voice.save(onFirst.id, transcript(onFirst, "Answer"))
        voice.create(first.id)
        val onSecond = voice.create(second.id)
        val secondJob = voice.save(onSecond.id, transcript(onSecond, "Answer")).session.submissionJobId!!

        transactions.execute { targetJobs.delete(first.targetJobId) }

        assertThat(jdbc.queryForList("SELECT id FROM ai_interview_app.voice_sessions", UUID::class.java)).containsExactly(onSecond.id)
        assertThat(jdbc.queryForList("SELECT id FROM ai_interview_app.background_jobs", UUID::class.java)).containsExactly(secondJob)

        library.delete(resumeId)

        assertThat(count("voice_sessions")).isZero()
        assertThat(count("background_jobs")).isZero()
    }

    /**
     * Pauses a Save after its report job insert, while it holds its locks, runs [racer] until it waits on a lock,
     * then lets the Save commit and returns both outcomes.
     */
    private fun <T> raceWithPausedSave(sessionId: UUID, reviewed: VoiceTranscript, racer: () -> T): Pair<Result<VoiceSaveResult>, Result<T>> {
        val inserted = CountDownLatch(1)
        val release = CountDownLatch(1)
        Mockito.doAnswer { invocation -> invocation.callRealMethod().also { inserted.countDown(); release.await(10, TimeUnit.SECONDS) } }
            .`when`(jobs).createIfAbsentWithInitialResult(any(), any(), any(), any(), any(), any(), Mockito.anyInt(), anyOrNull())
        val executor = Executors.newFixedThreadPool(2)
        try {
            val save = executor.submit(Callable { voice.save(sessionId, reviewed) })
            assertThat(inserted.await(10, TimeUnit.SECONDS)).isTrue()
            val race = executor.submit(Callable { racer() })
            awaitLockWait()
            release.countDown()
            return outcome { save.get(10, TimeUnit.SECONDS) } to outcome { race.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    private fun <T> outcome(call: () -> T): Result<T> = try { Result.success(call()) } catch (exception: ExecutionException) { Result.failure(exception.cause!!) }

    private fun awaitLockWait() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'", Int::class.java) == 0) {
            check(System.nanoTime() < deadline) { "The racing call never waited on a lock" }
            Thread.sleep(20)
        }
    }

    private fun <T> concurrently(threads: Int, call: () -> T): List<T> {
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(threads)
        return try {
            val calls = (1..threads).map { executor.submit(Callable { start.await(); call() }) }
            start.countDown()
            calls.map { it.get(10, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }
    }

    /** One reviewed answer per given text, for the session's questions in order. */
    private fun transcript(session: VoiceSessionView, vararg answers: String) = VoiceTranscript(
        session.questions.zip(answers).map { (question, text) -> VoiceAnswer(question.id, "Tell me about ${question.text}", text, false) }
    )

    /** Moves a session's timestamps [by] into the past. */
    private fun age(sessionId: UUID, by: Duration) = jdbc.update(
        """
            UPDATE ai_interview_app.voice_sessions
            SET created_at = created_at - (? * interval '1 second'), run_deadline = run_deadline - (? * interval '1 second'),
                draft_expires_at = draft_expires_at - (? * interval '1 second')
            WHERE id = ?
        """.trimIndent(),
        by.seconds, by.seconds, by.seconds, sessionId,
    )

    private fun expectCode(code: String, call: ThrowingCallable) = assertThatThrownBy(call)
        .isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo(code) }

    private data class SetFixture(val id: UUID, val resumeId: UUID, val targetJobId: UUID, val aiQuestions: List<UUID>, val userQuestions: List<UUID>)

    private fun readySet(
        aiQuestions: Int = 3, userQuestions: Int = 0, user: UUID = local.localUserId(),
        resumeId: UUID = insertResume(user), targetJobId: UUID = insertTargetJob(user),
    ): SetFixture {
        val setId = insertSet(resumeId, targetJobId, user)
        val ai = (1..aiQuestions).map { insertQuestion(setId, it, user = user) }
        val own = (1..userQuestions).map { insertQuestion(setId, it, origin = "USER", user = user) }
        return SetFixture(setId, resumeId, targetJobId, ai, own)
    }

    private fun insertSession(set: SetFixture, user: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
                INSERT INTO ai_interview_app.voice_sessions (id, user_id, practice_set_id, resume_id, target_job_id, questions, run_deadline, draft_expires_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, now() + interval '20 minutes', now() + interval '24 hours')
            """.trimIndent(),
            id, user, set.id, set.resumeId, set.targetJobId,
            """[{"id":"${set.aiQuestions.first()}","text":"Question 1?","category":"Depth","expectedSignals":["signal"]}]""",
        )
        return id
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", id, "$id@voice.test")
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

    private fun insertQuestion(setId: UUID, order: Int, origin: String = "AI", user: UUID = local.localUserId()): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            """
                INSERT INTO ai_interview_app.practice_questions (id, practice_set_id, user_id, origin, order_index, text, rationale, category, expected_signals)
                VALUES (?, ?, ?, ?, ?, ?, 'Reason', 'Depth', '["signal"]'::jsonb)
            """.trimIndent(),
            id, setId, user, origin, order, if (origin == "AI") "Question $order?" else "My question $order?",
        )
        return id
    }

    private fun count(table: String) = jdbc.queryForObject("SELECT count(*) FROM ai_interview_app.$table", Int::class.java)!!

    @BeforeEach fun reset() {
        jdbc.execute("TRUNCATE TABLE ai_interview_app.voice_sessions, ai_interview_app.answer_attempts, ai_interview_app.practice_questions, ai_interview_app.practice_sets, ai_interview_app.background_job_effects, ai_interview_app.background_jobs, ai_interview_app.job_descriptions, ai_interview_app.resume_chunks, ai_interview_app.resumes, ai_interview_app.app_users CASCADE")
        Mockito.reset(jobs)
        Mockito.clearInvocations(guard)
    }

    companion object {
        @Container @JvmField val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("ai_interview_voice_test").withUsername("ai_interview").withPassword("ai_interview")
        private val mapper: ObjectMapper = JsonMapper.builder().findAndAddModules().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS).build()
        private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private lateinit var jdbc: JdbcTemplate
        private lateinit var transactions: TransactionTemplate
        private lateinit var local: LocalUserService
        private lateinit var guard: RedisRequestGuard
        private lateinit var jobs: BackgroundJobStore
        private lateinit var voice: VoiceSessionService
        private lateinit var library: ResumeLibraryService
        private lateinit var targetJobs: TargetJobService

        @BeforeAll @JvmStatic fun setUp() {
            val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password)
            jdbc = JdbcTemplate(source)
            Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate()
            transactions = TransactionTemplate(DataSourceTransactionManager(source))
            local = LocalUserService(jdbc)
            jobs = Mockito.spy(BackgroundJobStore(jdbc, mapper))
            guard = Mockito.spy(RedisRequestGuard(StringRedisTemplate(), RedisUsageProperties(
                "voice-test:", RedisUsageProperties.RateLimit(false, 60, 12, 20), RedisUsageProperties.Idempotency(false, 86_400)
            ), mapper))
            val submissions = JobSubmissionService(
                jobs, Mockito.mock(JobDispatcher::class.java), RequestFingerprintService(mapper), local, guard,
                PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), mapper
            )
            voice = VoiceSessionService(jdbc, local, submissions, guard, mapper, transactions)
            val normalizer = ResumeTextNormalizer()
            library = ResumeLibraryService(
                jdbc, local, ResumePersistenceService(jdbc, local, SectionAwareTextChunker(), ContentHasher()), normalizer, guard, transactions,
                ResumeStorageCleanupService(jdbc, Mockito.mock(ResumeStorageService::class.java)), DeleteImpactService(jdbc), mapper
            )
            targetJobs = TargetJobService(jdbc, local, JobDescriptionPersistenceService(jdbc, normalizer, SectionAwareTextChunker(), ContentHasher()))
        }
    }
}
