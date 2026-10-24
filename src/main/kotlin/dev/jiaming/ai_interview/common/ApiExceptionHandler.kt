package dev.jiaming.ai_interview.common

import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.resume.ResumeExtractionException
import dev.jiaming.ai_interview.resume.ResumeParserBusyException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.server.ResponseStatusException
import tools.jackson.core.JacksonException

/** Maps request and provider exceptions to stable API status/error-code responses. */
@RestControllerAdvice
class ApiExceptionHandler {
    /** Returns 422 `RESUME_EXTRACTION_FAILED` with the extraction message. */
    @ExceptionHandler(ResumeExtractionException::class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_CONTENT)
    fun handleResumeExtractionException(exception: ResumeExtractionException) =
        ApiErrorResponse("RESUME_EXTRACTION_FAILED", exception.message)

    /** Returns 503 `RESUME_PARSER_BUSY` with the parser-busy message. */
    @ExceptionHandler(ResumeParserBusyException::class)
    @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
    fun handleResumeParserBusyException(exception: ResumeParserBusyException) =
        ApiErrorResponse("RESUME_PARSER_BUSY", exception.message)

    /** Returns 502 with the provider exception's `AiProviderErrorCode` wire value. */
    @ExceptionHandler(AiProviderException::class)
    @ResponseStatus(HttpStatus.BAD_GATEWAY)
    fun handleAiProviderException(exception: AiProviderException) =
        ApiErrorResponse(exception.code(), exception.message)

    @ExceptionHandler(ApiRequestException::class)
    fun handleApiRequestException(exception: ApiRequestException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(exception.status()).body(ApiErrorResponse(exception.code(), exception.message))

    /** Preserves the HTTP status and maps it with [ErrorCodes.forStatus]. */
    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(exception: ResponseStatusException): ResponseEntity<ApiErrorResponse> =
        ResponseEntity.status(exception.statusCode).body(
            ApiErrorResponse(ErrorCodes.forStatus(exception.statusCode), exception.reason ?: "Request failed")
        )

    /** An unreadable body names the field when Jackson knows it, e.g. an unknown enum value: "mode is invalid". */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleUnreadableBody(exception: HttpMessageNotReadableException): ApiErrorResponse {
        val field = (exception.cause as? JacksonException)?.path?.mapNotNull { it.propertyName }?.joinToString(".")
        return ApiErrorResponse("INVALID_REQUEST", if (field.isNullOrEmpty()) "The request payload is invalid" else "$field is invalid")
    }

    /** Returns 400 `INVALID_REQUEST` for validation and argument-type failures. */
    @ExceptionHandler(
        MethodArgumentNotValidException::class,
        MethodArgumentTypeMismatchException::class
    )
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun handleInvalidRequest(@Suppress("UNUSED_PARAMETER") exception: Exception) =
        ApiErrorResponse("INVALID_REQUEST", "The request payload is invalid")

    /** Returns 413 `UPLOAD_TOO_LARGE`. */
    @ExceptionHandler(MaxUploadSizeExceededException::class)
    @ResponseStatus(HttpStatus.CONTENT_TOO_LARGE)
    fun handleMaxUploadSize(@Suppress("UNUSED_PARAMETER") exception: MaxUploadSizeExceededException) =
        ApiErrorResponse("UPLOAD_TOO_LARGE", "The uploaded file exceeds the configured size limit")

    /** Logs unexpected failures and returns generic 500 `INTERNAL_ERROR` copy. */
    @ExceptionHandler(Exception::class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    fun handleUnexpectedException(exception: Exception): ApiErrorResponse {
        log.error("api_request_failed exceptionType={}", exception.javaClass.name, exception)
        return ApiErrorResponse("INTERNAL_ERROR", "The request could not be completed")
    }

    private companion object {
        val log = LoggerFactory.getLogger(ApiExceptionHandler::class.java)
    }
}
