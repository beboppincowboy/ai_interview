package dev.jiaming.ai_interview.coach

import dev.jiaming.ai_interview.document.ResolvedDocument
import java.util.Optional

class CoachFeedbackInput(
    private val resumeValue: ResolvedDocument,
    targetJobValue: Optional<ResolvedDocument>?,
    private val questionTextValue: String?,
    private val categoryValue: String?,
    expectedSignalsValue: List<String>?,
    private val answerTextValue: String?,
    private val incompleteCaptureValue: Boolean = false,
) {
    private val targetJobValue = targetJobValue ?: Optional.empty()
    private val expectedSignalsValue = expectedSignalsValue?.toList() ?: emptyList()
    fun resume() = resumeValue
    fun targetJob() = targetJobValue
    fun questionText() = questionTextValue
    fun category() = categoryValue
    fun expectedSignals() = expectedSignalsValue
    fun answerText() = answerTextValue
    fun incompleteCapture() = incompleteCaptureValue

    override fun equals(other: Any?): Boolean = other is CoachFeedbackInput &&
        resumeValue == other.resumeValue && targetJobValue == other.targetJobValue &&
        questionTextValue == other.questionTextValue && categoryValue == other.categoryValue &&
        expectedSignalsValue == other.expectedSignalsValue && answerTextValue == other.answerTextValue &&
        incompleteCaptureValue == other.incompleteCaptureValue

    override fun hashCode(): Int = listOf(
        resumeValue, targetJobValue, questionTextValue, categoryValue, expectedSignalsValue, answerTextValue, incompleteCaptureValue
    ).hashCode()

    override fun toString(): String =
        "CoachFeedbackInput[resume=$resumeValue, targetJob=$targetJobValue, questionText=$questionTextValue, category=$categoryValue, expectedSignals=$expectedSignalsValue, answerText=$answerTextValue, incompleteCapture=$incompleteCaptureValue]"
}
