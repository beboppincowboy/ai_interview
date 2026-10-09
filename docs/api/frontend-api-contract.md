# Frontend API Contract

- **Date:** 2026-09-28
- **Consumers:** the rewritten web app in `apps/web` and its MSW mocks (`apps/web/mocks/`).
- **Implementer:** the next backend plan. The frontend plan
  (`docs/plans/2026-09-28-2128-feat-frontend-ux-rewrite-plan.md`) does not change the backend.
- **Authority:** this document defines request and response shapes. `apps/web/lib/api/types.ts` mirrors it.
  When the backend ships an endpoint, change its status here to `existing`.

Status meanings:

| Status | Meaning |
|---|---|
| `existing` | Implemented today with this exact shape. The web app may call the real backend. |
| `changed` | Exists today, but the new UI needs a different shape or more values. Mocked until the backend ships the change. |
| `new` | Does not exist today. Mocked until the backend ships it. |

## 1. Conventions

- **Base path:** `/api`. Bodies are JSON (`application/json`) unless stated. IDs are UUID strings. Times are
  ISO-8601 instants in UTC (`2026-09-28T21:28:00Z`).
- **User scope:** there is no sign-in in this phase. Every read and write is scoped to the single local user
  (`LocalUserService`). Every lookup filters by owner, so an ID owned by another user returns the same `404` as
  a missing ID.
- **Errors:** every non-2xx response has the existing body:

  ```json
  { "code": "RESUME_NOT_FOUND", "message": "Resume not found" }
  ```

  `code` is SCREAMING_SNAKE and stable. `message` is for logs. The UI maps codes to its own copy.
- **Idempotency:** every `POST` accepts the existing `Idempotency-Key` header. The same key with the same body
  returns the first response (for job submissions, `reused: true`). The same key with a different body returns
  `409 CONFLICT`. The web app sends one key per user action: its own automatic network retries reuse the key,
  and a user pressing Retry sends a new key.
- **Rate limits (existing Redis guard):** every request that starts an AI job, including retries and re-scores,
  counts against the AI limit (default 12 per 60 s per client). Uploads count against the upload limit
  (default 20 per 60 s). Over the limit returns `429 RATE_LIMITED`.
- **Lists:** list endpoints return `{ "items": [...] }`, newest first, without pagination. Each user has at most a
  few hundred items in this phase.

### 1.1 Shared shapes

**`LatestJob`**: the newest background job for a resource, in any status. It can be `SUCCEEDED` or `FAILED`, so check
`status` before showing progress. It is `null` only when no job was ever submitted for the resource. It is not cleared
when the job ends, so the UI can show the last failure after a reload.

```json
{
  "jobId": "0b6f…",
  "jobType": "RESUME_SCORE",
  "status": "RETRYING",
  "stage": "SCORING_RESUME",
  "attempts": 2,
  "maxAttempts": 3,
  "error": null
}
```

| Field | Type | Notes |
|---|---|---|
| `jobId` | UUID | Poll with `GET /api/jobs/{jobId}`. |
| `jobType` | `JobType` | See section 8. |
| `status` | `JobStatus` | `QUEUED`, `PROCESSING`, `RETRYING`, `SUCCEEDED`, `FAILED`. |
| `stage` | `JobStage` | See section 8. |
| `attempts` | int | Attempts started so far. |
| `maxAttempts` | int | **new** field, used for copy such as "attempt 2 of 3". |
| `error` | `JobError` or null | Set when `status` is `RETRYING` or `FAILED`. |

**`JobError`** (existing): `{ "code": string | null, "message": string, "retryable": boolean | null }`.

**`JobAccepted`** (existing shape, returned by `202` job submissions):

```json
{
  "jobId": "0b6f…",
  "jobType": "RESUME_SCORE",
  "status": "QUEUED",
  "stage": "QUEUED",
  "statusUrl": "/api/jobs/0b6f…",
  "reused": false,
  "inputRefs": { "resumeId": "…", "targetJobId": null, "practiceSetId": null, "attemptId": null }
}
```

`inputRefs` contains four nullable references. Older jobs' `jobDescriptionId` is exposed as `targetJobId`.

**`DeleteImpact`** (new): what a delete removes, shown in the confirmation dialog.

```json
{
  "scores": 3,
  "fits": 2,
  "suggestionSets": 2,
  "practiceSets": 1,
  "attempts": 14,
  "staleSuggestionSets": 1
}
```

`staleSuggestionSets` counts suggestion sets for other pairs that used this item as a source. Those sets are
kept and marked stale (section 6), not deleted.

## 2. Endpoint index

