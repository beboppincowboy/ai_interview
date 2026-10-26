package dev.jiaming.ai_interview.fit.mcp

import com.fasterxml.jackson.annotation.JsonSetter
import com.fasterxml.jackson.annotation.Nulls
import dev.jiaming.ai_interview.fit.JobFitEvidenceItem
import dev.jiaming.ai_interview.fit.JobFitEvidenceRetriever
import dev.jiaming.ai_interview.fit.JobFitSourceType
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification
import io.modelcontextprotocol.spec.McpSchema
import java.util.concurrent.CancellationException
import org.slf4j.LoggerFactory

/** One model-chosen retrieval request for a run-local requirement; RAW query text. */
data class JobFitMcpQuery(val requirementId: String, val query: String)

/** Parsed tool call; the server validates count, IDs and query length before admission. Direct construction does not validate. */
data class JobFitMcpSearchCall(val queries: List<JobFitMcpQuery>)

/** Evidence for one query, in retriever rank order; each item's quote is its whole RAW passage. JSON decoding rejects null evidence entries. */
data class JobFitMcpRequirementEvidence(
    val requirementId: String,
    val query: String,
    @param:JsonSetter(contentNulls = Nulls.FAIL) val evidence: List<JobFitEvidenceItem>,
)

/** Tool result: one entry per requested query, in request order; [degradedSources] are kinds searched by local fallback.
 * JSON decoding rejects null result and degradation entries. */
data class JobFitMcpSearchResult(
    val snapshotId: String,
    @param:JsonSetter(contentNulls = Nulls.FAIL) val results: List<JobFitMcpRequirementEvidence>,
    @param:JsonSetter(contentNulls = Nulls.FAIL) val degradedSources: Set<JobFitSourceType>,
)

// A call the server refuses; its code is returned to the worker as the error text.
private class JobFitMcpRejection(val code: String) : RuntimeException(code)

/** The private MCP server's only tool, `search_candidate_evidence`, over an admitted frozen snapshot's resume and experiences.
 * The model controls only requirement IDs and queries; snapshot, owner and budget come from [authorization].
 * Refusals are `isError` results whose text is a code: MISSING_CAPABILITY, INVALID_ARGUMENTS, TOO_MANY_QUERIES,
 * QUERY_TOO_LONG, a Denied reason, or UNKNOWN_REQUIREMENT, all decided before any source read. Any other admission or
 * retrieval failure becomes TOOL_FAILED without its message; interruption propagates.
 */
class JobFitMcpTools(
    private val retriever: JobFitEvidenceRetriever,
    private val authorization: JobFitMcpAuthorization,
    private val properties: JobFitMcpProperties,
) {
    /** Builds the tool registration; retrieval blocks the request thread, and retriever failures and interruption propagate. */
    fun specification(): SyncToolSpecification = SyncToolSpecification(tool()) { _, request ->
        try {
            McpSchema.CallToolResult.builder().structuredContent(search(request.meta()?.get(JOB_FIT_MCP_CAPABILITY_META), request.arguments()))
                .addTextContent("ok").build()
        } catch (rejection: JobFitMcpRejection) {
            log.warn("job_fit_mcp_rejected code={}", rejection.code)
            McpSchema.CallToolResult.builder().isError(true).addTextContent(rejection.code).build()
        } catch (exception: RuntimeException) {
            // Admission or retrieval messages may carry the capability or RAW source text, so only a fixed code leaves the server.
            if (exception is CancellationException || Thread.currentThread().isInterrupted) throw exception
            log.warn("job_fit_mcp_tool_failed type={}", exception.javaClass.name)
            McpSchema.CallToolResult.builder().isError(true).addTextContent("TOOL_FAILED").build()
        }
    }

    // Validate, admit, then retrieve; retrieval may index shared RAG documents.
    private fun search(capability: Any?, arguments: Map<String, Any?>?): JobFitMcpSearchResult {
        if (capability !is String || capability.isBlank()) throw JobFitMcpRejection("MISSING_CAPABILITY")
        val call = parse(arguments)
        val admitted = when (val admission = authorization.admit(capability, call)) {
            is JobFitMcpAdmission.Denied -> throw JobFitMcpRejection(admission.reason)
            is JobFitMcpAdmission.Admitted -> admission
        }
        if (call.queries.any { it.requirementId !in admitted.requirementIds }) throw JobFitMcpRejection("UNKNOWN_REQUIREMENT")
        val snapshot = admitted.snapshot
        val retrieval = retriever.retrieve(snapshot, call.queries.map(JobFitMcpQuery::query))
        return JobFitMcpSearchResult(snapshot.snapshotId, call.queries.map { query ->
            JobFitMcpRequirementEvidence(query.requirementId, query.query, retrieval.passagesByQuery.getValue(query.query).map {
                JobFitEvidenceItem(it.id, it.snapshotId, it.sourceType, it.resourceId, it.contentHash, it.sourceLabel, it.text, it.text)
            })
        }, retrieval.degradedSources)
    }

    private fun parse(arguments: Map<String, Any?>?): JobFitMcpSearchCall {
        val queries = (arguments?.takeIf { it.keys == setOf("queries") }?.get("queries") as? List<*>)
            ?.takeIf { it.isNotEmpty() } ?: throw JobFitMcpRejection("INVALID_ARGUMENTS")
        if (queries.size > properties.maxQueriesPerCall) throw JobFitMcpRejection("TOO_MANY_QUERIES")
        return JobFitMcpSearchCall(queries.map { entry ->
            val fields = (entry as? Map<*, *>)?.takeIf { it.keys == setOf("requirementId", "query") }
            val requirementId = fields?.get("requirementId") as? String
            val query = fields?.get("query") as? String
            if (requirementId.isNullOrBlank() || query.isNullOrBlank()) throw JobFitMcpRejection("INVALID_ARGUMENTS")
            if (query.length > properties.maxQueryChars) throw JobFitMcpRejection("QUERY_TOO_LONG")
            JobFitMcpQuery(requirementId, query)
        })
    }

    private fun tool() = McpSchema.Tool.builder()
        .name(JOB_FIT_MCP_TOOL)
        .description("Search the candidate's frozen resume and saved experiences for evidence of unresolved job requirements.")
        .inputSchema(McpSchema.JsonSchema("object", mapOf(
            "queries" to mapOf(
                "type" to "array", "minItems" to 1, "maxItems" to properties.maxQueriesPerCall,
                "items" to mapOf(
                    "type" to "object", "additionalProperties" to false, "required" to listOf("requirementId", "query"),
                    "properties" to mapOf(
                        "requirementId" to mapOf("type" to "string", "minLength" to 1),
                        "query" to mapOf("type" to "string", "minLength" to 1, "maxLength" to properties.maxQueryChars),
                    ),
                ),
            ),
        ), listOf("queries"), false, null, null))
        .build()

    private companion object {
        private val log = LoggerFactory.getLogger(JobFitMcpTools::class.java)
    }
}
