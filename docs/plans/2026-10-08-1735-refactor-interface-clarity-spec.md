# Interface Clarity: Cross-Cutting Fixes - Spec

Source: a blind-reader check of the whole repo (interface-clarity skill, 2026-10-08). Blind agents read only names,
signatures and doc comments; verifier agents compared their predictions with the code. 130 confirmed findings
(26 high, 70 med, 34 low). This spec covers the findings that recur across slices. Single-slice findings are out of
scope (see Out of Scope).

## Problem Statement

A developer or coding agent working on this app reads the public surface (API field names, function names,
signatures, doc comments) to decide what code does, and the surface often says something other than what the code
does. The same mistakes show up in every layer:

- A field called `activeJob` holds the newest job for a resource in any status, including SUCCEEDED and FAILED. A
  client that shows a spinner while `activeJob` is non-null spins forever. It appears on resumes, fit, suggestions,
  practice sets and attempts.
- "job" names two concepts: a background job (AI or extraction work) and a target job (a posting the candidate aims
  at). A target job is also called a "job description" in some services and ids. One page holds a route param
  `jobId` (a target job) three lines from `job` (a background job).
- Deduplication and idempotency work in at least four ways under four names. The server dedupes only when an
  `Idempotency-Key` header is present. The web client sends that header only on POST, yet retries PATCH and DELETE
  twice by default without one, and its doc says every call gets a key. "This matched an existing record" is called
  `duplicate`, `skipped`, `replayed`, or (inverted) `created`. Background job submission dedupes on job type and
  resource only, ignoring the payload, and nothing says so.
- Error codes are not mapped in one place. The same storage failure is `REQUEST_FAILED` in an API response and
  `HTTP_502` on a failed job. Codes that reach clients (`RETRIES_EXHAUSTED_*`, `WORKER_LEASE_EXPIRED`, the
  reference-resolution codes) are documented nowhere. The AI provider exception, its codes, transport and metrics are
  named after Gemini but also carry OpenAI failures. The web app exports an `ErrorCode` union that nothing imports
  and that has drifted from the message table.
- Units and closed sets are unstated. RAG "budget" settings are snippet counts, which a reader takes for characters.
  Several fields are closed sets on the web side but plain strings on the server (practice-set mode, question origin,
  priority, report status, resume source and status). Practice-set `mode` is required to be exactly `PRACTICE`, but
  its type says optional.
- Two RAG retrieval overloads search every user's vectors with no filter. Nothing calls them today, but they look
  like the simplest entry point.
- The glossary has no entries for any of this: background job, target job, lease, checkpoint, dead-letter queue,
  idempotency key, RAG snippet.

## Solution

From the developer's point of view: each concept has one name in the glossary, used the same way in the Kotlin API,
the JSON contract, the web types and the UI code. A name that sounds like "in flight" means in flight. Every code a
client can receive is listed in the API contract document with the condition that produces it, and a given failure
gets the same code wherever it surfaces. Dedup and idempotency behaviour is stated on every route that has it, under
one field name. Settings say their unit. Retrieval cannot be called without a scope.

Changes that alter the wire contract (field renames, error code changes) ship in one change across server, Mock API,
web types and the contract document, because the web app and API deploy together. There is no compatibility period:
old field names are not served alongside new ones (decided 2026-10-08).

## User Stories

1. As a web developer, I want the "newest job for this resource" field to be named `latestJob`, so that I don't
   mistake a finished job for a running one.
2. As a web developer, I want a doc comment on `latestJob` saying it can be in any status and is null only when no
   job was ever submitted, so that I check its status before showing progress.
3. As a backend developer, I want the Kotlin type for that field renamed to match, so that the server and client
   share one name.
4. As a coding agent, I want the glossary to define "background job" and "target job" as distinct terms with their
   avoided synonyms, so that I pick the right word for new names.
5. As a coding agent, I want "job description" listed as an avoided synonym for target job, so that new code stops
   introducing `jobDescriptionId`.
6. As a web developer, I want the route param for a target job to be named `targetJobId`, so that `jobId` in UI code
   always means a background job.
7. As a backend developer, I want public ids and parameters that refer to a target job to say `targetJobId`, so that
   the same id has one name in every service.
8. As a coding agent, I want the glossary to define lease, checkpoint, dead-letter queue, idempotency key and RAG
   snippet, so that I can read the job and retrieval code without opening bodies.
9. As a web developer, I want every create-style response to report "matched an existing record" through one field
   with one polarity, so that I handle duplicates the same way everywhere.
