---
title: Real API Checks - Plan
type: test
date: 2026-10-03
artifact_contract: ce-unified-plan/v1
product_contract_source: ce-plan-bootstrap
execution: code
---

# Real API Checks - Plan

## Goal Capsule

- **Objective:** A reviewer of this work's PR can see that the web app works end to end against the real API with mocks off, at the code now on master. A change that makes the MSW fake API answer differently from the Kotlin API, on a route the shared scenarios call, fails a check the developer can run, instead of passing silently.
- **Means:** Run the candidate journey on the local Compose stack with real AI (KTD1, KTD7). Run one shared set of API scenarios against both the fake and a real API (KTD4, KTD10). Back `RedisRequestGuard` with a real Redis (KTD9).
- **Authority:** This plan, then `docs/api/frontend-api-contract.md`, then current code.
- **Execution profile:** U1 and U2 are independent. U3 runs after U1. PR #10 was merged into master on 2026-10-03, so this work goes on a new branch from `origin/master` and ships as a new PR.
- **Stop conditions:**
  - Stop and report if any of `ai-interview-postgres`, `ai-interview-redis`, `ai-interview-localstack`, `ai-interview-api`, `ai-interview-worker` or `ai-interview-web` exists, running or stopped, with a `com.docker.compose.project` label other than `frontend-backend-supabase`. Do not stop or remove such a container. The `ai-interview-supabase-*` containers belong to the separate Supabase stack, don't collide, and stay untouched.
  - Stop and report if the real API disagrees with the fake on a documented contract field. Deciding which side is wrong needs `docs/api/frontend-api-contract.md` and possibly the user. Do not edit either side just to make the check pass.
  - Never print or commit secret values from `.env`.
- **Finishing:** `ce-work` implements, and the calling pipeline reviews, ships and watches CI.

---

## Product Contract

### Summary

Prove the connected app on real infrastructure: a mocks-off browser journey on the local Compose stack, an opt-in suite that runs the shared API scenarios against a real API, and an integration test that runs the Redis idempotency scripts on a real Redis.

### Problem Frame

Every frontend test runs against MSW, an in-memory fake of the API in `apps/web/mocks/` of about 1,200 lines. PR #10's final browser check ran with mocks on. The last mocks-off journey, recorded in `docs/supabase-migration.md` on 2026-10-01, predates PR #10's review fixes. Nothing compares the fake to the Kotlin API, so a renamed field or changed error code on either side leaves every test green. The real pieces already exist: Docker Compose runs PostgreSQL, Redis and LocalStack, and the web image builds with mocks off by default. The AI keys are in the local `.env`. Redis is the one piece of real infrastructure that no test exercises: `RedisRequestGuardTests` imitates the Lua scripts in Kotlin, and review finding #6 on PR #10 asks for a real-Redis test.

### Requirements

**Mocks-off journey**
- R1. With mocks off, a candidate can paste a resume, score it, add a target job, run the fit and get suggestions from experiences, generate practice questions and get AI feedback on two attempts, and see all of it in history. This runs in a browser on the local Compose stack with real PostgreSQL, Redis, LocalStack and the AI provider selected in `.env`.
- R2. The journey's evidence is recorded where this work's PR reviewers read it: the date, commit, chat provider, each step's pass or fail, and any failure with its error code.

**Fake and real API stay in step**
- R3. One set of API scenarios runs against both the MSW fake and a real API, and a response from the real API that differs from what the scenario expects fails that run.
- R4. The run against a real API happens only when the developer names the API's address. A plain `npm test` skips it and passes without a running backend.
- R5. A scenario that needs fake-only controls (forced job failures, the simulated clock, store-level uploads, storage reload) or an empty database stays fake-only.

**Real Redis**
- R6. `RedisRequestGuard`'s reserve, store, renew and delete paths run against a real Redis in the integration suite.

### Scope Boundaries

- CI does not change. The live suite never runs there (KTD2).
- No new fake or stub infrastructure (KTD1).
- The MSW component tests and the backend mocks that isolate database logic stay as they are.
- The journey on the Supabase web origin remains PR #10's separate cutover gate.

#### Deferred to Follow-Up Work

