package dev.jiaming.ai_interview.ai

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

fun interface AiHttpTransport {
    @Throws(IOException::class, InterruptedException::class)
    fun send(request: HttpRequest): AiHttpTransportResponse
}

@JvmRecord
data class AiHttpTransportResponse(val statusCode: Int, val body: String)

/** The JDK HTTP client both chat providers send through. */
fun jdkTransport(): AiHttpTransport {
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    return AiHttpTransport { request ->
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        AiHttpTransportResponse(response.statusCode(), response.body())
    }
}
