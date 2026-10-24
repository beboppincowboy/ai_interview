package dev.jiaming.ai_interview.common

import org.springframework.http.HttpStatusCode

class ApiRequestException(
    private val responseStatus: HttpStatusCode,
    private val errorCode: String,
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause) {
    fun status(): HttpStatusCode = responseStatus
    fun code(): String = errorCode
}
