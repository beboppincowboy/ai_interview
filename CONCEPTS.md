# Concepts

> Shared domain vocabulary for this project — entities, named processes, and status concepts with project-specific meaning. Seeded with core domain vocabulary, then accretes as ce-compound and ce-compound-refresh process learnings; direct edits are fine. Glossary only, not a spec or catch-all.

## Database access

### Runtime role
The restricted database login that the API and background workers use in a hosted deployment: it can read and change application rows but cannot create, alter or drop schema objects.
*Avoid:* app user, service account

It is distinct from the Migration login and never owns application objects. Its grants are re-applied by the Runtime bootstrap. A migration that adds a table grants the role access itself only when the role already exists; otherwise the table is unreachable until a bootstrap has run.

### Migration login
The privileged database login used only to apply schema migrations and run the Runtime bootstrap; application processes never receive it.

### Runtime bootstrap
The operator step that creates the Runtime role if it is missing, resets its password and re-applies its grants on all application tables.

It runs after every release that applies migrations. Because it resets the password each time, the first runtime connection made right after it has been seen to fail authentication once and succeed on a retry; retry before treating that as a real credential problem.

## API checks

### Mock API
The in-browser fake of the backend API that the web app and its tests run against when mocks are on, so screens work without a running backend.
*Avoid:* fake API, MSW

It is a second implementation of the API contract, so it can silently drift from the real API; the Live API suite exists to catch that drift.

### Live API suite
The opt-in run of the shared API scenarios against a real, running API, which checks that the real API answers the way the Mock API does.

It makes real AI calls, so it runs only when a developer names the API's address and never in CI. A failure in it is either a contract difference between the two APIs or a defect that only the real stack shows.

## Background jobs

### Background job
A unit of AI or extraction work that the API records and a worker runs later, such as a resume extraction, a resume score or a job fit. A client follows it by its job id until it reaches a terminal status.
*Avoid:* task, run

Bare "job" always means a background job. A posting the candidate aims at is a Target job, never a job.

### Target job
A job posting the candidate targets, stored as its description text; its id is the target job id.
*Avoid:* job description, job

It is not a Background job. Older code and ids call it a job description (the `job_descriptions` table, `jobDescriptionId`); that name is legacy and new code says target job.

### Latest job
The newest Background job for a resource, of the types that resource cares about, in any status.
*Avoid:* active job, current job

It is not an in-flight job: it stays the latest job after it succeeds or fails, and is absent only when the resource has never had one. Check its status before treating it as running.

### Lease
A worker's time-limited claim on a Background job, identified by a lease token and an expiry time; only the holder of a live lease may advance or finish the job.
*Avoid:* lock

It is not permanent ownership. A worker that stops extending its lease loses it, and the job can then be claimed again. A lost lease means the worker must abandon the job without writing to it.

### Checkpoint
Partial progress a Background job saves while it runs, so a later attempt can resume instead of starting over.
*Avoid:* partial result

It is not the job's result. A checkpoint can be visible on a job that has not succeeded; only a succeeded job's payload is its result.

### Dead-letter queue
The queue that receives a job message the worker failed to process too many times. A reconciler reads it and either sends the job back for another attempt, if attempts remain, or fails it as out of retries.
*Avoid:* DLQ (in prose), error queue

It holds queue messages, not jobs; the job's own row stays the source of truth for its status.

## Deduplication

### Idempotency key
A client-chosen value sent in the `Idempotency-Key` request header so that a retried request returns the first request's response instead of acting twice.
*Avoid:* request id, dedup key

It is not content deduplication. Without the header nothing is replayed. Reusing a key for a different request is rejected, and a key whose first request is still running is refused until it finishes.

### Duplicate submission
A create request whose content matches a record the user already has, so the existing record is returned and nothing new is written.
*Avoid:* replay, skipped, conflict

It is decided by the content of the request, not by an Idempotency key, and it is not an error. Each create route has its own matching rule, and fields of the request that the existing record does not share are ignored.

## AI

### RAG snippet
One retrieved passage of resume or target job text, with its source and score, that is put into an AI prompt as context.
*Avoid:* chunk (for retrieved context), excerpt

A chunk is the stored piece of a document; a snippet is a chunk after retrieval has chosen it. Retrieval budgets count snippets, not characters or tokens.

### AI provider error
The failure raised when a call to the AI model provider fails or returns an answer that does not fit the expected shape. It carries a code, whether a retry may help, and the provider's HTTP status when there was one.
*Avoid:* Gemini error (for the general case)

It is not specific to one provider: the same error and codes are used whichever provider is configured. Its codes start with `GEMINI_` for historical reasons and keep that prefix because clients depend on them.