| # | Method and path | Status | Used by |
|---|---|---|---|
| 2.1 | `GET /api/status` | existing | Health badge (optional) |
| 3.1 | `POST /api/resumes` (multipart) | existing | Resume picker, library |
| 3.2 | `POST /api/resumes/paste` | existing | Resume picker, library |
| 3.3 | `GET /api/resumes` | existing | Resume picker, library, home |
| 3.4 | `GET /api/resumes/{resumeId}` | existing | Score page, home |
| 3.5 | `PATCH /api/resumes/{resumeId}` | existing | Rename, job-title edit |
| 3.6 | `GET /api/resumes/{resumeId}/delete-impact` | existing | Delete dialog |
| 3.7 | `DELETE /api/resumes/{resumeId}` | existing | Library |
| 3.8 | `POST /api/resumes/{resumeId}/score` | existing | Score page |
| 4.1 | `POST /api/target-jobs` | existing | Target job picker, library |
| 4.2 | `GET /api/target-jobs` | existing | Target job picker, library, home |
| 4.3 | `GET /api/target-jobs/{targetJobId}` | existing | Fit page, library |
| 4.4 | `PATCH /api/target-jobs/{targetJobId}` | existing | Rename |
| 4.5 | `GET /api/target-jobs/{targetJobId}/delete-impact` | existing | Delete dialog |
| 4.6 | `DELETE /api/target-jobs/{targetJobId}` | existing | Library |
| 5.1 | `POST /api/experiences` | existing | Experience library (project form) |
| 5.2 | `POST /api/experiences/linkedin-split` | existing | Experience library (LinkedIn paste) |
| 5.3 | `POST /api/experiences/batch` | existing | Experience library (save reviewed items) |
| 5.4 | `GET /api/experiences` | existing | Experience library |
| 5.5 | `PATCH /api/experiences/{experienceId}` | existing | Rename |
| 5.6 | `GET /api/experiences/{experienceId}/delete-impact` | existing | Delete dialog |
| 5.7 | `DELETE /api/experiences/{experienceId}` | existing | Experience library |
| 6.1 | `GET /api/resumes/{resumeId}/target-jobs/{targetJobId}/fit` | existing | Fit page |
| 6.2 | `POST /api/resumes/{resumeId}/target-jobs/{targetJobId}/fit` | existing | Fit page |
| 6.3 | `GET /api/resumes/{resumeId}/target-jobs/{targetJobId}/suggestions` | existing | Fit page |
| 6.4 | `POST /api/resumes/{resumeId}/target-jobs/{targetJobId}/suggestions` | existing | Fit page |
| 7.1 | `POST /api/practice-sets` | existing | Mode chooser |
| 7.2 | `GET /api/practice-sets/{setId}` | existing | Practice page |
| 7.3 | `POST /api/practice-sets/{setId}/retry` | existing | Practice page (generation failed) |
| 7.4 | `POST /api/practice-sets/{setId}/questions` | existing | Practice page (add your own question) |
| 7.5 | `POST /api/practice-sets/{setId}/questions/{questionId}/attempts` | existing | Practice page |
| 7.6 | `POST /api/attempts/{attemptId}/retry` | existing | Practice page |
| 8.1 | `GET /api/jobs/{jobId}` | existing | Every AI step |
| 9.1 | `GET /api/history` | existing | History |

## 3. Resumes

**`Resume`** (list item):

```json
{
  "id": "5d0c…",
  "name": "Backend 2026",
  "jobTitle": "Backend Engineer",
  "source": "UPLOAD",
  "originalFilename": "resume.pdf",
  "status": "READY",
  "latestScore": { "overall": 72, "scoredAt": "2026-09-28T21:30:00Z", "stale": false },
  "latestJob": null,
  "createdAt": "2026-09-28T21:28:00Z",
  "updatedAt": "2026-09-28T21:30:00Z"
}
```

| Field | Type | Notes |
|---|---|---|
| `name` | string, 1–80 | User-chosen. |
| `jobTitle` | string (≤100) or null | Optional context for the general score. |
| `source` | `UPLOAD` \| `PASTE` | |
| `originalFilename` | string or null | Null for pasted text. |
| `status` | `PROCESSING` \| `READY` \| `FAILED` | `PROCESSING` while `RESUME_EXTRACTION` runs. Pasted resumes start `READY`. |
| `latestScore` | object or null | `stale` is true when `jobTitle` changed after that score. |
| `latestJob` | `LatestJob` or null | The latest `RESUME_EXTRACTION` or `RESUME_SCORE` job. |

**`ResumeDetail`** = `Resume` plus:

| Field | Type | Notes |
|---|---|---|
| `text` | string or null | Normalized text. Null until extraction succeeds. |
| `score` | `ResumeScoreResult` or null | Latest general score (section 8.2). |

