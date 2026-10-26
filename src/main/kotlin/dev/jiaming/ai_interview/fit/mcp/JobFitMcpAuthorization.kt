package dev.jiaming.ai_interview.fit.mcp

import dev.jiaming.ai_interview.fit.JobFitSourceSnapshot

/** Decides whether one MCP retrieval call may read sources; the server asks before retrieval for each valid request, including retries and duplicates.
 * [capability] is the opaque per-operation token the worker sent outside model-visible arguments; it is never logged.
 * Implementations bind it to the persisted run, owner, snapshot, operation and lease generation and may reserve budget for [call];
 * a Denied result or a thrown exception stops the call before any source read.
 */
fun interface JobFitMcpAuthorization {
    fun admit(capability: String, call: JobFitMcpSearchCall): JobFitMcpAdmission
}

sealed interface JobFitMcpAdmission {
    /** The call may search [snapshot]; every requested requirement ID must be in [requirementIds]. */
    data class Admitted(val snapshot: JobFitSourceSnapshot, val requirementIds: Set<String>) : JobFitMcpAdmission

    /** [reason] is a machine-readable code returned to the worker, never source text or the capability. */
    data class Denied(val reason: String) : JobFitMcpAdmission
}

/** Fail-closed default until a persisted admission exists: every call is denied with NO_ADMISSION. */
object DenyAllJobFitMcpAuthorization : JobFitMcpAuthorization {
    override fun admit(capability: String, call: JobFitMcpSearchCall) = JobFitMcpAdmission.Denied("NO_ADMISSION")
}
