package dev.jiaming.ai_interview.fit

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.jobs.LatestJob
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobType
import java.util.UUID
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup

class JobFitResultTests {
    private val objectMapper = ObjectMapper().findAndRegisterModules()

    @Test
    fun `legacy saved result decodes without fabricated evidence metadata or freshness`() {
        val result = objectMapper.readValue(LEGACY_RESULT_JSON, JobFitResult::class.java)
        val view = FitView(UUID.fromString(RESUME_ID), UUID.fromString(TARGET_JOB_ID), result, null, null)
        val viewJson = objectMapper.readTree(objectMapper.writeValueAsString(view))

        assertThat(result.matchedRequirements).containsExactly(MatchedRequirement("Kotlin", "Built Kotlin services"))
        assertThat(result.missingRequirements).containsExactly(MissingRequirement("Kafka", "Describe relevant work if available."))
        assertThat(viewJson.path("freshness").asText()).isEqualTo("UNKNOWN")
        assertThat(viewJson.path("result").has("agenticMetadata")).isFalse()
    }

    @Test
    fun `legacy result with empty lists still decodes`() {
        val json = """{"fitScore":0,"summary":"No evidence","matchedRequirements":[],"missingRequirements":[],"feedback":[]}"""
        val result = objectMapper.readValue(json, JobFitResult::class.java)

        assertThat(result).isEqualTo(JobFitResult(0, "No evidence", emptyList(), emptyList(), emptyList()))
        assertThat(result.agenticMetadata).isNull()
    }

    @Test
    fun `declared conflict cannot be hidden by omitting its assessment link`() {
        val metadata = requireNotNull(objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java).agenticMetadata)
        val assessments = metadata.assessments.map {
            if (it.requirementId == "req-kafka") it.copy(
                status = JobFitEvidenceStatus.SUPPORTED,
                conflictIds = emptyList(),
                missingEvidenceDescription = null,
            ) else it
        }
        val judgments = metadata.judgments.map {
            if (it.requirementId == "req-kafka") it.copy(status = JobFitEvidenceStatus.SUPPORTED, missingEvidence = emptyList()) else it
        }

        val thrown = catchThrowable { metadata.copy(assessments = assessments, judgments = judgments) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("declared conflict")
    }

    @Test
    fun `one-sided supported citation cannot omit its declared conflict link`() {
        val metadata = requireNotNull(objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java).agenticMetadata)
        val assessments = metadata.assessments.map {
            if (it.requirementId == "req-kafka") it.copy(
                status = JobFitEvidenceStatus.SUPPORTED,
                evidenceIds = listOf("experience-kafka-a"),
                conflictIds = emptyList(),
                missingEvidenceDescription = null,
            ) else it
        }
        val judgments = metadata.judgments.map {
            if (it.requirementId == "req-kafka") it.copy(
                status = JobFitEvidenceStatus.SUPPORTED,
                evidenceIds = listOf("experience-kafka-a"),
                missingEvidence = emptyList(),
            ) else it
        }

        val thrown = catchThrowable { metadata.copy(assessments = assessments, judgments = judgments) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("declared conflict")
    }

    @Test
    fun `one-sided partial citation cannot omit its declared conflict link`() {
        val metadata = requireNotNull(objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java).agenticMetadata)
        val assessments = metadata.assessments.map {
            if (it.requirementId == "req-kafka") it.copy(
                evidenceIds = listOf("experience-kafka-a"),
                conflictIds = emptyList(),
            ) else it
        }
        val judgments = metadata.judgments.map {
            if (it.requirementId == "req-kafka") it.copy(evidenceIds = listOf("experience-kafka-a")) else it
        }

        val thrown = catchThrowable { metadata.copy(assessments = assessments, judgments = judgments) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("declared conflict")
    }

    @Test
    fun `a linked conflict still requires both candidate citations`() {
        val metadata = requireNotNull(objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java).agenticMetadata)
        val assessments = metadata.assessments.map {
            if (it.requirementId == "req-kafka") it.copy(evidenceIds = listOf("experience-kafka-a")) else it
        }
        val judgments = metadata.judgments.map {
            if (it.requirementId == "req-kafka") it.copy(evidenceIds = listOf("experience-kafka-a")) else it
        }