**`ResumeCreated`**: `{ "resume": Resume, "duplicate": boolean }`. When `duplicate` is true, `resume` is the
already-saved item and nothing new was created (R4). The UI shows "Already saved as <name>" with a link to it
and a rename action.

### 3.1 `POST /api/resumes` — changed

Multipart form:

| Part | Required | Notes |
|---|---|---|
| `file` | yes | PDF, DOC, DOCX, TXT or Markdown, at most 10 MB (existing validation). |
| `name` | no | 1–80 chars. Defaults to the filename without its extension. |
| `jobTitle` | no | ≤100 chars. |

- **Today:** returns `202` with `JobAccepted` and has no `name` or `jobTitle`.
- **New:** returns `202` with `ResumeCreated`. `resume.status` is `PROCESSING` and `resume.latestJob` is the
  `RESUME_EXTRACTION` job.
- If the file bytes match a saved resume, returns `200` with `duplicate: true` and starts no job.
- If the extracted text matches a different saved resume, the extraction job still ends `SUCCEEDED`. Its result
  is `{ "resumeId": "<new id>", "duplicateOf": { "id": "…", "name": "…" } }` and the new resume is removed. The UI
  then shows the duplicate notice, and `GET` of the new ID returns `404`.
- Errors:
  - `400 INVALID_REQUEST`: name or job title too long.
  - `413 UPLOAD_TOO_LARGE`.
  - `415 UNSUPPORTED_FILE_TYPE` (**new code**; today this maps to `REQUEST_FAILED`).
  - `429 RATE_LIMITED`.
  - `503 RESUME_PARSER_BUSY`.

### 3.2 `POST /api/resumes/paste` — new

```json
{ "name": "Backend 2026", "jobTitle": null, "text": "…" }
```

- `name`: 1–80 chars, required.
- `text`: 100–50,000 chars, required.
- Returns `201` with `ResumeCreated` and `resume.status: "READY"`. No extraction job runs.
- A duplicate (same normalized text) returns `200` with `duplicate: true`.
- Errors: `400 INVALID_REQUEST`.

### 3.3 `GET /api/resumes` — new

Returns `200` with `{ "items": Resume[] }`.

### 3.4 `GET /api/resumes/{resumeId}` — new

Returns `200` with `ResumeDetail`. Errors: `404 RESUME_NOT_FOUND`, which the UI shows as "this was deleted".

### 3.5 `PATCH /api/resumes/{resumeId}` — new

Body: `{ "name"?: string, "jobTitle"?: string | null }`. Returns `200` with `Resume`.

- Changing `jobTitle`, including clearing it, sets `latestScore.stale = true`.
- A new score is never started automatically (R8).
- Errors: `400 INVALID_REQUEST`, `404 RESUME_NOT_FOUND`.

### 3.6 `GET /api/resumes/{resumeId}/delete-impact` — new

Returns `200` with `DeleteImpact`: the resume's scores, its fits, its suggestion sets, its practice sets and
their attempts, plus `staleSuggestionSets` for other pairs that used this resume as a source.

### 3.7 `DELETE /api/resumes/{resumeId}` — new

Returns `204`.

- Cascades to the stored file, scores, fits, suggestion sets, practice sets, questions and attempts (R5).
- Cancels the resume's in-flight jobs; `GET /api/jobs/{id}` for them then returns `404 JOB_NOT_FOUND`.
- Marks other pairs' suggestion sets that used it as stale.
- Errors: `404 RESUME_NOT_FOUND`.

### 3.8 `POST /api/resumes/{resumeId}/score` — new

Body: `{}`. Starts a `RESUME_SCORE` job and returns `202` with `JobAccepted`.

- The score uses only the resume text and its `jobTitle`, never a job description or seniority (R6).
- A new score replaces `ResumeDetail.score`. Older scores stay in history.
- Errors:
  - `404 RESUME_NOT_FOUND`.
  - `409 RESUME_NOT_READY`: status is not `READY`.
  - `429 RATE_LIMITED`.

## 4. Target jobs

**`TargetJob`** (list item):

```json
{ "id": "a1…", "name": "Acme — Senior Backend", "createdAt": "…", "updatedAt": "…" }
```

**`TargetJobDetail`** = `TargetJob` plus `text: string`, the saved job description. The text cannot be edited
(R2).

**`TargetJobCreated`**: `{ "targetJob": TargetJobDetail, "duplicate": boolean }`.

### 4.1 `POST /api/target-jobs` — existing

```json
{ "name": "Acme — Senior Backend", "text": "…" }
```

- `name`: 1–80 chars.
- `text`: 100–20,000 chars.
- Returns `201` with `TargetJobCreated`.
- The same normalized text returns `200` with `duplicate: true` and the existing item (R4).
- Errors: `400 INVALID_REQUEST`.

### 4.2 `GET /api/target-jobs` — existing

