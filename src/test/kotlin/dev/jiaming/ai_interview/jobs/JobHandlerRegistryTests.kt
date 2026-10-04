package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JobHandlerRegistryTests {
	@Test
	fun startsWithExactlyTheEightContractJobTypes() {
		val types = listOf("RESUME_EXTRACTION", "RESUME_SCORE", "JOB_FIT", "EXPERIENCE_SUGGESTIONS", "PRACTICE_QUESTIONS",
			"EXPERIENCE_SPLIT", "ANSWER_FEEDBACK", "VOICE_REPORT").map(JobType::valueOf)
		val handlers = types.map(::handler)
		val registry = JobHandlerRegistry(handlers)
		types.zip(handlers).forEach { (type, handler) -> assertThat(registry.require(type)).isSameAs(handler) }
		assertThat(JobType.entries).containsExactlyInAnyOrderElementsOf(types)
	}

	@Test
	fun rejectsDuplicateHandlers() {
		assertThatThrownBy {
			JobHandlerRegistry(listOf(handler(JobType.RESUME_EXTRACTION), handler(JobType.JOB_FIT), handler(JobType.JOB_FIT), handler(JobType.ANSWER_FEEDBACK), handler(JobType.EXPERIENCE_SPLIT)))
		}.isInstanceOf(IllegalStateException::class.java).hasMessageContaining("Multiple job handlers")
	}

	@Test
	fun rejectsMissingHandlers() {
		assertThatThrownBy { JobHandlerRegistry(listOf(handler(JobType.JOB_FIT))) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("Missing job handlers")
	}

	private fun handler(type: JobType): JobHandler<Any> = object : JobHandler<Any> {
		override fun type() = type
		override fun payloadType() = Any::class.java
		override fun handle(payload: Any, context: JobExecutionContext): JsonNode? = null
	}
}
