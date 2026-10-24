package dev.jiaming.ai_interview.score

import dev.jiaming.ai_interview.coach.AssessmentScores
import dev.jiaming.ai_interview.coach.Priority
import java.time.Instant

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

@JvmRecord
data class ResumeScoreFix(val rank: Int, val section: String, val priority: Priority, val message: String)

@JvmRecord
data class ResumeScoreRewrite(
    val section: String,
    val original: String,
    val rewritten: String,
    val placeholders: List<String>
)

@JvmRecord
data class ResumeScoreSummary(val overall: Int, val scoredAt: Instant, val stale: Boolean)