Returns `200` with `{ "items": TargetJob[] }`.

### 4.3 `GET /api/target-jobs/{targetJobId}` — existing

Returns `200` with `TargetJobDetail`. Errors: `404 TARGET_JOB_NOT_FOUND`.

### 4.4 `PATCH /api/target-jobs/{targetJobId}` — existing

Body: `{ "name": string }`. Returns `200` with `TargetJob`. `text` is not accepted.

### 4.5 `GET /api/target-jobs/{targetJobId}/delete-impact` — existing

Returns `200` with `DeleteImpact`. `scores` is always `0` and `staleSuggestionSets` is always `0`.

### 4.6 `DELETE /api/target-jobs/{targetJobId}` — existing

Returns `204`. Cascades to fits, suggestion sets, practice sets, questions and attempts for every pair that uses
this job, and cancels their in-flight jobs.

## 5. Experiences

**`Experience`**:

```json
{
  "id": "e1…",
  "title": "Payments ledger rewrite",
  "organization": "Acme",
  "startDate": "2023-01",
  "endDate": null,
  "description": "…",
  "source": "FORM",
  "createdAt": "…"
}
```

| Field | Type | Notes |
|---|---|---|
| `title` | string, 1–120 | Required. Rename edits it. |
| `organization` | string (≤120) or null | |
| `startDate`, `endDate` | `YYYY-MM` or null | |
| `description` | string, 1–4,000 | Required. |
| `source` | `FORM` \| `LINKEDIN` | |

**`ExperienceInput`**: the same fields without `id`, `source` and `createdAt`.

### 5.1 `POST /api/experiences` — existing

Body: `ExperienceInput`. Returns `201` with `{ "experience": Experience, "duplicate": boolean }`.

- A duplicate (same normalized title and description) returns `200` with `duplicate: true`.
- Errors: `400 INVALID_REQUEST`.

### 5.2 `POST /api/experiences/linkedin-split` — existing

Body: `{ "text": string }`, 50–20,000 chars. Starts an `EXPERIENCE_SPLIT` job and returns `202` with
`JobAccepted`.

The job result is `ExperienceSplitResult` (section 8.2). Nothing is saved until the user confirms with 5.3.

The split and review are browser-assisted: the dialog keeps the pasted text, job ID and removed-item choices in
localStorage scoped to the current app/API environment. Closing the dialog preserves recovery data. Explicit
discard or successful save clears it. If the job is missing or expired, the dialog keeps the text and offers an
explicit resplit; it never resubmits automatically.

Errors: `400 INVALID_REQUEST`, `429 RATE_LIMITED`.

### 5.3 `POST /api/experiences/batch` — existing

Body: `{ "items": ExperienceInput[] }`, 1–30 items, with `source` recorded as `LINKEDIN`. Returns `201`:

```json
{
  "created": [ { "id": "…", "title": "…" } ],
  "skipped": [ { "title": "…", "existingId": "…", "existingTitle": "…" } ]
}
```

`created` holds full `Experience` objects (shortened above). Items already saved are skipped, not duplicated
(R3). The UI shows each skipped item with a note.

A list outside 1–30 items returns `400 INVALID_REQUEST` with a message naming `items`. Duplicate matching is
per user and uses whitespace-collapsed, case-insensitive title and description; organization and dates do not
participate. The batch checks again when saving, so changes after review cannot create a duplicate.

### 5.4 `GET /api/experiences` — existing

Returns `200` with `{ "items": Experience[] }`.

### 5.5 `PATCH /api/experiences/{experienceId}` — existing

Body: `{ "title": string }`. Returns `200` with the updated `Experience`. Renaming recomputes the duplicate
hash from the normalized title and unchanged description. Errors: `400 INVALID_REQUEST` for an invalid title,
`404 EXPERIENCE_NOT_FOUND` for a missing or other user's experience, and `409 CONFLICT` if another owned
experience already has the same normalized title and description.

### 5.6 `GET /api/experiences/{experienceId}/delete-impact` — new

Returns `200` with `DeleteImpact`. Only `staleSuggestionSets` can be non-zero.

### 5.7 `DELETE /api/experiences/{experienceId}` — new

Returns `204`.

- Suggestion sets that used the experience are marked stale.
- Their items that named it are dropped from the stored result.

## 6. Job fit and suggestions (per resume and target job pair)

Both resources are keyed by the pair. `GET` always returns `200` for an existing pair, even before any run, so
the page can render "not run yet". Either ID missing returns `404 RESUME_NOT_FOUND` or
`404 TARGET_JOB_NOT_FOUND`.

**`FitView`**:

```json
{
  "resumeId": "…",
  "targetJobId": "…",
  "result": null,
  "createdAt": null,
  "latestJob": null
}
```