        val thrown = catchThrowable { metadata.copy(assessments = assessments, judgments = judgments) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("cite both sides")
    }

    @Test
    fun `judgment evidence order is irrelevant while assessment order drives the legacy quote`() {
        val metadata = requireNotNull(objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java).agenticMetadata)
        val secondKotlinEvidence = JobFitEvidenceItem(
            id = "resume-kotlin-second",
            snapshotId = metadata.snapshotId,
            sourceType = JobFitSourceType.RESUME,
            resourceId = UUID.fromString(RESUME_ID),
            contentHash = "resume-hash",
            sourceLabel = "Resume",
            passage = "Led another Kotlin service.",
            quote = "Led another Kotlin service.",
        )
        val assessments = metadata.assessments.map {
            if (it.requirementId == "req-kotlin") {
                it.copy(evidenceIds = listOf("resume-kotlin", "resume-kotlin-second"))
            } else it
        }
        val judgments = metadata.judgments.map {
            if (it.requirementId == "req-kotlin") {
                it.copy(evidenceIds = listOf("resume-kotlin-second", "resume-kotlin"))
            } else it
        }

        val reordered = metadata.copy(
            assessments = assessments,
            evidenceCatalog = metadata.evidenceCatalog + secondKotlinEvidence,
            judgments = judgments,
        )

