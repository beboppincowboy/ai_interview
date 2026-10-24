# Interface-clarity re-check (issue #28)
## Method
Blind reader saw only names, signatures and doc comments of the 68 files changed by the refactor; a verifier then compared with code.

## Blind predictions
### apps/web/components/flow/JobProgress.tsx
- `JobProgress` — does: presents progress or recovery UI for one AI job and disappears on success | needs: job plus timeout/retry/error state and callbacks; nullability unclear | returns: rendered UI or nothing after success | touches: UI and invokes supplied callbacks | fails: unclear | confidence: high

### apps/web/components/flow/Stepper.tsx
- `Stepper` — does: renders the six-step journey and disables steps without prerequisite IDs | needs: current step and optional resume, job, and practice-set IDs | returns: navigation UI | touches: navigation only | fails: unclear | confidence: high

### apps/web/components/flow/SuggestionsPanel.tsx
- `SuggestionsPanel` — does: presents suggestions for a resume/job pair | needs: required resume and target-job IDs | returns: rendered panel | touches: likely queries suggestions and renders UI | fails: unclear | confidence: low | UNSURE: behavior is not documented beyond its name

### apps/web/components/library/LinkedInDialog.tsx
- `LinkedInDialog` — does: accepts LinkedIn experience text, splits it for review, and saves kept items | needs: optional save callback and optional trigger node | returns: dialog UI | touches: AI split request and persistence through UI actions | fails: unclear | confidence: high

### apps/web/components/library/ProjectFormDialog.tsx
- `ProjectFormDialog` — does: lets a user add an experience manually and shows duplicates in the dialog | needs: optional callback for newly added experience and optional trigger node | returns: dialog UI | touches: experience creation through UI actions | fails: unclear | confidence: high

### apps/web/components/library/ResumeRow.tsx
- `ResumeRow` — does: displays one resume row | needs: a Resume object | returns: rendered row | touches: likely navigation/actions through UI | fails: unclear | confidence: low | UNSURE: actions and displayed fields are not specified

### apps/web/components/practice/AttemptList.tsx
- `AttemptList` — does: shows the latest attempt in full and earlier attempts collapsed to score/change | needs: set ID and ordered or unordered attempt list (ordering contract beyond latest is unclear) | returns: rendered attempt history | touches: UI only, possibly attempt actions | fails: unclear | confidence: high

### apps/web/components/practice/AttemptWatcher.tsx
- `AttemptWatcher` — does: watches one pending attempt job and refreshes its set when the job ends; multiple watchers may coexist | needs: set ID and latest-job object | returns: watcher component, likely no visible UI | touches: job polling and query refresh | fails: unclear | confidence: high

### apps/web/lib/api/client.ts
- `ApiErrorKind` — does: names categories of API-client errors | needs: enum/string values not shown | returns: type-level error category | touches: none | fails: unclear | confidence: low | UNSURE: values and classification are omitted
- `ApiError` — does: represents an API request failure | needs: constructor contract omitted | returns: Error carrying API failure information | touches: none | fails: thrown by request handling, exact cases unclear | confidence: low | UNSURE: fields and status mapping are omitted
- `RequestOptions` — does: configures an API request | needs: fields omitted | returns: type-level request configuration | touches: none | fails: unclear | confidence: low | UNSURE: supported options are omitted
- `apiRequest` — does: sends an API request and automatically retries POST and GET according to method-specific policy | needs: path and optional request settings; POST reuses one idempotency key for automatic retries, GET has no key, PATCH/DELETE are sent once without one | returns: parsed response of type `T` | touches: network and possibly retry delay | fails: rejects on request failure; PATCH/DELETE transient errors reach caller | confidence: high

### apps/web/lib/api/types.ts
- `ApiErrorBody` — does: models an API error response body | needs: fields omitted | returns: type-level response shape | touches: none | fails: unclear | confidence: low | UNSURE: code/message fields cannot be confirmed from name alone
- `JobType` — does: identifies a background-job kind | needs: supported values omitted | returns: type-level job category | touches: none | fails: unclear | confidence: med | UNSURE: exact job kinds are omitted
- `JobStatus` — does: identifies a background-job lifecycle status | needs: supported values omitted | returns: type-level status | touches: none | fails: unclear | confidence: med | UNSURE: exact statuses/transitions are omitted
- `JobStage` — does: identifies a stage within a background job | needs: supported values omitted | returns: type-level stage | touches: none | fails: unclear | confidence: med | UNSURE: exact stages are omitted
- `JobError` — does: models a job failure | needs: fields omitted | returns: type-level error details | touches: none | fails: unclear | confidence: low | UNSURE: error fields and retry semantics are omitted
- `JobInputRefs` — does: carries resource references used as job inputs | needs: fields omitted | returns: type-level input-reference shape | touches: none | fails: unclear | confidence: low | UNSURE: reference fields are omitted
- `LatestJob` — does: models the newest job for a resource in any status | needs: fields omitted | returns: job view; null at resource level means no job was ever submitted, while this object may be succeeded or failed | touches: none | fails: unclear | confidence: high
- `JobAccepted` — does: models acceptance of a submitted job | needs: fields omitted | returns: type-level accepted-job response | touches: none | fails: unclear | confidence: low | UNSURE: response fields and reuse indication are omitted
- `JobStatusResponse<TResult>` — does: models a job status/result response | needs: result type and omitted fields | returns: type-level status plus generic result | touches: none | fails: unclear | confidence: med | UNSURE: full signature and fields are truncated
- `isTerminal` — does: checks whether a job status is terminal | needs: optional status, including undefined | returns: boolean | touches: none | fails: unclear | confidence: high
- `ErrorCode` — does: names API error codes | needs: values omitted | returns: type-level error-code set | touches: none | fails: unclear | confidence: low | UNSURE: codes are omitted
- `Priority` — does: classifies urgency of a feedback/fix item | needs: values omitted | returns: type-level priority | touches: none | fails: unclear | confidence: med | UNSURE: priority levels are omitted
- `DeleteImpact` — does: models the consequences/counts of deleting a resource | needs: fields omitted | returns: type-level impact summary | touches: none | fails: unclear | confidence: low | UNSURE: affected-resource counts are omitted
- `Page<T>` — does: models a paginated collection of `T` | needs: item type and omitted paging fields | returns: type-level page | touches: none | fails: unclear | confidence: med | UNSURE: page metadata is omitted
- `ScoreSummary` — does: summarizes a score | needs: fields omitted | returns: type-level score summary | touches: none | fails: unclear | confidence: low | UNSURE: score and freshness fields are omitted
- `Resume` — does: models a resume resource | needs: fields omitted | returns: type-level resume view | touches: none | fails: unclear | confidence: low | UNSURE: full versus summary fields are unclear
- `ResumeDetail` — does: models a detailed resume resource | needs: fields omitted | returns: type-level resume detail | touches: none | fails: unclear | confidence: low | UNSURE: included detail fields are omitted
- `ResumeCreated` — does: models resume creation response | needs: fields omitted | returns: type-level created-resume result | touches: none | fails: unclear | confidence: low | UNSURE: duplicate semantics are omitted
- `PasteResumeRequest` — does: models a pasted-resume request | needs: fields omitted | returns: type-level request | touches: none | fails: unclear | confidence: med | UNSURE: validation and optional fields are omitted
- `UpdateResumeRequest` — does: models a resume update request | needs: fields omitted | returns: type-level request | touches: none | fails: unclear | confidence: low | UNSURE: patch semantics and fields are omitted
- `TargetJob` — does: models a target-job resource | needs: fields omitted | returns: type-level target-job view | touches: none | fails: unclear | confidence: low | UNSURE: fields are omitted
- `TargetJobDetail` — does: models a detailed target-job resource | needs: fields omitted | returns: type-level detail | touches: none | fails: unclear | confidence: low | UNSURE: included detail fields are omitted
- `TargetJobCreated` — does: models target-job creation response | needs: fields omitted | returns: type-level created-target-job result | touches: none | fails: unclear | confidence: low | UNSURE: duplicate semantics are omitted
- `CreateTargetJobRequest` — does: models target-job creation input | needs: fields omitted | returns: type-level request | touches: none | fails: unclear | confidence: low | UNSURE: validation and optionality are omitted
- `ExperienceInput` — does: models experience input | needs: fields omitted | returns: type-level input | touches: none | fails: unclear | confidence: low | UNSURE: field contract is omitted
- `Experience` — does: models a saved experience | needs: fields omitted | returns: type-level experience view | touches: none | fails: unclear | confidence: med | UNSURE: fields and provenance are omitted
- `ExperienceCreated` — does: models experience creation result | needs: fields omitted | returns: type-level result | touches: none | fails: unclear | confidence: low | UNSURE: duplicate signaling is omitted
- `ExperienceBatchResult` — does: models a batch experience result | needs: fields omitted | returns: type-level result, likely stored items and duplicate items | touches: none | fails: unclear | confidence: low | UNSURE: exact fields are omitted
- `FitView` — does: models fit-analysis state for a resume/job pair | needs: fields omitted | returns: type-level view including an optional result/job state by name | touches: none | fails: unclear | confidence: low | UNSURE: fields and null meanings are omitted
- `SuggestionsView` — does: models experience-suggestion state for a resume/job pair | needs: fields omitted | returns: type-level view | touches: none | fails: unclear | confidence: low | UNSURE: freshness and job fields are omitted
- `PracticeSetCreated` — does: models creation/resolution of a practice set | needs: fields omitted | returns: pair's existing set when `duplicate` is true and no job starts, per doc | touches: none | fails: unclear | confidence: high
- `PracticeSet` — does: models a practice set | needs: fields omitted | returns: type-level practice-set view | touches: none | fails: unclear | confidence: low | UNSURE: status and question shape omitted
- `Question` — does: models a practice question | needs: fields omitted | returns: type-level question | touches: none | fails: unclear | confidence: low | UNSURE: question metadata omitted
- `Attempt` — does: models a submitted practice answer attempt | needs: fields omitted | returns: type-level attempt | touches: none | fails: unclear | confidence: low | UNSURE: scoring/status fields omitted
- `VoiceQuestion` — does: models a question in a spoken interview run | needs: fields omitted | returns: type-level question snapshot | touches: none | fails: unclear | confidence: low | UNSURE: exact fields omitted
- `VoiceAnswer` — does: models an answer in a spoken interview transcript | needs: fields omitted | returns: type-level answer | touches: none | fails: unclear | confidence: low | UNSURE: capture semantics omitted
- `VoiceTranscript` — does: models a spoken interview transcript | needs: fields omitted | returns: type-level transcript | touches: none | fails: unclear | confidence: low | UNSURE: transcript shape omitted
- `VoiceAnswerReport` — does: models feedback for one spoken answer | needs: fields omitted | returns: type-level per-answer report | touches: none | fails: unclear | confidence: low | UNSURE: score and feedback fields omitted
- `VoiceReport` — does: models a spoken-session report | needs: fields omitted | returns: type-level report | touches: none | fails: unclear | confidence: low | UNSURE: report structure omitted
- `VoiceSession` — does: models a spoken interview session | needs: fields omitted | returns: type-level session view | touches: none | fails: unclear | confidence: low | UNSURE: draft/save/report fields omitted
- `VoiceSaveResult` — does: models saving a spoken transcript | needs: fields omitted | returns: saved session when duplicate is true and no report job starts, per doc | touches: none | fails: unclear | confidence: high
- `VoiceToken` — does: models a credential for voice interaction | needs: fields omitted | returns: type-level token response | touches: none | fails: unclear | confidence: low | UNSURE: credential and expiry fields omitted
- `ResumeScoreResult` — does: models resume scoring output | needs: fields omitted | returns: type-level score result | touches: none | fails: unclear | confidence: low | UNSURE: exact score breakdown omitted
- `JobFitResult` — does: models job-fit analysis output | needs: fields omitted | returns: type-level fit result | touches: none | fails: unclear | confidence: low | UNSURE: score/feedback fields are omitted
- `SuggestionSource` — does: identifies a source used for suggestions | needs: fields omitted | returns: type-level source reference | touches: none | fails: unclear | confidence: low | UNSURE: source kinds and fields omitted
- `ExperienceSuggestionsResult` — does: models generated experience suggestions | needs: fields omitted | returns: type-level result | touches: none | fails: unclear | confidence: low | UNSURE: item shape omitted
- `ExperienceDraft` — does: models a proposed experience before saving | needs: fields omitted | returns: type-level draft | touches: none | fails: unclear | confidence: low | UNSURE: draft fields omitted
- `ExperienceSplitResult` — does: models results of splitting experience text | needs: fields omitted | returns: type-level result | touches: none | fails: unclear | confidence: low | UNSURE: item and duplicate fields omitted
- `ResumeExtractionResult` — does: models resume extraction outcome | needs: fields omitted | returns: type-level result | touches: none | fails: unclear | confidence: low | UNSURE: failure and duplicate semantics omitted
- `AnswerFeedbackResult` — does: models feedback for an answer | needs: fields omitted | returns: type-level answer feedback | touches: none | fails: unclear | confidence: low | UNSURE: scoring fields omitted
- `History` — does: models saved-result history | needs: fields omitted | returns: type-level history response | touches: none | fails: unclear | confidence: low | UNSURE: groups and ordering omitted
- `VoiceSessionHistory` — does: models history for spoken sessions | needs: fields omitted | returns: type-level session-history item | touches: none | fails: unclear | confidence: low | UNSURE: fields omitted

### apps/web/lib/errorMessages.ts
- `errorCode` — does: extracts a recognized API error code | needs: an unknown error value | returns: known `ErrorCode` or null for unknown/codeless errors | touches: none | fails: unclear | confidence: high
- `friendlyError` — does: converts an unknown error into user-facing text | needs: unknown error and optional fallback | returns: friendly string | touches: none | fails: unclear | confidence: med | UNSURE: code-specific messages and fallback conditions are omitted

