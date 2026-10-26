package dev.jiaming.ai_interview.fit.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.fit.JobFitSourceType
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport
import io.modelcontextprotocol.spec.McpSchema
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CancellationException

/** A failed MCP retrieval; never a success. [code] is a server rejection code, TOOL_FAILED (server-side failure, message withheld),
 * DEADLINE_EXCEEDED (the run deadline passed before or during the call), TRANSPORT_FAILED or MALFORMED_RESULT. */
class JobFitMcpException(val code: String, cause: Throwable? = null) : RuntimeException(code, cause)

/** Worker-side client for the private Job Fit MCP server at the configured base URL; callers cannot choose the server. */
class JobFitMcpClient(
    private val properties: JobFitMcpProperties,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {
    /** Blocks on one HTTP MCP session (initialize, then one tool call) bounded by the lesser of the request timeout and [deadline].
     * Sends the bearer token and [capability] outside the tool arguments. Returns only a result whose snapshot, query order and
     * candidate-only evidence match the request. Throws [JobFitMcpException] for every other outcome, including timeouts, and
     * [CancellationException] with the interrupt flag set when the thread is interrupted. Retries are the caller's decision.
     */
    fun search(capability: String, queries: List<JobFitMcpQuery>, deadline: Instant, snapshotId: String): JobFitMcpSearchResult {
        val remaining = Duration.between(clock.instant(), deadline)
        if (remaining <= Duration.ZERO) throw JobFitMcpException("DEADLINE_EXCEEDED")
        val timeout = minOf(properties.requestTimeout, remaining)
        val transport = HttpClientStreamableHttpTransport.builder(properties.baseUrl)
            .endpoint(JOB_FIT_MCP_ENDPOINT)
            .connectTimeout(timeout)
            .customizeRequest { it.header("Authorization", "Bearer ${properties.token}") }
            .build()
        val client = McpClient.async(transport).requestTimeout(timeout).initializationTimeout(timeout).build()
        val request = McpSchema.CallToolRequest.builder()
            .name(JOB_FIT_MCP_TOOL)
            .arguments(mapOf("queries" to queries.map { mapOf("requirementId" to it.requirementId, "query" to it.query) }))
            .meta(mapOf(JOB_FIT_MCP_CAPABILITY_META to capability))
            .build()
        val result = try {
            client.initialize().then(client.callTool(request)).block(timeout)
                ?: throw JobFitMcpException("MALFORMED_RESULT")
        } catch (exception: RuntimeException) {
            if (exception is JobFitMcpException) throw exception
            if (Thread.currentThread().isInterrupted || generateSequence<Throwable>(exception) { it.cause }.any { it is InterruptedException }) {
                Thread.currentThread().interrupt()
                throw CancellationException("Job Fit MCP call interrupted").apply { initCause(exception) }
            }
            if (!clock.instant().isBefore(deadline)) throw JobFitMcpException("DEADLINE_EXCEEDED", exception)
            throw JobFitMcpException("TRANSPORT_FAILED", exception)
        } finally {
            client.close()
        }
        if (result.isError == true) throw JobFitMcpException(result.content().filterIsInstance<McpSchema.TextContent>().firstOrNull()?.text() ?: "TOOL_FAILED")
        return validated(result, queries, snapshotId)
    }

    private fun validated(result: McpSchema.CallToolResult, queries: List<JobFitMcpQuery>, snapshotId: String): JobFitMcpSearchResult {
        val decoded = try {
            objectMapper.convertValue(result.structuredContent(), JobFitMcpSearchResult::class.java)
        } catch (exception: IllegalArgumentException) {
            throw JobFitMcpException("MALFORMED_RESULT", exception)
        } ?: throw JobFitMcpException("MALFORMED_RESULT")
        val matches = decoded.snapshotId == snapshotId &&
            decoded.results.map { it.requirementId to it.query } == queries.map { it.requirementId to it.query } &&
            decoded.results.flatMap { it.evidence }.all { it.snapshotId == snapshotId && it.sourceType != JobFitSourceType.TARGET_JOB }
        if (!matches) throw JobFitMcpException("MALFORMED_RESULT")
        return decoded
    }
}
