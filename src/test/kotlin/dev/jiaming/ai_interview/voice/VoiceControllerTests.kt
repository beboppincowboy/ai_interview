package dev.jiaming.ai_interview.voice

import dev.jiaming.ai_interview.common.ApiExceptionHandler
import java.time.Instant
import java.util.UUID
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class VoiceControllerTests {
    @Test
    fun `token route binds question id ignores caller constraints and marks response no-store`() {
        val sessionId = UUID.randomUUID()
        val question = question()
        val deadline = Instant.now().plusSeconds(300)
        val reservation = VoiceTokenReservation(question, deadline)
        val token = VoiceTokenResponse("auth_tokens/secret", "gemini-3.8-live", "v1beta", deadline, deadline)
        val service = Mockito.mock(VoiceSessionService::class.java)
        val tokenClient = Mockito.mock(GeminiLiveTokenClient::class.java)
        Mockito.`when`(service.reserveToken(sessionId, question.id)).thenReturn(reservation)
        Mockito.`when`(tokenClient.mint(question, deadline)).thenReturn(token)

        mvc(service, tokenClient).perform(
            post("/api/voice-sessions/{sessionId}/tokens", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"questionId":"${question.id}","ownerId":"${UUID.randomUUID()}","model":"caller-selected-model","systemInstruction":"caller prompt","tools":[{}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.token").value("auth_tokens/secret"))
            .andExpect(jsonPath("$.model").value("gemini-3.8-live"))
            .andExpect(jsonPath("$.apiVersion").value("v1beta"))

        Mockito.verify(service).reserveToken(sessionId, question.id)
        Mockito.verify(tokenClient).mint(question, deadline)
    }

    @Test
    fun `disabled gate blocks draft creation and token calls while reads remain available`() {
        val sessionId = UUID.randomUUID()
        val view = view(sessionId, question())
        val service = Mockito.mock(VoiceSessionService::class.java)
        val tokenClient = Mockito.mock(GeminiLiveTokenClient::class.java)
        Mockito.`when`(service.get(sessionId)).thenReturn(view)
        val mockMvc = mvc(service, tokenClient, enabled = false)

        mockMvc.perform(get("/api/voice-sessions/{sessionId}", sessionId))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(sessionId.toString()))

        mockMvc.perform(
            post("/api/voice-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"practiceSetId":"${UUID.randomUUID()}"}"""),
        ).andExpect(status().isServiceUnavailable)

        mockMvc.perform(
            post("/api/voice-sessions/{sessionId}/tokens", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"questionId":"${view.questions.first().id}"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("VOICE_DISABLED"))

        Mockito.verify(service).get(sessionId)
        Mockito.verifyNoMoreInteractions(service, tokenClient)
    }

    @Test
    fun `save body binds typed answers and malformed question ids return bad request`() {
        val sessionId = UUID.randomUUID()
        val question = question()
        val service = Mockito.mock(VoiceSessionService::class.java)
        val tokenClient = Mockito.mock(GeminiLiveTokenClient::class.java)
        val savedView = view(sessionId, question)
        val transcript = VoiceTranscript(listOf(VoiceAnswer(question.id, "Tell me about it", "Reviewed answer", true)))
        Mockito.`when`(service.save(sessionId, transcript)).thenReturn(VoiceSaveResult(savedView, true))
        val mockMvc = mvc(service, tokenClient)

        mockMvc.perform(
            post("/api/voice-sessions/{sessionId}/save", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":[{"questionId":"${question.id}","interviewerText":"Tell me about it","answerText":"Reviewed answer","incomplete":true}]}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.replayed").value(true))
            .andExpect(jsonPath("$.session.transcript.answers[0].answerText").value("Reviewed answer"))

        mockMvc.perform(
            post("/api/voice-sessions/{sessionId}/save", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":[{"questionId":"not-a-uuid","interviewerText":"Asked","answerText":"Answer"}]}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        Mockito.verify(service).save(sessionId, transcript)
        Mockito.verifyNoMoreInteractions(service, tokenClient)
    }

    @Test
    fun `missing create token and answer fields return bad request before service calls`() {
        val sessionId = UUID.randomUUID()
        val service = Mockito.mock(VoiceSessionService::class.java)
        val tokenClient = Mockito.mock(GeminiLiveTokenClient::class.java)
        val mockMvc = mvc(service, tokenClient)

        mockMvc.perform(
            post("/api/voice-sessions")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            post("/api/voice-sessions/{sessionId}/tokens", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
            .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))

        mockMvc.perform(
            post("/api/voice-sessions/{sessionId}/save", sessionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"answers":[{"questionId":"${UUID.randomUUID()}","interviewerText":"Asked"}]}"""),
        ).andExpect(status().isBadRequest)

        Mockito.verifyNoInteractions(service, tokenClient)
    }

    private fun mvc(
        service: VoiceSessionService,
        tokenClient: GeminiLiveTokenClient,
        enabled: Boolean = true,
    ): MockMvc = standaloneSetup(
        VoiceController(service, tokenClient, VoiceProperties(enabled, null, null, null, "server-key")),
    ).setControllerAdvice(ApiExceptionHandler()).build()

    private fun question() = VoiceQuestion(UUID.randomUUID(), "Tell me about a difficult tradeoff.", "Judgment", listOf("decision"))

    private fun view(sessionId: UUID, question: VoiceQuestion) = VoiceSessionView(
        sessionId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        VoiceSessionStatus.SAVED,
        listOf(question),
        VoiceTranscript(listOf(VoiceAnswer(question.id, "Tell me about it", "Reviewed answer", true))),
        UUID.randomUUID(),
        UUID.randomUUID(),
        Instant.now(),
        Instant.now().plusSeconds(300),
        Instant.now().plusSeconds(86_400),
        Instant.now(),
    )
}