### apps/web/lib/query/practice.ts
- `practiceKeys` — does: centralizes practice query keys | needs: key parameters not shown | returns: query-key values | touches: none | fails: unclear | confidence: low | UNSURE: key hierarchy is omitted
- `useCreatePracticeSet` — does: creates or resolves the pair's practice set | needs: likely resume/job IDs at mutation time | returns: mutation hook for created-or-existing set | touches: API and query cache | fails: request errors, exact handling unclear | confidence: high
- `usePracticeSet` — does: loads a practice set by ID | needs: set ID | returns: query hook | touches: network and query cache | fails: request error, exact handling unclear | confidence: med
- `useRetryPracticeSet` — does: retries question generation for a set | needs: set ID | returns: mutation hook | touches: API and query cache | fails: request error, exact handling unclear | confidence: med
- `useAddQuestion` — does: adds a user question to a set | needs: set ID and mutation input | returns: mutation hook | touches: API and query cache | fails: request error, exact handling unclear | confidence: med
- `useSubmitAttempt` — does: submits an attempt for a set | needs: set ID and attempt input | returns: mutation hook | touches: API and query cache | fails: request error, exact handling unclear | confidence: med
- `useRetryAttempt` — does: retries feedback for an attempt in a set | needs: set ID and likely attempt ID | returns: mutation hook | touches: API and query cache | fails: request error, exact handling unclear | confidence: low | UNSURE: argument use is not shown

### apps/web/lib/query/useJob.ts
- `jobQueryKey` — does: builds the cache key for a job | needs: job ID | returns: query key | touches: none | fails: unclear | confidence: med
- `useJob` — does: polls job status until terminal or a time ceiling, with `checkAgain` restarting polling | needs: job/result type parameter and job ID (remaining signature omitted) | returns: job query/state and restart callback | touches: network polling and query cache | fails: request error, exact handling unclear | confidence: high
- `useFollowJob` — does: follows a resource's newest job and refreshes resource queries when it ends | needs: optional latest job and query keys | returns: freshest job view, poll response while running or resource's copy otherwise | touches: polling and query invalidation/refresh | fails: request error, exact handling unclear | confidence: high

### apps/web/lib/query/voice.ts
- `voiceKeys` — does: centralizes voice-session query keys | needs: key parameters not shown | returns: query-key values | touches: none | fails: unclear | confidence: low | UNSURE: key hierarchy is omitted
- `createVoiceSession` — does: creates a spoken session | needs: request shape omitted | returns: likely a session | touches: API | fails: request error, exact handling unclear | confidence: low | UNSURE: export is a value, and its callable contract is omitted
- `getVoiceSession` — does: fetches a spoken session | needs: session ID likely | returns: likely session data | touches: API | fails: request error, exact handling unclear | confidence: low | UNSURE: callable signature omitted
- `mintVoiceToken` — does: requests a short-lived voice credential | needs: request shape omitted | returns: likely voice token | touches: API/provider credential endpoint | fails: request error, exact handling unclear | confidence: low | UNSURE: callable signature and token lifetime omitted
- `saveVoiceSession` — does: saves a reviewed voice transcript | needs: session and transcript likely | returns: saved-session result | touches: API | fails: request error, exact handling unclear | confidence: low | UNSURE: callable signature omitted
- `retryVoiceReport` — does: retries a voice-session report | needs: session ID likely | returns: session/report state | touches: API | fails: request error, exact handling unclear | confidence: low | UNSURE: callable signature omitted
- `discardVoiceDraft` — does: discards an unsaved voice-session draft | needs: session ID likely | returns: unclear | touches: API | fails: request error, exact handling unclear | confidence: low | UNSURE: callable signature and response omitted
- `deleteVoiceSession` — does: deletes a voice session | needs: session ID likely | returns: unclear | touches: API | fails: request error, exact handling unclear | confidence: low | UNSURE: callable signature and response omitted
- `useVoiceSession` — does: queries one voice session | needs: session ID | returns: query hook | touches: network and query cache | fails: request error, exact handling unclear | confidence: med
- `useRetryVoiceReport` — does: starts a report retry | needs: session ID | returns: mutation hook | touches: API and query cache | fails: request error, exact handling unclear | confidence: med
- `useDeleteVoiceSession` — does: deletes a session | needs: session ID | returns: mutation hook | touches: API and query cache | fails: request error, exact handling unclear | confidence: med

### apps/web/mocks/jobSimulator.ts
- `MAX_ATTEMPTS` — does: sets the mock job attempt limit | needs: none | returns: numeric constant | touches: none | fails: none | confidence: med | UNSURE: value and retry boundary omitted
- `JOB_STAGES` — does: maps each job type to its ordered mock stages | needs: job type keys and stage values | returns: record of job-stage lists | touches: none | fails: none | confidence: med | UNSURE: stage ordering and values omitted
- `JobFailure` — does: models a mock failure | needs: fields omitted | returns: type-level failure | touches: none | fails: unclear | confidence: low | UNSURE: failure details omitted
- `MockJob` — does: models a simulated background job | needs: fields omitted | returns: type-level mock job | touches: none | fails: unclear | confidence: low | UNSURE: job state fields omitted
- `JobState` — does: models the derived state of a mock job at a time | needs: fields omitted | returns: state including progress/failure by name | touches: none | fails: unclear | confidence: low | UNSURE: state fields omitted
- `jobState` — does: derives mock job state at a supplied time | needs: job and current time in numeric form | returns: state including progress/failure by name | touches: none | fails: unclear | confidence: low | UNSURE: timing and retry transitions omitted
- `latestJob` — does: converts an optional mock job to the resource's latest-job view at a supplied time | needs: optional job and current time | returns: `LatestJob` or null when no job exists | touches: none | fails: unclear | confidence: med

### apps/web/mocks/store.ts
- `STORAGE_KEY` — does: names the mock store's persistence key | needs: none | returns: string constant | touches: none | fails: none | confidence: med
- `MockHttpError` — does: represents a mock HTTP failure | needs: constructor contract omitted | returns: Error with mock response information | touches: none | fails: thrown/returned by mock API, exact behavior unclear | confidence: low | UNSURE: fields and status mapping omitted
- `MockStoreOptions` — does: configures mock-store creation | needs: fields omitted | returns: type-level options | touches: none | fails: unclear | confidence: low | UNSURE: supported options omitted
- `createMockStore` — does: creates a mock store with optional configuration | needs: optional options | returns: mock store | touches: likely browser storage for persistence | fails: unclear | confidence: low | UNSURE: initialization and persistence behavior omitted
- `MockStore` — does: exposes mock application data/actions | needs: members omitted | returns: type-level store contract | touches: likely local storage and simulated requests | fails: unclear | confidence: low | UNSURE: store methods and side effects omitted

### src/main/kotlin/dev/jiaming/ai_interview/ai/AiHttpTransport.kt
- `AiHttpTransport` — does: abstracts sending an HTTP request to an AI provider | needs: `HttpRequest` | returns: `AiHttpTransportResponse` | touches: network | fails: transport exceptions, exact types unclear | confidence: med
- `AiHttpTransport.send` — does: sends one HTTP request | needs: JDK `HttpRequest` | returns: status code and response body | touches: network | fails: unclear | confidence: med | UNSURE: exception contract omitted
- `AiHttpTransportResponse` — does: carries provider HTTP status and body | needs: integer status and string body | returns: data value | touches: none | fails: none | confidence: high
- `jdkTransport` — does: provides a transport backed by the JDK HTTP client | needs: none | returns: `AiHttpTransport` | touches: network when used | fails: transport-level failures, exact handling unclear | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt
- `AiProviderErrorCode` — does: centralizes public AI-provider wire error codes with a historical `GEMINI_` prefix | needs: none | returns: constants | touches: none | fails: none | confidence: high
- `NOT_CONFIGURED` — does: identifies a provider with no API key | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `RATE_LIMITED` — does: identifies provider rate-limit/quota rejection | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `TIMEOUT` — does: identifies timeout or interruption | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `UPSTREAM_ERROR` — does: identifies unreachable provider or unsupported/unreadable response | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `SAFETY` — does: identifies safety refusal/filtering | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `RECITATION` — does: identifies Gemini recitation blocking | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `MAX_TOKENS` — does: identifies provider output-token-limit termination | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `EMPTY_RESPONSE` — does: identifies absence of candidate, choice, or text | needs: none | returns: wire-code constant | touches: none | fails: none | confidence: high
- `INVALID_RESPONSE` — does: identifies output outside the coach schema after a single repair attempt also fails | needs: none | returns: wire-code constant | touches: none | fails: raised after second invalid output, per doc | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderException.kt
- `AiProviderException` — does: represents a provider failure with code, optional status, cause, and retryability | needs: private constructor receives those details and message | returns: runtime exception | touches: none | fails: thrown for provider errors | confidence: high
- `code` — does: exposes provider wire code | needs: none | returns: string | touches: none | fails: none | confidence: high
- `statusCode` — does: exposes optional HTTP status | needs: none | returns: nullable integer | touches: none | fails: none | confidence: high
- `retryable` — does: exposes retryability decision | needs: none | returns: boolean | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt
- `AiResumeCoachService` — does: coordinates structured AI coaching tasks, context building, mapping, and metrics | needs: generation client, RAG context, prompt builder, response mapper, and meter registry | returns: service | touches: AI client and metrics; RAG through context service | fails: provider/validation failures, exact propagation unclear | confidence: med
- `assessJobFit` — does: assesses a resume against a target job | needs: coach analysis input with resume and optional target job | returns: job-fit result | touches: AI generation, RAG, metrics | fails: provider or invalid-response error likely | confidence: med
- `suggestExperiences` — does: suggests saved experiences relevant to a resume/job | needs: resolved resume, resolved job, and source list | returns: experience-suggestions result | touches: AI generation, RAG/context, metrics | fails: provider or invalid-response error likely | confidence: med
- `generatePracticeQuestions` — does: drafts practice questions for a resume/job pair | needs: coach analysis input | returns: question drafts | touches: AI generation, RAG, metrics | fails: provider or invalid-response error likely | confidence: med
- `scorePracticeAnswer` — does: scores a practice answer against its question and resume/job pair | needs: feedback input | returns: answer-feedback result | touches: AI generation, RAG, metrics | fails: provider or invalid-response error likely | confidence: high
- `scoreResume` — does: scores resume text, optionally for a job title | needs: resume text and nullable job title | returns: resume-score result | touches: AI generation and metrics; retrieval unclear | fails: provider or invalid-response error likely | confidence: med
- `splitExperience` — does: splits pasted experience text into experience items | needs: text | returns: experience-split result | touches: AI generation and metrics | fails: provider or invalid-response error likely | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/coach/CoachAnalysisInput.kt
- `CoachAnalysisInput` — does: carries a resume and optional target job for analysis | needs: required resolved resume and nullable optional wrapper for target job | returns: input object | touches: none | fails: unclear | confidence: high
- `resume` — does: returns resolved resume | needs: none | returns: `ResolvedDocument` | touches: none | fails: none | confidence: high
- `targetJob` — does: returns optional resolved target job | needs: none | returns: `Optional<ResolvedDocument>` | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt
- `CoachFeedbackInput` — does: packages documents, question metadata, answer, and capture completeness for scoring | needs: required resume; optional target job, question/category/signals/answer; incomplete-capture defaults false | returns: input object | touches: none | fails: unclear | confidence: high
- `resume` — does: returns the resolved resume | needs: none | returns: `ResolvedDocument` | touches: none | fails: none | confidence: high
- `targetJob` — does: returns optional target job | needs: none | returns: `Optional<ResolvedDocument>` | touches: none | fails: none | confidence: high
- `questionText` — does: returns question text | needs: none | returns: nullable string | touches: none | fails: none | confidence: high
- `category` — does: returns question category | needs: none | returns: nullable string | touches: none | fails: none | confidence: high
- `expectedSignals` — does: returns expected answer signals | needs: none | returns: list, normalization/null policy unclear | touches: none | fails: none | confidence: med | UNSURE: nullable constructor input may be normalized or retained
- `answerText` — does: returns submitted answer text | needs: none | returns: nullable string | touches: none | fails: none | confidence: high
- `incompleteCapture` — does: indicates whether voice capture was cut off/incomplete | needs: none | returns: boolean | touches: none | fails: none | confidence: med | UNSURE: exact scoring interpretation is omitted

### src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt
- `CoachPromptBuilder` — does: builds prompts for structured coach tasks | needs: none | returns: prompt builder | touches: none | fails: unclear | confidence: med
- `buildExperienceSplitPrompt` — does: builds prompt to split pasted experience text | needs: source text | returns: prompt string | touches: none | fails: unclear | confidence: med
- `buildResumeScorePrompt` — does: builds resume-scoring prompt with optional job title | needs: resume text and nullable title | returns: prompt string | touches: none | fails: unclear | confidence: med
- `buildJobFitPrompt` — does: builds job-fit prompt from RAG context | needs: coach RAG context | returns: prompt string | touches: none | fails: unclear | confidence: med
- `buildExperienceSuggestionsPrompt` — does: builds prompt from resume/job text and typed source text | needs: two text strings and source/type-text pairs | returns: prompt string | touches: none | fails: unclear | confidence: med
- `buildPracticeQuestionPrompt` — does: builds practice-question prompt from RAG context | needs: coach RAG context | returns: prompt string | touches: none | fails: unclear | confidence: med
- `buildPracticeFeedbackPrompt` — does: builds answer-scoring prompt from feedback input and context | needs: input and RAG context | returns: prompt string | touches: none | fails: unclear | confidence: high
- `buildRepairPrompt` — does: builds a repair prompt from original prompt and invalid output, optionally including parse error | needs: prompt, invalid output, nullable parse error | returns: prompt string | touches: none | fails: unclear | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt
- `CoachRagContextService` — does: assembles RAG context and fallback chunks for coach tasks | needs: chunker, indexing/retrieval services, metrics, and RAG properties | returns: context service | touches: vector retrieval/indexing and metrics | fails: task context methods are documented to fall back rather than throw on RAG failures | confidence: high
- `jobFitContext` — does: builds job-fit context with at most assessment snippet limit; sends resume+job whole at <=6,000 chars without retrieval/budget | needs: analysis input | returns: context (return type omitted) | touches: RAG when over limit; metrics likely | fails: RAG errors fall back to local chunks, never throw | confidence: high
- `practiceQuestionContext` — does: builds question-generation context with at most question snippet limit; sends pair whole at <=6,000 chars without retrieval/budget | needs: analysis input | returns: context (return type omitted) | touches: RAG when over limit; metrics likely | fails: RAG errors fall back to local chunks, never throw | confidence: high
- `feedbackContext` — does: builds answer-feedback context with at most feedback snippet limit; sends pair whole at <=6,000 chars without retrieval/budget | needs: feedback input | returns: context (return type omitted) | touches: RAG when over limit; metrics likely | fails: RAG errors fall back to local chunks, never throw | confidence: high
- `suggestionSourceText` — does: selects source resume text for suggestions | needs: resolved resume and target job | returns: full resume up to 6,000 chars, otherwise retrieved snippets bounded by assessment maximum | touches: retrieval when resume exceeds limit | fails: unclear | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt
- `CoachResponseMapper` — does: parses and normalizes structured AI responses | needs: Jackson object mapper | returns: mapper | touches: none | fails: parse/validation failures, exact exception unclear | confidence: med
- `parse` — does: deserializes JSON into requested response class | needs: JSON string and class | returns: parsed instance | touches: none | fails: parsing exception likely | confidence: med
- `normalizeExperienceSplit` — does: normalizes AI experience-split response | needs: split response | returns: experience-split result | touches: none | fails: invalid response may throw | confidence: low | UNSURE: normalization rules omitted
- `normalizeResumeScore` — does: normalizes score and keeps only rewrites whose original line occurs in resume text | needs: draft response, resume text, nullable job title | returns: resume-score result | touches: none | fails: invalid response may throw | confidence: high
- `normalizeFeedback` — does: normalizes answer-feedback response | needs: response | returns: answer-feedback result | touches: none | fails: invalid response may throw | confidence: low | UNSURE: normalization rules omitted
- `normalizeJobFit` — does: normalizes job-fit response | needs: response | returns: job-fit result | touches: none | fails: invalid response may throw | confidence: low | UNSURE: normalization rules omitted
- `normalizePracticeQuestions` — does: retains 3–8 valid questions, each with text, rationale, and at least one expected signal | needs: questions response | returns: practice-question drafts | touches: none | fails: fewer than 3 valid questions is invalid and triggers caller repair | confidence: high
- `normalizeExperienceSuggestions` — does: normalizes suggestions against submitted sources | needs: response and source list | returns: experience-suggestions result | touches: none | fails: invalid response may throw | confidence: low | UNSURE: source-matching rules omitted