        assertThat(reordered.legacyMatchedRequirements()).containsExactly(
            MatchedRequirement("Kotlin", "Built Kotlin services in production. Led another Kotlin service."),
        )
        assertThat(reordered.legacyMissingRequirements()).containsExactlyElementsOf(metadata.legacyMissingRequirements())
    }

    @Test
    fun `judgment evidence with a different set from its assessment is rejected`() {
        val metadata = requireNotNull(objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java).agenticMetadata)
        val judgments = metadata.judgments.map {
            if (it.requirementId == "req-kafka") it.copy(evidenceIds = listOf("experience-kafka-a")) else it
        }

        val thrown = catchThrowable { metadata.copy(judgments = judgments) }

        assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("same candidate evidence")
    }

    @Test
    fun `agentic fit score must stay within zero and one hundred`() {
        val result = objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java)

        for (score in listOf(-1, 101)) {
            val thrown = catchThrowable { result.copy(fitScore = score) }
            assertThat(thrown).isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("Agentic fit score")
        }
        for (score in listOf(0, 100)) {
            assertThat(result.copy(fitScore = score).fitScore).isEqualTo(score)
        }
    }

    @Test
    fun `legacy saved results keep their historical fit score decoding`() {
        val historical = LEGACY_RESULT_JSON.replaceFirst("\"fitScore\": 68", "\"fitScore\": 101")

        assertThat(objectMapper.readValue(historical, JobFitResult::class.java).fitScore).isEqualTo(101)
    }

    @Test
    fun `evidence must match its frozen manifest identity hash and label`() {
        for ((field, value) in mapOf(
            "sourceType" to "EXPERIENCE",
            "resourceId" to "00000000-0000-0000-0000-000000000099",
            "contentHash" to "changed-hash",
            "sourceLabel" to "changed-label",
        )) {
            val tree = objectMapper.readTree(AGENTIC_RESULT_JSON)
            val evidence = tree.path("agenticMetadata").path("evidenceCatalog")[3]
                as com.fasterxml.jackson.databind.node.ObjectNode
            evidence.put(field, value)
            assertContractFailure(objectMapper.writeValueAsString(tree), "Evidence source does not match the frozen source manifest")
        }
    }

    @Test
    fun `unrun fit view has unknown freshness and no result`() {
        val view = FitView(UUID.fromString(RESUME_ID), UUID.fromString(TARGET_JOB_ID), null, null, null)
        val json = objectMapper.readTree(objectMapper.writeValueAsString(view))

        assertThat(json.path("result").isNull).isTrue()
        assertThat(json.path("freshness").asText()).isEqualTo("UNKNOWN")
    }

    @Test
    fun `fit endpoint omits agentic metadata for legacy results through Spring MVC`() {
        val resumeId = UUID.fromString(RESUME_ID)
        val targetJobId = UUID.fromString(TARGET_JOB_ID)
        val result = objectMapper.readValue(LEGACY_RESULT_JSON, JobFitResult::class.java)
        val service = Mockito.mock(JobFitService::class.java)
        Mockito.`when`(service.get(resumeId, targetJobId)).thenReturn(FitView(resumeId, targetJobId, result, null, null))

        standaloneSetup(FitController(service)).build()
            .perform(get("/api/resumes/$resumeId/target-jobs/$targetJobId/fit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.freshness").value("UNKNOWN"))
            .andExpect(jsonPath("$.result.agenticMetadata").doesNotHaveJsonPath())
            .andExpect(jsonPath("$.result.fitScore").value(68))
    }

    @Test
    fun `versioned evidence result round trips statuses conflicts references and fallback provenance`() {
        val result = objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java)
        val roundTripped = objectMapper.readValue(objectMapper.writeValueAsString(result), JobFitResult::class.java)
        val metadata = objectMapper.readTree(objectMapper.writeValueAsString(roundTripped)).path("agenticMetadata")
        val projected = JobFitResult.fromAgentic(result.fitScore, result.summary, result.feedback, result.agenticMetadata!!)

        assertThat(roundTripped).isEqualTo(result)
        assertThat(projected).isEqualTo(result)
        assertThat(result.matchedRequirements).containsExactly(
            MatchedRequirement("Kotlin", "Built Kotlin services in production."),
        )
        assertThat(result.missingRequirements).containsExactly(
            MissingRequirement("Kafka", "Production ownership is not established."),
            MissingRequirement("Kubernetes", "No frozen record describes Kubernetes deployment."),
        )
        assertThat(metadata.path("assessments").map { it.path("status").asText() })
            .containsExactly("SUPPORTED", "PARTIALLY_SUPPORTED", "NOT_ESTABLISHED")
        assertThat(metadata.path("conflicts").single().path("evidenceIds").map { it.asText() })
            .containsExactly("experience-kafka-a", "experience-kafka-b")
        assertThat(metadata.path("fallbackUsed").asBoolean()).isTrue()
        assertThat(metadata.path("fallbackReason").asText()).isEqualTo("Jev timed out.")
    }

    @Test
    fun `unknown evidence ids cannot support an agentic assessment`() {
        val malformed = AGENTIC_RESULT_JSON.replaceFirst("resume-kotlin", "unknown-evidence")

        assertContractFailure(malformed, "Unknown candidate evidence reference")
    }

    @Test
    fun `cross snapshot citations cannot support an agentic assessment`() {
        val malformed = AGENTIC_RESULT_JSON.replaceFirst(
            "\"snapshotId\":\"snapshot-1\"",
            "\"snapshotId\":\"another-snapshot\"",
        )

        assertContractFailure(malformed, "Evidence belongs to a different snapshot")
    }

    @Test
    fun `a quote outside its frozen passage cannot support an agentic assessment`() {
        val malformed = AGENTIC_RESULT_JSON.replaceFirst(
            "\"quote\":\"Built Kotlin services in production.\"",
            "\"quote\":\"Experienced in Go instead.\"",
        )

        assertContractFailure(malformed, "quote must be an exact substring of its frozen passage")
    }

    @Test
    fun `unknown evidence statuses are rejected instead of falling through to support`() {
        val malformed = AGENTIC_RESULT_JSON.replaceFirst("\"status\":\"SUPPORTED\"", "\"status\":\"LIKELY\"")

        assertContractFailure(malformed, "LIKELY")
    }

    @Test
    fun `unchecked judgment with a supported status cannot masquerade as verified support`() {
        val malformed = AGENTIC_RESULT_JSON.replaceFirst("\"state\":\"JUDGED\"", "\"state\":\"UNCHECKED\"")

        assertContractFailure(malformed, "unchecked judgment cannot carry a status or provenance")
    }

    @Test
    fun `agentic result rejects legacy arrays that disagree with canonical assessments`() {
        val malformed = AGENTIC_RESULT_JSON.replaceFirst(
            "\"evidence\":\"Built Kotlin services in production.\"",
            "\"evidence\":\"Unrelated legacy evidence.\"",
        )

        assertContractFailure(malformed, "Legacy matched requirements must be derived from Supported assessments")
    }

    @Test
    fun `fit endpoint serializes agentic result through Spring MVC Jackson`() {
        val resumeId = UUID.fromString(RESUME_ID)
        val targetJobId = UUID.fromString(TARGET_JOB_ID)
        val result = objectMapper.readValue(AGENTIC_RESULT_JSON, JobFitResult::class.java)
        val service = Mockito.mock(JobFitService::class.java)
        Mockito.`when`(service.get(resumeId, targetJobId)).thenReturn(FitView(resumeId, targetJobId, result, null, null))
        val mockMvc = standaloneSetup(FitController(service)).build()

        mockMvc.perform(get("/api/resumes/$resumeId/target-jobs/$targetJobId/fit"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.freshness").value("UNKNOWN"))
            .andExpect(jsonPath("$.result.agenticMetadata.schemaVersion").value(1))
            .andExpect(jsonPath("$.result.agenticMetadata.assessments[0].status").value("SUPPORTED"))
            .andExpect(jsonPath("$.result.agenticMetadata.fallbackUsed").value(true))
            .andExpect(jsonPath("$.result.agenticMetadata.evidenceCatalog[3].quote").value("Built Kotlin services in production."))
    }

    private fun assertContractFailure(json: String, message: String) {
        val thrown = catchThrowable { objectMapper.readValue(json, JobFitResult::class.java) }
        assertThat(thrown).isNotNull
        val messages = mutableListOf<String>()
        var cause: Throwable? = thrown
        while (cause != null) {
            cause.message?.let(messages::add)
            cause = cause.cause
        }
        val exceptionMessages = messages.joinToString("\n")
        assertThat(exceptionMessages).contains(message)
    }

    private companion object {
        const val RESUME_ID = "00000000-0000-0000-0000-000000000001"
        const val TARGET_JOB_ID = "00000000-0000-0000-0000-000000000002"

        val LEGACY_RESULT_JSON = """
            {
              "fitScore": 68,
              "summary": "Good platform match.",
              "matchedRequirements": [{"requirement":"Kotlin","evidence":"Built Kotlin services"}],
              "missingRequirements": [{"requirement":"Kafka","guidance":"Describe relevant work if available."}],
              "feedback": []
            }
        """.trimIndent()

        val AGENTIC_RESULT_JSON = """
            {
              "fitScore": 80,
              "summary": "The frozen records support Kotlin and partially support Kafka.",
              "matchedRequirements": [{"requirement":"Kotlin","evidence":"Built Kotlin services in production."}],
              "missingRequirements": [
                {"requirement":"Kafka","guidance":"Production ownership is not established."},
                {"requirement":"Kubernetes","guidance":"No frozen record describes Kubernetes deployment."}
              ],
              "feedback": [],
              "agenticMetadata": {
                "schemaVersion": 1,
                "snapshotId": "snapshot-1",
                "sourceManifest": [
                  {"sourceType":"RESUME","resourceId":"00000000-0000-0000-0000-000000000001","sourceLabel":"Resume","contentHash":"resume-hash"},
                  {"sourceType":"TARGET_JOB","resourceId":"00000000-0000-0000-0000-000000000002","sourceLabel":"Platform Engineer","contentHash":"job-hash"},
                  {"sourceType":"EXPERIENCE","resourceId":"00000000-0000-0000-0000-000000000003","sourceLabel":"Payments migration","contentHash":"experience-a-hash"},
                  {"sourceType":"EXPERIENCE","resourceId":"00000000-0000-0000-0000-000000000004","sourceLabel":"Event platform","contentHash":"experience-b-hash"}
                ],
                "assessments": [
                  {"requirementId":"req-kotlin","requirement":"Kotlin","status":"SUPPORTED","targetJobEvidenceIds":["job-kotlin"],"evidenceIds":["resume-kotlin"],"conflictIds":[],"missingEvidenceDescription":null},
                  {"requirementId":"req-kafka","requirement":"Kafka","status":"PARTIALLY_SUPPORTED","targetJobEvidenceIds":["job-kafka"],"evidenceIds":["experience-kafka-a","experience-kafka-b"],"conflictIds":["conflict-kafka"],"missingEvidenceDescription":"Production ownership is not established."},
                  {"requirementId":"req-kubernetes","requirement":"Kubernetes","status":"NOT_ESTABLISHED","targetJobEvidenceIds":["job-kubernetes"],"evidenceIds":[],"conflictIds":[],"missingEvidenceDescription":"No frozen record describes Kubernetes deployment."}
                ],
                "evidenceCatalog": [
                  {"id":"job-kotlin","snapshotId":"snapshot-1","sourceType":"TARGET_JOB","resourceId":"00000000-0000-0000-0000-000000000002","contentHash":"job-hash","sourceLabel":"Platform Engineer","passage":"Kotlin is required.","quote":"Kotlin is required."},
                  {"id":"job-kafka","snapshotId":"snapshot-1","sourceType":"TARGET_JOB","resourceId":"00000000-0000-0000-0000-000000000002","contentHash":"job-hash","sourceLabel":"Platform Engineer","passage":"Kafka event streaming is expected.","quote":"Kafka event streaming is expected."},
                  {"id":"job-kubernetes","snapshotId":"snapshot-1","sourceType":"TARGET_JOB","resourceId":"00000000-0000-0000-0000-000000000002","contentHash":"job-hash","sourceLabel":"Platform Engineer","passage":"Deploy Kubernetes workloads.","quote":"Deploy Kubernetes workloads."},
                  {"id":"resume-kotlin","snapshotId":"snapshot-1","sourceType":"RESUME","resourceId":"00000000-0000-0000-0000-000000000001","contentHash":"resume-hash","sourceLabel":"Resume","passage":"Built Kotlin services in production.","quote":"Built Kotlin services in production."},
                  {"id":"experience-kafka-a","snapshotId":"snapshot-1","sourceType":"EXPERIENCE","resourceId":"00000000-0000-0000-0000-000000000003","contentHash":"experience-a-hash","sourceLabel":"Payments migration","passage":"Configured a Kafka consumer for test events, not production.","quote":"Configured a Kafka consumer for test events"},
                  {"id":"experience-kafka-b","snapshotId":"snapshot-1","sourceType":"EXPERIENCE","resourceId":"00000000-0000-0000-0000-000000000004","contentHash":"experience-b-hash","sourceLabel":"Event platform","passage":"The event platform used RabbitMQ rather than Kafka.","quote":"The event platform used RabbitMQ rather than Kafka."}
                ],
                "conflicts": [
                  {"id":"conflict-kafka","description":"The saved project records disagree about the event broker.","evidenceIds":["experience-kafka-a","experience-kafka-b"]}
                ],
                "judgments": [
                  {"requirementId":"req-kotlin","state":"JUDGED","provenance":{"provider":"GENERATION_MODEL","model":"gemini-3.6-flash","rubricVersion":"job-fit-v1"},"status":"SUPPORTED","evidenceIds":["resume-kotlin"],"missingEvidence":[],"providerProbabilities":{"SUPPORTED":0.91}},
                  {"requirementId":"req-kafka","state":"JUDGED","provenance":{"provider":"GENERATION_MODEL","model":"gemini-3.6-flash","rubricVersion":"job-fit-v1"},"status":"PARTIALLY_SUPPORTED","evidenceIds":["experience-kafka-a","experience-kafka-b"],"missingEvidence":["Production ownership is not established."],"providerProbabilities":{"PARTIALLY_SUPPORTED":0.72}},
                  {"requirementId":"req-kubernetes","state":"UNCHECKED","provenance":null,"status":null,"evidenceIds":[],"missingEvidence":["No frozen candidate passage directly establishes Kubernetes deployment."],"providerProbabilities":{}}
                ],
                "trace": [
                  {"kind":"FALLBACK","summary":"Jev was unavailable; the generation model judged the available evidence.","round":1}
                ],
                "fallbackUsed": true,
                "fallbackReason": "Jev timed out."
              }
            }
        """.trimIndent()
    }
}
