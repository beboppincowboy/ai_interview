package dev.jiaming.ai_interview.practice

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.LocalUserService
import dev.jiaming.ai_interview.common.RedisRequestGuard
import dev.jiaming.ai_interview.common.lockOwnerShared
import dev.jiaming.ai_interview.common.sha256Hex
import dev.jiaming.ai_interview.jobs.ActiveJob
import dev.jiaming.ai_interview.jobs.AttemptFeedbackPayload
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobSubmissionService
import dev.jiaming.ai_interview.jobs.JobType
import java.time.Instant
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionOperations

@Service
class PracticeService(
    private val jdbcTemplate: JdbcTemplate,
    private val localUserService: LocalUserService,
    private val jobSubmissionService: JobSubmissionService,
    private val requestGuard: RedisRequestGuard,
    private val backgroundJobStore: BackgroundJobStore,
    private val objectMapper: ObjectMapper,
    private val transactionOperations: TransactionOperations,
) {
    fun get(setId: UUID): PracticeSetView {
        val userId = localUserService.localUserId()
        return view(userId, findSet(userId, "id = ?", setId) ?: notFound())
    }

    /** Returns the pair's set, creating it with its first generation job when it does not exist yet (§7.1). */
    fun create(resumeId: UUID, targetJobId: UUID): PracticeSetCreation {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            jdbcTemplate.lockOwnerShared(userId)
            val resumeReady = lockInputs(userId, resumeId, targetJobId)
            val existing = findPairSet(userId, resumeId, targetJobId)
            when {
                existing != null -> PracticeSetCreation(view(userId, existing), false)
                !resumeReady -> throw ApiRequestException(HttpStatus.CONFLICT, "RESUME_NOT_READY", "Resume is not ready for practice")
                else -> {
                    // The unique pair constraint picks one winner among concurrent creates; the loser returns the winner's set.
                    val setId = jdbcTemplate.query(
                        """
                            INSERT INTO ai_interview_app.practice_sets (user_id, resume_id, target_job_id)
                            VALUES (?, ?, ?)
                            ON CONFLICT (user_id, resume_id, target_job_id) DO NOTHING
                            RETURNING id
                        """.trimIndent(),
                        RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, userId, resumeId, targetJobId,
                    ).firstOrNull()
                    if (setId == null) {
                        PracticeSetCreation(view(userId, findPairSet(userId, resumeId, targetJobId) ?: notFound()), false)
                    } else {
                        val set = findSet(userId, "id = ?", setId) ?: notFound()
                        startGeneration(set)
                        PracticeSetCreation(view(userId, set), true)
                    }
                }
            }
        }
    }

    /** Starts a new generation job for a set whose last generation failed (§7.3). */
    fun retry(setId: UUID): PracticeSetView {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            jdbcTemplate.lockOwnerShared(userId)
            val found = findSet(userId, "id = ?", setId) ?: notFound()
            // Lock the pair's rows before the set, in create's order, so a concurrent delete either removes this job or wins first.
            lockInputs(userId, found.resumeId, found.targetJobId)
            val set = findSet(userId, "id = ? FOR UPDATE", setId) ?: notFound()
            if (view(userId, set).status != PracticeSetStatus.FAILED) {
                throw ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_FAILED", "Practice set generation did not fail")
            }
            startGeneration(set)
            view(userId, set)
        }
    }

    /**
     * Appends a user question after the AI questions (§7.4). [text] is already trimmed and checked. A repeated
     * [idempotencyKey] replays the question it saved, because the key is recorded in the same transaction as the insert.
     */
    fun addQuestion(setId: UUID, text: String, idempotencyKey: String? = null): PracticeQuestionView {
        val userId = localUserService.localUserId()
        val keyHash = idempotencyKey?.let(::sha256Hex)
        return inTransaction {
            // KTD19: the set row lock serializes adds, so the count below is the committed count and a same-key retry
            // waits for the first add to commit, then finds its question.
            val set = findSet(userId, "id = ? FOR UPDATE", setId) ?: notFound()
            if (keyHash != null) savedForKey(userId, setId, keyHash, text)?.let { return@inTransaction it }
            val current = view(userId, set)
            if (current.status == PracticeSetStatus.GENERATING) {
                throw ApiRequestException(HttpStatus.CONFLICT, "PRACTICE_SET_NOT_READY", "Practice questions are still being generated")
            }
            val userQuestions = current.questions.count { it.origin == USER }
            if (userQuestions >= MAX_USER_QUESTIONS) {
                throw ApiRequestException(HttpStatus.CONFLICT, "QUESTION_LIMIT_REACHED", "A practice set can have at most $MAX_USER_QUESTIONS of your own questions")
            }
            val questionId = jdbcTemplate.queryForObject(
                """
                    INSERT INTO ai_interview_app.practice_questions (practice_set_id, user_id, origin, order_index, text, idempotency_key_hash)
                    VALUES (?, ?, 'USER', ?, ?, ?)
                    RETURNING id
                """.trimIndent(),
                UUID::class.java, setId, userId, userQuestions + 1, text, keyHash,
            )
            jdbcTemplate.update("UPDATE ai_interview_app.practice_sets SET updated_at = now() WHERE id = ? AND user_id = ?", setId, userId)
            questions(userId, setId).single { it.id == questionId }
        }
    }

    private fun savedForKey(userId: UUID, setId: UUID, keyHash: String, text: String): PracticeQuestionView? {
        val (savedId, savedText) = jdbcTemplate.query(
            "SELECT id, text FROM ai_interview_app.practice_questions WHERE practice_set_id = ? AND user_id = ? AND idempotency_key_hash = ?",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getString("text") },
            setId, userId, keyHash,
        ).singleOrNull() ?: return null
        if (savedText != text) {
            throw ApiRequestException(HttpStatus.CONFLICT, "CONFLICT", "Idempotency-Key was already used for a different question.")
        }
        return questions(userId, setId).single { it.id == savedId }
    }

    /** The set's questions: AI questions first, then user questions in the order they were added, each with its attempts. */
    fun questions(userId: UUID, setId: UUID): List<PracticeQuestionView> {
        val attempts = attemptRows(userId, "q.practice_set_id = ?", setId).groupBy { it.questionId }
        return jdbcTemplate.query(
            """
                SELECT id, origin, text, rationale, category, expected_signals::text AS expected_signals
                FROM ai_interview_app.practice_questions
                WHERE practice_set_id = ? AND user_id = ?
                ORDER BY origin = 'USER', order_index
            """.trimIndent(),
            RowMapper { rs, row ->
                PracticeQuestionView(
                    rs.getObject("id", UUID::class.java), row + 1, rs.getString("origin"), rs.getString("text"),
                    rs.getString("rationale"), rs.getString("category"),
                    objectMapper.readValue(rs.getString("expected_signals"), Array<String>::class.java).toList(),
                )
            },
            setId, userId,
        ).map { it.copy(attempts = attemptViews(userId, attempts[it.id].orEmpty())) }
    }

    /** Saves the next numbered attempt and starts its feedback job in one transaction (§7.5). [text] is already trimmed and checked. */
    fun submitAttempt(setId: UUID, questionId: UUID, text: String): AttemptView {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            val question = lockQuestion(userId, "q.id = ? AND q.practice_set_id = ?", questionId, setId)
                ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "QUESTION_NOT_FOUND", "Question was not found")
            // KTD19: the question row lock serializes submissions, so the latest attempt and next number below are committed values.
            val latest = attemptRows(userId, "a.question_id = ?", questionId).lastOrNull()
            if (latest?.text == text) {
                throw ApiRequestException(HttpStatus.CONFLICT, "ANSWER_UNCHANGED", "The answer is the same as the latest attempt")
            }
            val attemptId = requireNotNull(jdbcTemplate.queryForObject(
                """
                    INSERT INTO ai_interview_app.answer_attempts (question_id, user_id, number, text)
                    VALUES (?, ?, ?, ?)
                    RETURNING id
                """.trimIndent(),
                UUID::class.java, questionId, userId, (latest?.number ?: 0) + 1, text,
            ))
            startFeedback(attemptId, question)
            attemptView(userId, questionId, attemptId)
        }
    }

    /** Starts a new feedback job for a failed attempt, keeping its ID, number and text (§7.6). */
    fun retryAttempt(attemptId: UUID): AttemptView {
        jobSubmissionService.assertApiAvailable()
        val userId = localUserService.localUserId()
        return inTransaction {
            val question = lockQuestion(userId, "q.id = (SELECT question_id FROM ai_interview_app.answer_attempts WHERE id = ? AND user_id = ?)", attemptId, userId)
                ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "ATTEMPT_NOT_FOUND", "Attempt was not found")
            if (attemptView(userId, question.id, attemptId).status != AttemptStatus.FAILED) {
                throw ApiRequestException(HttpStatus.CONFLICT, "ATTEMPT_NOT_FAILED", "Attempt feedback did not fail")
            }
            startFeedback(attemptId, question)
            attemptView(userId, question.id, attemptId)
        }
    }

    /** The attempt and its question, read by the feedback handler; null when the attempt no longer exists. */
    fun attemptForScoring(userId: UUID, attemptId: UUID): AttemptScoringInput? = jdbcTemplate.query(
        """
            SELECT a.text, q.text AS question_text, q.category, q.expected_signals::text AS expected_signals
            FROM ai_interview_app.answer_attempts a
            JOIN ai_interview_app.practice_questions q ON q.id = a.question_id AND q.user_id = a.user_id
            WHERE a.id = ? AND a.user_id = ?
        """.trimIndent(),
        RowMapper { rs, _ ->
            AttemptScoringInput(
                rs.getString("text"), rs.getString("question_text"), rs.getString("category"),
                objectMapper.readValue(rs.getString("expected_signals"), Array<String>::class.java).toList(),
            )
        },
        attemptId, userId,
    ).firstOrNull()

    private fun startGeneration(set: SetRow) {
        requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        jobSubmissionService.createOrReuse(
            JobType.PRACTICE_QUESTIONS,
            RESOURCE,
            set.id,
            PracticeQuestionsPayload(set.id, set.resumeId, set.targetJobId),
            jobSubmissionService.fingerprint(JobType.PRACTICE_QUESTIONS.name, set.id),
        )
    }

    private fun startFeedback(attemptId: UUID, question: QuestionRow) {
        requestGuard.assertAiAllowed(JobSubmissionService.AI_JOB_ACTION)
        jobSubmissionService.createOrReuse(
            JobType.ANSWER_FEEDBACK,
            AttemptFeedbackPayload.RESOURCE,
            attemptId,
            AttemptFeedbackPayload(attemptId, question.setId, question.resumeId, question.targetJobId),
            jobSubmissionService.fingerprint(JobType.ANSWER_FEEDBACK.name, attemptId),
        )
    }

    /**
     * Finds an owned question and locks it, after the owner and the pair's rows in [create]'s order, so a concurrent
     * resume or target job delete either removes the new attempt's job or wins first.
     */
    private fun lockQuestion(userId: UUID, condition: String, vararg arguments: Any): QuestionRow? {
        jdbcTemplate.lockOwnerShared(userId)
        val question = findQuestion(userId, condition, *arguments) ?: return null
        lockInputs(userId, question.resumeId, question.targetJobId)
        return findQuestion(userId, "q.id = ?", question.id, lock = true)
    }

    private fun findQuestion(userId: UUID, condition: String, vararg arguments: Any, lock: Boolean = false): QuestionRow? = jdbcTemplate.query(
        """
            SELECT q.id, s.id AS set_id, s.resume_id, s.target_job_id
            FROM ai_interview_app.practice_questions q
            JOIN ai_interview_app.practice_sets s ON s.id = q.practice_set_id AND s.user_id = q.user_id
            WHERE q.user_id = ? AND $condition
            ${if (lock) "FOR UPDATE OF q" else ""}
        """.trimIndent(),
        RowMapper { rs, _ ->
            QuestionRow(
                rs.getObject("id", UUID::class.java), rs.getObject("set_id", UUID::class.java),
                rs.getObject("resume_id", UUID::class.java), rs.getObject("target_job_id", UUID::class.java),
            )
        },
        userId, *arguments,
    ).firstOrNull()

    private fun attemptView(userId: UUID, questionId: UUID, attemptId: UUID): AttemptView =
        attemptViews(userId, attemptRows(userId, "a.question_id = ?", questionId)).single { it.id == attemptId }

    // KTD4: SCORED when feedback exists, else FAILED when the latest job failed, else PENDING. scoreDelta skips unscored attempts.
    private fun attemptViews(userId: UUID, rows: List<AttemptRow>): List<AttemptView> {
        val jobs = backgroundJobStore.findLatestForResources(userId, AttemptFeedbackPayload.RESOURCE, rows.map { it.id }, listOf(JobType.ANSWER_FEEDBACK))
        var previousScore: Int? = null
        return rows.map { row ->
            val job = jobs[row.id]
            val status = when {
                row.feedback != null -> AttemptStatus.SCORED
                job?.status == JobStatus.FAILED -> AttemptStatus.FAILED
                else -> AttemptStatus.PENDING
            }
            val score = row.feedback?.score
            val delta = if (score != null) previousScore?.let { score - it } else null
            if (score != null) previousScore = score
            AttemptView(row.id, row.number, row.text, status, row.feedback, delta, job?.let(ActiveJob::from), row.createdAt)
        }
    }

    private fun attemptRows(userId: UUID, condition: String, vararg arguments: Any): List<AttemptRow> = jdbcTemplate.query(
        """
            SELECT a.id, a.question_id, a.number, a.text, a.feedback::text AS feedback, a.created_at
            FROM ai_interview_app.answer_attempts a
            JOIN ai_interview_app.practice_questions q ON q.id = a.question_id AND q.user_id = a.user_id
            WHERE a.user_id = ? AND $condition
            ORDER BY a.question_id, a.number
        """.trimIndent(),
        RowMapper { rs, _ ->
            AttemptRow(
                rs.getObject("id", UUID::class.java), rs.getObject("question_id", UUID::class.java), rs.getInt("number"),
                rs.getString("text"), rs.getString("feedback")?.let { objectMapper.readValue(it, AnswerFeedbackResult::class.java) },
                rs.getTimestamp("created_at").toInstant(),
            )
        },
        userId, *arguments,
    )

    // KTD4: the status is derived. AI questions mean READY; otherwise the latest generation job decides.
    private fun view(userId: UUID, set: SetRow): PracticeSetView {
        val questions = questions(userId, set.id)
        val job = backgroundJobStore.findLatestForResource(userId, RESOURCE, set.id, listOf(JobType.PRACTICE_QUESTIONS)).orElse(null)
        val status = when {
            questions.any { it.origin == AI } -> PracticeSetStatus.READY
            job?.status == JobStatus.FAILED -> PracticeSetStatus.FAILED
            else -> PracticeSetStatus.GENERATING
        }
        return PracticeSetView(set.id, set.resumeId, set.targetJobId, set.mode, status, questions, job?.let(ActiveJob::from), set.createdAt, set.updatedAt)
    }

    /** Locks the pair's rows against deletion and returns whether the resume is READY. */
    private fun lockInputs(userId: UUID, resumeId: UUID, targetJobId: UUID): Boolean {
        val resumeStatus = jdbcTemplate.query(
            "SELECT processing_status FROM ai_interview_app.resumes WHERE id = ? AND user_id = ? FOR KEY SHARE",
            RowMapper { rs, _ -> rs.getString("processing_status") }, resumeId, userId,
        ).firstOrNull() ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "RESUME_NOT_FOUND", "Resume was not found")
        jdbcTemplate.query(
            "SELECT id FROM ai_interview_app.job_descriptions WHERE id = ? AND user_id = ? FOR KEY SHARE",
            RowMapper { rs, _ -> rs.getObject("id", UUID::class.java) }, targetJobId, userId,
        ).firstOrNull() ?: throw ApiRequestException(HttpStatus.NOT_FOUND, "TARGET_JOB_NOT_FOUND", "Target job was not found")
        return resumeStatus == "READY"
    }

    private fun findPairSet(userId: UUID, resumeId: UUID, targetJobId: UUID) =
        findSet(userId, "resume_id = ? AND target_job_id = ?", resumeId, targetJobId)

    private fun findSet(userId: UUID, condition: String, vararg arguments: Any): SetRow? = jdbcTemplate.query(
        "SELECT id, resume_id, target_job_id, mode, created_at, updated_at FROM ai_interview_app.practice_sets WHERE user_id = ? AND $condition",
        RowMapper { rs, _ ->
            SetRow(
                rs.getObject("id", UUID::class.java), rs.getObject("resume_id", UUID::class.java),
                rs.getObject("target_job_id", UUID::class.java), rs.getString("mode"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
            )
        },
        userId, *arguments,
    ).firstOrNull()

    private fun <T> inTransaction(work: () -> T): T = requireNotNull(transactionOperations.execute { work() })

    private fun notFound(): Nothing = throw ApiRequestException(HttpStatus.NOT_FOUND, "PRACTICE_SET_NOT_FOUND", "Practice set was not found")

    private data class QuestionRow(val id: UUID, val setId: UUID, val resumeId: UUID, val targetJobId: UUID)

    private data class AttemptRow(
        val id: UUID, val questionId: UUID, val number: Int, val text: String, val feedback: AnswerFeedbackResult?, val createdAt: Instant,
    )

    private data class SetRow(
        val id: UUID, val resumeId: UUID, val targetJobId: UUID, val mode: String, val createdAt: Instant, val updatedAt: Instant,
    )

    companion object {
        const val RESOURCE = "practice-set"
        const val AI = "AI"
        const val USER = "USER"
        const val MAX_USER_QUESTIONS = 10
    }
}