### src/main/kotlin/dev/jiaming/ai_interview/coach/Priority.kt
- `Priority` — does: classifies urgency of a resume-score fix or job-fit feedback item | needs: enum value (values omitted) | returns: priority category | touches: none | fails: invalid enum value at deserialization likely | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt
- `ApiExceptionHandler` — does: maps application exceptions into HTTP API responses | needs: exceptions from request handling | returns: error response/status, varies by handler | touches: response serialization/logging likely | fails: handles listed exception classes; unexpected handler behavior unclear | confidence: med
- `handleResumeExtractionException` — does: maps resume-extraction failure | needs: extraction exception | returns: omitted | touches: response handling | fails: exception-specific response, status/code unclear | confidence: low | UNSURE: return type omitted
- `handleResumeParserBusyException` — does: maps parser-busy failure | needs: parser-busy exception | returns: omitted | touches: response handling | fails: exception-specific response, status/code unclear | confidence: low | UNSURE: return type omitted
- `handleAiProviderException` — does: maps provider failure | needs: provider exception | returns: omitted | touches: response handling | fails: likely preserves provider code/status, exact mapping unclear | confidence: low | UNSURE: return type omitted
- `handleApiRequestException` — does: maps an API request exception to its own wire code and HTTP status | needs: exception with status/code | returns: `ResponseEntity<ApiErrorResponse>` | touches: response handling | fails: encodes the exception as response | confidence: high
- `handleResponseStatusException` — does: maps Spring status exception to API error response | needs: status exception | returns: `ResponseEntity<ApiErrorResponse>` | touches: response handling | fails: uses status-to-code mapping likely | confidence: med
- `handleUnreadableBody` — does: maps unreadable request bodies; when Jackson identifies a field, names it in the message | needs: unreadable-body exception | returns: API error response | touches: response handling | fails: returns a client error, exact code/status unclear | confidence: high
- `handleInvalidRequest` — does: maps invalid request exception | needs: exception (unused parameter) | returns: omitted | touches: response handling | fails: likely client-error response | confidence: low | UNSURE: status/body omitted
- `handleMaxUploadSize` — does: maps oversized-upload exception | needs: exception (unused parameter) | returns: omitted | touches: response handling | fails: likely client-error response | confidence: low | UNSURE: status/body omitted
- `handleUnexpectedException` — does: maps unhandled exception | needs: exception | returns: API error response | touches: response handling and likely logging | fails: produces generic server error, exact details unclear | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/common/ApiRequestException.kt
- `ApiRequestException` — does: carries an API wire error code and HTTP status; job classifier preserves code and marks 408/429/5xx retryable | needs: status, code, message, optional cause | returns: runtime exception | touches: none | fails: thrown by request/domain failures; classifier makes other statuses permanent | confidence: high
- `status` — does: exposes HTTP status | needs: none | returns: `HttpStatusCode` | touches: none | fails: none | confidence: high
- `code` — does: exposes wire error code | needs: none | returns: string | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/common/ErrorCodes.kt
- `ErrorCodes` — does: centralizes mapping from Spring response status to wire code | needs: none | returns: code mapper | touches: none | fails: unclear | confidence: high
- `forStatus` — does: maps HTTP status to one wire error code used by API and failed jobs | needs: HTTP status | returns: string code | touches: none | fails: unclear | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt
- `RedisRequestGuard` — does: applies shared request limits and optional idempotent response caching | needs: Redis template, usage properties, object mapper | returns: guard | touches: Redis and request context | fails: Redis unavailability bypasses idempotency work per doc; other guard failures as statuses | confidence: high
- `assertAiAllowed` — does: checks shared AI action allowance | needs: action string | returns: unit on allowed request | touches: Redis/rate-limit state | fails: likely 429 when limit exceeded | confidence: med
- `assertUploadAllowed` — does: checks upload allowance | needs: none | returns: unit on allowed request | touches: Redis/rate-limit state | fails: likely 429 when limit exceeded | confidence: med
- `withIdempotentRetryCache` — does: replays stored response for same action/client-IP/key; only applies when header exists and caching enabled/Redis available; does not dedupe by body | needs: action, optional fingerprint source, response class, work supplier | returns: work result or cached replay | touches: Redis and invokes work | fails: changed fingerprint gives 409 `CONFLICT`; in-flight duplicate gives retryable 503; thrown work stores nothing | confidence: high
- `withIdempotentHttpCache` — does: same idempotency-key replay behavior while retaining original HTTP status | needs: action, fingerprint source, body class, response-entity supplier | returns: response entity from work or replay | touches: Redis and invokes work | fails: changed fingerprint gives 409; in-flight duplicate gives retryable 503; Redis unavailable runs work | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/document/DocumentReferenceResolver.kt
- `DocumentReferenceResolver` — does: loads stored resume and optional target job referenced by a background job; no text/latest-resume fallback | needs: resume and target-job persistence services | returns: resolver | touches: database reads | fails: missing/unready references produce documented `ApiRequestException`s | confidence: high
- `resolveStrict` — does: resolves exact stored document references for a user | needs: user ID, nullable resume ID, nullable target-job ID | returns: resolved job inputs; null target gives empty optional | touches: database reads | fails: null resume 400 `RESUME_REFERENCE_REQUIRED`; missing resume/job 404; unready resume 409 | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/document/ResolvedJobInputs.kt
- `ResolvedJobInputs` — does: bundles resolved resume and optional target job for serialization | needs: required resume and nullable Optional target job | returns: value object | touches: none | fails: unclear | confidence: med
- `resume` — does: returns resolved resume | needs: none | returns: `ResolvedDocument` | touches: none | fails: none | confidence: high
- `targetJob` — does: returns resolved optional target job | needs: none | returns: `Optional<ResolvedDocument>` | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt
- `ExperienceController` — does: handles experience-library endpoints | needs: experience service, local-user service, request guard | returns: controller | touches: request handling and service operations | fails: service/validation exceptions mapped by API handler | confidence: med
- `list` — does: lists current user's experiences | needs: authenticated request context likely | returns: experience-list response | touches: database read | fails: unclear | confidence: med
- `rename` — does: renames one experience | needs: experience UUID and rename request | returns: experience | touches: database write | fails: validation/not-found/duplicate errors likely | confidence: med
- `deleteImpact` — does: previews effects of deleting an experience | needs: experience UUID | returns: delete-impact summary | touches: database read | fails: not-found likely | confidence: med
- `delete` — does: deletes one experience | needs: experience UUID | returns: empty response entity | touches: database write | fails: not-found likely | confidence: med
- `create` — does: creates one experience | needs: experience input | returns: created response entity | touches: database write | fails: validation or duplicate behavior returned in result | confidence: med
- `batch` — does: saves a batch of reviewed experiences | needs: batch request | returns: created/duplicate batch result | touches: database writes | fails: validation errors likely | confidence: med
- `split` — does: submits experience text for background splitting | needs: split request | returns: accepted-job response | touches: background job submission | fails: validation/rate-limit/job-submission errors likely | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt
- `ExperienceSource` — does: identifies how an experience entered the library | needs: enum value (values omitted) | returns: source category | touches: none | fails: invalid enum input likely | confidence: med | UNSURE: exact sources omitted
- `ExperienceInput` — does: carries experience fields for create/batch requests | needs: nullable title, organization, dates, description so validation can report missing fields | returns: request data | touches: none | fails: validation may return field-specific 400 | confidence: high
- `ExperienceBatchRequest` — does: wraps submitted batch items | needs: nullable list of experience inputs | returns: request data | touches: none | fails: null/invalid list likely validation error | confidence: med
- `ExperienceSplitRequest` — does: carries pasted text for splitting | needs: nullable text | returns: request data | touches: none | fails: blank/missing validation likely | confidence: med
- `ExperienceRenameRequest` — does: carries a replacement experience title | needs: nullable title | returns: request data | touches: none | fails: missing/invalid title likely validation error | confidence: med
- `Experience` — does: represents a saved experience with source and creation time | needs: UUID, required title/description/source/time; organization/dates nullable | returns: data value | touches: none | fails: none | confidence: high
- `ExperienceListResponse` — does: wraps experience list | needs: item list | returns: response data | touches: none | fails: none | confidence: high
- `ExperienceCreated` — does: returns a created or existing experience; duplicate means same title+description after whitespace collapse/case folding, ignoring organization/dates | needs: experience and duplicate flag | returns: saved matching experience unchanged on duplicate | touches: none | fails: none | confidence: high
- `ExperienceBatchDuplicate` — does: identifies a submitted item skipped as duplicate | needs: submitted title, existing ID, and existing title | returns: duplicate record | touches: none | fails: none | confidence: high
- `ExperienceBatchResult` — does: reports stored batch experiences and skipped duplicates | needs: created and duplicate lists | returns: batch result | touches: none | fails: none | confidence: high
- `ExperienceSplitJobPayload` — does: stores text for an experience-split job with versioning | needs: version defaulting to current version and text | returns: payload | touches: none | fails: none | confidence: med | UNSURE: version value omitted
- `ExperienceDuplicate` — does: identifies an existing duplicate experience | needs: ID and title | returns: duplicate reference | touches: none | fails: none | confidence: high
- `ExperienceSplitItem` — does: models one split experience with optional match to saved duplicate | needs: required title/description; organization/dates/duplicate nullable | returns: split item | touches: none | fails: none | confidence: high
- `ExperienceSplitResult` — does: wraps split items | needs: item list | returns: split result | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt
- `ExperienceService` — does: manages experience persistence, duplicate detection, and split-job submission | needs: JDBC, content hasher, job-submission service | returns: service | touches: database and background jobs | fails: validation and persistence errors likely | confidence: med
- `list` — does: lists a user's experiences | needs: user ID | returns: experience-list response | touches: database read | fails: unclear | confidence: med
- `rename` — does: changes title and recalculates duplicate identity | needs: user ID, experience ID, rename request | returns: updated experience | touches: database write | fails: 409 `EXPERIENCE_DUPLICATE` if title+description matches another | confidence: high
- `deleteImpact` — does: previews impact of deleting an experience | needs: user ID and experience ID | returns: delete-impact summary | touches: database read | fails: missing-resource behavior unclear | confidence: med
- `delete` — does: deletes a user's experience | needs: user and experience IDs | returns: unit | touches: database write and dependent cleanup likely | fails: missing-resource behavior unclear | confidence: med
- `create` — does: creates one experience with duplicate resolution | needs: user ID and input | returns: created-or-existing experience result | touches: database write/read | fails: validation errors likely | confidence: med
- `batch` — does: saves 1–30 reviewed items as LINKEDIN and reports matching saved experiences as duplicates, not stored | needs: user ID and batch request | returns: created and duplicate lists | touches: database writes | fails: invalid batch size/content likely validation error | confidence: high
- `split` — does: submits a split job for pasted text | needs: split request | returns: accepted-job response | touches: job submission | fails: validation/job policy errors likely | confidence: med
- `annotateDuplicates` — does: marks split items matching saved experiences | needs: user ID and split result | returns: result with duplicate annotations | touches: database read | fails: unclear | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/fit/FitView.kt
- `FitView` — does: represents fit-analysis state for a resume/job pair | needs: pair IDs, nullable result/time, nullable newest job | returns: view; latest job is any status and null only if never submitted | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitJobHandler.kt
- `JobFitJobHandler` — does: handles a background job that assesses resume/job fit | needs: coach service and strict document resolver | returns: job handler | touches: AI coaching and document database reads | fails: handler/provider/reference errors feed job failure policy | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitResult.kt
- `JobFitResult` — does: carries fit score, summary, matched/missing requirements, and feedback | needs: integer score, text, and lists | returns: result data | touches: none | fails: none | confidence: high
- `MatchedRequirement` — does: pairs a requirement with resume evidence | needs: requirement and evidence strings | returns: value | touches: none | fails: none | confidence: high
- `MissingRequirement` — does: pairs a missing requirement with guidance | needs: requirement and guidance strings | returns: value | touches: none | fails: none | confidence: high
- `FitFeedback` — does: carries prioritized fit feedback | needs: priority and message | returns: value | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitService.kt
- `JobFitService` — does: reads fit state and submits fit-analysis jobs | needs: JDBC, user, submission/store, JSON, and transaction services | returns: service | touches: database and job submission/dispatch | fails: request/job errors likely | confidence: med
- `get` — does: fetches fit view for a resume/job pair | needs: two UUIDs | returns: fit view | touches: database read | fails: ownership/not-found behavior unclear | confidence: med
- `run` — does: starts or reuses fit-analysis work | needs: resume and target-job UUIDs | returns: accepted job response | touches: database and job submission/dispatch | fails: readiness/rate-limit/API-availability errors likely | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/gemini/GeminiClient.kt
- `GeminiClient` — does: implements structured generation using Gemini settings and transport | needs: mapper, transport, metrics, endpoint, nullable key, model, generation settings, and timeout | returns: structured-generation client | touches: network and metrics | fails: provider failures mapped to `AiProviderException` codes likely | confidence: med | UNSURE: callable methods are not shown

