package dev.jiaming.ai_interview.fit.mcp

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import dev.jiaming.ai_interview.fit.JobFitEvidenceRetriever
import dev.jiaming.ai_interview.fit.JobFitFrozenSource
import dev.jiaming.ai_interview.fit.JobFitRetrieval
import dev.jiaming.ai_interview.fit.JobFitSourceSnapshot
import dev.jiaming.ai_interview.fit.JobFitSourceType
import dev.jiaming.ai_interview.rag.RagIndexingService
import dev.jiaming.ai_interview.rag.RagProperties
import dev.jiaming.ai_interview.rag.RagRetrievalService
import dev.jiaming.ai_interview.resume.SectionAwareTextChunker
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Optional
import java.util.UUID
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.apache.catalina.startup.Tomcat
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension

@ExtendWith(OutputCaptureExtension::class)
class JobFitMcpIntegrationTests {
    private val resume = JobFitFrozenSource(JobFitSourceType.RESUME, UUID.randomUUID(), "Backend resume", "Built Kotlin services on Postgres.")
    private val targetJob = JobFitFrozenSource(JobFitSourceType.TARGET_JOB, UUID.randomUUID(), "Platform role", "Requires Kubernetes operations.")
    private val experience = JobFitFrozenSource(JobFitSourceType.EXPERIENCE, UUID.randomUUID(), "Cluster migration", "Deployed Kubernetes on EKS.")
    private val snapshot = JobFitSourceSnapshot("snapshot-1", Instant.parse("2026-10-25T00:00:00Z"), listOf(resume, targetJob, experience))

    private val admission = FakeAdmission()
    private val servers = mutableListOf<Server>()

    @AfterEach
    fun stop() = servers.forEach(Server::stop)

    @Test
    fun theAgentCallsTheSingleRetrievalToolOverHttpAndGetsSnapshotBoundEvidence(output: CapturedOutput) {
        val server = serve()
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes", "r-kotlin"))

        val result = client(server).search(
            CAPABILITY,
            listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes"), JobFitMcpQuery("r-kotlin", "Kotlin")),
            deadline = Instant.now().plusSeconds(30),
            snapshotId = "snapshot-1",
        )

        assertThat(result.snapshotId).isEqualTo("snapshot-1")
        assertThat(result.results.map { it.requirementId }).containsExactly("r-kubernetes", "r-kotlin")
        val kubernetes = result.results[0].evidence.single()
        assertThat(kubernetes.sourceType).isEqualTo(JobFitSourceType.EXPERIENCE)
        assertThat(kubernetes.resourceId).isEqualTo(experience.resourceId)
        assertThat(kubernetes.sourceLabel).isEqualTo("Cluster migration")
        assertThat(kubernetes.contentHash).isEqualTo(experience.contentHash)
        assertThat(kubernetes.quote).isEqualTo("Deployed Kubernetes on EKS.")
        assertThat(result.results[1].evidence.single().sourceType).isEqualTo(JobFitSourceType.RESUME)
        assertThat(result.results.flatMap { it.evidence }).noneMatch { it.sourceType == JobFitSourceType.TARGET_JOB }
        // Vectors are unavailable in this harness, so both kinds were searched by the local fallback inside the tool.
        assertThat(result.degradedSources).containsExactlyInAnyOrder(JobFitSourceType.RESUME, JobFitSourceType.EXPERIENCE)
        assertThat(output.all).doesNotContain(TOKEN, CAPABILITY, "Deployed Kubernetes on EKS.")
    }

    @Test
    fun theServerAdvertisesOnlyTheRetrievalTool() {
        rawClient(serve()).use { client ->
            client.initialize()
            assertThat(client.listTools().tools().map { it.name() }).containsExactly(JOB_FIT_MCP_TOOL)
        }
    }

    @Test
    fun aWrongTokenOrAnyOriginIsRefusedBeforeTheProtocolRuns() {
        val server = serve()

        assertThatThrownBy { rawClient(server, "Authorization" to "Bearer wrong-token-0123456789-abcdefghijk").use { it.initialize() } }
            .isInstanceOf(RuntimeException::class.java)
        assertThatThrownBy { rawClient(server, "Authorization" to "Bearer $TOKEN", "Origin" to "https://evil.example").use { it.initialize() } }
            .isInstanceOf(RuntimeException::class.java)
        assertThat(admission.calls).isEmpty()
    }

