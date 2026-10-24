package dev.jiaming.ai_interview.jobs

import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.server.ResponseStatusException
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.common.ErrorCodes
import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.resume.ResumeExtractionException
import dev.jiaming.ai_interview.resume.ResumeParserBusyException

@Component
internal class JobFailureClassifier {
    fun classify(throwable: Throwable): JobFailure {
        val cause = unwrap(throwable)
        return when (cause) {
            is AiProviderException -> JobFailure(cause.code(), message(cause), cause.retryable())
            is ApiRequestException -> JobFailure(cause.code(), message(cause), retryable(cause.status()))
            is ResumeParserBusyException -> JobFailure("RESUME_PARSER_BUSY", message(cause), true)
            is ResumeExtractionException -> JobFailure("RESUME_EXTRACTION_FAILED", message(cause), false)
            is ResponseStatusException -> JobFailure(ErrorCodes.forStatus(cause.statusCode), message(cause), retryable(cause.statusCode))
            is IllegalArgumentException -> JobFailure("INVALID_REQUEST", message(cause), false)
            else -> JobFailure("PROCESSING_ERROR", message(cause), true)
        }
    }

    private fun unwrap(throwable: Throwable): Throwable {
        var current = throwable
        while (current.cause != null && current !== current.cause) {
            if (current is AiProviderException || current is ApiRequestException || current is ResumeExtractionException ||
                current is ResponseStatusException || current is IllegalArgumentException) return current
            current = current.cause!!
        }
        return current
    }

    private fun retryable(status: HttpStatusCode) = status.value() == 408 || status.value() == 429 || status.is5xxServerError

    private fun message(throwable: Throwable): String = throwable.message?.takeIf { it.isNotBlank() }
        ?: throwable.javaClass.simpleName
}
