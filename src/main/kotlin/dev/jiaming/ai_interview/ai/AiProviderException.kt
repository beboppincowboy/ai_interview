package dev.jiaming.ai_interview.ai

class AiProviderException private constructor(
    val code: String,
    message: String,
    cause: Throwable?,
    val statusCode: Int?,
    val retryable: Boolean
) : RuntimeException(message, cause) {
    fun code() = code
    fun statusCode() = statusCode
    fun retryable() = retryable
    constructor(code: String, message: String, retryable: Boolean) : this(code, message, null, null, retryable)
    constructor(code: String, message: String, cause: Throwable?, retryable: Boolean) : this(code, message, cause, null, retryable)
    constructor(code: String, message: String, statusCode: Int?, retryable: Boolean) : this(code, message, null, statusCode, retryable)
}
