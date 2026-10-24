package dev.jiaming.ai_interview.ai

import java.io.IOException
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Sends one provider HTTP request; I/O and interruption failures propagate to the provider client. */
fun interface AiHttpTransport {
    /** Returns the response status and body without interpreting HTTP status as success or failure. */
    @Throws(IOException::class, InterruptedException::class)
    fun send(request: HttpRequest): AiHttpTransportResponse
}

/** Raw HTTP status and response body returned by [AiHttpTransport.send]. */
@JvmRecord
data class AiHttpTransportResponse(val statusCode: Int, val body: String)

/** JDK transport shared by both chat providers; it uses a 10-second connect timeout and propagates I/O failures. */
fun jdkTransport(): AiHttpTransport {
    val httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    return AiHttpTransport { request ->
        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        AiHttpTransportResponse(response.statusCode(), response.body())
    }
}
