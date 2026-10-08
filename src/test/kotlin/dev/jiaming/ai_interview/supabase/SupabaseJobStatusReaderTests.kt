package dev.jiaming.ai_interview.supabase

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.ApiRequestException
import dev.jiaming.ai_interview.jobs.BackgroundJob
import dev.jiaming.ai_interview.jobs.BackgroundJobStore
import dev.jiaming.ai_interview.jobs.JobErrorResponse
import dev.jiaming.ai_interview.jobs.JobStage
import dev.jiaming.ai_interview.jobs.JobStatus
import dev.jiaming.ai_interview.jobs.JobStatusReaderConfiguration
import dev.jiaming.ai_interview.jobs.JobType
import dev.jiaming.ai_interview.voice.VoiceReportPayload
import io.github.jan.supabase.SupabaseClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.mockito.Mockito
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import java.time.Instant
import java.util.Optional
import java.util.UUID

class SupabaseJobStatusReaderTests {
    private val jobId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val userId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val resourceId = UUID.fromString("30000000-0000-0000-0000-000000000003")
    private val jobDescriptionId = UUID.fromString("40000000-0000-0000-0000-000000000004")
    private val mapper = ObjectMapper()

    @Test
    fun `queries owner scoped custom schema and maps raw JSON without inventing timestamps`() = runBlocking {
        val body = row(
            requestPayload = """{"resumeId":"invalid","jobDescriptionId":"$jobDescriptionId"}""",
            resultPayload = """{"overallScore":84,"tags":["clear",null],"details":{"rating":7.5}}"""
        )
        val (client, reader) = reader(body) { request ->
            assertTrue(request.url.encodedPath.endsWith("/rest/v1/job_status"))
            assertEquals("ai_interview_api", request.headers["Accept-Profile"])
            assertEquals("eq.$jobId", request.url.parameters["id"])
            assertEquals("eq.$userId", request.url.parameters["user_id"])
            assertEquals("1", request.url.parameters["limit"])
            assertEquals("id,user_id,job_type,status,stage,attempts,result_payload,last_error,error_code,retryable,created_at,started_at,completed_at,resource_id,request_payload,max_attempts,resource_type", request.url.parameters["select"])
            assertEquals(KEY, request.headers["apikey"])
            assertNull(request.headers[HttpHeaders.Authorization])
        }
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(jobId, status.jobId)
            assertEquals(JobStatus.QUEUED, status.status)
            assertEquals(3, status.maxAttempts)
            assertEquals(84, (status.result as Map<*, *>)["overallScore"])
            assertEquals(listOf("clear", null), (status.result as Map<*, *>)["tags"])
            assertEquals(7.5, ((status.result as Map<*, *>)["details"] as Map<*, *>)["rating"])
            assertEquals(resourceId, status.inputRefs.resumeId)
            assertEquals(jobDescriptionId, status.inputRefs.targetJobId)
            assertNull(status.createdAt)
            assertNull(status.startedAt)
            assertNull(status.completedAt)
            assertNull(status.error)
        } finally { client.close() }
    }

    @Test
    fun `same job fixture has the same status contract through JDBC and SDK readers`() = runBlocking {
        val resumeId = UUID.randomUUID()
        val practiceSetId = UUID.randomUUID()
        val attemptId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-09-30T12:34:56Z")
        val requestPayload = """{"resumeId":"$resumeId","jobDescriptionId":"$jobDescriptionId","practiceSetId":"$practiceSetId","attemptId":"$attemptId","prompt":"PRIVATE_PROMPT_MARKER"}"""
        val resultPayload = """{"overallScore":84,"tags":["clear",null],"details":{"rating":7.5}}"""
        val localJob = BackgroundJob(
            id = jobId,
            userId = userId,
            jobType = JobType.RESUME_SCORE,
            resourceType = "resource",
            resourceId = resourceId,
            status = JobStatus.QUEUED,
            stage = JobStage.QUEUED,
            requestPayload = mapper.readTree(requestPayload),
            resultPayload = mapper.readTree(resultPayload),
            requestFingerprint = "fixture-fingerprint",
            attempts = 2,
            maxAttempts = 3,
            errorCode = null,
            lastError = null,
            retryable = null,
            runAfter = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            enqueuedAt = null,
            startedAt = null,
            completedAt = null,
            leaseToken = null,
            leaseExpiresAt = null
        )
        val jobStore = Mockito.mock(BackgroundJobStore::class.java)
        Mockito.`when`(jobStore.findForUser(jobId, userId)).thenReturn(Optional.of(localJob))
        val jdbcReader = JobStatusReaderConfiguration().localJobStatusReader(jobStore)
        val (client, reader) = reader(row(
            requestPayload = requestPayload,
            resultPayload = resultPayload,
            createdAt = "\"$createdAt\"",
            resourceType = "\"resource\""
        ))
        try {
            assertEquals(jdbcReader.findForUser(jobId, userId), reader.findForUser(jobId, userId))
        } finally { client.close() }
    }

    @Test
    fun `voice report nested JSON and session reference match through JDBC and SDK readers`() = runBlocking {
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        val questionId = UUID.randomUUID()
        val createdAt = Instant.parse("2026-10-04T12:00:00Z")
        val requestPayload = """{"voiceSessionId":"$resourceId","resumeId":"$resumeId","targetJobId":"$targetJobId","payloadVersion":1}"""
        val resultPayload = """{"selectedCount":2,"answeredCount":1,"overallScore":82,"answers":[{"questionId":"$questionId","score":82,"summary":"Clear example.","nextStep":null,"strengths":["specific"],"gaps":[],"betterAnswerOutline":[],"followUpQuestion":null,"incomplete":false}],"weakestQuestionIds":["$questionId"],"unansweredQuestionIds":[]}"""
        val localJob = BackgroundJob(
            id = jobId,
            userId = userId,
            jobType = JobType.VOICE_REPORT,
            resourceType = VoiceReportPayload.RESOURCE,
            resourceId = resourceId,
            status = JobStatus.QUEUED,
            stage = JobStage.SCORING_ANSWER,
            requestPayload = mapper.readTree(requestPayload),
            resultPayload = mapper.readTree(resultPayload),
            requestFingerprint = "voice-fingerprint",
            attempts = 2,
            maxAttempts = 3,
            errorCode = null,
            lastError = null,
            retryable = null,
            runAfter = null,
            createdAt = createdAt,
            updatedAt = createdAt,
            enqueuedAt = createdAt,
            startedAt = null,
            completedAt = null,
            leaseToken = null,
            leaseExpiresAt = null,
        )
        val jobStore = Mockito.mock(BackgroundJobStore::class.java)
        Mockito.`when`(jobStore.findForUser(jobId, userId)).thenReturn(Optional.of(localJob))
        val jdbcReader = JobStatusReaderConfiguration().localJobStatusReader(jobStore)
        val (client, sdkReader) = reader(row(
            requestPayload = requestPayload,
            resultPayload = resultPayload,
            createdAt = "\"$createdAt\"",
            resourceType = "\"${VoiceReportPayload.RESOURCE}\"",
            jobType = JobType.VOICE_REPORT.name,
            stage = JobStage.SCORING_ANSWER.name,
        ))
        try {
            val jdbcStatus = jdbcReader.findForUser(jobId, userId)!!
            val sdkStatus = sdkReader.findForUser(jobId, userId)!!
            assertEquals(jdbcStatus, sdkStatus)
            assertEquals(JobType.VOICE_REPORT, sdkStatus.jobType)
            assertEquals(resourceId, sdkStatus.inputRefs.voiceSessionId)
            assertEquals(resumeId, sdkStatus.inputRefs.resumeId)
            assertEquals(targetJobId, sdkStatus.inputRefs.targetJobId)
            assertEquals(82, (sdkStatus.result as Map<*, *>) ["overallScore"])
            val answer = ((sdkStatus.result as Map<*, *>) ["answers"] as List<*>).single() as Map<*, *>
            assertEquals(questionId.toString(), answer["questionId"])
            assertEquals(false, answer["incomplete"])
        } finally { client.close() }
    }

    @Test
    fun `null JSON and nullable status fields stay null`() = runBlocking {
        val (client, reader) = reader(row(requestPayload = "null", resourceType = "null"))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertNull(status.result)
            assertNull(status.error)
            assertNull(status.createdAt)
            assertNull(status.startedAt)
            assertNull(status.completedAt)
            assertNull(status.inputRefs.resumeId)
            assertNull(status.inputRefs.targetJobId)
            assertNull(status.inputRefs.practiceSetId)
            assertNull(status.inputRefs.attemptId)
        } finally { client.close() }
    }

    @Test
    fun `maps resource-less experience split jobs and their review-only result`() = runBlocking {
        val resultPayload = """{"items":[{"title":"Engineer","organization":null,"startDate":"2023-01","endDate":null,"description":"Built a durable service.","duplicateOf":null}]}"""
        val (client, reader) = reader(row(
            jobType = "EXPERIENCE_SPLIT",
            stage = "SPLITTING_EXPERIENCE",
            requestPayload = """{"payloadVersion":1}""",
            resultPayload = resultPayload,
            resourceType = "null"
        ))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(JobType.EXPERIENCE_SPLIT, status.jobType)
            assertEquals(JobStage.SPLITTING_EXPERIENCE, status.stage)
            assertNull(status.inputRefs.resumeId)
            assertNull(status.inputRefs.targetJobId)
            val item = ((status.result as Map<*, *>) ["items"] as List<*>).single() as Map<*, *>
            assertEquals("Engineer", item["title"])
            assertNull(item["duplicateOf"])
        } finally { client.close() }
    }

    @Test
    fun `maps resume score jobs to their resume and nested score result`() = runBlocking {
        val resultPayload = """{"overall":72,"scores":{"technicalDepth":70,"impact":64,"clarity":80,"relevance":75,"ats":71},"summary":"Solid.","fixes":[{"rank":1,"section":"Experience","priority":"HIGH","message":"Quantify it."}],"rewrites":[{"section":"Experience","original":"Did it.","rewritten":"Cut cost by [X%].","placeholders":["[X%]"]}],"jobTitle":null,"scoredAt":"2026-09-30T21:00:00Z"}"""
        val (client, reader) = reader(row(
            jobType = "RESUME_SCORE",
            stage = "SCORING_RESUME",
            requestPayload = """{"resumeId":"$resourceId"}""",
            resultPayload = resultPayload
        ))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(JobType.RESUME_SCORE, status.jobType)
            assertEquals(JobStage.SCORING_RESUME, status.stage)
            assertEquals(resourceId, status.inputRefs.resumeId)
            val result = status.result as Map<*, *>
            assertEquals(72, result["overall"])
            assertNull(result["jobTitle"])
            val rewrite = (result["rewrites"] as List<*>).single() as Map<*, *>
            assertEquals(listOf("[X%]"), rewrite["placeholders"])
        } finally { client.close() }
    }

    @Test
    fun `maps job fit refs from the payload instead of the fit pair resource`() = runBlocking {
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        val (client, reader) = reader(row(
            jobType = "JOB_FIT",
            stage = "MATCHING_JOB",
            requestPayload = """{"resumeId":"$resumeId","targetJobId":"$targetJobId"}""",
            resultPayload = """{"fitScore":68,"summary":"Good.","matchedRequirements":[],"missingRequirements":[],"feedback":[]}""",
            resourceType = "\"job-fit\""
        ))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(JobType.JOB_FIT, status.jobType)
            assertEquals(resumeId, status.inputRefs.resumeId)
            assertEquals(targetJobId, status.inputRefs.targetJobId)
            assertEquals(68, (status.result as Map<*, *>)["fitScore"])
        } finally { client.close() }
    }

    @Test
    fun `maps practice question jobs to their set and pair refs`() = runBlocking {
        val practiceSetId = UUID.randomUUID()
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        val (client, reader) = reader(row(
            jobType = "PRACTICE_QUESTIONS",
            stage = "GENERATING_QUESTIONS",
            requestPayload = """{"practiceSetId":"$practiceSetId","resumeId":"$resumeId","targetJobId":"$targetJobId"}""",
            resultPayload = """{"questions":[{"id":"$jobId","order":1,"origin":"AI","text":"Why Kafka?","rationale":"The job needs it.","category":null,"expectedSignals":[],"attempts":[]}]}""",
            resourceType = "\"practice-set\""
        ))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(JobType.PRACTICE_QUESTIONS, status.jobType)
            assertEquals(JobStage.GENERATING_QUESTIONS, status.stage)
            assertEquals(practiceSetId, status.inputRefs.practiceSetId)
            assertEquals(resumeId, status.inputRefs.resumeId)
            assertEquals(targetJobId, status.inputRefs.targetJobId)
            val question = ((status.result as Map<*, *>)["questions"] as List<*>).single() as Map<*, *>
            assertEquals("The job needs it.", question["rationale"])
            assertEquals(emptyList<Any>(), question["attempts"])
        } finally { client.close() }
    }

    @Test
    fun `maps experience suggestion refs from the payload and keeps nested source items`() = runBlocking {
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        val experienceId = UUID.randomUUID()
        val (client, reader) = reader(row(
            jobType = "EXPERIENCE_SUGGESTIONS",
            stage = "MATCHING_EXPERIENCE",
            requestPayload = """{"payloadVersion":1,"suggestionsId":"$resourceId","resumeId":"$resumeId","targetJobId":"$targetJobId"}""",
            resultPayload = """{"items":[{"requirement":"Kafka","source":{"type":"EXPERIENCE","id":"$experienceId","name":"Ledger"},"match":"m","whyItFits":"w","guidance":"g"}]}""",
            resourceType = "\"experience-suggestions\""
        ))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(JobType.EXPERIENCE_SUGGESTIONS, status.jobType)
            assertEquals(JobStage.MATCHING_EXPERIENCE, status.stage)
            assertEquals(resumeId, status.inputRefs.resumeId)
            assertEquals(targetJobId, status.inputRefs.targetJobId)
            val item = ((status.result as Map<*, *>)["items"] as List<*>).single() as Map<*, *>
            assertEquals(experienceId.toString(), (item["source"] as Map<*, *>)["id"])
        } finally { client.close() }
    }

    @Test
    fun `maps attempt feedback jobs to their attempt set and pair refs`() = runBlocking {
        val attemptId = UUID.randomUUID()
        val practiceSetId = UUID.randomUUID()
        val resumeId = UUID.randomUUID()
        val targetJobId = UUID.randomUUID()
        val (client, reader) = reader(row(
            jobType = "ANSWER_FEEDBACK",
            stage = "SCORING_ANSWER",
            requestPayload = """{"payloadVersion":3,"attemptId":"$attemptId","practiceSetId":"$practiceSetId","resumeId":"$resumeId","targetJobId":"$targetJobId"}""",
            resultPayload = """{"score":74,"summary":"Clear.","nextStep":null,"strengths":["Ownership"],"gaps":[],"betterAnswerOutline":[],"followUpQuestion":null}""",
            resourceType = "\"attempt\""
        ))
        try {
            val status = reader.findForUser(jobId, userId)!!
            assertEquals(JobType.ANSWER_FEEDBACK, status.jobType)
            assertEquals(JobStage.SCORING_ANSWER, status.stage)
            assertEquals(attemptId, status.inputRefs.attemptId)
            assertEquals(practiceSetId, status.inputRefs.practiceSetId)
            assertEquals(resumeId, status.inputRefs.resumeId)
            assertEquals(targetJobId, status.inputRefs.targetJobId)
            assertEquals(74, (status.result as Map<*, *>)["score"])
            assertNull((status.result as Map<*, *>)["followUpQuestion"])
        } finally { client.close() }
    }

    @Test
    fun `resume fallback is resource aware and malformed reference UUIDs stay null`() = runBlocking {
        val invalidRefs = """{"resumeId":"invalid","jobDescriptionId":"invalid","practiceSetId":"invalid","attemptId":"invalid"}"""
        val (resumeClient, resumeReader) = reader(row(requestPayload = invalidRefs, resourceType = "\"resume\""))
        try {
            val refs = resumeReader.findForUser(jobId, userId)!!.inputRefs
            assertEquals(resourceId, refs.resumeId)
            assertNull(refs.targetJobId)
            assertNull(refs.practiceSetId)
            assertNull(refs.attemptId)
        } finally { resumeClient.close() }

        val (attemptClient, attemptReader) = reader(row(requestPayload = invalidRefs, resourceType = "\"attempt\""))
        try {
            val refs = attemptReader.findForUser(jobId, userId)!!.inputRefs
            assertNull(refs.resumeId)
            assertNull(refs.targetJobId)
            assertNull(refs.practiceSetId)
            assertNull(refs.attemptId)
        } finally { attemptClient.close() }
    }

    @Test
    fun `maps job errors only when an error message is present`() = runBlocking {
        val body = row(lastError = "\"temporary backend failure\"", errorCode = "\"UPSTREAM\"", retryable = "true")
        val (client, reader) = reader(body)
        try {
            assertEquals(JobErrorResponse("UPSTREAM", "temporary backend failure", true), reader.findForUser(jobId, userId)!!.error)
        } finally { client.close() }
    }

    @Test
    fun `empty array stays missing for the controller to return not found`() = runBlocking {
        val (client, reader) = reader("[]")
        try { assertNull(reader.findForUser(jobId, userId)) } finally { client.close() }
    }

    @Test
    fun `malformed payloads and mismatched rows become sanitized service unavailable`() = runBlocking {
        val mismatchedOwner = row(user = UUID.randomUUID())
        val mismatchedJob = row(job = UUID.randomUUID())
        listOf(
            "{", "{}", "[{}]", mismatchedOwner, mismatchedJob,
            row(maxAttempts = "\"three\""), row(resourceType = "42")
        ).forEach { body ->
            val (client, reader) = reader(body)
            try { assertUnavailable { reader.findForUser(jobId, userId) } } finally { client.close() }
        }
    }

    @Test
    fun `upstream errors and request timeout become sanitized service unavailable`() = runBlocking {
        val failedClient = SupabaseClientConfig.createClient(URL, KEY, MockEngine {
            respond("private upstream detail", HttpStatusCode.ServiceUnavailable, jsonHeaders)
        })
        try { assertUnavailable { SupabaseJobStatusReader(failedClient, ObjectMapper()).findForUser(jobId, userId) } }
        finally { failedClient.close() }

        val deniedClient = SupabaseClientConfig.createClient(URL, KEY, MockEngine {
            respond("private authorization detail", HttpStatusCode.Forbidden, jsonHeaders)
        })
        try { assertUnavailable { SupabaseJobStatusReader(deniedClient, ObjectMapper()).findForUser(jobId, userId) } }
        finally { deniedClient.close() }

        val slowClient = SupabaseClientConfig.createClient(URL, KEY, MockEngine {
            delay(15_000)
            respond("[]", HttpStatusCode.OK, jsonHeaders)
        })
        try { assertUnavailable { SupabaseJobStatusReader(slowClient, ObjectMapper()).findForUser(jobId, userId) } }
        finally { slowClient.close() }
    }

    private fun reader(body: String, inspectRequest: (io.ktor.client.request.HttpRequestData) -> Unit = {}): Pair<SupabaseClient, SupabaseJobStatusReader> {
        val client = SupabaseClientConfig.createClient(URL, KEY, MockEngine { request ->
            inspectRequest(request)
            respond(body, HttpStatusCode.OK, jsonHeaders)
        })
        return client to SupabaseJobStatusReader(client, ObjectMapper())
    }

    private fun row(
        job: UUID = jobId,
        user: UUID = userId,
        requestPayload: String = "{}",
        resultPayload: String = "null",
        createdAt: String = "null",
        maxAttempts: String = "3",
        resourceType: String = "\"resume\"",
        lastError: String = "null",
        errorCode: String = "\"STALE_CODE\"",
        retryable: String = "true",
        jobType: String = "RESUME_SCORE",
        stage: String = "QUEUED"
    ) = """[{"id":"$job","user_id":"$user","job_type":"$jobType","status":"QUEUED","stage":"$stage","attempts":2,"result_payload":$resultPayload,"last_error":$lastError,"error_code":$errorCode,"retryable":$retryable,"created_at":$createdAt,"started_at":null,"completed_at":null,"resource_id":"$resourceId","request_payload":$requestPayload,"max_attempts":$maxAttempts,"resource_type":$resourceType}]"""

    private fun assertUnavailable(action: () -> Any?) {
        val exception = assertFailsWith<ApiRequestException> { action() }
        assertEquals(HttpStatusCode.ServiceUnavailable.value, exception.status().value())
        assertEquals("SERVICE_UNAVAILABLE", exception.code())
        assertEquals("Job status is temporarily unavailable", exception.message)
        assertNull(exception.cause)
        assertFalse(exception.message.orEmpty().contains("private upstream detail"))
        assertFalse(exception.message.orEmpty().contains(KEY))
    }

    private companion object {
        const val URL = "https://example.supabase.co"
        const val KEY = "sb_secret_test"
        val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    }
}
