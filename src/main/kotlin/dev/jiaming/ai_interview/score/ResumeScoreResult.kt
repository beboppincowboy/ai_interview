package dev.jiaming.ai_interview.score

import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.coach.Priority
import java.time.Instant

/** Scored resume output with component scores, summary, prioritized fixes, grounded rewrites, optional job title, and time. */
@JvmRecord
data class ResumeScoreResult(
    val overall: Int,
    val scores: AssessmentScores,
    val summary: String,
    val fixes: List<ResumeScoreFix>,
    val rewrites: List<ResumeScoreRewrite>,
    val jobTitle: String?,
    val scoredAt: Instant
)

/** Ranked and prioritized action for improving a resume. */
@JvmRecord
data class ResumeScoreFix(val rank: Int, val section: String, val priority: Priority, val message: String)

/** Rewrite suggestion tied to an original line and required placeholders. */
@JvmRecord
data class ResumeScoreRewrite(
    val section: String,
    val original: String,
    val rewritten: String,
    val placeholders: List<String>
)

/** Latest score shown in the library with its timestamp and stale-title flag. */
@JvmRecord
data class ResumeScoreSummary(val overall: Int, val scoredAt: Instant, val stale: Boolean)
