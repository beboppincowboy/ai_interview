package dev.jiaming.ai_interview.fit.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import dev.jiaming.ai_interview.common.RuntimeModeProperties
import dev.jiaming.ai_interview.fit.JobFitEvidenceRetriever
import io.modelcontextprotocol.server.McpServer
import io.modelcontextprotocol.server.transport.HttpServletStatelessServerTransport
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator
import io.modelcontextprotocol.spec.McpSchema
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.web.servlet.ServletRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Servlet path of the private MCP server; deliberately outside `/api/`, the only prefix the public web ingress proxies. */
const val JOB_FIT_MCP_ENDPOINT = "/internal/mcp/job-fit"
const val JOB_FIT_MCP_TOOL = "search_candidate_evidence"
/** `_meta` key that carries the per-operation capability on tool calls, outside model-visible arguments. */
const val JOB_FIT_MCP_CAPABILITY_META = "dev.jiaming/jobFitCapability"

/** Internal MCP settings (`app.job-fit.mcp.*`). Disabled by default; enabled requires a [token] of at least 32 characters,
 * an http(s) [baseUrl] the worker can reach (loopback by default), a positive [requestTimeout] and positive query limits.
 * Startup validates the URL's HTTP(S) prefix, not syntax or reachability; query limits must be positive even when disabled.
 * Invalid validated values throw [IllegalArgumentException] at startup. [token] is a shared secret and is never logged.
 */
@ConfigurationProperties(prefix = "app.job-fit.mcp")
data class JobFitMcpProperties(
    val enabled: Boolean = false,
    val token: String = "",
    val baseUrl: String = "http://127.0.0.1:8080",
    val requestTimeout: Duration = Duration.ofSeconds(30),
    val maxQueriesPerCall: Int = 8,
    val maxQueryChars: Int = 500,
) {
    init {
        require(maxQueriesPerCall > 0 && maxQueryChars > 0) { "Job Fit MCP query limits must be positive" }
        if (enabled) {
            require(token.length >= 32) { "JOB_FIT_MCP_TOKEN must be at least 32 characters when Job Fit MCP is enabled" }
            require(baseUrl.startsWith("http://") || baseUrl.startsWith("https://")) { "JOB_FIT_MCP_BASE_URL must be an http(s) URL" }
            require(requestTimeout > Duration.ZERO) { "Job Fit MCP request timeout must be positive" }
        }
    }

    override fun toString() = "JobFitMcpProperties(enabled=$enabled, baseUrl=$baseUrl, requestTimeout=$requestTimeout, " +
        "maxQueriesPerCall=$maxQueriesPerCall, maxQueryChars=$maxQueryChars)"
}

/** Registers the private MCP server (worker runtime only) and client when `app.job-fit.mcp.enabled=true`; otherwise nothing. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "app.job-fit.mcp", name = ["enabled"], havingValue = "true")
class JobFitMcpConfiguration {
    @Bean
    fun jobFitMcpServlet(
        retriever: JobFitEvidenceRetriever,
        authorization: ObjectProvider<JobFitMcpAuthorization>,
        properties: JobFitMcpProperties,
        runtimeMode: RuntimeModeProperties,
    ): ServletRegistrationBean<HttpServletStatelessServerTransport> {
        val tools = JobFitMcpTools(retriever, authorization.getIfAvailable { DenyAllJobFitMcpAuthorization }, properties)
        return ServletRegistrationBean(jobFitMcpServerTransport(tools, properties.token), JOB_FIT_MCP_ENDPOINT).apply {
            setName("jobFitMcp")
            isEnabled = runtimeMode.workerEnabled()
        }
    }

    @Bean
    fun jobFitMcpClient(properties: JobFitMcpProperties, objectMapper: ObjectMapper) =
        JobFitMcpClient(properties, objectMapper, Clock.systemUTC())
}

/** Builds the stateless MCP servlet exposing only [tools]; every request needs `Authorization: Bearer <token>` and no Origin header. */
internal fun jobFitMcpServerTransport(tools: JobFitMcpTools, token: String): HttpServletStatelessServerTransport {
    val transport = HttpServletStatelessServerTransport.builder()
        .messageEndpoint(JOB_FIT_MCP_ENDPOINT)
        .securityValidator(BearerTokenValidator(token))
        .build()
    // Building the server registers its handler on the transport; the servlet is the only reference callers need.
    McpServer.sync(transport)
        .serverInfo("ai-interview-job-fit", "1")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .tools(tools.specification())
        .build()
    return transport
}

// No browser ever calls this server, so any Origin is hostile; the token comparison is constant-time.
private class BearerTokenValidator(token: String) : ServerTransportSecurityValidator {
    private val expected = "Bearer $token".toByteArray()

    override fun validateHeaders(headers: Map<String, List<String>>) {
        val byName = headers.mapKeys { it.key.lowercase() }
        if (!byName["origin"].isNullOrEmpty()) throw ServerTransportSecurityException(403, "Origin not allowed")
        val presented = byName["authorization"]?.singleOrNull()?.toByteArray()
        if (presented == null || !MessageDigest.isEqual(presented, expected)) throw ServerTransportSecurityException(401, "Unauthorized")
    }
}