### src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt
- `HistoryResponse` — does: aggregates resume, target-job, practice-set, and voice-session history | needs: lists, voice sessions default empty | returns: record response | touches: none | fails: none | confidence: high
- `VoiceSessionHistory` — does: summarizes a saved voice session, counts, optional overall score, and report job state | needs: listed identifiers/names/time/counts and nullable score | returns: history item | touches: none | fails: none | confidence: high
- `ResumeHistory` — does: groups score points for a resume | needs: ID, name, and points | returns: history item | touches: none | fails: none | confidence: high
- `ScorePoint` — does: records overall score and scoring time | needs: integer and instant | returns: value | touches: none | fails: none | confidence: high
- `TargetJobHistory` — does: groups fit points for a target job | needs: ID, name, and points | returns: history item | touches: none | fails: none | confidence: high
- `FitPoint` — does: records one resume's fit score against a target job | needs: resume ID/name, score, and time | returns: value | touches: none | fails: none | confidence: high
- `PracticeSetHistory` — does: summarizes a pair's practice-set history and questions | needs: IDs/names, updated time, and question points | returns: history item | touches: none | fails: none | confidence: high
- `QuestionHistory` — does: summarizes question text and attempt scores | needs: question ID/text and score list | returns: value | touches: none | fails: none | confidence: high
- `HistoryService` — does: reads owner-scoped trends from saved results only; never calls AI; points oldest first | needs: JDBC and user service | returns: service | touches: database reads | fails: unclear | confidence: high
- `history` — does: returns current user's saved-result history | needs: authenticated user context likely | returns: history response with oldest-first trends | touches: database read | fails: unclear | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt
- `BackgroundJobStore` — does: persists and transitions background jobs, leases, checkpoints, and retention | needs: JDBC and JSON mapper | returns: store | touches: database | fails: persistence/lease errors, exact exceptions unclear | confidence: med
- `createIfAbsent` — does: creates a job if no matching deduplicated job exists | needs: owner/type, optional resource/payload/fingerprint, attempt limit | returns: optional job | touches: database write/read | fails: database errors unclear | confidence: med | UNSURE: absence versus conflict details omitted
- `createIfAbsentWithInitialResult` — does: creates a fresh retry job with durable validated-answer checkpoint | needs: same job data plus initial checkpoint | returns: optional job | touches: database | fails: database errors unclear | confidence: high
- `findReusable` — does: finds a reusable job by owner/type/fingerprint | needs: user ID, type, fingerprint | returns: optional job | touches: database read | fails: database errors unclear | confidence: med
- `findLatestForResource` — does: finds newest job for one resource, optionally restricted to job types | needs: owner, resource type/ID, optional type collection | returns: optional job | touches: database read | fails: database errors unclear | confidence: med
- `findLatestForResources` — does: finds newest jobs for many resources in one query, keyed by resource ID | needs: owner, resource type, resource IDs, optional job types | returns: map from ID to job | touches: database read | fails: database errors unclear | confidence: high
- `deleteByResources` — does: deletes jobs associated with resource IDs | needs: resource type and IDs | returns: number deleted | touches: database write | fails: database errors unclear | confidence: med
- `findForUser` — does: finds a job only for specified owner | needs: job ID and user ID | returns: optional job | touches: database read | fails: database errors unclear | confidence: med
- `findById` — does: finds job by ID without owner argument | needs: job ID | returns: optional job | touches: database read | fails: database errors unclear | confidence: med | UNSURE: authorization expectations are not shown
- `findUndispatched` — does: lists job IDs awaiting dispatch | needs: result limit | returns: UUID list | touches: database read | fails: database errors unclear | confidence: med
- `markEnqueued` — does: marks a job as enqueued | needs: job ID | returns: unit | touches: database write | fails: database/state errors unclear | confidence: med
- `claim` — does: claims a job under a lease | needs: job ID, lease token, duration | returns: optional claimed job | touches: database write/read | fails: lease/state conflict likely represented by empty optional | confidence: med
- `extendLease` — does: extends a job lease | needs: job ID, matching lease token, duration | returns: boolean success | touches: database write | fails: false if lease cannot be extended | confidence: med
- `updateStage` — does: records current job stage | needs: job ID, lease token, stage | returns: unit | touches: database write | fails: stale/invalid lease likely | confidence: med
- `checkpointResult` — does: saves interim result/checkpoint | needs: job ID, lease token, nullable JSON payload | returns: unit | touches: database write | fails: stale/invalid lease likely | confidence: med
- `markSucceeded` — does: marks job successful with optional result | needs: job ID, lease token, nullable result payload | returns: unit | touches: database write | fails: stale/invalid lease likely | confidence: med
- `markFailed` — does: marks job permanently failed | needs: job ID, lease token, error code and optional message | returns: unit | touches: database write | fails: stale/invalid lease likely | confidence: med
- `markRetrying` — does: schedules job retry after delay | needs: job ID, lease token, error code/message, delay | returns: boolean | touches: database write | fails: false when transition cannot be made | confidence: med
- `releaseForRedispatch` — does: releases a claimed job for later dispatch | needs: job ID and lease token | returns: boolean | touches: database write | fails: false when release not possible | confidence: med
- `prepareDlqRecovery` — does: prepares a dead-letter job for recovery | needs: job ID | returns: boolean | touches: database write | fails: false when recovery cannot be prepared | confidence: low | UNSURE: transition conditions omitted
- `markExhaustedFromDlq` — does: marks a dead-letter job exhausted | needs: job ID | returns: boolean | touches: database write | fails: false when transition cannot be made | confidence: low | UNSURE: transition conditions omitted
- `reapExpiredLeases` — does: reaps jobs with expired leases | needs: none | returns: number processed | touches: database write | fails: database errors unclear | confidence: med
- `clearExpiredPayloads` — does: clears retained payloads older than policy | needs: retention days | returns: number processed | touches: database write | fails: database errors unclear | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/jobs/FeedbackJobHandler.kt
- `FeedbackJobHandler` — does: handles background scoring of a practice attempt | needs: coach, strict document resolver, practice service | returns: job handler | touches: AI service and practice/database state | fails: handler errors classified by job system | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/jobs/JobFailureClassifier.kt
- `classify` — does: classifies thrown failure into a job failure code/message/retry policy | needs: throwable | returns: `JobFailure` | touches: none | fails: unclear | confidence: med | UNSURE: exact mapping omitted

### src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt
- `JobSubmissionService` — does: validates, deduplicates, creates, and dispatches background jobs | needs: job store/dispatcher, fingerprints, user/guard/config, metrics, mapper | returns: submission service | touches: request guard, database, dispatch, metrics | fails: worker-only mode 503 and AI limit 429 before writes; other errors per dependencies | confidence: high
- `submit` — does: checks API availability, then shared AI rate limit, then reuses active matching job or creates and dispatches after transaction commit | needs: type, optional resource identifiers, payload, optional dedup key defaulting to type+resource fingerprint, optional checkpoint | returns: accepted-job response with reuse indication | touches: rate-limit state, database, dispatch | fails: worker-only 503; rate-limit 429; dependency/transaction errors; upload-extraction job exempt from AI limit | confidence: high
- `fingerprint` — does: hashes action and source into deduplication key | needs: action and arbitrary source | returns: string fingerprint | touches: none | fails: unclear | confidence: high
- `assertApiAvailable` — does: rejects API-only work in worker-only mode | needs: none | returns: unit when available | touches: runtime config only | fails: 503 in worker-only mode | confidence: high
- `findReusable` — does: reads active job matching type and dedup key without submitting | needs: type and nullable key | returns: optional accepted-job response | touches: database read | fails: unclear | confidence: high
- `withIdempotency` — does: wraps work in request idempotency response cache | needs: action, fingerprint source, response class, supplier | returns: work/cached result | touches: Redis and work | fails: same semantics as Redis guard; only with header | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/jobs/LatestJob.kt
- `LatestJob` — does: represents newest resource job in any status, including succeeded/failed | needs: job/type/status/stage, attempt counts, nullable error | returns: view; null at containing resource means no job was submitted | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/openai/OpenAiClient.kt
- `OpenAiClient` — does: implements structured chat via OpenAI gpt-4.1-mini under Gemini-compatible JSON/error contract; embeddings remain Gemini with stored 1,024-dimension vectors | needs: mapper, transport, metrics, endpoint/key, temperature, timeout, token limit | returns: structured-generation client | touches: network and metrics | fails: reuses `GEMINI_*` error codes | confidence: high | UNSURE: callable methods are not shown

### src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeController.kt
- `PracticeController` — does: handles practice-set and question endpoints | needs: practice service and request guard | returns: controller | touches: request guard and service | fails: validation/domain exceptions mapped by API layer | confidence: med
- `create` — does: creates or resolves practice set | needs: create request | returns: response entity with created result | touches: database/job submission | fails: validation/readiness/rate-limit errors likely | confidence: med
- `get` — does: fetches practice-set view | needs: set ID | returns: set view | touches: database read | fails: missing/ownership error likely | confidence: med
- `retry` — does: retries question generation for set | needs: set ID | returns: response entity with set view | touches: database and job submission | fails: invalid state/rate-limit errors likely | confidence: med
- `addQuestion` — does: appends user-authored question | needs: set ID and add-question request | returns: question response entity | touches: database write | fails: validation/state errors likely | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt
- `PracticeSetView` — does: models a practice set and newest job of any status | needs: IDs, mode/status, questions, nullable latest job, timestamps | returns: view | touches: none | fails: none | confidence: high
- `PracticeSetStatus` — does: identifies set lifecycle state | needs: enum value (values omitted) | returns: status | touches: none | fails: invalid value likely deserialization error | confidence: med | UNSURE: exact states omitted
- `PracticeMode` — does: identifies practice mode | needs: enum value; request defaults to PRACTICE | returns: mode | touches: none | fails: other request values fail deserialization with 400 `INVALID_REQUEST` | confidence: high | UNSURE: full set of modes omitted
- `QuestionOrigin` — does: identifies whether a question is AI- or user-originated | needs: enum value (values omitted) | returns: origin | touches: none | fails: invalid value likely deserialization error | confidence: med | UNSURE: exact values omitted
- `PracticeQuestionView` — does: models ordered question and its attempts | needs: ID/order/origin/text, nullable rationale/category, expected signals, attempts default empty; attempts oldest first | returns: question view | touches: none | fails: none | confidence: high
- `AttemptView` — does: models submitted answer with derived status and score change | needs: identity/number/text/status, nullable feedback/delta/job, creation time | returns: attempt view; status and delta derived on read | touches: none | fails: none | confidence: high
- `AttemptStatus` — does: identifies attempt feedback lifecycle state | needs: enum value (values omitted) | returns: status | touches: none | fails: invalid value likely | confidence: med | UNSURE: exact states omitted
- `AnswerFeedbackResult` — does: carries attempt score, summary, next step, strengths, gaps, outline, follow-up | needs: score/summary and nullable next-step/follow-up with lists | returns: feedback result and job result | touches: none | fails: none | confidence: high
- `AttemptScoringInput` — does: supplies answer and question context to feedback handler | needs: answer/question text, nullable category, expected signals | returns: scoring input | touches: none | fails: none | confidence: high
- `SubmitAttemptRequest` — does: carries submitted answer text | needs: nullable text | returns: request data | touches: none | fails: missing/invalid text likely validation error | confidence: med
- `PracticeQuestionDraft` — does: models one normalized generated question before saving | needs: text/rationale, nullable category, expected signals | returns: draft | touches: none | fails: none | confidence: high
- `PracticeQuestionDrafts` — does: wraps generated questions for durable checkpointing | needs: draft list | returns: checkpoint data | touches: none | fails: none | confidence: high
- `PracticeQuestionsResult` — does: represents question-generation job output | needs: question views | returns: job result | touches: none | fails: none | confidence: high
- `PracticeSetCreated` — does: returns pair's set; duplicate means existing pair set and no job started | needs: set view and duplicate flag | returns: create result | touches: none | fails: none | confidence: high
- `CreatePracticeSetRequest` — does: requests a set for resume/job pair | needs: nullable pair IDs; mode defaults to PRACTICE | returns: request | touches: none | fails: unsupported mode fails deserialization with 400 `INVALID_REQUEST` | confidence: high
- `AddPracticeQuestionRequest` — does: carries user-added question text | needs: nullable string | returns: request | touches: none | fails: validation likely for missing/blank text | confidence: med
- `PracticeQuestionsPayload` — does: stores versioned generation-job references | needs: version, set/resume/job IDs | returns: job payload | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeQuestionsJobHandler.kt
- `PracticeQuestionsJobHandler` — does: handles AI practice-question generation jobs | needs: coach, strict document resolver, practice service | returns: job handler | touches: AI and practice persistence | fails: handler errors flow through job retry/classification | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt
- `PracticeService` — does: manages practice sets, questions, attempts, and their background jobs | needs: JDBC, user, job submission/store, JSON, transactions | returns: service | touches: database and jobs | fails: validation/state/ownership errors likely | confidence: med
- `get` — does: loads a practice set | needs: set ID | returns: set view | touches: database read | fails: missing/ownership behavior unclear | confidence: med
- `create` — does: returns pair's set or creates it with first question-generation job | needs: resume/job IDs | returns: created result; existing pair has `duplicate=true` and no new job | touches: database and job submission | fails: new set on unready resume gives 409 `RESUME_NOT_READY` | confidence: high
- `retry` — does: starts generation job after prior generation failed | needs: set ID | returns: set view | touches: database and job submission | fails: invalid state errors likely | confidence: high
- `addQuestion` — does: appends user question after AI questions | needs: set ID and already trimmed/checked text | returns: question view | touches: database write | fails: invalid state errors likely | confidence: high
- `questions` — does: returns AI questions then user questions in add order, each with attempts | needs: user ID and set ID | returns: ordered question list | touches: database read | fails: ownership/missing behavior unclear | confidence: high
- `submitAttempt` — does: saves next-numbered attempt and starts feedback job atomically | needs: set ID, question ID, already trimmed/checked text | returns: attempt view | touches: database and job submission | fails: invalid set/question/state errors likely | confidence: high
- `retryAttempt` — does: starts feedback job again for failed attempt while preserving identity, number, and text | needs: attempt ID | returns: attempt view | touches: database and job submission | fails: attempt-not-failed/state errors likely | confidence: high
- `attemptForScoring` — does: loads attempt and question for feedback handler | needs: user ID and attempt ID | returns: scoring input or null if missing | touches: database read | fails: unclear | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt
- `RagProperties` — does: holds validated/configured RAG retrieval and embedding parameters | needs: dimensions, top-Ks, snippet limits, minimum job snippets, section limit, RRF constant, optional model/schema | returns: properties object | touches: none | fails: invalid config may fail binding, exact validation unclear | confidence: med
- `embeddingDimensions` — does: returns embedding vector size | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `resumeCandidateTopK` — does: returns candidate count for resume retrieval | needs: none | returns: integer | touches: none | fails: none | confidence: med
- `jobDescriptionCandidateTopK` — does: returns candidate count for job-description retrieval | needs: none | returns: integer | touches: none | fails: none | confidence: med
- `assessmentMaxSnippets` — does: returns maximum snippets for assessment context | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `questionMaxSnippets` — does: returns maximum snippets for practice-question context | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `feedbackMaxSnippets` — does: returns maximum snippets for answer-feedback context | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `assessmentMinJobDescriptionSnippets` — does: returns minimum job-description snippets for assessment | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `questionMinJobDescriptionSnippets` — does: returns minimum job-description snippets for question context | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `feedbackMinJobDescriptionSnippets` — does: returns minimum job-description snippets for feedback | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `maxSnippetsPerSection` — does: returns per-section snippet cap | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `rrfK` — does: returns reciprocal-rank-fusion parameter | needs: none | returns: integer | touches: none | fails: none | confidence: med
- `embeddingModel` — does: returns optional embedding-model name | needs: none | returns: nullable string | touches: none | fails: none | confidence: med
- `chunkSchema` — does: returns optional chunk schema/version | needs: none | returns: nullable string | touches: none | fails: none | confidence: low | UNSURE: whether schema means identifier or version