When a fit has succeeded, `result` is a `JobFitResult` (section 8.2).

**`SuggestionsView`**:

```json
{
  "resumeId": "…",
  "targetJobId": "…",
  "sourcesAvailable": true,
  "stale": false,
  "result": null,
  "createdAt": null,
  "latestJob": null
}
```

| Field | Notes |
|---|---|
| `sourcesAvailable` | True when the user has at least one other `READY` resume or any experience. When false, the UI makes no request and invites the user to add a project or paste LinkedIn text (R11). |
| `stale` | True when sources were added or removed after `createdAt`. The UI offers "Refresh" (R12). |
| `result` | `ExperienceSuggestionsResult` (section 8.2). An empty `items` list means "no strong matches" (R11). |

### 6.1 `GET …/fit` — new

Returns `200` with `FitView`.

### 6.2 `POST …/fit` — new

Body: `{}`. Starts `JOB_FIT` and returns `202` with `JobAccepted`.

- A successful run replaces the stored result. One fit is kept per pair (R9).
- Errors: `409 RESUME_NOT_READY`, `429 RATE_LIMITED`.

### 6.3 `GET …/suggestions` — new

Returns `200` with `SuggestionsView`.

### 6.4 `POST …/suggestions` — new

Body: `{}`. Starts `EXPERIENCE_SUGGESTIONS` and returns `202` with `JobAccepted`.

- Sources are every other `READY` resume and every experience the user has.
- A successful run replaces the stored result and clears `stale`.
- Errors:
  - `409 NO_EXPERIENCE_SOURCES`: `sourcesAvailable` is false.
  - `409 RESUME_NOT_READY`.
  - `429 RATE_LIMITED`.

## 7. Practice

**`PracticeSet`**:

```json
{
  "id": "p1…",
  "resumeId": "…",
  "targetJobId": "…",
  "mode": "PRACTICE",
  "status": "GENERATING",
  "questions": [],
  "latestJob": { "jobId": "…", "jobType": "PRACTICE_QUESTIONS", "status": "PROCESSING", "stage": "GENERATING_QUESTIONS", "attempts": 1, "maxAttempts": 3, "error": null },
  "createdAt": "…",
  "updatedAt": "…"
}
```

| Field | Notes |
|---|---|
| `status` | `GENERATING` \| `READY` \| `FAILED`. `FAILED` means question generation failed; retry with 7.3. |
| `questions` | Ordered AI questions first, then user questions in the order they were added. |

**`Question`**:

```json
{
  "id": "q1…",
  "order": 1,
  "origin": "AI",
  "text": "Tell me about a time you reduced p99 latency.",
  "rationale": "The job lists latency SLOs as a core duty.",
  "category": "Technical depth",
  "expectedSignals": ["measured baseline", "trade-offs"],
  "attempts": []
}
```

| Field | Notes |
|---|---|
| `origin` | `AI` \| `USER`. The UI tags `USER` questions "Added by you". |
| `rationale` | The "why this question" tag. Null for `USER` questions. |
| `category` | Nullable. |
| `expectedSignals` | Empty for `USER` questions. |
| `attempts` | Oldest first. |

**`Attempt`**:

```json
{
  "id": "t1…",
  "number": 2,
  "text": "…",
  "status": "SCORED",
  "feedback": null,
  "scoreDelta": 12,
  "latestJob": null,
  "createdAt": "…"
}
```

| Field | Notes |
|---|---|
| `status` | `PENDING` \| `SCORED` \| `FAILED`. |
| `feedback` | `AnswerFeedbackResult` (section 8.2). Null unless `SCORED`. |
| `scoreDelta` | This score minus the previous `SCORED` attempt's score. Null for the first scored attempt or when not scored. |
| `latestJob` | The `ANSWER_FEEDBACK` job. On failure its `error` holds the reason; the attempt text is kept for retry (R17). |

### 7.1 `POST /api/practice-sets` — new

Body: `{ "resumeId": "…", "targetJobId": "…", "mode": "PRACTICE" }`.

- Returns the pair's set right away (R14):
  - `201` when it is new, with `status: "GENERATING"`, empty `questions` and the `PRACTICE_QUESTIONS` job as
    `latestJob`.
  - `200` when the set already exists.
- The AI chooses between 3 and 8 questions from the job description (R15).
- Errors:
  - `400 INVALID_REQUEST`: `mode` other than `PRACTICE`. Voice mode is out of scope.
  - `404 RESUME_NOT_FOUND`, `404 TARGET_JOB_NOT_FOUND`.
  - `409 RESUME_NOT_READY`.
  - `429 RATE_LIMITED`: only when a new set starts a job.

### 7.2 `GET /api/practice-sets/{setId}` — new