- Deleting the resumes and target jobs that live-suite runs leave in the local database.
- Drift checks for routes no shared scenario calls: experiences and the LinkedIn split, target-job edit and delete, practice and attempt retry, and resume upload.
- Replacing the mocked Redis in `AttemptIntegrationTests` with the real container.

---

## Planning Contract

### Key Technical Decisions

- KTD1. **Real infrastructure and the real AI client, with no stub provider.** The journey and the live suite use the Compose PostgreSQL, Redis and LocalStack and whichever provider `.env` selects through `AI_CHAT_PROVIDER`. (session-settled: user-directed — chosen over adding a stub AI provider for the end-to-end check: LocalStack and the real Gemini/OpenAI clients already exist.)
- KTD2. **The live suite is opt-in and stays out of CI.** (session-settled: user-approved — chosen over adding a Gemini or OpenAI key as a GitHub Actions secret: CI holds only `GITHUB_TOKEN`, and paid, provider-dependent calls would cost money and fail CI on provider outages.)
- KTD3. **OpenAI generation stays `gpt-4.1-mini`.** When `.env` selects `openai`, nothing in this work changes `OpenAiClient.MODEL`. (session-settled: user-directed — chosen over any other OpenAI model: the user's model and cost limit.)
- KTD4. **One scenario module, two targets.** The shared scenarios move out of `apps/web/tests/mocks.test.ts` into a module that registers them for a target with two members:
  - `base`: a URL prefix, empty for the fake.
  - `settle(jobIds)`: on the fake, it advances the simulated clock. On a real API, it polls `GET /api/jobs/{id}` until every job is terminal. Then, if any job ended `FAILED`, it fails the scenario with that job's ID and `error.code`, labelled as a runtime failure rather than a contract disagreement.

  `mocks.test.ts` runs the module against MSW, and a new `apps/web/tests/liveApi.test.ts` runs it against a real address. Drift is only caught when both targets run the same assertions. `apiRequest` prepends `API_BASE_URL`, which is empty by default, so an absolute address passes through with no change to configuration.
- KTD5. **`LIVE_API_URL` both enables and addresses the live suite.** When it is set, the suite runs against that origin, for example `http://127.0.0.1:3000` through nginx. When it is unset, the suite is skipped. This is the same opt-in shape as `SUPABASE_LIVE_SMOKE` in `SupabaseLiveSmokeTests`, with one variable.
- KTD6. **Shared assertions hold on a persistent, shared database.** The local user's data piles up across runs. So each shared scenario makes its pasted texts unique per run and asserts that lists contain or omit specific items, rather than comparing whole lists. Fake-only scenarios keep their exact assertions.
- KTD7. **The journey runs on `docker compose --profile app up --build` from this worktree.** The web image already defaults to `VITE_API_MOCKS=off` in `apps/web/Dockerfile`. Compose names the project after the worktree directory (`frontend-backend-supabase`), so its volumes are separate from the original `ai_interview` bundle's, which PR #10 forbids migrating. This stack is already running from an older build. `up --build` recreates its API, worker and web on the final commit and keeps its volumes, and the stack stays running afterwards.
- KTD8. **Evidence goes in the new PR's Testing section, with no new document.** The journey proves the code at one commit. The live suite and its README note are the repeatable record. The PR body names finding #6 from PR #10 as closed by U2.
- KTD9. **The Redis test drives `RedisRequestGuard` through its public methods, on a Testcontainers `redis:7.4-alpine`.** That is the image Compose uses. The test builds a real `StringRedisTemplate`, so `STORE_IF_RESERVED_SCRIPT`, `RENEW_IF_RESERVED_SCRIPT` and `DELETE_IF_MATCHES_SCRIPT` execute in Redis rather than in a Kotlin imitation. The guard's `inFlightTtl` and `heartbeatInterval` are `internal`, so `build.gradle.kts` associates the `integrationTest` Kotlin compilation with `main`, as the `test` compilation already is.
- KTD10. **Both targets compare response shapes against one committed file.** Each shared scenario records the shape of every response it reads. Shape means the sorted key paths, recursing into nested objects; a list contributes the item the scenario created, not whatever comes first. The scenario compares that record with Vitest's `toMatchFileSnapshot` against one file under `apps/web/tests/`. The fake run owns updates to the file (`npm test -u`). The live run compares against the same file, so a renamed or missing field fails whichever target differs. Partial value assertions alone miss renamed fields that no scenario names.

### Assumptions

- R6 and U2 are included because the Redis mock is the remaining stand-in for real infrastructure and finding #6 on PR #10 asks for this test. The request itself named only the journey and the live suite.
- The journey runs on the chat provider the local `.env` selects, currently OpenAI (`gpt-4.1-mini`, per KTD3). Embeddings stay on Gemini's free tier.
- One live-suite run submits 11 AI jobs. The API allows 12 per client per 60 seconds (`REDIS_AI_RATE_LIMIT`), and the browser and the suite share that budget through nginx.
- `org.testcontainers:testcontainers`, which provides `GenericContainer`, is on the integration test classpath through the existing Testcontainers modules. It is in the Gradle cache.

---

## Implementation Units

### U1. Shared API scenarios and the opt-in live suite

- **Goal:** The same API scenarios run against the MSW fake on every `npm test`, and against a real API when `LIVE_API_URL` is set.
- **Requirements:** R3, R4, R5; KTD2, KTD4, KTD5, KTD6, KTD10.
- **Dependencies:** none.
- **Files:**
  - `apps/web/tests/apiScenarios.ts` (new)
  - `apps/web/tests/mocks.test.ts`
  - `apps/web/tests/liveApi.test.ts` (new)
  - the shared shape file under `apps/web/tests/` (new, written by the fake run)
  - `README.md` (one short run note next to the Compose section)
- **Approach:**
  1. Move the HTTP-only scenarios from `mocks.test.ts` into `apiScenarios.ts`, behind the target described in KTD4. Get job IDs from the responses: `jobId` on accepted jobs, and `activeJob.jobId` on practice sets and attempts.
  2. Record shapes per KTD10, including the settled job results for score, fit, suggestions, practice questions and feedback. The delete-impact scenario already runs those jobs, so this adds no AI calls.
  3. Keep these scenarios in `mocks.test.ts` as fake-only, per R5:
     - the store upload scenarios
     - the forced failure and retrying scenarios
     - the storage reload scenario
     - "refuses suggestions without other sources", which needs an empty database
  4. `liveApi.test.ts` skips its whole block unless `LIVE_API_URL` is set. Give its tests a generous timeout, because real AI jobs take seconds.
  5. Add the README note:
     - start the Compose `app` profile, then run the live suite with `LIVE_API_URL` pointing at the web origin;
     - one run uses 11 of the 12 AI jobs allowed per minute, so wait a minute between runs and treat a 429 as the rate limit, not drift.
- **Patterns to follow:**
  - The `post`, `get` and `errorOf` helpers in `apps/web/tests/mocks.test.ts`.
  - The opt-in gate in `src/integrationTest/kotlin/dev/jiaming/ai_interview/supabase/SupabaseLiveSmokeTests.kt`.
- **Test scenarios:** These run on both targets.
  - Pasting the same text again, with surrounding whitespace, returns `duplicate: true` and the first resume's ID and name.
  - Delete impact for a resume with one score, one fit, one suggestion set, one practice set and one attempt is exactly those counts plus `staleSuggestionSets: 1`. After the delete:
    - the resume, its practice set and its job return their `*_NOT_FOUND` codes;
    - the other resume's suggestions are stale;
    - history no longer lists the deleted resume or set and still lists the other resume.
  - A new practice set returns `GENERATING` with no questions and a `PRACTICE_QUESTIONS` job. Once settled, it is `READY` with 3 to 8 questions that each carry a rationale, and posting the same request returns the same set ID.
  - Attempts:
    - A second attempt identical to the first, apart from surrounding whitespace, returns 409 `ANSWER_UNCHANGED`.
    - A blank attempt returns 400 `ANSWER_EMPTY`.
    - A changed second attempt returns `number: 2` and `PENDING`. Once settled, both attempts are `SCORED`, and the second's `scoreDelta` equals the difference between the two feedback scores.
  - Changing a scored resume's job title marks its latest score stale.
  - Every response and job result these scenarios read matches the committed shape file.
  - On the fake, renaming one field in a fixture makes the shape comparison fail. This is a manual check, reverted afterwards.
  - With `LIVE_API_URL` unset, the live block is skipped, and `npm test` passes with no backend running.
- **Verification:**
  - `npm test` passes, the live suite is reported as skipped, and every scenario that moved still runs against the fake.
  - The live suite passes against the running Compose stack (U3).

### U2. RedisRequestGuard against a real Redis

- **Goal:** The idempotency scripts run in a real Redis in the integration suite, which closes finding #6 on PR #10.
- **Requirements:** R6; KTD9.
- **Dependencies:** none.
- **Files:**
  - `src/integrationTest/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuardIntegrationTests.kt` (new)
  - `build.gradle.kts` (associate the `integrationTest` compilation with `main`, per KTD9)
- **Approach:**
  1. Start a Testcontainers Redis.
  2. Build a `LettuceConnectionFactory` and a `StringRedisTemplate` against it.
  3. Construct `RedisRequestGuard` with idempotency enabled and short TTL and heartbeat settings, so the expiry paths run in seconds.
  4. Set the `Idempotency-Key` header through `RequestContextHolder`, as the unit test does.
- **Patterns to follow:**
  - The request-context setup in `src/test/kotlin/dev/jiaming/ai_interview/common/RedisRequestGuardTests.kt`.
  - The `@Testcontainers(disabledWithoutDocker = true)` container lifecycle in `src/integrationTest/kotlin/dev/jiaming/ai_interview/jobs/QueueAndStorageLocalStackIntegrationTests.kt`.
- **Test scenarios:**
  - The same key and payload a second time replays the stored response without running the work again.
  - The same key while the first request is still running returns the retryable 503.
  - The same key with a different payload is rejected, as the unit test expects.
  - A first request that throws releases its key, so a retry runs the work.
  - A request whose work outlives the initial TTL keeps its reservation, because the heartbeat renews it, and a concurrent retry still gets the 503.
  - A stale owner whose reservation expired and was taken by a successor cannot overwrite or delete the successor's stored response.
- **Verification:** `./gradlew integrationTest` passes with the new class, and the class is skipped without Docker.

### U3. Mocks-off journey on the Compose stack

- **Goal:** Evidence that the full journey and the live suite pass on the real local stack.
- **Requirements:** R1, R2; KTD1, KTD3, KTD7, KTD8.
- **Dependencies:** U1.
- **Files:** none in the repository. The evidence goes in the new PR's Testing section (KTD8).
- **Approach:**
  1. Check the six container names against the stop conditions.
  2. Rebuild and start the `app` profile per KTD7, and wait for the API, worker and web health checks.
  3. Run the R1 journey in the browser at `http://127.0.0.1:3000`. Reload once while a job is running, and use browser back once.
  4. Wait at least 60 seconds after the journey's last AI action. Then run the live suite with `LIVE_API_URL=http://127.0.0.1:3000`.
  5. Record the R2 evidence, citing the 2026-10-01 run by date. Leave the stack running, as it was before.
- **Execution note:** This is a runtime smoke. The proof is the browser run and the live-suite result, not new unit tests.
- **Test scenarios:** Test expectation: none -- this unit verifies the running stack with the U1 suite and a browser journey.
- **Verification:**
  - Every R1 step passes with no console errors and no request served by MSW.
  - The live suite passes.
  - The recorded evidence names the commit and the provider.

---

## Verification Contract

| Gate | Command | Applies to |
|---|---|---|
| Frontend lint | `npm run lint` in `apps/web` | U1 |
| Frontend types | `npm run typecheck` in `apps/web` | U1 |
| Frontend tests, live suite skipped | `npm test` in `apps/web` on Node 22 | U1 |
| Live suite | `LIVE_API_URL=http://127.0.0.1:3000 npm test -- liveApi` in `apps/web`, with the Compose `app` profile running | U1, U3 |
| Backend | `./gradlew check --no-daemon`, which includes `integrationTest` | U2 |
| Journey | Browser on `http://127.0.0.1:3000`, built from this branch with mocks off | U3 |

## Definition of Done

- All gates pass, and the live suite has passed at least once against the Compose stack built from the final commit.
- The new PR's Testing section carries the R2 evidence, and its body names finding #6 from PR #10 as closed by U2.
- No secret value appears in the diff, the PR body or the test output that was shared.
- No leftover code from approaches that were tried and dropped remains in the diff.