### src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt
- `RagRetrievalService` — does: queries configured vector store for indexed context | needs: vector-store provider and metrics | returns: retrieval service | touches: vector store and metrics | fails: retrieval errors propagate; missing store raises illegal state | confidence: high
- `retrieve` (indexes) — does: searches only listed index handles at each claim version | needs: nullable query/index list and top-K; blank query or no indexes allowed | returns: snippets, empty for blank/no indexes | touches: vector store | fails: `IllegalStateException` when no store; vector errors propagate | confidence: high
- `retrieve` (single index) — does: searches one claimed index | needs: nullable query/index and top-K; null index or blank query allowed | returns: snippet list, empty for null/blank | touches: vector store | fails: `IllegalStateException` when no store; vector errors propagate | confidence: high
- `deleteIndexClaim` — does: deletes vectors for one index claim version | needs: index ID and claim version | returns: unclear | touches: vector store | fails: vector-store errors likely propagate | confidence: med | UNSURE: return type omitted
- `deleteIndex` — does: deletes vectors for one index | needs: index ID | returns: unclear | touches: vector store | fails: vector-store errors likely propagate | confidence: med | UNSURE: return type omitted

### src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeJobSubmissionService.kt
- `ResumeJobSubmissionService` — does: validates/stores/uploads a resume and submits extraction work | needs: file validation/read/storage/persistence/library/job/user/guard/cleanup/transaction services | returns: submission service | touches: file storage, database, request limits, background jobs | fails: upload/parse/storage/rate-limit/job errors likely | confidence: med
- `submit` — does: processes uploaded resume and starts extraction | needs: multipart file plus optional requested name/job title | returns: response entity containing resume creation result | touches: file read, storage, persistence and job submission; cleanup on failure likely | fails: validation, upload, storage, quota, or job-submission errors | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt
- `ResumeLibraryService` — does: manages resume library CRUD, pasted resume creation, impacts, and cleanup | needs: JDBC/user/persistence/normalizer/guard/transactions/cleanup/impact/JSON/job-store services | returns: library service | touches: database, storage cleanup, request guard, jobs | fails: validation/ownership/persistence errors likely | confidence: med
- `list` — does: lists current user's resumes | needs: authenticated user context likely | returns: resume page | touches: database read | fails: unclear | confidence: med
- `get` — does: retrieves one resume detail | needs: resume ID | returns: detailed library item | touches: database read | fails: missing/ownership error likely | confidence: med
- `findByFileHash` — does: finds resume by uploaded-file hash | needs: hash string | returns: optional item | touches: database read | fails: unclear | confidence: med
- `paste` — does: creates or resolves a resume from pasted text | needs: paste request | returns: response entity with resume-created result | touches: database and likely extraction/scoring jobs | fails: validation/duplicate/job errors likely | confidence: med
- `patch` — does: updates selected resume fields | needs: resume ID and arbitrary field map | returns: updated library item | touches: database write | fails: invalid field/value or not-found errors likely | confidence: med
- `deleteImpact` — does: previews resume deletion effects | needs: resume ID | returns: omitted | touches: database read | fails: not-found likely | confidence: low | UNSURE: return type omitted
- `delete` — does: deletes resume and related data | needs: resume ID | returns: omitted | touches: database and storage/job cleanup | fails: not-found/cleanup errors likely | confidence: med | UNSURE: return type omitted

### src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt
- `FailedResumeJob` — does: records a failed extraction job for a resume | needs: resume ID and nullable error code/message | returns: data value | touches: none | fails: none | confidence: med
- `FailedResumeStorage` — does: identifies storage object left by failed resume processing | needs: resume ID and storage key | returns: data value | touches: none | fails: none | confidence: med | UNSURE: cleanup usage not shown
- `ResumeChunkResponse` — does: reports one resume text chunk and its character count | needs: index, section, content, count | returns: response value | touches: none | fails: none | confidence: high
- `ResumeExtractionJobPayload` — does: carries stored upload metadata for extraction job | needs: resume/storage IDs and optional original/detected types plus size/extension | returns: payload | touches: none | fails: none | confidence: high
- `ResumeExtractionProperties` — does: configures parser queue, timeouts, and resource limits | needs: queue capacity, timeout, max characters/pages/embedded resources | returns: properties | touches: none | fails: invalid config may fail binding, exact validation unclear | confidence: med
- `queueCapacity` — does: returns extraction queue capacity | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `timeoutSeconds` — does: returns parse timeout | needs: none | returns: integer seconds | touches: none | fails: none | confidence: high
- `maxParseChars` — does: returns maximum parse characters | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `maxPdfPages` — does: returns maximum PDF pages | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `maxEmbeddedResources` — does: returns maximum embedded resources | needs: none | returns: integer | touches: none | fails: none | confidence: high
- `ResumeFileContent` — does: carries uploaded file bytes and detected metadata | needs: nullable filename/content type, size, bytes, nullable detected type, extension | returns: data value | touches: none | fails: none | confidence: high
- `ResumeSource` — does: identifies resume origin | needs: enum value (values omitted) | returns: source category | touches: none | fails: invalid enum likely on deserialization | confidence: med | UNSURE: exact origins omitted
- `ResumeStatus` — does: identifies resume processing state; stored PENDING is exposed as PROCESSING | needs: enum value | returns: public status | touches: none | fails: invalid enum likely | confidence: high | UNSURE: other statuses omitted
- `ResumeLibraryItem` — does: models a resume list row with current score and newest job | needs: required IDs/name/source/status/times; nullable title/file/score/job | returns: list item; latest job null only if never submitted | touches: none | fails: none | confidence: high
- `ResumeLibraryDetail` — does: models resume detail with optional text and full score in addition to list fields | needs: list fields plus nullable text/score | returns: detail | touches: none | fails: none | confidence: high
- `toItem` — does: converts detail to list item | needs: detail instance | returns: `ResumeLibraryItem` | touches: none | fails: none | confidence: med | UNSURE: exact copied/derived fields omitted
- `ResumePage` — does: wraps resume list items | needs: item list | returns: page data | touches: none | fails: none | confidence: high
- `ResumeCreated` — does: models resume creation outcome | needs: resume and duplicate flag | returns: creation result | touches: none | fails: none | confidence: med | UNSURE: duplicate match contract omitted
- `DuplicateResume` — does: identifies existing duplicate resume | needs: ID and name | returns: reference | touches: none | fails: none | confidence: med
- `ResumeExtractionResult` — does: reports extracted resume ID and optional duplicate reference | needs: resume ID and nullable duplicate | returns: job result | touches: none | fails: none | confidence: med
- `PasteResumeRequest` — does: carries name, optional job title, and pasted text | needs: required name/text and nullable title | returns: request | touches: none | fails: validation likely for blank name/text | confidence: med
- `PastePersistenceResult` — does: reports persisted resume ID and whether duplicate | needs: ID and boolean | returns: value | touches: none | fails: none | confidence: med
- `TextChunk` — does: models an indexed section chunk | needs: index, section, content | returns: data value | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/score/ResumeScoreResult.kt
- `ResumeScoreResult` — does: carries overall score, component scores, summary, fixes, rewrites, optional title, and scoring time | needs: listed fields | returns: score result | touches: none | fails: none | confidence: high
- `ResumeScoreFix` — does: describes a ranked, prioritized resume fix | needs: rank, section, priority, message | returns: value | touches: none | fails: none | confidence: high
- `ResumeScoreRewrite` — does: proposes a section rewrite and required placeholders | needs: section, original/rewritten strings, placeholder list | returns: value | touches: none | fails: none | confidence: high
- `ResumeScoreSummary` — does: summarizes overall score with timestamp and stale flag | needs: score, time, boolean | returns: summary | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/storage/ObjectStorageService.kt
- `ObjectStorageService` — does: abstracts object storage and normalizes S3 errors to 502 `STORAGE_UNAVAILABLE` | needs: implementation | returns: storage operations | touches: object storage | fails: each S3 error becomes `ApiRequestException` with documented code/status | confidence: high
- `put` — does: stores bytes with optional type, metadata, and tags | needs: key, byte array, nullable content type/metadata/tags | returns: stored-object metadata | touches: object storage | fails: storage error becomes 502 `STORAGE_UNAVAILABLE` | confidence: high
- `get` — does: loads object content | needs: key | returns: stored object content | touches: object storage | fails: storage error becomes 502 `STORAGE_UNAVAILABLE` | confidence: high
- `delete` — does: deletes object | needs: nullable key | returns: unclear | touches: object storage | fails: storage error becomes 502 `STORAGE_UNAVAILABLE` | confidence: high | UNSURE: null-key behavior and return type omitted
- `tag` — does: updates object tags | needs: nullable key and tags | returns: unclear | touches: object storage | fails: storage error becomes 502 `STORAGE_UNAVAILABLE` | confidence: high | UNSURE: null behavior and return type omitted

### src/main/kotlin/dev/jiaming/ai_interview/storage/S3ObjectStorageService.kt
- `S3ObjectStorageService` — does: implements object storage through AWS S3 | needs: S3 client and storage properties | returns: storage service | touches: S3 | fails: S3 errors follow interface's 502 `STORAGE_UNAVAILABLE` contract | confidence: high | UNSURE: method behavior is inherited/omitted

### src/main/kotlin/dev/jiaming/ai_interview/suggestions/ExperienceSuggestionsJobHandler.kt
- `ExperienceSuggestionsJobHandler` — does: handles background generation of experience suggestions | needs: coach service, strict document resolver, suggestions service | returns: job handler | touches: AI and suggestion persistence | fails: handler errors flow through job retry/classification | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt
- `SuggestionSourceType` — does: identifies kind of suggestion source | needs: enum value (values omitted) | returns: source category | touches: none | fails: invalid enum likely | confidence: med | UNSURE: exact source kinds omitted
- `SuggestionSource` — does: identifies a source by kind, ID, and name | needs: source type, UUID, name | returns: source reference | touches: none | fails: none | confidence: high
- `ExperienceSuggestionItem` — does: explains one requirement match to a source and gives guidance | needs: requirement, source, match, rationale, guidance | returns: suggestion item | touches: none | fails: none | confidence: high
- `ExperienceSuggestionsResult` — does: wraps generated suggestion items | needs: item list | returns: result | touches: none | fails: none | confidence: high
- `ExperienceSuggestionsRun` — does: checkpoints result plus sorted IDs of every supplied source | needs: result and source-ID list | returns: run checkpoint | touches: none | fails: none | confidence: high
- `SuggestionsView` — does: models suggestions state for a pair, including source availability and staleness | needs: IDs, flags, nullable result/time/job | returns: view; latest job may be terminal and null only when never submitted | touches: none | fails: none | confidence: high
- `SuggestionSourceInput` — does: represents an input source used to build a prompt | needs: source reference | returns: sealed source contract | touches: none | fails: unclear | confidence: med
- `SuggestionSourceInput.source` — does: exposes common source reference for either input variant | needs: none | returns: `SuggestionSource` | touches: none | fails: none | confidence: high
- `SuggestionSourceInput.Resume` — does: supplies another resume document as prompt source | needs: source and resolved document | returns: source input; resume is narrowed to job-budgeted text per doc | touches: none | fails: unclear | confidence: high
- `SuggestionSourceInput.Experience` — does: supplies experience text whole as prompt source | needs: source and text | returns: source input | touches: none | fails: unclear | confidence: high
- `ExperienceSuggestionsPayload` — does: carries versioned job references for suggestion generation | needs: version and suggestion/resume/job UUIDs | returns: job payload | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsService.kt
- `SuggestionsService` — does: reads, runs, and identifies current suggestion sources | needs: JDBC/user/job submission/store/JSON/transactions/resume persistence | returns: service | touches: database and jobs | fails: request/job errors likely | confidence: med
- `get` — does: gets suggestions state for resume/job pair | needs: resume and target-job IDs | returns: suggestions view | touches: database read | fails: ownership/not-found behavior unclear | confidence: med
- `run` — does: starts or reuses suggestions job for pair | needs: resume and target-job IDs | returns: accepted-job response | touches: database and job submission | fails: readiness/rate-limit/API-availability errors likely | confidence: med
- `currentSources` — does: lists every other READY resume and every experience owned by the user; defines current sources | needs: user ID and excluded resume ID | returns: source list | touches: database read | fails: unclear | confidence: high
- `promptSources` — does: builds prompt-ready inputs from current sources | needs: user ID and resume ID | returns: resume/experience source inputs | touches: database reads and document resolution | fails: unclear | confidence: med

