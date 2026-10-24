package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatusCode

/**
 * The one mapping from a library failure's HTTP status (a Spring `ResponseStatusException`) to its wire error code.
 * [ApiExceptionHandler] uses it for API responses and the job failure classifier for failed jobs, so a failure has
 * one code wherever it surfaces. Failures with their own code (storage, AI provider, `ApiRequestException`) keep it.
 */
object ErrorCodes {
    fun forStatus(status: HttpStatusCode): String = when (status.value()) {
        400 -> "INVALID_REQUEST"
        404 -> "NOT_FOUND"
        409 -> "CONFLICT"
        413 -> "UPLOAD_TOO_LARGE"
        415 -> "UNSUPPORTED_FILE_TYPE"
        422 -> "UNPROCESSABLE_CONTENT"
        429 -> "RATE_LIMITED"
        503 -> "SERVICE_UNAVAILABLE"
        else -> "REQUEST_FAILED"
    }
}
