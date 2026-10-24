package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.ObjectMapper
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class JobInputRefsTests {
	private val objectMapper = ObjectMapper()

	@Test
	fun storedPayloadWithLegacyJobDescriptionIdKeyStillDecodesItsTargetJob() {
		val targetJobId = UUID.randomUUID()
		val stored = objectMapper.readTree("""{"payloadVersion":1,"resumeId":"${UUID.randomUUID()}","jobDescriptionId":"$targetJobId"}""")

		assertThat(JobInputRefs.from(stored, UUID.randomUUID(), "job-fit").targetJobId).isEqualTo(targetJobId)
	}

	@Test
	fun targetJobIdKeyWinsOverLegacyKey() {
		val targetJobId = UUID.randomUUID()
		val stored = objectMapper.readTree("""{"targetJobId":"$targetJobId","jobDescriptionId":"${UUID.randomUUID()}"}""")

		assertThat(JobInputRefs.from(stored, null, null).targetJobId).isEqualTo(targetJobId)
	}
}
