package dev.jiaming.ai_interview.practice

import dev.jiaming.ai_interview.jobs.LatestJob
import java.time.Instant
import java.util.UUID

/** Contract §7 `PracticeSet`. */
data class PracticeSetView(
    val id: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
    val mode: String,
    val status: PracticeSetStatus,
    val questions: List<PracticeQuestionView>,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

enum class PracticeSetStatus { GENERATING, READY, FAILED }

/** Contract §7 `Question`; `attempts` are oldest first. */
data class PracticeQuestionView(
    val id: UUID,
    val order: Int,
    val origin: String,
    val text: String,
    val rationale: String?,
    val category: String?,
    val expectedSignals: List<String>,
    val attempts: List<AttemptView> = emptyList(),
)

/** Contract §7 `Attempt`. `status` and `scoreDelta` are derived when read (KTD4). */
data class AttemptView(
    val id: UUID,
    val number: Int,
    val text: String,
    val status: AttemptStatus,
    val feedback: AnswerFeedbackResult?,
    val scoreDelta: Int?,
    /** Newest background job for this resource, in any status (check [LatestJob.status]); null only if none was ever submitted. */
    val latestJob: LatestJob?,
    val createdAt: Instant,
)

enum class AttemptStatus { PENDING, SCORED, FAILED }

/** Contract §8.2 `AnswerFeedbackResult`: the attempt's stored feedback and its `ANSWER_FEEDBACK` job result. */
data class AnswerFeedbackResult(
    val score: Int,
    val summary: String,
    val nextStep: String?,
    val strengths: List<String>,
    val gaps: List<String>,
    val betterAnswerOutline: List<String>,
    val followUpQuestion: String?,
)

/** What the feedback handler scores: the attempt's text and its question. */
data class AttemptScoringInput(val text: String, val questionText: String, val category: String?, val expectedSignals: List<String>)

data class SubmitAttemptRequest(val text: String?)

/** One normalized AI question before it is saved. */
data class PracticeQuestionDraft(val text: String, val rationale: String, val category: String?, val expectedSignals: List<String>)

/** The generated questions, saved as the job checkpoint so a retry never calls the model again. */
data class PracticeQuestionDrafts(val drafts: List<PracticeQuestionDraft>)

/** The `PRACTICE_QUESTIONS` job result (contract §8.1). */
data class PracticeQuestionsResult(val questions: List<PracticeQuestionView>)

data class PracticeSetCreation(val set: PracticeSetView, val created: Boolean)

data class CreatePracticeSetRequest(val resumeId: UUID?, val targetJobId: UUID?, val mode: String?)

data class AddPracticeQuestionRequest(val text: String?)

@JvmRecord
data class PracticeQuestionsPayload(
    val payloadVersion: Int,
    val practiceSetId: UUID,
    val resumeId: UUID,
    val targetJobId: UUID,
) {
    constructor(practiceSetId: UUID, resumeId: UUID, targetJobId: UUID) : this(CURRENT_VERSION, practiceSetId, resumeId, targetJobId)

    companion object { const val CURRENT_VERSION = 1 }
}