10. As a web developer, I want each create route's doc to say what the dedup key is (for example normalized text
    only, or title plus description), so that I know when a submission will be merged with an existing record.
11. As a web developer, I want the doc to say which submitted fields are dropped on a duplicate (such as a new name),
    so that I can tell the user their name was not applied.
12. As a web developer, I want the API client's doc to state which methods send an `Idempotency-Key` and which are
    retried, so that I know when a retry can repeat a side effect.
13. As a web developer, I want non-idempotent methods not to be retried automatically without a key, so that a
    timed-out PATCH or DELETE is never applied twice.
14. As a backend developer, I want the idempotency helpers' docs to say they act only when the request carries an
    `Idempotency-Key` header, and what status a reused or in-flight key gets, so that I don't assume they dedupe by
    body.
15. As a backend developer, I want the job submission doc to state that dedup is per job type and resource and
    ignores the payload, so that I don't expect a changed payload to start a new job.
16. As a backend developer, I want a single job submission entry point that always applies the availability check
    and the AI rate limit, so that a new caller cannot bypass them.
17. As a web developer, I want every error code a route can return listed in the API contract document with its HTTP
    status and trigger, so that I branch on codes instead of messages.
18. As a web developer, I want a background job's failure codes, including retry exhaustion and lease expiry, listed
    in the contract document, so that the job progress UI can explain them.
19. As a backend developer, I want a storage failure to map to the same error code in API responses and in failed
    jobs, so that clients see one code per failure.
20. As a backend developer, I want error mapping for a library failure to happen at one boundary, so that I change
    it in one place.
21. As a backend developer, I want the AI provider exception, transport and metrics to have provider-neutral names,
    so that an OpenAI failure isn't reported as a Gemini one in code and dashboards.
22. As a web developer, I want the wire error codes the client already maps to stay unchanged, so that the
    provider-neutral rename doesn't break the UI copy.
23. As a web developer, I want one error code type that both the message table and the code extractor use, so that
    a new server code without a message is a compile error.
24. As a backend developer, I want the experience rename conflict to use a specific code instead of generic
    `CONFLICT`, so that the client can branch on it.
25. As a backend developer, I want RAG budget settings named for snippet counts, so that tuning them doesn't send
    whole documents to the model.
26. As a backend developer, I want the direct-context threshold (whole text sent when short) documented on the
    context methods, so that I know when retrieval and budgets apply at all.
27. As a backend developer, I want server fields that are closed sets typed as enums, so that an unknown value fails
    at the boundary instead of reaching the client.
28. As a web developer, I want practice-set `mode` either required in the type or defaulted on the server, so that
    leaving it out doesn't always produce a 400.
29. As a backend developer, I want the unscoped retrieval overloads removed, so that every vector search is filtered
    to the caller's own indexes.
30. As a backend developer, I want the remaining retrieval methods to document that they throw when no vector store
    is configured, so that callers don't expect an empty list.
31. As a reviewer, I want the shared API scenarios to fail when the server and Mock API disagree on any renamed field
    or code, so that a half-done rename can't merge.
32. As a reviewer, I want the contract freeze tests to pin the new names and the error code list, so that a later
    accidental rename is caught.
33. As a coding agent, I want the interface-clarity blind-reader check to pass on the changed names, so that the fix
    is judged by the same test that found the problem.

## Implementation Decisions

- **Glossary first.** Add terms to the repo glossary before renaming code: Background job, Target job (avoid: job
  description, job), Latest job, Lease, Checkpoint, Dead-letter queue, Idempotency key, Duplicate submission,
  RAG snippet, AI provider error. Each entry says what the term is not, as the existing entries do.
- **`activeJob` becomes `latestJob`** everywhere: the Kotlin model type and every view model field (resumes, fit,
  suggestions, practice sets, attempts), the JSON contract, the Mock API, the web types and the UI. The resume
  library's own lookup for this field is replaced by the shared job store lookup so both paths return the same thing.
  Semantics stay the same; only the name and doc change.
- **"job" means background job only.** Public ids, parameters and route params that refer to a target job are named
  `targetJobId`. The job-description persistence module keeps its table name, but its public surface uses target job
  vocabulary. Internal job type names (`JOB_FIT` and similar) are frozen contract values and stay.
- **One duplicate flag.** Create-style responses report a match with an existing record as `duplicate: Boolean`
  (true means the returned item already existed and nothing new was stored). Batch responses keep counts but name
  them consistently with this flag. The voice save `replayed` flag and the inverted practice-set `created` flag move
  to the same convention. Each route's contract entry states its dedup key and which submitted fields are ignored on
  a duplicate.