### src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt
- `TargetJobController` — does: handles target-job endpoints | needs: target-job service and request guard | returns: controller | touches: service and guard | fails: validation/domain exceptions mapped by API handler | confidence: med
- `create` — does: creates or resolves target job from request | needs: create request | returns: response entity with created result | touches: database | fails: validation likely | confidence: med
- `list` — does: lists target jobs for current user | needs: request context | returns: target-job list response | touches: database read | fails: unclear | confidence: med
- `get` — does: fetches target-job detail | needs: target-job ID | returns: detail | touches: database read | fails: missing/ownership error likely | confidence: med
- `rename` — does: renames target job | needs: target-job ID and rename request | returns: target job | touches: database write | fails: validation/not-found behavior unclear | confidence: med
- `deleteImpact` — does: previews target-job deletion effects | needs: target-job ID | returns: impact summary | touches: database read | fails: missing/ownership behavior unclear | confidence: med
- `delete` — does: deletes target job | needs: target-job ID | returns: empty response entity | touches: database and dependent cleanup likely | fails: missing/cleanup behavior unclear | confidence: med
- `CreateTargetJobRequest` — does: carries target-job name/text for creation | needs: both nullable, validation likely follows controller contract | returns: request value | touches: none | fails: missing/blank values likely client error | confidence: med
- `RenameTargetJobRequest` — does: carries replacement target-job name | needs: nullable name | returns: request value | touches: none | fails: missing/blank name likely client error | confidence: med
- `TargetJobCreated` — does: returns target-job detail plus duplicate indicator | needs: detail and flag | returns: creation result | touches: none | fails: none | confidence: med | UNSURE: duplicate matching rule omitted

### src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobPersistenceService.kt
- `TargetJobPersistenceService` — does: stores target jobs with normalized text/RAG chunks and loads resolved documents | needs: JDBC, normalizer, chunker, content hasher | returns: persistence service | touches: database and RAG chunk/index persistence | fails: storage/normalization errors likely | confidence: high
- `findOrCreateTargetJob` — does: saves text unless owner already has same normalized text; duplicate returns existing unchanged and ignores new name | needs: user ID, name, text | returns: document plus created flag | touches: database and chunk persistence | fails: blank text throws `IllegalArgumentException` | confidence: high
- `findDocument` — does: loads owner's target-job document and chunks | needs: user ID and target-job ID | returns: optional resolved document; empty if absent or owned by someone else | touches: database read | fails: unclear | confidence: high
- `TargetJobDocumentSave` — does: reports resolved document and whether it was newly created | needs: document and boolean | returns: value | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt
- `TargetJobService` — does: manages target-job create/list/read/rename/delete | needs: JDBC, user service, persistence service | returns: service | touches: database | fails: validation/ownership errors likely | confidence: med
- `create` — does: creates or resolves target job | needs: name and text | returns: create result with duplicate flag | touches: database and persistence | fails: blank text likely rejected by persistence | confidence: med
- `list` — does: lists current user's target jobs | needs: request context | returns: list response | touches: database read | fails: unclear | confidence: med
- `get` — does: fetches target-job detail | needs: ID | returns: detail | touches: database read | fails: missing/ownership error likely | confidence: med
- `rename` — does: changes target-job name | needs: ID and name | returns: target job | touches: database write | fails: missing/validation errors likely | confidence: med
- `deleteImpact` — does: counts downstream effects of deleting a target job, including stale suggestion sets | needs: ID | returns: impact counts for scores, fits, suggestions, practice, attempts, stale suggestions, voice sessions | touches: database read | fails: missing-resource behavior unclear | confidence: high
- `delete` — does: deletes target job and associated data | needs: ID | returns: unit | touches: database and dependent cleanup | fails: missing/cleanup behavior unclear | confidence: med
- `TargetJobCreateResult` — does: returns target-job detail and duplicate flag | needs: detail and boolean | returns: create result | touches: none | fails: none | confidence: med | UNSURE: duplicate matching details omitted
- `TargetJobListResponse` — does: wraps target-job list | needs: item list | returns: response data | touches: none | fails: none | confidence: high
- `TargetJob` — does: represents target-job list item | needs: ID, name, timestamps | returns: data value | touches: none | fails: none | confidence: high
- `TargetJobDetail` — does: represents target job with text | needs: ID, name, timestamps, text | returns: detail | touches: none | fails: none | confidence: high
- `TargetJobDeleteImpact` — does: reports downstream counts affected by deletion | needs: counts defaulting to zero | returns: impact summary | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt
- `VoiceSessionView` — does: models a spoken interview run with question snapshot, optional transcript/jobs/report, and draft/save times | needs: IDs/status/questions and run deadline; transcript/jobs/saved/report nullable | returns: session view | touches: none | fails: none | confidence: high
- `VoiceSessionStatus` — does: identifies spoken-session state; EXPIRED means unsaved draft past expiry and cleanup removes it | needs: enum value | returns: status | touches: none | fails: invalid value likely | confidence: high | UNSURE: other statuses omitted
- `VoiceQuestion` — does: snapshots a practice question at run start | needs: question ID/text/category/signals | returns: value; ID is original practice question ID | touches: none | fails: none | confidence: high
- `VoiceTranscript` — does: wraps reviewed answers, one per reached question | needs: answer list | returns: transcript | touches: none | fails: none | confidence: high
- `VoiceAnswer` — does: records interviewer speech and corrected candidate answer for a question, with incomplete-capture flag | needs: question ID and both strings plus boolean | returns: answer value | touches: none | fails: none | confidence: high
- `VoiceSaveResult` — does: reports transcript save; duplicate means same transcript already saved and no new report job started | needs: session and duplicate flag | returns: saved session | touches: none | fails: none | confidence: high
- `VoiceTokenReservation` — does: records canonical question and run deadline reserved before provider call; each attempt consumes a DB slot | needs: question and deadline | returns: reservation | touches: none | fails: none | confidence: high
- `VoiceTokenResponse` — does: returns short-lived provider credential and session expiry info; value is never stored | needs: token, model, API version and expiry times | returns: token response | touches: none | fails: none | confidence: high
- `VoiceReportPayload` — does: carries versioned voice-report job references; transcript remains on session row | needs: payload version and session/resume/job IDs | returns: job payload | touches: none | fails: none | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceReportJobHandler.kt
- `VoiceReportJobHandler` — does: scores immutable reviewed transcript and writes one report through job effect fence | needs: coach, document resolver, session service, JSON mapper | returns: job handler | touches: AI and session/report persistence | fails: handler/provider/reference errors follow job policy | confidence: high

### src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt
- `VoiceSessionService` — does: manages spoken sessions with coordinated locking; writers lock owner, resume/job, then session; delete locks report jobs before session | needs: JDBC/user/job submission/JSON/transactions | returns: service | touches: database and background jobs | fails: state/ownership/job errors likely | confidence: high
- `get` — does: loads one session view | needs: session ID | returns: session view | touches: database read | fails: missing/ownership error likely | confidence: med
- `reserveToken` — does: atomically consumes one of twelve Live token attempts before provider call | needs: session and question IDs | returns: reserved question and run deadline | touches: database write/lock | fails: exhausted/invalid session or question likely | confidence: high
- `create` — does: starts draft on READY practice set with copy of first MAX_QUESTIONS AI questions in stored order | needs: practice-set ID | returns: session view | touches: database write | fails: non-ready/empty set likely state error | confidence: high
- `save` — does: saves reviewed transcript and starts report job atomically; identical repeated save returns committed outcome as duplicate; different post-save transcript conflicts; accepted until expiry | needs: session ID and transcript | returns: save result | touches: database and background job submission | fails: expired draft rejected; changed transcript after save conflicts | confidence: high
- `retryReport` — does: replaces failed current report job while preserving save and valid completed answers | needs: session ID | returns: session view | touches: database and job submission | fails: non-failed/invalid session state likely | confidence: high
- `discard` — does: removes unsaved draft; single statement preserves a row whose Save commits first | needs: session ID | returns: unit | touches: database write | fails: saved/missing session behavior unclear | confidence: high
- `delete` — does: removes session, report jobs, checkpoints, and effects | needs: session ID | returns: unit | touches: database writes | fails: missing/locking errors unclear | confidence: high
- `removeExpiredDrafts` — does: removes expired unsaved drafts, which contain no transcript | needs: none | returns: unit | touches: database cleanup | fails: database errors unclear | confidence: high

## Verification

Each prediction below is compared with its implementation. “Incomplete” means the blind prediction left an input, return, side effect, or failure detail uncertain or unstated; comments added in this unit clarify the supported contract.

