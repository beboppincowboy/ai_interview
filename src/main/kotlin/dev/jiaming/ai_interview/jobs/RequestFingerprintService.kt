package dev.jiaming.ai_interview.jobs

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.sha256Hex
import org.springframework.stereotype.Component

@Component
class RequestFingerprintService(private val objectMapper: ObjectMapper) {
    fun fingerprint(action: String, source: Any): String = try {
        sha256Hex(objectMapper.writeValueAsString(listOf(action, source)))
    } catch (_: JsonProcessingException) {
        sha256Hex("$action:$source")
    }
}
