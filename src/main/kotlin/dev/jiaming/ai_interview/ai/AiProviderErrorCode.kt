package dev.jiaming.ai_interview.ai

/** The `GEMINI_` prefix is historical and frozen in the public wire codes, regardless of the configured provider. */
object AiProviderErrorCode {
    /** Raised when the configured AI provider has no API key. */
    const val NOT_CONFIGURED = "GEMINI_NOT_CONFIGURED"
    /** Raised when the AI provider rejects a request because of rate limiting or quota. */
    const val RATE_LIMITED = "GEMINI_RATE_LIMITED"
    /** Raised when an AI provider request times out or is interrupted. */
    const val TIMEOUT = "GEMINI_TIMEOUT"
    /** Raised when the AI provider is unreachable or returns an unsupported or unreadable response. */
    const val UPSTREAM_ERROR = "GEMINI_UPSTREAM_ERROR"
    /** Raised when an AI provider refuses or filters content for safety. */
    const val SAFETY = "GEMINI_SAFETY"
    /** Raised when Gemini blocks a response for recitation. */
    const val RECITATION = "GEMINI_RECITATION"
    /** Raised when the AI provider stops generation at its output token limit. */
    const val MAX_TOKENS = "GEMINI_MAX_TOKENS"
    /** Raised when the AI provider returns no candidate, choice, or text. */
    const val EMPTY_RESPONSE = "GEMINI_EMPTY_RESPONSE"
    /** Raised for model output outside the coach schema; the coach service retries once, then returns this code on a second failure. */
    const val INVALID_RESPONSE = "GEMINI_INVALID_RESPONSE"
}