- **incomplete** — `JobProgress` — `apps/web/components/flow/JobProgress.tsx:18` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Stepper` — `apps/web/components/flow/Stepper.tsx:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `SuggestionsPanel` — `apps/web/components/flow/SuggestionsPanel.tsx:13` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `LinkedInDialog` — `apps/web/components/library/LinkedInDialog.tsx:16` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ProjectFormDialog` — `apps/web/components/library/ProjectFormDialog.tsx:13` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeRow` — `apps/web/components/library/ResumeRow.tsx:13` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `AttemptList` — `apps/web/components/practice/AttemptList.tsx:17` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `AttemptWatcher` — `apps/web/components/practice/AttemptWatcher.tsx:6` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ApiErrorKind` — `apps/web/lib/api/client.ts:5` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ApiError` — `apps/web/lib/api/client.ts:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `RequestOptions` — `apps/web/lib/api/client.ts:26` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `apiRequest` — `apps/web/lib/api/client.ts:42` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ApiErrorBody` — `apps/web/lib/api/types.ts:5` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobType` — `apps/web/lib/api/types.ts:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobStatus` — `apps/web/lib/api/types.ts:19` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobStage` — `apps/web/lib/api/types.ts:22` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobError` — `apps/web/lib/api/types.ts:38` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobInputRefs` — `apps/web/lib/api/types.ts:41` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `LatestJob` — `apps/web/lib/api/types.ts:50` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobAccepted` — `apps/web/lib/api/types.ts:61` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobStatusResponse<TResult>` — `apps/web/lib/api/types.ts:72` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `isTerminal` — `apps/web/lib/api/types.ts:87` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ErrorCode` — `apps/web/lib/api/types.ts:92` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Priority` — `apps/web/lib/api/types.ts:109` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `DeleteImpact` — `apps/web/lib/api/types.ts:113` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Page<T>` — `apps/web/lib/api/types.ts:124` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ScoreSummary` — `apps/web/lib/api/types.ts:128` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Resume` — `apps/web/lib/api/types.ts:131` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeDetail` — `apps/web/lib/api/types.ts:146` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeCreated` — `apps/web/lib/api/types.ts:148` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `PasteResumeRequest` — `apps/web/lib/api/types.ts:150` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `UpdateResumeRequest` — `apps/web/lib/api/types.ts:152` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `TargetJob` — `apps/web/lib/api/types.ts:156` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `TargetJobDetail` — `apps/web/lib/api/types.ts:158` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `TargetJobCreated` — `apps/web/lib/api/types.ts:160` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `CreateTargetJobRequest` — `apps/web/lib/api/types.ts:162` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceInput` — `apps/web/lib/api/types.ts:166` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Experience` — `apps/web/lib/api/types.ts:174` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceCreated` — `apps/web/lib/api/types.ts:176` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceBatchResult` — `apps/web/lib/api/types.ts:178` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `FitView` — `apps/web/lib/api/types.ts:185` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `SuggestionsView` — `apps/web/lib/api/types.ts:195` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `PracticeSetCreated` — `apps/web/lib/api/types.ts:208` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `PracticeSet` — `apps/web/lib/api/types.ts:208` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Question` — `apps/web/lib/api/types.ts:224` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Attempt` — `apps/web/lib/api/types.ts:236` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceQuestion` — `apps/web/lib/api/types.ts:249` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceAnswer` — `apps/web/lib/api/types.ts:251` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceTranscript` — `apps/web/lib/api/types.ts:253` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceAnswerReport` — `apps/web/lib/api/types.ts:255` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceReport` — `apps/web/lib/api/types.ts:257` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceSession` — `apps/web/lib/api/types.ts:266` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceSaveResult` — `apps/web/lib/api/types.ts:283` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceToken` — `apps/web/lib/api/types.ts:285` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeScoreResult` — `apps/web/lib/api/types.ts:146` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobFitResult` — `apps/web/lib/api/types.ts:300` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `SuggestionSource` — `apps/web/lib/api/types.ts:309` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceSuggestionsResult` — `apps/web/lib/api/types.ts:312` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceDraft` — `apps/web/lib/api/types.ts:317` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceSplitResult` — `apps/web/lib/api/types.ts:319` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeExtractionResult` — `apps/web/lib/api/types.ts:322` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `AnswerFeedbackResult` — `apps/web/lib/api/types.ts:255` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `History` — `apps/web/lib/api/types.ts:337` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `VoiceSessionHistory` — `apps/web/lib/api/types.ts:357` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `errorCode` — `apps/web/lib/errorMessages.ts:65` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `friendlyError` — `apps/web/lib/errorMessages.ts:71` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `practiceKeys` — `apps/web/lib/query/practice.ts:6` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useCreatePracticeSet` — `apps/web/lib/query/practice.ts:11` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `usePracticeSet` — `apps/web/lib/query/practice.ts:22` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useRetryPracticeSet` — `apps/web/lib/query/practice.ts:41` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useAddQuestion` — `apps/web/lib/query/practice.ts:48` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useSubmitAttempt` — `apps/web/lib/query/practice.ts:55` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useRetryAttempt` — `apps/web/lib/query/practice.ts:62` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `jobQueryKey` — `apps/web/lib/query/useJob.ts:10` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useJob` — `apps/web/lib/query/useJob.ts:15` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useFollowJob` — `apps/web/lib/query/useJob.ts:57` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `voiceKeys` — `apps/web/lib/query/voice.ts:7` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `createVoiceSession` — `apps/web/lib/query/voice.ts:11` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `getVoiceSession` — `apps/web/lib/query/voice.ts:15` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `mintVoiceToken` — `apps/web/lib/query/voice.ts:19` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `saveVoiceSession` — `apps/web/lib/query/voice.ts:23` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `retryVoiceReport` — `apps/web/lib/query/voice.ts:27` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `discardVoiceDraft` — `apps/web/lib/query/voice.ts:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `deleteVoiceSession` — `apps/web/lib/query/voice.ts:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useVoiceSession` — `apps/web/lib/query/voice.ts:39` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useRetryVoiceReport` — `apps/web/lib/query/voice.ts:49` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `useDeleteVoiceSession` — `apps/web/lib/query/voice.ts:60` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `MAX_ATTEMPTS` — `apps/web/mocks/jobSimulator.ts:5` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JOB_STAGES` — `apps/web/mocks/jobSimulator.ts:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobFailure` — `apps/web/mocks/jobSimulator.ts:20` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `MockJob` — `apps/web/mocks/jobSimulator.ts:23` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `JobState` — `apps/web/mocks/jobSimulator.ts:39` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `jobState` — `apps/web/mocks/jobSimulator.ts:49` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `latestJob` — `apps/web/mocks/jobSimulator.ts:91` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `STORAGE_KEY` — `apps/web/mocks/store.ts:35` — The implementation agrees with the predicted role and contract.
- **incomplete** — `MockHttpError` — `apps/web/mocks/store.ts:38` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `MockStoreOptions` — `apps/web/mocks/store.ts:73` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `createMockStore` — `apps/web/mocks/store.ts:102` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `MockStore` — `apps/web/mocks/store.ts:899` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `AiHttpTransport` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiHttpTransport.kt:10` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `AiHttpTransport.send` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiHttpTransport.kt:13` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `AiHttpTransportResponse` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiHttpTransport.kt:13` — The implementation agrees with the predicted role and contract.
- **incomplete** — `jdkTransport` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiHttpTransport.kt:21` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `AiProviderErrorCode` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:4` — The implementation agrees with the predicted role and contract.
- **correct** — `NOT_CONFIGURED` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:6` — The implementation agrees with the predicted role and contract.
- **correct** — `RATE_LIMITED` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:8` — The implementation agrees with the predicted role and contract.
- **correct** — `TIMEOUT` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:10` — The implementation agrees with the predicted role and contract.
- **correct** — `UPSTREAM_ERROR` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:12` — The implementation agrees with the predicted role and contract.
- **correct** — `SAFETY` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:14` — The implementation agrees with the predicted role and contract.
- **correct** — `RECITATION` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:16` — The implementation agrees with the predicted role and contract.
- **correct** — `MAX_TOKENS` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:18` — The implementation agrees with the predicted role and contract.
- **correct** — `EMPTY_RESPONSE` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:20` — The implementation agrees with the predicted role and contract.
- **correct** — `INVALID_RESPONSE` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:22` — The implementation agrees with the predicted role and contract.
- **correct** — `AiProviderException` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderException.kt:4` — The implementation agrees with the predicted role and contract.
- **correct** — `code` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderException.kt:5` — The implementation agrees with the predicted role and contract.
- **correct** — `statusCode` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderException.kt:8` — The implementation agrees with the predicted role and contract.
- **correct** — `retryable` — `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderException.kt:9` — The implementation agrees with the predicted role and contract.
- **incomplete** — `AiResumeCoachService` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:17` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `assessJobFit` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:25` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `suggestExperiences` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `generatePracticeQuestions` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:47` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `scorePracticeAnswer` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:54` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `scoreResume` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:61` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `splitExperience` — `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:67` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `CoachAnalysisInput` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachAnalysisInput.kt:7` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `resume` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachAnalysisInput.kt:12` — The implementation agrees with the predicted role and contract.
- **correct** — `targetJob` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachAnalysisInput.kt:13` — The implementation agrees with the predicted role and contract.
- **incomplete** — `CoachFeedbackInput` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:7` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `resume` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:18` — The implementation agrees with the predicted role and contract.
- **correct** — `targetJob` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:19` — The implementation agrees with the predicted role and contract.
- **correct** — `questionText` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:20` — The implementation agrees with the predicted role and contract.
- **correct** — `category` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:21` — The implementation agrees with the predicted role and contract.
- **incomplete** — `expectedSignals` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:22` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `answerText` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:23` — The implementation agrees with the predicted role and contract.
- **incomplete** — `incompleteCapture` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachFeedbackInput.kt:24` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `CoachPromptBuilder` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildExperienceSplitPrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:10` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildResumeScorePrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildJobFitPrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:63` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildExperienceSuggestionsPrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:92` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildPracticeQuestionPrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:121` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildPracticeFeedbackPrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:144` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `buildRepairPrompt` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachPromptBuilder.kt:180` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `CoachRagContextService` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt:20` — The implementation agrees with the predicted role and contract.
- **incomplete** — `jobFitContext` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt:32` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `practiceQuestionContext` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `feedbackContext` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt:38` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `suggestionSourceText` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt:42` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `CoachResponseMapper` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:28` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `parse` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:30` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `normalizeExperienceSplit` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:36` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `normalizeResumeScore` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:56` — The implementation agrees with the predicted role and contract.
- **incomplete** — `normalizeFeedback` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:88` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `normalizeJobFit` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:95` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `normalizePracticeQuestions` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:113` — The implementation agrees with the predicted role and contract.
- **incomplete** — `normalizeExperienceSuggestions` — `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachResponseMapper.kt:130` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `Priority` — `src/main/kotlin/dev/jiaming/ai_interview/coach/Priority.kt:4` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ApiExceptionHandler` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:21` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleResumeExtractionException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:25` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleResumeParserBusyException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleAiProviderException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:37` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `handleApiRequestException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:41` — The implementation agrees with the predicted role and contract.
- **incomplete** — `handleResponseStatusException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:46` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleUnreadableBody` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:54` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleInvalidRequest` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:65` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleMaxUploadSize` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:71` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `handleUnexpectedException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:77` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ApiRequestException` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiRequestException.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `status` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiRequestException.kt:17` — The implementation agrees with the predicted role and contract.
- **correct** — `code` — `src/main/kotlin/dev/jiaming/ai_interview/common/ApiRequestException.kt:18` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ErrorCodes` — `src/main/kotlin/dev/jiaming/ai_interview/common/ErrorCodes.kt:10` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `forStatus` — `src/main/kotlin/dev/jiaming/ai_interview/common/ErrorCodes.kt:12` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `RedisRequestGuard` — `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:24` — The implementation agrees with the predicted role and contract.
- **incomplete** — `assertAiAllowed` — `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:33` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `assertUploadAllowed` — `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `withIdempotentRetryCache` — `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:47` — The implementation agrees with the predicted role and contract.
- **correct** — `withIdempotentHttpCache` — `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:87` — The implementation agrees with the predicted role and contract.
- **correct** — `DocumentReferenceResolver` — `src/main/kotlin/dev/jiaming/ai_interview/document/DocumentReferenceResolver.kt:14` — The implementation agrees with the predicted role and contract.
- **correct** — `resolveStrict` — `src/main/kotlin/dev/jiaming/ai_interview/document/DocumentReferenceResolver.kt:23` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ResolvedJobInputs` — `src/main/kotlin/dev/jiaming/ai_interview/document/ResolvedJobInputs.kt:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `resume` — `src/main/kotlin/dev/jiaming/ai_interview/document/ResolvedJobInputs.kt:15` — The implementation agrees with the predicted role and contract.
- **correct** — `targetJob` — `src/main/kotlin/dev/jiaming/ai_interview/document/ResolvedJobInputs.kt:12` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceController` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:23` — The implementation agrees with the predicted role and contract.
- **incomplete** — `list` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:30` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `rename` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:34` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `deleteImpact` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:41` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:46` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:53` — The implementation agrees with the predicted role and contract.
- **incomplete** — `batch` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:62` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `split` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceController.kt:70` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceSource` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:7` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ExperienceInput` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:10` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ExperienceBatchRequest` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:19` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceSplitRequest` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:21` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ExperienceRenameRequest` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:23` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `Experience` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:26` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceListResponse` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:37` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceCreated` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:42` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceBatchDuplicate` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:44` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceBatchResult` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:46` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ExperienceSplitJobPayload` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:49` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ExperienceDuplicate` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:56` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceSplitItem` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:57` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceSplitResult` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:65` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ExperienceService` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:21` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `list` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:27` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `rename` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:35` — The implementation agrees with the predicted role and contract.
- **incomplete** — `deleteImpact` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:60` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:73` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:80` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `batch` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:91` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `split` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:114` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `annotateDuplicates` — `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceService.kt:126` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `FitView` — `src/main/kotlin/dev/jiaming/ai_interview/fit/FitView.kt:7` — The implementation agrees with the predicted role and contract.
- **correct** — `JobFitJobHandler` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitJobHandler.kt:16` — The implementation agrees with the predicted role and contract.
- **correct** — `JobFitResult` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitResult.kt:6` — The implementation agrees with the predicted role and contract.
- **correct** — `MatchedRequirement` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitResult.kt:9` — The implementation agrees with the predicted role and contract.
- **correct** — `MissingRequirement` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitResult.kt:10` — The implementation agrees with the predicted role and contract.
- **correct** — `FitFeedback` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitResult.kt:11` — The implementation agrees with the predicted role and contract.
- **incomplete** — `JobFitService` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitService.kt:22` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitService.kt:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `run` — `src/main/kotlin/dev/jiaming/ai_interview/fit/JobFitService.kt:43` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `GeminiClient` — `src/main/kotlin/dev/jiaming/ai_interview/gemini/GeminiClient.kt:30` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `HistoryResponse` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceSessionHistory` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeHistory` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `ScorePoint` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:17` — The implementation agrees with the predicted role and contract.
- **correct** — `TargetJobHistory` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `FitPoint` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:19` — The implementation agrees with the predicted role and contract.
- **correct** — `PracticeSetHistory` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `QuestionHistory` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:23` — The implementation agrees with the predicted role and contract.
- **incomplete** — `HistoryService` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:29` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `history` — `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:30` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `BackgroundJobStore` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:19` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `createIfAbsent` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:22` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **wrong** — `createIfAbsentWithInitialResult` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:28` — The method was renamed to `createIfAbsentWithInitialCheckpoint`; the current parameter is a checkpoint, not a result.
- **incomplete** — `findReusable` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:44` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `findLatestForResource` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:50` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `findLatestForResources` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:59` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `deleteByResources` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:69` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `findForUser` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:75` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `findById` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:77` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `findUndispatched` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:79` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `markEnqueued` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:85` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `claim` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:91` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `extendLease` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:102` — The implementation agrees with the predicted role and contract.
- **incomplete** — `updateStage` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:107` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `checkpointResult` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:112` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `markSucceeded` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:117` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `markFailed` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:119` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `markRetrying` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:122` — The implementation agrees with the predicted role and contract.
- **correct** — `releaseForRedispatch` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:128` — The implementation agrees with the predicted role and contract.
- **incomplete** — `prepareDlqRecovery` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:134` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `markExhaustedFromDlq` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:142` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `reapExpiredLeases` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:149` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `clearExpiredPayloads` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/BackgroundJobStore.kt:160` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `FeedbackJobHandler` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/FeedbackJobHandler.kt:13` — The implementation agrees with the predicted role and contract.
- **incomplete** — `classify` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobFailureClassifier.kt:16` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `JobSubmissionService` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:19` — The implementation agrees with the predicted role and contract.
- **correct** — `submit` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:40` — The implementation agrees with the predicted role and contract.
- **incomplete** — `fingerprint` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:53` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `assertApiAvailable` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:55` — The implementation agrees with the predicted role and contract.
- **incomplete** — `findReusable` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:60` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `withIdempotency` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:86` — The implementation agrees with the predicted role and contract.
- **correct** — `LatestJob` — `src/main/kotlin/dev/jiaming/ai_interview/jobs/LatestJob.kt:11` — The implementation agrees with the predicted role and contract.
- **incomplete** — `OpenAiClient` — `src/main/kotlin/dev/jiaming/ai_interview/openai/OpenAiClient.kt:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `PracticeController` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeController.kt:18` — The implementation agrees with the predicted role and contract.
- **incomplete** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeController.kt:24` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeController.kt:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `retry` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeController.kt:39` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `addQuestion` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeController.kt:45` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `PracticeSetView` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:8` — The implementation agrees with the predicted role and contract.
- **incomplete** — `PracticeSetStatus` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:13` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `PracticeMode` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:12` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `QuestionOrigin` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:28` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `PracticeQuestionView` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:14` — The implementation agrees with the predicted role and contract.
- **correct** — `AttemptView` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:39` — The implementation agrees with the predicted role and contract.
- **incomplete** — `AttemptStatus` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:47` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `AnswerFeedbackResult` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:48` — The implementation agrees with the predicted role and contract.
- **correct** — `AttemptScoringInput` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:71` — The implementation agrees with the predicted role and contract.
- **incomplete** — `SubmitAttemptRequest` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:74` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `PracticeQuestionDraft` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:77` — The implementation agrees with the predicted role and contract.
- **correct** — `PracticeQuestionDrafts` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:80` — The implementation agrees with the predicted role and contract.
- **correct** — `PracticeQuestionsResult` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:83` — The implementation agrees with the predicted role and contract.
- **correct** — `PracticeSetCreated` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:86` — The implementation agrees with the predicted role and contract.
- **correct** — `CreatePracticeSetRequest` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:89` — The implementation agrees with the predicted role and contract.
- **incomplete** — `AddPracticeQuestionRequest` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:92` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `PracticeQuestionsPayload` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:96` — The implementation agrees with the predicted role and contract.
- **correct** — `PracticeQuestionsJobHandler` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeQuestionsJobHandler.kt:16` — The implementation agrees with the predicted role and contract.
- **incomplete** — `PracticeService` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:22` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:40` — The implementation agrees with the predicted role and contract.
- **incomplete** — `retry` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:74` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `addQuestion` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:92` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `questions` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:101` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `submitAttempt` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:140` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `retryAttempt` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:165` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `attemptForScoring` — `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeService.kt:180` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `RagProperties` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:8` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `embeddingDimensions` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:23` — The implementation agrees with the predicted role and contract.
- **correct** — `resumeCandidateTopK` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:24` — The implementation agrees with the predicted role and contract.
- **correct** — `jobDescriptionCandidateTopK` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:25` — The implementation agrees with the predicted role and contract.
- **correct** — `assessmentMaxSnippets` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:26` — The implementation agrees with the predicted role and contract.
- **correct** — `questionMaxSnippets` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:27` — The implementation agrees with the predicted role and contract.
- **correct** — `feedbackMaxSnippets` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:28` — The implementation agrees with the predicted role and contract.
- **correct** — `assessmentMinJobDescriptionSnippets` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:29` — The implementation agrees with the predicted role and contract.
- **correct** — `questionMinJobDescriptionSnippets` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:30` — The implementation agrees with the predicted role and contract.
- **correct** — `feedbackMinJobDescriptionSnippets` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:31` — The implementation agrees with the predicted role and contract.
- **correct** — `maxSnippetsPerSection` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:32` — The implementation agrees with the predicted role and contract.
- **correct** — `rrfK` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:33` — The implementation agrees with the predicted role and contract.
- **wrong** — `embeddingModel` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:34` — The getter returns a non-null string and defaults to `gemini-embedding-001`.
- **wrong** — `chunkSchema` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:35` — The getter returns a non-null string and defaults to `section-block-v3`.
- **correct** — `RagRetrievalService` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt:16` — The implementation agrees with the predicted role and contract.
- **correct** — `retrieve (indexes)` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt:24` — The implementation agrees with the predicted role and contract.
- **correct** — `retrieve (single index)` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt:31` — The implementation agrees with the predicted role and contract.
- **incomplete** — `deleteIndexClaim` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `deleteIndex` — `src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt:41` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeJobSubmissionService` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeJobSubmissionService.kt:19` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `submit` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeJobSubmissionService.kt:32` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeLibraryService` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:26` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `list` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:39` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:50` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `findByFileHash` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:59` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **wrong** — `paste` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:68` — Pasted resumes are persisted directly as READY; this method does not start extraction or scoring jobs.
- **incomplete** — `patch` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:87` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `deleteImpact` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:128` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeLibraryService.kt:131` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `FailedResumeJob` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:12` — The implementation agrees with the predicted role and contract.
- **incomplete** — `FailedResumeStorage` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:16` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ResumeChunkResponse` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:19` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeExtractionJobPayload` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:23` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ResumeExtractionProperties` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:35` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `queueCapacity` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:42` — The implementation agrees with the predicted role and contract.
- **correct** — `timeoutSeconds` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:43` — The implementation agrees with the predicted role and contract.
- **correct** — `maxParseChars` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:44` — The implementation agrees with the predicted role and contract.
- **correct** — `maxPdfPages` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:45` — The implementation agrees with the predicted role and contract.
- **correct** — `maxEmbeddedResources` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:46` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeFileContent` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:51` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ResumeSource` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:61` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `ResumeStatus` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:64` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ResumeLibraryItem` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:67` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeLibraryDetail` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:82` — The implementation agrees with the predicted role and contract.
- **incomplete** — `toItem` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:97` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ResumePage` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:101` — The implementation agrees with the predicted role and contract.
- **incomplete** — `ResumeCreated` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:104` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `DuplicateResume` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:107` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeExtractionResult` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:110` — The implementation agrees with the predicted role and contract.
- **incomplete** — `PasteResumeRequest` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:113` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `PastePersistenceResult` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:116` — The implementation agrees with the predicted role and contract.
- **correct** — `TextChunk` — `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:121` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeScoreResult` — `src/main/kotlin/dev/jiaming/ai_interview/score/ResumeScoreResult.kt:9` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeScoreFix` — `src/main/kotlin/dev/jiaming/ai_interview/score/ResumeScoreResult.kt:13` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeScoreRewrite` — `src/main/kotlin/dev/jiaming/ai_interview/score/ResumeScoreResult.kt:14` — The implementation agrees with the predicted role and contract.
- **correct** — `ResumeScoreSummary` — `src/main/kotlin/dev/jiaming/ai_interview/score/ResumeScoreResult.kt:34` — The implementation agrees with the predicted role and contract.
- **correct** — `ObjectStorageService` — `src/main/kotlin/dev/jiaming/ai_interview/storage/ObjectStorageService.kt:4` — The implementation agrees with the predicted role and contract.
- **correct** — `put` — `src/main/kotlin/dev/jiaming/ai_interview/storage/ObjectStorageService.kt:6` — The implementation agrees with the predicted role and contract.
- **correct** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/storage/ObjectStorageService.kt:8` — The implementation agrees with the predicted role and contract.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/storage/ObjectStorageService.kt:10` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `tag` — `src/main/kotlin/dev/jiaming/ai_interview/storage/ObjectStorageService.kt:12` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `S3ObjectStorageService` — `src/main/kotlin/dev/jiaming/ai_interview/storage/S3ObjectStorageService.kt:33` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ExperienceSuggestionsJobHandler` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/ExperienceSuggestionsJobHandler.kt:14` — The implementation agrees with the predicted role and contract.
- **incomplete** — `SuggestionSourceType` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:9` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `SuggestionSource` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:11` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceSuggestionItem` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:13` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceSuggestionsResult` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:21` — The implementation agrees with the predicted role and contract.
- **correct** — `ExperienceSuggestionsRun` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:24` — The implementation agrees with the predicted role and contract.
- **correct** — `SuggestionsView` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:26` — The implementation agrees with the predicted role and contract.
- **incomplete** — `SuggestionSourceInput` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:38` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `SuggestionSourceInput.source` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:15` — The implementation agrees with the predicted role and contract.
- **wrong** — `SuggestionSourceInput.Resume` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:41` — This variant stores a full `ResolvedDocument`; budget selection happens later in the coach service.
- **incomplete** — `SuggestionSourceInput.Experience` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:43` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `ExperienceSuggestionsPayload` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsModels.kt:48` — The implementation agrees with the predicted role and contract.
- **incomplete** — `SuggestionsService` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsService.kt:24` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsService.kt:34` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `run` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsService.kt:55` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `currentSources` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsService.kt:38` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `promptSources` — `src/main/kotlin/dev/jiaming/ai_interview/suggestions/SuggestionsService.kt:99` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `TargetJobController` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:20` — The implementation agrees with the predicted role and contract.
- **incomplete** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:26` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `list` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:43` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:47` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `rename` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:51` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `deleteImpact` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:58` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:63` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `CreateTargetJobRequest` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:26` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `RenameTargetJobRequest` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:72` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `TargetJobCreated` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobController.kt:26` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `TargetJobPersistenceService` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobPersistenceService.kt:19` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `findOrCreateTargetJob` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobPersistenceService.kt:30` — The implementation agrees with the predicted role and contract.
- **incomplete** — `findDocument` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobPersistenceService.kt:34` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `TargetJobDocumentSave` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobPersistenceService.kt:30` — The implementation agrees with the predicted role and contract.
- **incomplete** — `TargetJobService` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:16` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:23` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `list` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:31` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:46` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `rename` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:53` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **wrong** — `deleteImpact` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:70` — The service counts fits, suggestions, practice sets, attempts, and saved voice sessions; scores and stale suggestions remain zero by contract.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:91` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `TargetJobCreateResult` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:23` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `TargetJobListResponse` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:31` — The implementation agrees with the predicted role and contract.
- **correct** — `TargetJob` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:53` — The implementation agrees with the predicted role and contract.
- **correct** — `TargetJobDetail` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:46` — The implementation agrees with the predicted role and contract.
- **correct** — `TargetJobDeleteImpact` — `src/main/kotlin/dev/jiaming/ai_interview/targetjob/TargetJobService.kt:70` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceSessionView` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:7` — The implementation agrees with the predicted role and contract.
- **incomplete** — `VoiceSessionStatus` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:12` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `VoiceQuestion` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:13` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceTranscript` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:14` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceAnswer` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:31` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceSaveResult` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:37` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceTokenReservation` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:40` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceTokenResponse` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:43` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceReportPayload` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:53` — The implementation agrees with the predicted role and contract.
- **correct** — `VoiceReportJobHandler` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceReportJobHandler.kt:19` — The implementation agrees with the predicted role and contract.
- **incomplete** — `VoiceSessionService` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:29` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `get` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:37` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `reserveToken` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:40` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `create` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:79` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **correct** — `save` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:123` — The implementation agrees with the predicted role and contract.
- **incomplete** — `retryReport` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:162` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `discard` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:202` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `delete` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:214` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
- **incomplete** — `removeExpiredDrafts` — `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceSessionService.kt:235` — The implementation supplies the input, return, side-effect, or failure detail omitted or left uncertain by the prediction.
## Original findings re-check

- **Latest job naming — resolved.** Resource views expose nullable `latestJob` in every status; null means no job was ever submitted, and the client checks status before showing progress. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/jobs/LatestJob.kt:11`, `apps/web/lib/api/types.ts:50`, `apps/web/components/flow/JobProgress.tsx:18`.
- **Target job vs job — resolved.** Public resource types and inputs use `targetJobId`; `JobInputRefs` accepts legacy `jobDescriptionId` only when reading older job payloads. Evidence: `apps/web/lib/api/types.ts:43`, `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobInputRefs.kt:19`.
- **Duplicate flag — resolved.** Create-style responses use `duplicate: Boolean` to identify an unchanged existing item; practice-set and voice-save docs say no new job starts on duplicate, while batch experience results keep a `duplicates` list. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:104`, `src/main/kotlin/dev/jiaming/ai_interview/experience/ExperienceModels.kt:42`, `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:86`, `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:37`.
- **Idempotency and retries — resolved.** The web client keys POST and reuses that key for automatic retries, retries GET without a key, and sends PATCH/DELETE once. Server caching is header-driven, rejects a reused key with a changed fingerprint as 409 and an in-flight duplicate as 503, and bypasses caching when disabled or Redis is unavailable. Evidence: `apps/web/lib/api/client.ts:37`, `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:38`, `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:138`, `src/main/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuard.kt:153`.
- **Job submission entry point — resolved.** `JobSubmissionService.submit` is the job submission path and runs availability and AI-limit checks before reuse/write; resume extraction is exempt from the AI limit. Read-only `findReusable` can return an existing active job without creating one. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:26`, `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:40`, `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobSubmissionService.kt:60`.
- **Error code mapping — resolved.** Spring status failures use `ErrorCodes.forStatus` in API and job boundaries, while exceptions with their own code preserve it; the API contract lists route and failed-job codes. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/common/ErrorCodes.kt:12`, `src/main/kotlin/dev/jiaming/ai_interview/common/ApiExceptionHandler.kt:48`, `src/main/kotlin/dev/jiaming/ai_interview/jobs/JobFailureClassifier.kt:23`, `docs/api/frontend-api-contract.md:774`.
- **AI provider naming — resolved.** Provider exception, code holder, HTTP transport, and repair metric are provider-neutral; historical `GEMINI_*` wire values remain documented and unchanged. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderException.kt:3`, `src/main/kotlin/dev/jiaming/ai_interview/ai/AiProviderErrorCode.kt:3`, `src/main/kotlin/dev/jiaming/ai_interview/ai/AiHttpTransport.kt:10`, `src/main/kotlin/dev/jiaming/ai_interview/coach/AiResumeCoachService.kt:80`.
- **RAG scoping and snippet budgets — resolved.** Retrieval accepts only claimed index handles, returns empty for blank/no-index inputs, and throws when no vector store is configured. Context APIs use snippet-count settings and document direct short-context handling and fallback behavior. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/rag/RagRetrievalService.kt:24`, `src/main/kotlin/dev/jiaming/ai_interview/rag/RagProperties.kt:47`, `src/main/kotlin/dev/jiaming/ai_interview/coach/CoachRagContextService.kt:32`.
- **Closed sets — resolved.** Practice mode, question origin, resume source/status, priority, and voice-session status are enums with stable values; practice mode defaults to `PRACTICE`, and voice report status uses `JobStatus`. Evidence: `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:25`, `src/main/kotlin/dev/jiaming/ai_interview/practice/PracticeModels.kt:28`, `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:61`, `src/main/kotlin/dev/jiaming/ai_interview/resume/ResumeModels.kt:64`, `src/main/kotlin/dev/jiaming/ai_interview/coach/Priority.kt:4`, `src/main/kotlin/dev/jiaming/ai_interview/voice/VoiceModels.kt:25`, `src/main/kotlin/dev/jiaming/ai_interview/history/HistoryService.kt:15`.

## New findings

- **Fixed:** added the required glossary-aligned `AiProviderException` contract; renamed `createIfAbsentWithInitialResult` to `createIfAbsentWithInitialCheckpoint` across implementation, service, tests, and integration tests; clarified incomplete contracts for UI components, API types/client, query hooks, mocks, provider/coaching, request/error mapping, experience, fit, job storage/submission, practice, RAG, resume, storage, suggestions, target jobs, and voice. Specific wrong predictions were corrected in docs: pasted resumes are already READY, a suggestion resume source carries its full document until budget selection, and target-job impact's score/stale fields are zero by contract.
- **To file:** none; the re-check found no new issue requiring a JSON, route, error-code, or behavior change.

## Result

The interface-clarity refactor meets its stated goal for all nine in-scope original findings. The blind re-check found 149 correct, 267 incomplete, and 6 wrong predictions out of 422; the incomplete and wrong cases are recorded above and clarified with non-wire documentation or the permitted symbol rename. No wire-contract or behavior change was made.
