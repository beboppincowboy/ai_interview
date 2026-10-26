# Agentic Job Fit operations

Agentic Job Fit is off by default. This page covers the parts that exist so far. Later units of the [plan](../plans/2026-10-24-1621-feat-agentic-job-fit-plan.md) add the run flag, budgets and evaluation.

## Private MCP retrieval (U9)

The Job Fit worker exposes one MCP tool, `search_candidate_evidence`, and calls it itself. The tool searches a frozen snapshot's resume and saved experiences through the existing RAG retrieval, with the same local fallback when vectors are unavailable. Target-job text is never returned as candidate evidence.

- **Transport:** stateless Streamable HTTP at `/internal/mcp/job-fit`, using the MCP Java SDK 1.1.0 that Spring AI 2.0.0-M4 already brings in. There is no new dependency.
- **Where it runs:** only in processes with `JOB_RUNTIME_MODE` set to `worker` or `all`. The client calls `JOB_FIT_MCP_BASE_URL`, which defaults to the same process over loopback (`http://127.0.0.1:${server.port}`).
- **Authentication:** every request needs `Authorization: Bearer $JOB_FIT_MCP_TOKEN`. Any request with an `Origin` header is refused, because no browser should ever call this endpoint.
- **Per-call authority:** each tool call carries an opaque capability in the request's `_meta`, outside the model-visible arguments. The server asks `JobFitMcpAuthorization` to admit every call, including retries, before reading any source. Until U5/U6 provide the persisted run, lease and budget admission, the default denies every call (`NO_ADMISSION`). So enabling the flag today opens the endpoint, but no call can read sources.
- **What the model controls:** only requirement IDs and queries. The arguments allow 1 to `JOB_FIT_MCP_MAX_QUERIES_PER_CALL` queries, each up to `JOB_FIT_MCP_MAX_QUERY_CHARS` characters. Unknown fields, such as a server URL or snapshot ID, are rejected. Server address, token, owner, snapshot, deadline and round number all come from configuration or admission.
- **Failures:** the client turns every non-success into `JobFitMcpException`, never a partial success. Its code is the server's rejection code, `TOOL_FAILED` (a server-side failure, whose message is never sent back), `DEADLINE_EXCEEDED` (the run deadline passed before or during the call), `TRANSPORT_FAILED` or `MALFORMED_RESULT`. Each call is bounded by the lesser of `JOB_FIT_MCP_REQUEST_TIMEOUT` and the time the run has left. An interrupted worker gets a `CancellationException` with its interrupt flag kept.

### Configuration

| Variable | Default | Notes |
|---|---|---|
| `JOB_FIT_MCP_ENABLED` | `false` | When false, there is no endpoint and no client bean. |
| `JOB_FIT_MCP_TOKEN` | empty | Required when enabled, at least 32 characters. Generate with `openssl rand -hex 32`. Never logged. |
| `JOB_FIT_MCP_BASE_URL` | `http://127.0.0.1:${server.port}` | Only change this if the server runs in another process. Across hosts, use `https://` and a private network. |
| `JOB_FIT_MCP_REQUEST_TIMEOUT` | `30s` | Upper bound per call. The run deadline can shorten it. |
| `JOB_FIT_MCP_MAX_QUERIES_PER_CALL` | `8` | Larger batches are rejected, not truncated. |
| `JOB_FIT_MCP_MAX_QUERY_CHARS` | `500` | Longer queries are rejected. |

Startup fails if the flag is on and the token is too short or the base URL lacks an HTTP(S) prefix. URL syntax and reachability are checked when the client uses it.

### Local development

Set `JOB_FIT_MCP_ENABLED=true` and a generated `JOB_FIT_MCP_TOKEN` in `.env`, then start the app in `all` mode. To inspect the server by hand, send MCP JSON-RPC to `http://127.0.0.1:8080/internal/mcp/job-fit` with the bearer header and no `Origin`. `tools/list` should show only `search_candidate_evidence`. Calls are denied until admission exists.

### Deployment isolation

- The web nginx proxies only `/api/`, so `/internal/mcp/job-fit` cannot be reached through public ingress.
- Worker pods have no Kubernetes Service, and each worker calls its own endpoint over loopback.
- An API-only process never serves the route, even when the flag is on.
- The Helm chart does not set the MCP variables yet. Add the token as a worker secret when the feature is enabled (U6/U8), not before.

### Token rotation

Each worker calls its own endpoint, so there is no cross-pod token agreement to coordinate. To rotate, update the secret and do a rolling restart. No pod ever sees two tokens at once.

### Rollback

Set `JOB_FIT_MCP_ENABLED=false` and restart. The endpoint and client disappear, and existing Job Fit behaviour is unchanged, because nothing outside this package calls the client yet.
