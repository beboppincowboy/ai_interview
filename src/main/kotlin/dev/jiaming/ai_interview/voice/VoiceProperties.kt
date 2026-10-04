package dev.jiaming.ai_interview.voice

import org.springframework.boot.cloud.CloudPlatform
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component

/** Spoken interviews. The defaults are the model, API version and silence threshold the U1 provider proof used. */
@ConfigurationProperties(prefix = "app.voice")
class VoiceProperties(enabled: Boolean?, model: String?, apiVersion: String?, silenceMs: Int?, apiKey: String?) {
    val enabled: Boolean = enabled ?: false
    val model: String = model?.takeIf(String::isNotBlank) ?: "gemini-3.8-live"
    val apiVersion: String = (apiVersion?.takeIf(String::isNotBlank) ?: "v1alpha").also {
        require(it == "v1alpha" || it == "v1beta") { "VOICE_API_VERSION must be v1alpha or v1beta" }
    }
    val silenceMs: Int = silenceMs?.takeIf { it > 0 } ?: 4500
    val apiKey: String = apiKey.orEmpty()
}

/** Voice mints provider tokens with no authentication, so it may only start as a local development feature (KTD2). */
@Component
class VoiceStartupGate(properties: VoiceProperties, environment: Environment) {
    init {
        if (properties.enabled) {
            val platform = CloudPlatform.getActive(environment)
            check(platform == null || platform == CloudPlatform.NONE) {
                "VOICE_ENABLED is for local development only; refusing to start on $platform"
            }
            check(!environment.getProperty("server.address").isNullOrBlank()) {
                "VOICE_ENABLED needs an explicit SERVER_ADDRESS (127.0.0.1 for bootRun); otherwise Tomcat listens on every interface"
            }
            check(properties.apiKey.isNotBlank()) { "VOICE_ENABLED needs GEMINI_API_KEY to mint Live tokens" }
        }
    }
}
