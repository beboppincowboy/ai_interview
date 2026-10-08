package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.experience.ExperienceSplitJobPayload
import dev.jiaming.ai_interview.voice.VoiceReportPayload
import java.time.Instant
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class JobPayloadDecoderTests {
	private val objectMapper = ObjectMapper().findAndRegisterModules()
	private val decoder = JobPayloadDecoder(objectMapper)

	@Test
	fun readsCurrentJobFitPayload() {
		val payload = JobFitPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

		val decoded = decoder.decode(job(objectMapper.valueToTree(payload), JobType.JOB_FIT, "job-fit"), JobFitPayload::class.java)

		assertThat(decoded).isEqualTo(payload)
	}

	@Test
	fun readsExperienceSplitPayloadWithoutResourceResolution() {
		val payload = ExperienceSplitJobPayload(text = "LinkedIn Experience section with enough detail to split")

		val decoded = decoder.decode(job(objectMapper.valueToTree(payload), JobType.EXPERIENCE_SPLIT, null), ExperienceSplitJobPayload::class.java)

		assertThat(decoded).isEqualTo(payload)
	}

	@Test
	fun attemptJobsDecodeTheirOwnPayloadVersion() {
		val payload = AttemptFeedbackPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
		val stored = objectMapper.valueToTree<JsonNode>(payload)
		assertThat(stored.fieldNames().asSequence().toList()).containsExactly("payloadVersion", "attemptId", "practiceSetId", "resumeId", "targetJobId")
		assertThat(stored.path("payloadVersion").asInt()).isEqualTo(3)

		val decoded = decoder.decode(job(stored, JobType.ANSWER_FEEDBACK, AttemptFeedbackPayload.RESOURCE), AttemptFeedbackPayload::class.java)

		assertThat(decoded).isEqualTo(payload)
	}

	@Test
	fun feedbackJobsThatAreNotCurrentAttemptJobsAreRejected() {
		val attempt = objectMapper.valueToTree<JsonNode>(AttemptFeedbackPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()))
		val oldVersion = objectMapper.readTree("""{"payloadVersion":2,"resumeId":"${UUID.randomUUID()}","answerText":"A"}""")
		val unversioned = objectMapper.readTree("""{"resumeText":"resume","answerText":"A"}""")

		val rejected = listOf(oldVersion to AttemptFeedbackPayload.RESOURCE, unversioned to AttemptFeedbackPayload.RESOURCE, attempt to "resume")
		for ((payload, resourceType) in rejected) {
			assertThatThrownBy { decoder.decode(job(payload, JobType.ANSWER_FEEDBACK, resourceType), AttemptFeedbackPayload::class.java) }
				.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("AttemptFeedbackPayload")
		}
	}

	@Test
	fun voiceReportJobsDecodeOnlyTheCurrentPayloadVersion() {
		val payload = VoiceReportPayload(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())
		val stored = objectMapper.valueToTree<JsonNode>(payload)
		assertThat(stored.fieldNames().asSequence().toList()).containsExactly("payloadVersion", "voiceSessionId", "resumeId", "targetJobId")

		assertThat(decoder.decode(job(stored, JobType.VOICE_REPORT, VoiceReportPayload.RESOURCE), VoiceReportPayload::class.java)).isEqualTo(payload)
		val unversioned = objectMapper.readTree("""{"voiceSessionId":"${payload.voiceSessionId}"}""")
		assertThatThrownBy { decoder.decode(job(unversioned, JobType.VOICE_REPORT, VoiceReportPayload.RESOURCE), VoiceReportPayload::class.java) }
			.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining("VoiceReportPayload")
	}

	private fun job(payload: JsonNode, jobType: JobType, resourceType: String?): BackgroundJob {
		val now = Instant.now()
		return BackgroundJob(UUID.randomUUID(), UUID.randomUUID(), jobType, resourceType, null, JobStatus.PROCESSING, JobStage.QUEUED, payload, null, "fingerprint", 1, 3, null, null, null, now, now, now, now, now, null, UUID.randomUUID(), now.plusSeconds(300))
	}
}
