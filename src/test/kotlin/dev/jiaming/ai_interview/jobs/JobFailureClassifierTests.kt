package dev.jiaming.ai_interview.jobs

import dev.jiaming.ai_interview.common.ApiExceptionHandlerContractTests
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.ai.AiProviderErrorCode
import dev.jiaming.ai_interview.ai.AiProviderException
import dev.jiaming.ai_interview.resume.ResumeExtractionException
import dev.jiaming.ai_interview.resume.ResumeParserBusyException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

class JobFailureClassifierTests {
	private val classifier = JobFailureClassifier()

	@Test
	fun retriesAiProviderRateLimitsAndServerErrors() {
		val rateLimit = classifier.classify(AiProviderException(AiProviderErrorCode.RATE_LIMITED, "quota", 429, true))
		assertThat(rateLimit.code).isEqualTo(AiProviderErrorCode.RATE_LIMITED)
		assertThat(rateLimit.retryable).isTrue()
		assertThat(classifier.classify(ResponseStatusException(HttpStatus.BAD_GATEWAY)).retryable).isTrue()
	}

	@Test
	fun storageFailureHasTheApiCodeAndIsRetried() {
		val thrown = runCatching { ApiExceptionHandlerContractTests.failingStorage().delete("resumes/key") }.exceptionOrNull()!!
		val failure = classifier.classify(RuntimeException("job failed", thrown))
		assertThat(failure.code).isEqualTo("STORAGE_UNAVAILABLE")
		assertThat(failure.retryable).isTrue()
	}

	@Test
	fun libraryStatusFailuresUseTheApiCodes() {
		assertThat(classifier.classify(ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS)).code).isEqualTo("RATE_LIMITED")
		assertThat(classifier.classify(ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE)).code).isEqualTo("SERVICE_UNAVAILABLE")
	}

	@Test
	fun preservesAiProviderRetryMetadataForEmptyAndTerminalFinishReasons() {
		val empty = classifier.classify(AiProviderException(AiProviderErrorCode.EMPTY_RESPONSE, "empty", true))
		val maxTokens = classifier.classify(AiProviderException(AiProviderErrorCode.MAX_TOKENS, "truncated", false))
		assertThat(empty.code).isEqualTo(AiProviderErrorCode.EMPTY_RESPONSE)
		assertThat(empty.retryable).isTrue()
		assertThat(maxTokens.code).isEqualTo(AiProviderErrorCode.MAX_TOKENS)
		assertThat(maxTokens.retryable).isFalse()
	}

	@Test
	fun preservesPermanentApiReferenceFailureCode() {
		val failure = classifier.classify(ApiRequestException(HttpStatus.CONFLICT, "REFERENCE_MISMATCH", "Document reference does not match"))
		assertThat(failure.code).isEqualTo("REFERENCE_MISMATCH")
		assertThat(failure.retryable).isFalse()
	}

	@Test
	fun retriesTemporaryParserSaturation() {
		val failure = classifier.classify(ResumeParserBusyException())
		assertThat(failure.code).isEqualTo("RESUME_PARSER_BUSY")
		assertThat(failure.retryable).isTrue()
	}

	@Test
	fun doesNotRetryInvalidDocumentsOrClientErrors() {
		assertThat(classifier.classify(ResumeExtractionException("Encrypted PDF")).retryable).isFalse()
		assertThat(classifier.classify(ResponseStatusException(HttpStatus.BAD_REQUEST)).retryable).isFalse()
		assertThat(classifier.classify(IllegalArgumentException("bad payload")).retryable).isFalse()
	}
}
