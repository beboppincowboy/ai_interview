package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.time.Duration
import java.util.UUID

class BackgroundJobStoreIntegrationTests {
    @Test fun claimAcquiresQueuedJobAndRejectsASecondHolder() { val job = createJob(); val lease = UUID.randomUUID(); val claimed = store.claim(job.id, lease, LEASE); assertThat(claimed).isPresent(); assertThat(claimed.get().status).isEqualTo(JobStatus.PROCESSING); assertThat(claimed.get().attempts).isEqualTo(1); assertThat(claimed.get().leaseToken).isEqualTo(lease); assertThat(store.claim(job.id, UUID.randomUUID(), LEASE)).isEmpty() }
    @Test fun claimTakesOverAnExpiredLease() { val job = createJob(); store.claim(job.id, UUID.randomUUID(), LEASE); expireLease(job.id); val takeover = UUID.randomUUID(); val claimed = store.claim(job.id, takeover, LEASE); assertThat(claimed).isPresent(); assertThat(claimed.get().leaseToken).isEqualTo(takeover); assertThat(claimed.get().attempts).isEqualTo(2) }
    @Test fun extendLeaseOnlyForTheCurrentHolder() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id, lease, LEASE); assertThat(store.extendLease(job.id, lease, Duration.ofSeconds(600))).isTrue(); assertThat(store.extendLease(job.id, UUID.randomUUID(), Duration.ofSeconds(600))).isFalse() }
    @Test fun stageAndCheckpointRequireLeaseOwnership() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id, lease, LEASE); store.updateStage(job.id, lease, JobStage.MATCHING_JOB); assertThat(store.findById(job.id).orElseThrow().stage).isEqualTo(JobStage.MATCHING_JOB); val wrong = UUID.randomUUID(); assertThatThrownBy { store.updateStage(job.id, wrong, JobStage.COMPLETED) }.isInstanceOf(JobLeaseLostException::class.java); assertThatThrownBy { store.checkpointResult(job.id, wrong, fitResult()) }.isInstanceOf(JobLeaseLostException::class.java) }
    @Test fun markSucceededClearsLeaseAndStoresResult() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id, lease, LEASE); assertThat(store.markSucceeded(job.id, lease, fitResult())).isTrue(); val done = store.findById(job.id).orElseThrow(); assertThat(done.status).isEqualTo(JobStatus.SUCCEEDED); assertThat(done.stage).isEqualTo(JobStage.COMPLETED); assertThat(done.leaseToken).isNull(); assertThat(done.completedAt).isNotNull(); assertThat(requireNotNull(done.resultPayload).hasNonNull("fitScore")).isTrue(); assertThat(store.markSucceeded(job.id, lease, fitResult())).isFalse() }
    @Test fun markFailedRecordsTheFailure() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id, lease, LEASE); assertThat(store.markFailed(job.id, lease, "PROCESSING_ERROR", "boom")).isTrue(); val failed = store.findById(job.id).orElseThrow(); assertThat(failed.status).isEqualTo(JobStatus.FAILED); assertThat(failed.errorCode).isEqualTo("PROCESSING_ERROR"); assertThat(failed.leaseToken).isNull(); assertThat(store.markFailed(job.id, lease, "PROCESSING_ERROR", "again")).isFalse() }
    @Test fun markRetryingReschedulesAndClearsLease() { val job = createJob(); val lease = UUID.randomUUID(); store.claim(job.id, lease, LEASE); assertThat(store.markRetrying(job.id, lease, "RETRY_CODE", "later", Duration.ofSeconds(30))).isTrue(); val retrying = store.findById(job.id).orElseThrow(); assertThat(retrying.status).isEqualTo(JobStatus.RETRYING); assertThat(retrying.leaseToken).isNull(); assertThat(retrying.enqueuedAt).isNull(); assertThat(retrying.retryable).isTrue(); assertThat(requireNotNull(retrying.runAfter)).isAfter(retrying.createdAt); assertThat(store.markRetrying(job.id, UUID.randomUUID(), "X", "y", Duration.ofSeconds(1))).isFalse() }
    @Test fun reapRequeuesExpiredLeaseWithAttemptsRemaining() { val job = createJob(); store.claim(job.id, UUID.randomUUID(), LEASE); expireLease(job.id); assertThat(store.reapExpiredLeases()).isGreaterThanOrEqualTo(1); val reaped = store.findById(job.id).orElseThrow(); assertThat(reaped.status).isEqualTo(JobStatus.RETRYING); assertThat(reaped.errorCode).isEqualTo("WORKER_LEASE_EXPIRED"); assertThat(reaped.leaseToken).isNull() }
    @Test fun reapFailsExpiredLeaseWhenAttemptsAreExhausted() { val job = createJob(); store.claim(job.id, UUID.randomUUID(), LEASE); jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET attempts = max_attempts, lease_expires_at = now() - interval '1 minute' WHERE id = ?", job.id); store.reapExpiredLeases(); val reaped = store.findById(job.id).orElseThrow(); assertThat(reaped.status).isEqualTo(JobStatus.FAILED); assertThat(reaped.errorCode).isEqualTo("RETRIES_EXHAUSTED_WORKER_LEASE_EXPIRED") }
    @Test fun undispatchedJobsBecomeInvisibleOnceEnqueued() { val job = createJob(); assertThat(store.findUndispatched(50)).contains(job.id); store.markEnqueued(job.id); assertThat(store.findUndispatched(50)).doesNotContain(job.id) }
    @Test fun markExhaustedFromDlqFailsAJobPastItsAttemptLimit() { val job = createJob(); jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET attempts = max_attempts WHERE id = ?", job.id); assertThat(store.markExhaustedFromDlq(job.id)).isTrue(); val failed = store.findById(job.id).orElseThrow(); assertThat(failed.status).isEqualTo(JobStatus.FAILED); assertThat(failed.errorCode).isEqualTo("RETRIES_EXHAUSTED_DLQ") }
    @Test fun submitReusesTheActiveJobForAResourceAndStartsANewOneAfterItSucceeds() {
        val resume = UUID.randomUUID(); val first = submissions.submit(JobType.JOB_FIT, "resume", resume, PAYLOAD); val lease = UUID.randomUUID(); store.claim(first.jobId, lease, LEASE)
        val second = submissions.submit(JobType.JOB_FIT, "resume", resume, PAYLOAD); assertThat(first.reused).isFalse(); assertThat(second.reused).isTrue(); assertThat(second.jobId).isEqualTo(first.jobId)
        assertThat(store.markSucceeded(first.jobId, lease, fitResult())).isTrue(); val third = submissions.submit(JobType.JOB_FIT, "resume", resume, PAYLOAD); assertThat(third.reused).isFalse(); assertThat(third.jobId).isNotEqualTo(first.jobId)
    }
    @Test fun submitStartsOneJobPerResourceAndOneForEveryResourcelessSubmission() {
        val jobs = listOf(submissions.submit(JobType.ANSWER_FEEDBACK, "attempt", UUID.randomUUID(), PAYLOAD), submissions.submit(JobType.ANSWER_FEEDBACK, "attempt", UUID.randomUUID(), PAYLOAD), submissions.submit(JobType.JOB_FIT, null, null, PAYLOAD), submissions.submit(JobType.JOB_FIT, null, null, PAYLOAD))
        assertThat(jobs.map { it.jobId }).doesNotHaveDuplicates(); assertThat(jobs).noneMatch { it.reused }; assertThat(store.findById(jobs[2].jobId).orElseThrow().requestFingerprint).isNull()
    }
    @Test fun submitJoinsTheCallersTransactionAndDispatchesOnlyAfterCommit() {
        val accepted = requireNotNull(transactions.execute { submissions.submit(JobType.JOB_FIT, "resume", UUID.randomUUID(), PAYLOAD).also { Mockito.verify(dispatcher, Mockito.never()).dispatch(it.jobId) } })
        Mockito.verify(dispatcher).dispatch(accepted.jobId)
    }
    @Test fun rateLimitedSubmitRollsBackTheCallersNewRow() {
        val guard = Mockito.mock(RedisRequestGuard::class.java); Mockito.doThrow(ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).`when`(guard).assertAiAllowed(JobSubmissionService.AI_JOB_ACTION); val resourceId = UUID.randomUUID()
        assertThatThrownBy { transactions.execute { jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", resourceId, "$resourceId@ai-interview.test"); submissionService(guard).submit(JobType.JOB_FIT, "resume", resourceId, PAYLOAD) } }.isInstanceOf(ResponseStatusException::class.java)
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_interview_app.app_users WHERE id = ?", Int::class.java, resourceId)).isZero(); assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM ai_interview_app.background_jobs WHERE resource_id = ?", Int::class.java, resourceId)).isZero()
    }
    @Test fun latestForResourceReturnsTheUsersNewestJobOptionallyByType() {
        val resume = UUID.randomUUID(); val otherUser = UUID.randomUUID(); jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", otherUser, "$otherUser@ai-interview.test"); val payload = ObjectMapper().createObjectNode()
        val older = store.createIfAbsent(userId, JobType.RESUME_EXTRACTION, "resume", resume, payload, null, 3).orElseThrow(); val newer = store.createIfAbsent(userId, JobType.JOB_FIT, "resume", resume, payload, null, 3).orElseThrow(); val foreign = store.createIfAbsent(otherUser, JobType.JOB_FIT, "resume", resume, payload, null, 3).orElseThrow()
        jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET created_at = now() - interval '1 minute' WHERE id = ?", older.id); jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET created_at = now() + interval '1 minute' WHERE id = ?", foreign.id)
        assertThat(store.findLatestForResource(userId, "resume", resume).map(LatestJob::from)).contains(LatestJob(newer.id, JobType.JOB_FIT, JobStatus.QUEUED, JobStage.QUEUED, 0, 3, null))
        assertThat(store.findLatestForResource(userId, "resume", resume, listOf(JobType.RESUME_EXTRACTION)).map { it.id }).contains(older.id); assertThat(store.findLatestForResource(userId, "practice-set", resume)).isEmpty()
    }
    @Test fun latestForResourcesReturnsEachResourcesNewestJobInOneLookup() {
        val first = UUID.randomUUID(); val second = UUID.randomUUID(); val payload = ObjectMapper().createObjectNode()
        val firstOlder = store.createIfAbsent(userId, JobType.ANSWER_FEEDBACK, "attempt", first, payload, null, 3).orElseThrow(); val firstNewer = store.createIfAbsent(userId, JobType.ANSWER_FEEDBACK, "attempt", first, payload, null, 3).orElseThrow(); val secondOnly = store.createIfAbsent(userId, JobType.ANSWER_FEEDBACK, "attempt", second, payload, null, 3).orElseThrow()
        jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET created_at = now() - interval '1 minute' WHERE id = ?", firstOlder.id)
        val latest = store.findLatestForResources(userId, "attempt", listOf(first, second, UUID.randomUUID()), listOf(JobType.ANSWER_FEEDBACK))
        assertThat(latest.mapValues { it.value.id }).containsExactlyInAnyOrderEntriesOf(mapOf(first to firstNewer.id, second to secondOnly.id))
        assertThat(store.findLatestForResources(userId, "attempt", emptyList())).isEmpty(); assertThat(store.findLatestForResources(userId, "attempt", listOf(first), listOf(JobType.JOB_FIT))).isEmpty()
    }
    @Test fun deleteByResourcesRemovesTheirJobsSoPollsAndLeaseWritesFail() {
        val resume = UUID.randomUUID(); val doomed = submissions.submit(JobType.JOB_FIT, "resume", resume, PAYLOAD); val kept = submissions.submit(JobType.JOB_FIT, "resume", UUID.randomUUID(), PAYLOAD); val lease = UUID.randomUUID(); store.claim(doomed.jobId, lease, LEASE)
        assertThat(store.deleteByResources("resume", listOf(resume))).isEqualTo(1); assertThat(store.deleteByResources("resume", emptyList())).isZero()
        assertThatThrownBy { jobs.status(doomed.jobId) }.isInstanceOfSatisfying(ApiRequestException::class.java) { assertThat(it.code()).isEqualTo("JOB_NOT_FOUND") }; assertThatThrownBy { store.checkpointResult(doomed.jobId, lease, fitResult()) }.isInstanceOf(JobLeaseLostException::class.java)
        assertThat(jobs.status(kept.jobId).maxAttempts).isEqualTo(3)
    }
    private fun createJob(): BackgroundJob { val payload = ObjectMapper().createObjectNode().put("resumeText", "Java"); return store.createIfAbsent(userId, JobType.JOB_FIT, "resume", null, payload, UUID.randomUUID().toString(), 3).orElseThrow() }
    private fun expireLease(id: UUID) { jdbcTemplate.update("UPDATE ai_interview_app.background_jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", id) }
    private fun fitResult(): ObjectNode = ObjectMapper().createObjectNode().put("fitScore", 80)
    companion object {
        private val LEASE = Duration.ofSeconds(300); private val POSTGRES = PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres")).withDatabaseName("ai_interview_job_store_test").withUsername("ai_interview").withPassword("ai_interview")
        private val PAYLOAD = mapOf("answerText" to "same text"); private val PROPERTIES = JobProperties(true, "http://localhost:4566", "us-east-1", "test", "test", "jobs", "jobs-dlq", 3, 2, 20, 300, 60, 3, 15, 5_000, 30_000, 3_600_000, 120, 7)
        private val dispatcher = Mockito.mock(JobDispatcher::class.java); private val localUsers = Mockito.mock(LocalUserService::class.java)
        private lateinit var jdbcTemplate: JdbcTemplate; private lateinit var store: BackgroundJobStore; private lateinit var userId: UUID; private lateinit var submissions: JobSubmissionService; private lateinit var jobs: JobController; private lateinit var transactions: TransactionTemplate
        @BeforeAll @JvmStatic fun setUp() { POSTGRES.start(); val source = DriverManagerDataSource(POSTGRES.jdbcUrl, POSTGRES.username, POSTGRES.password); jdbcTemplate = JdbcTemplate(source); Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate(); store = BackgroundJobStore(jdbcTemplate, ObjectMapper()); userId = UUID.randomUUID(); jdbcTemplate.update("INSERT INTO ai_interview_app.app_users (id, email) VALUES (?, ?)", userId, "job-store-test@ai-interview.dev")
            Mockito.`when`(localUsers.localUserId()).thenReturn(userId); submissions = submissionService(Mockito.mock(RedisRequestGuard::class.java)); jobs = JobController(JobStatusReaderConfiguration().localJobStatusReader(store), localUsers); transactions = TransactionTemplate(DataSourceTransactionManager(source)) }
        private fun submissionService(guard: RedisRequestGuard) = JobSubmissionService(store, dispatcher, RequestFingerprintService(ObjectMapper()), localUsers, guard, PROPERTIES, RuntimeModeProperties("all"), JobMetrics(SimpleMeterRegistry()), ObjectMapper())
        @AfterAll @JvmStatic fun tearDown() { POSTGRES.stop() }
    }
}