- **Idempotency.** The web client attaches an `Idempotency-Key` to POST only (as now) and stops automatically
  retrying PATCH and DELETE; GET keeps its retries. The client doc is corrected to match. Server-side idempotency
  helpers keep their header-driven behaviour, and their docs state it, including the reused-key and in-flight
  responses.
- **Job submission.** One public submission entry point applies the availability check and the AI rate limit, and
  takes an explicit dedup key. The variant with an initial checkpoint becomes a parameter of that entry point. The
  bare create-or-reuse methods stop being public. Dedup remains per job type and resource unless a caller passes
  another key; the doc states this.
- **Error codes.** One mapping turns library and storage failures into codes, used by both the API exception handler
  and the job failure classifier, so a failure has one code in both places. Retry-exhaustion and lease-expiry codes,
  reference-resolution codes, voice codes, and the experience rename conflict (now a specific code) are listed in the
  API contract document. The web `ErrorCode` union becomes the key type of the message table, and the code
  extractor returns it, so drift fails type checking.
- **AI provider naming.** The provider exception, error code holder, transport and repair metric get
  provider-neutral names. The wire error code strings (`GEMINI_*`) are frozen client contract and stay unchanged;
  the contract document notes that the prefix is historical. The two exception constructors that silently default
  to an upstream error are removed so every raise names its code.
- **RAG.** Budget settings are renamed to say they count snippets. The context methods document the direct-context
  threshold. The two unscoped retrieval overloads are deleted. The remaining methods document the missing-vector-store
  failure.
- **Closed sets.** Server fields that the web app already narrows to unions become enums (or a validated type) on
  the server: practice-set mode, question origin, priority, voice report status, resume source and status. JSON
  values do not change. Practice-set mode gets a server default so it can be omitted.
- **Order of work.** Glossary, then wire renames (one change across server, Mock API, types, contract doc), then
  server-internal renames and doc comments, then deletions. Each step leaves both test suites green.

## Testing Decisions

- **What a good test is here:** it asserts what a client can observe (field names in a response, the error code and
  status for a condition, whether a second submission creates a record) and never the internal call graph. A rename
  done right changes test expectations only at the contract seam.
- **Primary seam: the API contract.** The shared API scenarios run against the Mock API in CI and against a real API
  on demand, recording every response shape and comparing it with the committed shapes file. Renamed fields and
  changed duplicate flags are asserted there, so the server and Mock API must change together. New scenarios cover:
  a finished job still appears as `latestJob`; a duplicate create returns `duplicate: true` with the existing item;
  a reused idempotency key with a different body gets its documented code.
- **Contract freeze tests** on the server pin the renamed fields, the frozen `GEMINI_*` wire codes, and the full list
  of documented error codes, extending the existing freeze tests for job status, type and stage values.
- **Existing service and handler tests** cover behaviour that a client cannot observe directly: job submission always
  applies the rate limit (submission service tests), one mapping for storage failures in both the API handler and the
  failure classifier (exception handler contract tests and failure classifier tests), retrieval always filters by
  index (retrieval service tests), and snippet-count budgets (RAG context service tests). No new seams are added.
- **Web unit tests** for the API client cover that PATCH and DELETE are not retried and that POST retries reuse
  their key. Type checking covers the error code union.
- **Interface check:** after the change, rerun the blind-reader check on the changed names; the findings this spec
  addresses should not reappear.

## Out of Scope

- Single-slice findings from the same check, tracked in the report for later specs, including: the feedback view
  crashing on an unscored attempt, the two voice pages whose names read in reverse, the LinkedIn split hook name, the
  library-wide cache invalidation scope, the materialize methods that return a ledger id instead of a row id, the
  extraction completion that returns a deleted row's id, voice report materialization treating a domain mismatch as
  lease loss, and the duplicate job metrics.
- Changing dedup or retry semantics beyond what is listed (for example fingerprinting job payloads).
- Changing the persisted schema; table and column names stay.
- Changing frozen enum values (job status, type, stage) or the `GEMINI_*` wire codes.

## Further Notes

- The full finding list with file:line evidence, predicted vs actual behaviour and suggested fixes is in
  `2026-10-08-1735-interface-clarity-findings.md` next to this spec; copy the relevant rows into the implementation
  plan.
- The unscoped retrieval overloads have no production callers, so deleting them is safe. It is listed here because
  the risk (a cross-user search) is a security concern, not a naming one.
- Several findings came from doc comments that cite design-plan labels (KTD numbers, contract sections) a reader
  can't see. New doc comments should state the rule itself.