Returns `200` with `PracticeSet`, including questions and attempts. Errors: `404 PRACTICE_SET_NOT_FOUND`.

### 7.3 `POST /api/practice-sets/{setId}/retry` — new

Starts a new `PRACTICE_QUESTIONS` job for a `FAILED` set and returns `202` with `PracticeSet`.

Errors: `409 PRACTICE_SET_NOT_FAILED`, `429 RATE_LIMITED`.

### 7.4 `POST /api/practice-sets/{setId}/questions` — new

Body: `{ "text": string }`, 10–500 chars after trimming. Returns `201` with the `Question` (`origin: "USER"`).

Errors:

- `400 INVALID_REQUEST`.
- `409 QUESTION_LIMIT_REACHED`: the set already has 10 user questions (R16).
- `409 PRACTICE_SET_NOT_READY`: the set is still generating.

### 7.5 `POST /api/practice-sets/{setId}/questions/{questionId}/attempts` — new

Body: `{ "text": string }`, 1–4,000 chars after trimming.

- Creates the next numbered attempt with `status: "PENDING"` and starts `ANSWER_FEEDBACK`.
- Returns `201` with `Attempt`, whose `latestJob` is set.
- Several questions may have pending attempts at once (R18).
- Errors:
  - `400 ANSWER_EMPTY`.
  - `400 ANSWER_TOO_LONG`.
  - `409 ANSWER_UNCHANGED`: the text equals the latest attempt's text after trimming.
  - `404 QUESTION_NOT_FOUND`.
  - `429 RATE_LIMITED`.

### 7.6 `POST /api/attempts/{attemptId}/retry` — new

Starts a new `ANSWER_FEEDBACK` job for a `FAILED` attempt. The attempt keeps its ID, number and text. Returns
`202` with `Attempt` in `PENDING`.

Errors: `404 ATTEMPT_NOT_FOUND`, `409 ATTEMPT_NOT_FAILED`, `429 RATE_LIMITED`.

## 8. Jobs

### 8.1 `GET /api/jobs/{jobId}` — existing

Returns the same contract through JDBC and Supabase polling, including `maxAttempts`, all four nullable input
references, nested result JSON, nullable timestamps and error values:

```json
{
  "jobId": "…",
  "jobType": "JOB_FIT",
  "status": "SUCCEEDED",
  "stage": "COMPLETED",
  "attempts": 1,
  "maxAttempts": 3,
  "result": { },
  "error": null,
  "createdAt": "…",
  "startedAt": "…",
  "completedAt": "…",
  "inputRefs": { "resumeId": "…", "targetJobId": "…", "practiceSetId": null, "attemptId": null }
}
```

- Job types and stages are extended as in the table below.
- `result` is set only on `SUCCEEDED`. For resource-backed jobs it equals what the owning resource
  then returns, so the UI may read either. `EXPERIENCE_SPLIT` is review-only and its result exists only on the
  job until the user saves items through 5.3.
- Every job ends `SUCCEEDED` or `FAILED`.
- Errors: `404 JOB_NOT_FOUND`, which covers unknown IDs, other users' jobs and jobs of deleted resources.

| Job type | Status | Started by | Stages after `QUEUED` | Result |
|---|---|---|---|---|
| `RESUME_EXTRACTION` | existing (result changed) | 3.1 | `READING_FILE`, `EXTRACTING_TEXT`, `NORMALIZING_TEXT`, `CHUNKING_TEXT` | `{ resumeId, duplicateOf: {id, name} \| null }` |
| `RESUME_SCORE` | new | 3.8 | `SCORING_RESUME` | `ResumeScoreResult` |
| `JOB_FIT` | new | 6.2 | `MATCHING_JOB` | `JobFitResult` |
| `EXPERIENCE_SUGGESTIONS` | new | 6.4 | `RETRIEVING_EXPERIENCE`, `MATCHING_EXPERIENCE` | `ExperienceSuggestionsResult` |
| `PRACTICE_QUESTIONS` | new | 7.1, 7.3 | `GENERATING_QUESTIONS` (existing stage) | `{ questions: Question[] }` |
| `EXPERIENCE_SPLIT` | existing | 5.2 | `SPLITTING_EXPERIENCE` | `ExperienceSplitResult` |
| `ANSWER_FEEDBACK` | changed (attempt-based input) | 7.5, 7.6 | `SCORING_ANSWER` (existing stage) | `AnswerFeedbackResult` |

These are the only job types. A `SUCCEEDED` job ends at stage `COMPLETED`; a `FAILED` job keeps the stage it failed in.

### 8.2 Result shapes

**`ResumeScoreResult`**:

