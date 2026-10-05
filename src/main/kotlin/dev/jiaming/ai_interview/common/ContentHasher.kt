package dev.jiaming.ai_interview.common

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.security.NoSuchAlgorithmException
import java.util.HexFormat
import org.springframework.stereotype.Component

@Component
class ContentHasher {
    fun sha256(normalizedText: String?): String {
        require(normalizedText != null) { "Normalized text is required" }
        return sha256Hex(normalizedText)
    }
}

fun sha256Hex(value: String): String = try {
    HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8)))
} catch (exception: NoSuchAlgorithmException) {
    throw IllegalStateException("SHA-256 is unavailable", exception)
}
