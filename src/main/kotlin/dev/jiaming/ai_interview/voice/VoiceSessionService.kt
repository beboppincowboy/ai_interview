package dev.jiaming.ai_interview.voice

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.RequestValidation
import dev.jiaming.ai_interview.common.lockOwnerShared
import dev.jiaming.ai_interview.common.sha256Hex
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.time.Duration
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

/**
 * Spoken interview runs (KTD5, KTD6). Writers lock the owner, then the session's resume and target job, then the session,
 * the order practice writers use. Session delete locks its report jobs before the session, like TargetJobService.delete.
 */
@Service
class VoiceSessionService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobSubmissionService: JobSubmissionService,
    private val requestGuard: RedisRequestGuard,
    private val objectMapper: ObjectMapper,
    private val transactionOperations: TransactionOperations,
) {
    fun get(sessionId: UUID): VoiceSessionView = find(localUserService.localUserId(), sessionId)?.view ?: notFound()

    /** Worker read bound to the claimed job owner, not the request-time local-user shortcut. */
    internal fun getForReport(userId: UUID, sessionId: UUID): VoiceSessionView =
        find(userId, sessionId)?.view ?: throw IllegalStateException("Voice report session $sessionId was not found for its job owner")

    /** Starts a draft on a READY practice set with a copy of its first [MAX_QUESTIONS] AI questions in stored order. */
    fun create(practiceSetId: UUID): VoiceSessionView {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            jdbcTemplate.lockOwnerShared(userId)
            val (resumeId, targetJobId) = jdbcTemplate.query(
                "SELECT resume_id, target_job_id FROM ai_interview_app.practice_sets WHERE id = ? AND user_id = ?",
                RowMapper { rs, _ -> rs.getObject("resume_id", UUID::class.java) to rs.getObject("target_job_id", UUID::class.java) },
                practiceSetId, userId,
            ).firstOrNull()?.takeIf { lockSources(userId, it.first, it.second) } ?: practiceSetNotFound()
            val questions = jdbcTemplate.query(
                """
                    SELECT id, text, category, expected_signals::text AS expected_signals
                    FROM ai_interview_app.practice_questions
                    WHERE practice_set_id = ? AND user_id = ? AND origin = 'AI'
                    ORDER BY order_index
                    LIMIT $MAX_QUESTIONS
                """.trimIndent(),
                RowMapper { rs, _ ->
                    VoiceQuestion(
                        rs.getObject("id", UUID::class.java), rs.getString("text"), rs.getString("category"),
                        objectMapper.readValue(rs.getString("expected_signals"), Array<String>::class.java).toList(),
                    )
                },
                practiceSetId, userId,
            )
            if (questions.isEmpty()) {
                throw ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_READY", "Practice questions are not ready")
            }
            val sessionId = requireNotNull(jdbcTemplate.queryForObject(
                """
                    INSERT INTO ai_interview_app.voice_sessions
                        (user_id, practice_set_id, resume_id, target_job_id, questions, run_deadline, draft_expires_at)
                    VALUES (?, ?, ?, ?, ?::jsonb, now() + (? * interval '1 second'), now() + (? * interval '1 second'))
                    RETURNING id
                """.trimIndent(),
                UUID::class.java, userId, practiceSetId, resumeId, targetJobId, objectMapper.writeValueAsString(questions),
                RUN_LIMIT.seconds, DRAFT_LIFETIME.seconds,
            ))
            find(userId, sessionId)!!.view
        }
    }

    /**
     * Saves the reviewed transcript and starts its report job in one transaction (KTD6). An identical repeated Save
     * returns the committed outcome; a different one after Save is a conflict. Save is accepted until the draft expires.
     */
    fun save(sessionId: UUID, transcript: VoiceTranscript): VoiceSaveResult {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            jdbcTemplate.lockOwnerShared(userId)
            val found = find(userId, sessionId) ?: notFound()
            if (!lockSources(userId, found.view.resumeId, found.view.targetJobId)) notFound()
            // The row lock serializes Save, Discard and Delete; a waiting Save sees the committed transcript hash.
            val session = find(userId, sessionId, lock = true) ?: notFound()
            val json = objectMapper.writeValueAsString(normalize(session.view.questions, transcript))
            if (json.toByteArray(Charsets.UTF_8).size > MAX_TRANSCRIPT_BYTES) {
                throw RequestValidation.invalid("The transcript must be at most $MAX_TRANSCRIPT_BYTES bytes", "TRANSCRIPT_TOO_LARGE")
            }
            val hash = sha256Hex(json)
            when {
                session.transcriptHash == hash -> VoiceSaveResult(session.view, true)
                session.transcriptHash != null -> alreadySaved()
                session.view.status == VoiceSessionStatus.EXPIRED ->
                    throw ApiRequestException(HttpStatus.CONFLICT, "VOICE_SESSION_EXPIRED", "The interview draft expired before it was saved")
                else -> {
                    requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
                    val job = jobSubmissionService.createOrReuse(
                        JobType.VOICE_REPORT, VoiceReportPayload.RESOURCE, sessionId,
                        VoiceReportPayload(sessionId, session.view.resumeId, session.view.targetJobId),
                        jobSubmissionService.fingerprint(JobType.VOICE_REPORT.name, sessionId),
                    )
                    jdbcTemplate.update(
                        """
                            UPDATE ai_interview_app.voice_sessions
                            SET transcript = ?::jsonb, transcript_hash = ?, saved_at = now(), submission_job_id = ?, report_job_id = ?
                            WHERE id = ? AND user_id = ?
                        """.trimIndent(),
                        json, hash, job.jobId, job.jobId, sessionId, userId,
                    )
                    VoiceSaveResult(find(userId, sessionId)!!.view, false)
                }
            }
        }
    }

    /** Replaces a failed current report job while preserving the original Save and valid completed answers. */
    fun retryReport(sessionId: UUID): VoiceSessionView {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            jdbcTemplate.lockOwnerShared(userId)
            // Match Delete's job-before-session order; never lock an old job after acquiring the session row.
            lockReportJobs(userId, sessionId)
            val current = find(userId, sessionId, lock = true) ?: notFound()
            if (current.view.status != VoiceSessionStatus.SAVED || current.view.transcript == null) notFound()
            if (current.view.report != null) return@inTransaction current.view

            val oldJobId = current.view.reportJobId ?: notRetryable()
            val oldJob = findReportJob(userId, oldJobId) ?: notRetryable()
            if (oldJob.jobType != JobType.VOICE_REPORT.name || oldJob.resourceType != VoiceReportPayload.RESOURCE ||
                oldJob.resourceId != sessionId || oldJob.status != "FAILED") notRetryable()
            val payload = try {
                objectMapper.treeToValue(oldJob.requestPayload, VoiceReportPayload::class.java)
            } catch (exception: Exception) {
                throw IllegalStateException("Failed voice report job $oldJobId has an invalid payload", exception)
            }
            if (payload.payloadVersion != VoiceReportPayload.CURRENT_VERSION || payload.voiceSessionId != sessionId ||
                payload.resumeId != current.view.resumeId || payload.targetJobId != current.view.targetJobId) notRetryable()

            requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
            val checkpoints = transferableCheckpoints(current.view, oldJob.resultPayload)
            val replacement = jobSubmissionService.createOrReuseWithInitialResult(
                JobType.VOICE_REPORT,
                VoiceReportPayload.RESOURCE,
                sessionId,
                VoiceReportPayload(sessionId, current.view.resumeId, current.view.targetJobId),
                jobSubmissionService.fingerprint(JobType.VOICE_REPORT.name, sessionId),
                checkpoints,
            )
            val updated = jdbcTemplate.update(
                "UPDATE ai_interview_app.voice_sessions SET report = NULL, report_job_id = ? WHERE id = ? AND user_id = ? AND saved_at IS NOT NULL AND submission_job_id = ? AND report_job_id = ?",
                replacement.jobId, sessionId, userId, current.view.submissionJobId, oldJobId,
            )
            if (updated != 1) throw IllegalStateException("Voice report session $sessionId changed during retry")
            find(userId, sessionId)!!.view
        }
    }

    /** Removes an unsaved draft. One statement, so a Save holding the row commits first and its saved row is kept. */
    fun discard(sessionId: UUID) {
        val userId = localUserService.localUserId()
        val deleted = jdbcTemplate.update(
            "DELETE FROM ai_interview_app.voice_sessions WHERE id = ? AND user_id = ? AND saved_at IS NULL", sessionId, userId,
        )
        if (deleted == 0) {
            find(userId, sessionId) ?: notFound()
            alreadySaved()
        }
    }

    /** Removes a session with its report jobs, their checkpoints and effects. */
    fun delete(sessionId: UUID) {
        val userId = localUserService.localUserId()
        inTransaction {
            val jobIds = lockReportJobs(userId, sessionId).toMutableSet()
            if (jdbcTemplate.queryForList(
                    "SELECT id FROM ai_interview_app.voice_sessions WHERE id = ? AND user_id = ? FOR UPDATE", sessionId, userId,
                ).isEmpty()) notFound()
            // A Save or Retry can commit its report job while this waits for the session row.
            jobIds += lockReportJobs(userId, sessionId)
            if (jobIds.isNotEmpty()) {
                jdbcTemplate.update(
                    "DELETE FROM ai_interview_app.background_jobs WHERE user_id = ? AND id IN (${jobIds.joinToString { "?" }})",
                    userId, *jobIds.toTypedArray(),
                )
            }
            jdbcTemplate.update("DELETE FROM ai_interview_app.voice_sessions WHERE id = ? AND user_id = ?", sessionId, userId)
        }
    }

    /** Unsaved drafts past their expiry hold no transcript; Save already rejects them, so they go (KTD5). */
    @Scheduled(fixedDelayString = "\${app.voice.draft-cleanup-interval-ms:3600000}")
    fun removeExpiredDrafts() {
        val removed = jdbcTemplate.update("DELETE FROM ai_interview_app.voice_sessions WHERE saved_at IS NULL AND draft_expires_at <= now()")
        if (removed > 0) log.info("voice_drafts_expired count={}", removed)
    }

    /**
     * Trims every text and orders answers by question, so an identical reviewed transcript always hashes the same.
     * Checks canonical IDs and limits; nothing is ever truncated.
     */
    private fun normalize(questions: List<VoiceQuestion>, transcript: VoiceTranscript): VoiceTranscript {
        val positions = questions.withIndex().associate { (index, question) -> question.id to index }
        val answers = transcript.answers.map {
            if (it.questionId !in positions) throw RequestValidation.invalid("answers must only reference this session's questions")
            val answerText = it.answerText.trim()
            if (answerText.length > MAX_ANSWER_LENGTH) {
                throw RequestValidation.invalid("answerText must be at most $MAX_ANSWER_LENGTH characters", "ANSWER_TOO_LONG")
            }
            it.copy(interviewerText = it.interviewerText.trim(), answerText = answerText)
        }
        if (answers.distinctBy { it.questionId }.size != answers.size) throw RequestValidation.invalid("answers must not repeat a question")
        if (answers.all { it.answerText.isEmpty() }) throw RequestValidation.invalid("At least one answer must not be blank", "ANSWER_EMPTY")
        return VoiceTranscript(answers.sortedBy { positions.getValue(it.questionId) })
    }

    /** Locks the resume and target job against deletion; false when either is gone, which also removed its sessions. */
    private fun lockSources(userId: UUID, resumeId: UUID, targetJobId: UUID): Boolean =
        jdbcTemplate.queryForList("SELECT id FROM ai_interview_app.resumes WHERE id = ? AND user_id = ? FOR KEY SHARE", resumeId, userId).isNotEmpty() &&
            jdbcTemplate.queryForList("SELECT id FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ? FOR KEY SHARE", targetJobId, userId).isNotEmpty()

    // Stable order keeps overlapping deletes consistent.
    private fun lockReportJobs(userId: UUID, sessionId: UUID): List<UUID?> = jdbcTemplate.queryForList(
        "SELECT id FROM ai_interview_app.background_jobs WHERE user_id = ? AND resource_type = ? AND resource_id = ? ORDER BY id FOR UPDATE",
        UUID::class.java, userId, VoiceReportPayload.RESOURCE, sessionId,
    )

    private fun findReportJob(userId: UUID, jobId: UUID): ReportJobRow? = jdbcTemplate.query(
        "SELECT job_type, resource_type, resource_id, status, request_payload::text AS request_payload, result_payload::text AS result_payload FROM ai_interview_app.background_jobs WHERE id = ? AND user_id = ?",
        RowMapper { rs, _ ->
            ReportJobRow(
                rs.getString("job_type"), rs.getString("resource_type"), rs.getObject("resource_id", UUID::class.java),
                rs.getString("status"), readJson(rs.getString("request_payload")), readJson(rs.getString("result_payload")),
            )
        },
        jobId, userId,
    ).firstOrNull()

    private fun transferableCheckpoints(session: VoiceSessionView, resultPayload: JsonNode?): ObjectNode {
        val transferred = objectMapper.createObjectNode()
        if (resultPayload == null || !resultPayload.isObject || session.transcript == null) return transferred
        val answers = session.transcript.answers.associateBy { it.questionId }
        session.questions.forEach { question ->
            val answer = answers[question.id]?.takeIf { it.answerText.isNotBlank() } ?: return@forEach
            val field = "answer:${question.id}"
            val stored = resultPayload.get(field) ?: return@forEach
            val checkpoint = try {
                objectMapper.treeToValue(stored, VoiceAnswerScoreCheckpoint::class.java)
            } catch (_: Exception) {
                return@forEach
            }
            if (validVoiceAnswerCheckpoint(checkpoint, session, question, answer, objectMapper)) {
                transferred.set<JsonNode>(field, stored.deepCopy())
            }
        }
        return transferred
    }

    private fun readJson(raw: String?): JsonNode? = raw?.let {
        try {
            objectMapper.readTree(it)
        } catch (exception: Exception) {
            throw IllegalStateException("Could not read voice report job JSON", exception)
        }
    }

    private fun find(userId: UUID, sessionId: UUID, lock: Boolean = false): SessionRow? = jdbcTemplate.query(
        """
            SELECT id, practice_set_id, resume_id, target_job_id, questions::text AS questions, transcript::text AS transcript,
                transcript_hash, submission_job_id, report_job_id, report::text AS report, created_at, run_deadline, draft_expires_at, saved_at,
                draft_expires_at <= now() AS expired
            FROM ai_interview_app.voice_sessions
            WHERE id = ? AND user_id = ?
            ${if (lock) "FOR UPDATE" else ""}
        """.trimIndent(),
        RowMapper { rs, _ ->
            val savedAt = rs.getTimestamp("saved_at")?.toInstant()
            val status = when {
                savedAt != null -> VoiceSessionStatus.SAVED
                rs.getBoolean("expired") -> VoiceSessionStatus.EXPIRED
                else -> VoiceSessionStatus.DRAFT
            }
            SessionRow(
                VoiceSessionView(
                    rs.getObject("id", UUID::class.java), rs.getObject("practice_set_id", UUID::class.java),
                    rs.getObject("resume_id", UUID::class.java), rs.getObject("target_job_id", UUID::class.java), status,
                    objectMapper.readValue(rs.getString("questions"), Array<VoiceQuestion>::class.java).toList(),
                    rs.getString("transcript")?.let { objectMapper.readValue(it, VoiceTranscript::class.java) },
                    rs.getObject("submission_job_id", UUID::class.java), rs.getObject("report_job_id", UUID::class.java),
                    rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("run_deadline").toInstant(),
                    rs.getTimestamp("draft_expires_at").toInstant(), savedAt,
                    rs.getString("report")?.let { objectMapper.readValue(it, VoiceSessionReport::class.java) },
                ),
                rs.getString("transcript_hash"),
            )
        },
        sessionId, userId,
    ).firstOrNull()

    private fun <T> inTransaction(work: () -> T): T = requireNotNull(transactionOperations.execute { work() })

    private fun notFound(): Nothing = throw ApiRequestException(HttpStatus.NOT_FOUND, "VOICE_SESSION_NOT_FOUND", "Interview session was not found")

    private fun practiceSetNotFound(): Nothing = throw ApiRequestException(HttpStatus.NOT_FOUND, "PRACTICE_SET_NOT_FOUND", "Practice set was not found")

    private fun alreadySaved(): Nothing =
        throw ApiRequestException(HttpStatus.CONFLICT, "VOICE_SESSION_ALREADY_SAVED", "This interview was already saved")

    private fun notRetryable(): Nothing =
        throw ApiRequestException(HttpStatus.CONFLICT, "VOICE_REPORT_NOT_RETRYABLE", "Only a failed current report can be retried")

    private data class SessionRow(val view: VoiceSessionView, val transcriptHash: String?)
    private data class ReportJobRow(
        val jobType: String,
        val resourceType: String?,
        val resourceId: UUID?,
        val status: String,
        val requestPayload: JsonNode?,
        val resultPayload: JsonNode?,
    )

    companion object {
        const val MAX_QUESTIONS = 6
        const val MAX_ANSWER_LENGTH = 4_000
        const val MAX_TRANSCRIPT_BYTES = 64 * 1024
        val RUN_LIMIT: Duration = Duration.ofMinutes(20)
        val DRAFT_LIFETIME: Duration = Duration.ofHours(24)
        private val log = LoggerFactory.getLogger(VoiceSessionService::class.java)
    }
}