```json
{
  "overall": 72,
  "scores": { "technicalDepth": 70, "impact": 64, "clarity": 80, "relevance": 75, "ats": 71 },
  "summary": "Strong backend depth; impact is under-quantified.",
  "fixes": [
    { "rank": 1, "section": "Experience", "priority": "HIGH", "message": "Quantify the outcome of the ledger migration." }
  ],
  "rewrites": [
    {
      "section": "Experience",
      "original": "Worked on the payments ledger.",
      "rewritten": "Led the payments ledger rewrite, cutting reconciliation time by [X%].",
      "placeholders": ["[X%]"]
    }
  ],
  "jobTitle": "Backend Engineer",
  "scoredAt": "…"
}
```

- All scores are integers from 0 to 100.
- The `scores` keys are the existing `AssessmentScores` keys. `relevance` is judged against `jobTitle`, or
  against the resume's own direction when there is no job title.
- `fixes` is ranked, with `rank` 1 first. `priority` is `HIGH`, `MEDIUM` or `LOW`.
- `rewrites` never invent facts. Unknown numbers or scope appear as bracketed placeholders, listed in
  `placeholders` (R7).
- `jobTitle` is the title used for this score.

**`JobFitResult`**:

```json
{
  "fitScore": 68,
  "summary": "Good platform match; missing Kafka and on-call ownership.",
  "matchedRequirements": [ { "requirement": "Java 17+", "evidence": "5 years of Java services" } ],
  "missingRequirements": [ { "requirement": "Kafka", "guidance": "Mention any event-streaming work, or say how you would ramp up." } ],
  "feedback": [ { "priority": "HIGH", "message": "Move the latency work to the top of the Acme role." } ]
}
```

**`ExperienceSuggestionsResult`**:

```json
{
  "items": [
    {
      "requirement": "Event-driven systems",
      "source": { "type": "EXPERIENCE", "id": "e1…", "name": "Payments ledger rewrite" },
      "match": "Built an outbox-based event pipeline for ledger updates.",
      "whyItFits": "The job asks for event-driven design; this shows it in production.",
      "guidance": "Add it under the Acme role and lead with the delivery guarantee you chose."
    }
  ]
}
```

- `source.type` is `RESUME` or `EXPERIENCE`. `source.name` is the resume name or experience title (R10).
- There are no generated bullets.
- An empty `items` list means no strong matches.

**`ExperienceSplitResult`**:

```json
{
  "items": [
    {
      "title": "Senior Engineer",
      "organization": "Acme",
      "startDate": "2021-03",
      "endDate": null,
      "description": "…",
      "duplicateOf": null
    }
  ]
}
```

`duplicateOf` is `{ id, title }` when the item is already saved. The UI shows these as "already saved" and leaves
them out of 5.3.

