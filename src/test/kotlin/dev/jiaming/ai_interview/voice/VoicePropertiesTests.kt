package dev.jiaming.ai_interview.voice

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.mock.env.MockEnvironment

class VoicePropertiesTests {
	private fun enabled(apiKey: String? = "test-key") = VoiceProperties(true, null, null, null, apiKey)

	@Test
	fun voiceIsOffByDefaultWithBetaLiveSettings() {
		val properties = VoiceProperties(null, null, null, null, null)
		assertThat(properties.enabled).isFalse()
		assertThat(properties.model).isEqualTo("gemini-3.8-live")
		assertThat(properties.apiVersion).isEqualTo("v1beta")
		assertThat(properties.silenceMs).isEqualTo(4500)
	}

	@Test
	fun rejectsApiVersionsOutsideThePinnedBetaContract() {
		assertThatThrownBy { VoiceProperties(false, null, "v1alpha", null, null) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("v1beta")
		assertThatThrownBy { VoiceProperties(false, null, "v2", null, null) }
			.isInstanceOf(IllegalArgumentException::class.java)
			.hasMessageContaining("v1beta")
		assertThat(VoiceProperties(false, null, "v1beta", null, null).apiVersion).isEqualTo("v1beta")
	}

	@Test
	fun disabledVoiceStartsAnywhere() {
		VoiceStartupGate(VoiceProperties(false, null, null, null, null), MockEnvironment().withProperty("spring.main.cloud-platform", "kubernetes"))
	}

	@Test
	fun enabledVoiceRefusesToStartOnACloudPlatform() {
		val kubernetes = MockEnvironment().withProperty("spring.main.cloud-platform", "kubernetes").withProperty("server.address", "127.0.0.1")
		assertThatThrownBy { VoiceStartupGate(enabled(), kubernetes) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("local development only")
	}

	@Test
	fun enabledVoiceNeedsAnExplicitBindAddress() {
		assertThatThrownBy { VoiceStartupGate(enabled(), MockEnvironment()) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("SERVER_ADDRESS")
	}

	@Test
	fun enabledVoiceNeedsTheServerKey() {
		assertThatThrownBy { VoiceStartupGate(enabled(apiKey = " "), MockEnvironment().withProperty("server.address", "127.0.0.1")) }
			.isInstanceOf(IllegalStateException::class.java)
			.hasMessageContaining("GEMINI_API_KEY")
	}

	@Test
	fun enabledVoiceStartsLocallyOnAnExplicitAddress() {
		VoiceStartupGate(enabled(), MockEnvironment().withProperty("server.address", "127.0.0.1"))
		VoiceStartupGate(enabled(), MockEnvironment().withProperty("server.address", "0.0.0.0").withProperty("spring.main.cloud-platform", "none"))
	}
}
