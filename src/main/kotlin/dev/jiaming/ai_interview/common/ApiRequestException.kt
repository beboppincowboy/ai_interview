package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatusCode

/**
 * An API failure with its own wire code (contract §10) and HTTP status; the API returns both.
 *
 * In a background job, `JobFailureClassifier` preserves this code and marks statuses 408, 429, and 5xx retryable; all
 * other statuses are non-retryable and fail the job permanently with this code.
 */
class ApiRequestException(
    private val responseStatus: HttpStatusCode,
    private val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    fun status(): HttpStatusCode = responseStatus
    fun code(): String = errorCode
}