    @Test
    fun missingDeniedOrForgedCapabilitiesFailClosedWithoutReadingSources() {
        val retriever = Mockito.mock(JobFitEvidenceRetriever::class.java)
        val server = serve(retriever)
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))
        val queries = listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes"))

        assertThat(callError(server, arguments(queries), meta = null)).isEqualTo("MISSING_CAPABILITY")
        assertThat(rejection(server, "forged-capability", queries)).isEqualTo("UNKNOWN_CAPABILITY")
        admission.deny(CAPABILITY, "LEASE_LOST")
        assertThat(rejection(server, CAPABILITY, queries)).isEqualTo("LEASE_LOST")
        Mockito.verifyNoInteractions(retriever)
    }

    @Test
    fun unknownRequirementsOversizedBatchesAndExtraArgumentsAreRejected() {
        val retriever = Mockito.mock(JobFitEvidenceRetriever::class.java)
        val server = serve(retriever)
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))

        assertThat(rejection(server, CAPABILITY, listOf(JobFitMcpQuery("r-invented", "Kubernetes")))).isEqualTo("UNKNOWN_REQUIREMENT")
        assertThat(rejection(server, CAPABILITY, (1..3).map { JobFitMcpQuery("r-kubernetes", "Kubernetes $it") })).isEqualTo("TOO_MANY_QUERIES")
        assertThat(rejection(server, CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", "k".repeat(101))))).isEqualTo("QUERY_TOO_LONG")
        val modelSuppliedServer = arguments(listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes"))) + ("serverUrl" to "http://attacker.example")
        assertThat(callError(server, modelSuppliedServer)).isEqualTo("INVALID_ARGUMENTS")
        val modelSuppliedSnapshot = mapOf("queries" to listOf(mapOf("requirementId" to "r-kubernetes", "query" to "Kubernetes", "snapshotId" to "snapshot-2")))
        assertThat(callError(server, modelSuppliedSnapshot)).isEqualTo("INVALID_ARGUMENTS")
        Mockito.verifyNoInteractions(retriever)
    }

    @Test
    fun everyDuplicateCallAsksAdmissionAgainAndCannotBypassALaterDenial() {
        val server = serve()
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))
        val queries = listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes"))

        client(server).search(CAPABILITY, queries, Instant.now().plusSeconds(30), "snapshot-1")
        admission.deny(CAPABILITY, "BUDGET_EXHAUSTED")

        assertThat(rejection(server, CAPABILITY, queries)).isEqualTo("BUDGET_EXHAUSTED")
        assertThat(admission.calls.map { it.first }).containsExactly(CAPABILITY, CAPABILITY)
    }

    @Test
    fun concurrentHttpCallsCannotBypassAdmission() {
        val entered = CountDownLatch(2)
        val claimed = AtomicBoolean()
        val admissionQueries = ConcurrentLinkedQueue<String>()
        val retrievedQueries = ConcurrentLinkedQueue<List<String>>()
        val authorization = JobFitMcpAuthorization { capability, call ->
            check(capability == CAPABILITY)
            admissionQueries.add(call.queries.single().query)
            entered.countDown()
            check(entered.await(10, TimeUnit.SECONDS)) { "Both calls must reach admission concurrently" }
            if (claimed.compareAndSet(false, true)) JobFitMcpAdmission.Admitted(snapshot, setOf("r-kubernetes"))
            else JobFitMcpAdmission.Denied("BUDGET_EXHAUSTED")
        }
        val retriever = Mockito.mock(JobFitEvidenceRetriever::class.java)
        Mockito.`when`(retriever.retrieve(any(), any())).thenAnswer { invocation ->
            val queries = invocation.getArgument<List<String>>(1)
            retrievedQueries.add(queries)
            JobFitRetrieval(queries.associateWith { emptyList() }, emptySet())
        }
        val server = serve(retriever, authorization)
        val executor = Executors.newFixedThreadPool(2)

        try {
            val calls = listOf("Kubernetes cluster", "Kubernetes services").map { query ->
                executor.submit<Result<JobFitMcpSearchResult>> {
                    runCatching {
                        client(server, Duration.ofSeconds(20)).search(
                            CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", query)), Instant.now().plusSeconds(30), snapshot.snapshotId,
                        )
                    }
                }
            }
            val outcomes = calls.map { it.get(25, TimeUnit.SECONDS) }

            assertThat(admissionQueries).containsExactlyInAnyOrder("Kubernetes cluster", "Kubernetes services")
            assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
            val successfulQuery = outcomes.single { it.isSuccess }.getOrThrow().results.single().query
            assertThat(outcomes.single { it.isFailure }.exceptionOrNull())
                .isInstanceOfSatisfying(JobFitMcpException::class.java) { assertThat(it.code).isEqualTo("BUDGET_EXHAUSTED") }
            assertThat(retrievedQueries.toList()).containsExactly(listOf(successfulQuery))
        } finally {
            repeat(2) { entered.countDown() }
            executor.shutdownNow()
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue()
        }
    }

    @Test
    fun aResultForAnotherSnapshotIsNeverAccepted() {
        val server = serve()
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))

        assertThatThrownBy { client(server).search(CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes")), Instant.now().plusSeconds(30), "snapshot-2") }
            .isInstanceOfSatisfying(JobFitMcpException::class.java) { assertThat(it.code).isEqualTo("MALFORMED_RESULT") }
    }

    @ParameterizedTest(name = "{0} is rejected as MALFORMED_RESULT")
    @ValueSource(strings = ["absent_content", "wrong_results_shape", "null_results", "null_result_entry",
        "null_evidence_entry", "null_degraded_source", "query_order", "target_job_evidence"])
    fun malformedToolResultsFailThroughTheTypedBoundary(scenario: String) {
        val queries = listOf(JobFitMcpQuery("r-kotlin", "Kotlin"), JobFitMcpQuery("r-kubernetes", "Kubernetes"))
        val evidence = mapOf(
            "id" to "resume-passage", "snapshotId" to snapshot.snapshotId, "sourceType" to "RESUME",
            "resourceId" to resume.resourceId.toString(), "contentHash" to resume.contentHash,
            "sourceLabel" to resume.sourceLabel, "passage" to resume.text, "quote" to resume.text,
        )
        val results = queries.map { mapOf("requirementId" to it.requirementId, "query" to it.query, "evidence" to listOf(evidence)) }
        val content = mapOf("snapshotId" to snapshot.snapshotId, "results" to results, "degradedSources" to emptyList<String>())
        val structuredContent: Map<String, Any?>? = when (scenario) {
            "absent_content" -> null
            "wrong_results_shape" -> content + ("results" to "invalid")
            "null_results" -> content + ("results" to null)
            "null_result_entry" -> content + ("results" to listOf(null))
            "null_evidence_entry" -> content + ("results" to listOf(results[0] + ("evidence" to listOf(null)), results[1]))
            "null_degraded_source" -> content + ("degradedSources" to listOf(null))
            "query_order" -> content + ("results" to results.reversed())
            "target_job_evidence" -> content + ("results" to listOf(
                results[0] + ("evidence" to listOf(evidence + ("sourceType" to "TARGET_JOB"))), results[1],
            ))
            else -> error("Unknown malformed-result scenario: $scenario")
        }
        val response = McpSchema.CallToolResult.builder().addTextContent("ok").apply {
            if (structuredContent != null) structuredContent(structuredContent)
        }.build()
        val server = serveResult(response)

        assertThatThrownBy { client(server).search(CAPABILITY, queries, Instant.now().plusSeconds(30), snapshot.snapshotId) }
            .isInstanceOfSatisfying(JobFitMcpException::class.java) { assertThat(it.code).isEqualTo("MALFORMED_RESULT") }
    }

    @Test
    fun anExpiredDeadlineFailsWithoutCallingTheServer() {
        val server = serve()
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))

        assertThatThrownBy { client(server).search(CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes")), Instant.now().minusSeconds(1), "snapshot-1") }
            .isInstanceOfSatisfying(JobFitMcpException::class.java) { assertThat(it.code).isEqualTo("DEADLINE_EXCEEDED") }
        assertThat(admission.calls).isEmpty()
    }

    @Test
    fun aSlowToolCallTimesOutWithinTheRemainingDeadlineInsteadOfSucceeding() {
        val release = CountDownLatch(1)
        val server = serve(blockingRetriever(release))
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))
        val started = System.nanoTime()

        try {
            assertThatThrownBy { client(server).search(CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes")), Instant.now().plusMillis(700), "snapshot-1") }
                .isInstanceOfSatisfying(JobFitMcpException::class.java) { assertThat(it.code).isEqualTo("DEADLINE_EXCEEDED") }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3))
        } finally {
            release.countDown()
        }
    }

    @Test
    fun serverSideFailuresReturnAFixedCodeWithoutTheirMessage() {
        val leakyAdmission = JobFitMcpAuthorization { capability, _ -> throw IllegalStateException("lease row missing for $capability") }
        val failingRetriever = Mockito.mock(JobFitEvidenceRetriever::class.java)
        Mockito.`when`(failingRetriever.retrieve(any(), any())).thenThrow(IllegalStateException("vector store down: Deployed Kubernetes on EKS."))
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))
        val queries = listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes"))

        listOf(serve(authorization = leakyAdmission), serve(failingRetriever)).forEach { server ->
            assertThatThrownBy { client(server).search(CAPABILITY, queries, Instant.now().plusSeconds(30), "snapshot-1") }
                .isInstanceOfSatisfying(JobFitMcpException::class.java) { failure ->
                    assertThat(failure.code).isEqualTo("TOOL_FAILED")
                    assertThat(generateSequence<Throwable>(failure) { it.cause }.mapNotNull { it.message }.toList())
                        .noneMatch { it.contains(CAPABILITY) || it.contains("Deployed Kubernetes") }
                }
        }
    }

    @Test
    fun anUnreachableServerIsATransportFailure() {
        val server = serve()
        server.stop()

        assertThatThrownBy { client(server).search(CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes")), Instant.now().plusSeconds(5), "snapshot-1") }
            .isInstanceOfSatisfying(JobFitMcpException::class.java) { assertThat(it.code).isEqualTo("TRANSPORT_FAILED") }
    }

    @Test
    fun interruptingTheWorkerCancelsTheCallAndKeepsTheInterruptFlag() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val server = serve(blockingRetriever(release, entered))
        admission.allow(CAPABILITY, snapshot, setOf("r-kubernetes"))
        var failure: Throwable? = null
        var interrupted = false
        val worker = Thread {
            try {
                client(server).search(CAPABILITY, listOf(JobFitMcpQuery("r-kubernetes", "Kubernetes")), Instant.now().plusSeconds(30), "snapshot-1")
            } catch (exception: Throwable) {
                failure = exception
                interrupted = Thread.currentThread().isInterrupted
            }
        }

        try {
            worker.start()
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue()
            worker.interrupt()
            worker.join(5_000)
            assertThat(failure).isInstanceOf(CancellationException::class.java)
            assertThat(interrupted).isTrue()
        } finally {
            release.countDown()
        }
    }

    private fun serve(
        retriever: JobFitEvidenceRetriever = localRetriever(),
        authorization: JobFitMcpAuthorization = admission,
    ) = Server(JobFitMcpTools(retriever, authorization, properties("http://127.0.0.1:1"))).also(servers::add)

    private fun serveResult(result: McpSchema.CallToolResult): Server {
        val registration = JobFitMcpTools(localRetriever(), admission, properties("http://127.0.0.1:1")).specification()
        val tools = Mockito.mock(JobFitMcpTools::class.java)
        Mockito.`when`(tools.specification()).thenReturn(SyncToolSpecification(registration.tool()) { _, _ -> result })
        return Server(tools).also(servers::add)
    }

    private fun client(server: Server, requestTimeout: Duration = Duration.ofSeconds(5)) =
        JobFitMcpClient(properties(server.baseUrl).copy(requestTimeout = requestTimeout), jacksonObjectMapper(), Clock.systemUTC())

    private fun rawClient(server: Server, vararg headers: Pair<String, String> = arrayOf("Authorization" to "Bearer $TOKEN")): McpSyncClient {
        val transport = HttpClientStreamableHttpTransport.builder(server.baseUrl).endpoint(JOB_FIT_MCP_ENDPOINT)
            .customizeRequest { request -> headers.forEach { (name, value) -> request.header(name, value) } }.build()
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(5)).build()
    }

    private fun rejection(server: Server, capability: String, queries: List<JobFitMcpQuery>): String {
        var code = ""
        assertThatThrownBy { client(server).search(capability, queries, Instant.now().plusSeconds(30), "snapshot-1") }
            .isInstanceOfSatisfying(JobFitMcpException::class.java) { code = it.code }
        return code
    }

    private fun callError(server: Server, arguments: Map<String, Any>, meta: Map<String, Any>? = mapOf(JOB_FIT_MCP_CAPABILITY_META to CAPABILITY)): String =
        rawClient(server).use { client ->
            client.initialize()
            val result = client.callTool(McpSchema.CallToolRequest.builder().name(JOB_FIT_MCP_TOOL).arguments(arguments).meta(meta).build())
            assertThat(result.isError).isTrue()
            (result.content().single() as McpSchema.TextContent).text()
        }

    private fun arguments(queries: List<JobFitMcpQuery>): Map<String, Any> =
        mapOf("queries" to queries.map { mapOf("requirementId" to it.requirementId, "query" to it.query) })

    // RAG vectors are unavailable here, so the real retriever searches local chunks of the frozen sources.
    private fun localRetriever(): JobFitEvidenceRetriever {
        val indexing = Mockito.mock(RagIndexingService::class.java)
        Mockito.`when`(indexing.ensureIndexed(any())).thenReturn(Optional.empty())
        return JobFitEvidenceRetriever(SectionAwareTextChunker(), indexing, Mockito.mock(RagRetrievalService::class.java),
            RagProperties(1024, "gemini-embedding-001", "section-block-v3"))
    }

    private fun blockingRetriever(release: CountDownLatch, entered: CountDownLatch = CountDownLatch(1)): JobFitEvidenceRetriever {
        val retriever = Mockito.mock(JobFitEvidenceRetriever::class.java)
        Mockito.`when`(retriever.retrieve(any(), any())).thenAnswer {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            JobFitRetrieval(mapOf("Kubernetes" to emptyList()), emptySet())
        }
        return retriever
    }

    private fun properties(baseUrl: String) = JobFitMcpProperties(
        enabled = true, token = TOKEN, baseUrl = baseUrl, requestTimeout = Duration.ofSeconds(5), maxQueriesPerCall = 2, maxQueryChars = 100,
    )

    class FakeAdmission : JobFitMcpAuthorization {
        private val decisions = mutableMapOf<String, JobFitMcpAdmission>()
        val calls = mutableListOf<Pair<String, JobFitMcpSearchCall>>()

        fun allow(capability: String, snapshot: JobFitSourceSnapshot, requirementIds: Set<String>) {
            decisions[capability] = JobFitMcpAdmission.Admitted(snapshot, requirementIds)
        }

        fun deny(capability: String, reason: String) {
            decisions[capability] = JobFitMcpAdmission.Denied(reason)
        }

        @Synchronized
        override fun admit(capability: String, call: JobFitMcpSearchCall): JobFitMcpAdmission {
            calls += capability to call
            return decisions[capability] ?: JobFitMcpAdmission.Denied("UNKNOWN_CAPABILITY")
        }
    }

    class Server(tools: JobFitMcpTools) {
        private val tomcat = Tomcat().apply {
            setPort(0)
            setBaseDir(kotlin.io.path.createTempDirectory("job-fit-mcp").toString())
            val context = addContext("", null)
            Tomcat.addServlet(context, "jobFitMcp", jobFitMcpServerTransport(tools, TOKEN)).setAsyncSupported(true)
            context.addServletMappingDecoded(JOB_FIT_MCP_ENDPOINT, "jobFitMcp")
            connector
            start()
        }
        val baseUrl = "http://127.0.0.1:${tomcat.connector.localPort}"
        private var stopped = false

        fun stop() {
            if (stopped) return
            stopped = true
            tomcat.stop()
            tomcat.destroy()
        }
    }

    private companion object {
        const val TOKEN = "test-token-0123456789-abcdefghijklmnop"
        const val CAPABILITY = "capability-0123456789"
    }
}