**`AnswerFeedbackResult`** (the normalized form of the model's `AnswerFeedbackResponse`):

```json
{
  "score": 74,
  "summary": "Clear situation, thin on measurable result.",
  "nextStep": "Add the before/after latency numbers.",
  "strengths": ["Clear ownership"],
  "gaps": ["No metric"],
  "betterAnswerOutline": ["Context", "Action", "Measured result"],
  "followUpQuestion": "How did you verify the fix held under peak load?"
}
```

`score` is an integer from 0 to 100. The list fields are always arrays, possibly empty. `nextStep` and
`followUpQuestion` may be null.

## 9. History

### 9.1 `GET /api/history` — new

```json
{
  "resumes": [
    { "id": "…", "name": "Backend 2026", "scores": [ { "overall": 64, "scoredAt": "…" }, { "overall": 72, "scoredAt": "…" } ] }
  ],
  "targetJobs": [
    { "id": "…", "name": "Acme — Senior Backend", "fits": [ { "resumeId": "…", "resumeName": "Backend 2026", "fitScore": 68, "createdAt": "…" } ] }
  ],
  "practiceSets": [
    {
      "id": "…", "resumeId": "…", "resumeName": "…", "targetJobId": "…", "targetJobName": "…", "updatedAt": "…",
      "questions": [ { "id": "…", "text": "…", "scores": [58, 70, 74] } ]
    }
  ]
}
```

- Score arrays are oldest first. The UI draws a trend once an array has two points (R19).
- `questions[].scores` lists the scores of `SCORED` attempts only.

## 10. Error codes

Existing codes stay unchanged. The UI maps every code below. AI-provider codes (`GEMINI_*`) remain as they are;
the UI never shows the code or the provider name.

| Code | HTTP | Status | Meaning |
|---|---|---|---|
| `INVALID_REQUEST` | 400 | existing | Validation failed. The message names the field. |
| `ANSWER_EMPTY` | 400 | new | The answer is blank after trimming. |
| `ANSWER_TOO_LONG` | 400 | new | The answer is over 4,000 chars. |
| `NOT_FOUND` | 404 | existing | Generic missing resource. |
| `RESUME_NOT_FOUND` | 404 | existing code, new use | |
| `TARGET_JOB_NOT_FOUND` | 404 | new | Replaces `JOB_DESCRIPTION_NOT_FOUND`. |
| `EXPERIENCE_NOT_FOUND` | 404 | new | |
| `PRACTICE_SET_NOT_FOUND` | 404 | new | |
| `QUESTION_NOT_FOUND` | 404 | new | |
| `ATTEMPT_NOT_FOUND` | 404 | new | |
| `JOB_NOT_FOUND` | 404 | existing | |
| `CONFLICT` | 409 | existing | An idempotency key was reused with a different body, or an experience rename would duplicate another owned item. |
| `RESUME_NOT_READY` | 409 | existing code, new use | |
| `NO_EXPERIENCE_SOURCES` | 409 | new | |
| `PRACTICE_SET_NOT_READY` | 409 | new | |
| `PRACTICE_SET_NOT_FAILED` | 409 | new | |
| `QUESTION_LIMIT_REACHED` | 409 | new | |
| `ANSWER_UNCHANGED` | 409 | new | |
| `ATTEMPT_NOT_FAILED` | 409 | new | |
| `UPLOAD_TOO_LARGE` | 413 | existing | |
| `UNSUPPORTED_FILE_TYPE` | 415 | new | |
| `UNPROCESSABLE_CONTENT`, `RESUME_EXTRACTION_FAILED` | 422 | existing | |
| `RATE_LIMITED` | 429 | existing | |
| `SERVICE_UNAVAILABLE`, `RESUME_PARSER_BUSY` | 503 | existing | |
| `INTERNAL_ERROR`, `REQUEST_FAILED` | 500 | existing | |

`JobError.code` values: the AI and processing codes above, `PROCESSING_ERROR`, `RETRIES_EXHAUSTED_*` and
`HTTP_<status>` (all existing). `retryable` follows the existing `JobFailureClassifier`.

## 11. Backend obligations

The frontend assumes each of these. The backend plan must implement and test them.

1. **Owner scope.** Every lookup filters by the local user ID. A foreign ID gets the same `404` as a missing one.
2. **AI rate limit on every AI job**, including user retries (3.8, 5.2, 6.2, 6.4, 7.1, 7.3, 7.5, 7.6).
3. **Resource-ID job fingerprints.**
   - Job de-duplication keys on resource IDs (resume, pair, set, attempt), never on request text alone.
   - Two attempts with identical text on different questions, or a retry of a failed attempt, always start a
     new job.
   - `reused: true` comes only from the same `Idempotency-Key`, or from a submit while a job for the same resource
     is still running, which returns that running job.
4. **Cascade delete** as described in 3.7, 4.6 and 5.7. `delete-impact` counts match what the delete removes.
5. **Duplicate resolution.**
   - Applies to resumes (file bytes, then normalized text), target jobs (normalized text) and experiences
     (normalized title plus description).
   - A duplicate returns the existing item with `duplicate: true` and creates nothing.
6. **General score inputs.** Resume text and optional job title only. No job description and no seniority. The
   seniority input is removed from prompts.
7. **Rewrites use placeholders** instead of invented numbers or scope.
8. **Limits.**

   | Limit | Value |
   |---|---|
   | AI questions per set | 3–8 |
   | User questions | 10–500 chars each, at most 10 per set |
   | Answer length | 1–4,000 chars |
   | Resume name | 1–80 chars |
   | Job title | ≤100 chars |
   | Pasted resume | 100–50,000 chars |
   | Job description | 100–20,000 chars |
   | Experience title | 1–120 chars |
   | Experience description | 1–4,000 chars |
   | LinkedIn paste | 50–20,000 chars |
   | Batch save | at most 30 items |
   | File size | 10 MB |

9. **Attempt rules.**
   - No empty answer and no answer identical to the latest attempt.
   - Failed attempts keep their text.
   - `scoreDelta` compares against the previous `SCORED` attempt.
10. **Latest jobs.** Every resource that owns jobs returns its newest job, in any status, as `latestJob`, so the UI
    can resume polling after a reload.
11. **One practice set per pair**, created by 7.1 and never regenerated once `READY`.

## 12. Removed endpoints

U13 removed the old analysis, assessment, interview-question, interview-feedback and current-resume endpoints,
job type `ANALYSIS`, stage `ASSESSING_RESUME`, status `PARTIAL` and the seniority setting. Migration V18 deleted
their jobs and tables. Section 2 lists every endpoint the backend serves.

## 13. Mock mode notes

`VITE_API_MOCKS` selects the mock mode:

- `all`: every endpoint is mocked. This is the default in development.
- `off`: no mocks; every request goes to the backend. This is the default in production.

Any other value falls back to the build's default.
