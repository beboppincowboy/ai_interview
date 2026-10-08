# Supabase development migration

This migration starts with an empty project, mjzycnjhtwyqcblwbjvy. It copies no existing records or embeddings. JDBC retains writes, transactions, leases, checkpoints, document persistence and Spring AI vector access. Only job-status polling uses the Supabase Kotlin SDK in the supabase profile. Local PostgreSQL remains supported. The fixed local user remains a development boundary; public multi-user deployment requires the separate authentication work.

## Credentials and certificate

Use the project's [API keys](https://supabase.com/dashboard/project/mjzycnjhtwyqcblwbjvy/settings/api-keys) for a backend secret key starting sb_secret_. A personal access token starting sbp_ is not an SDK key. Keep the backend key out of browsers and chat.

Use [database settings](https://supabase.com/dashboard/project/mjzycnjhtwyqcblwbjvy/database/settings) for the project database password and root certificate. The migration password is the current project password. Choose a separate new password for the runtime role. Get the session-pooler host from the dashboard's Connect panel (session mode, port 5432); do not use transaction mode or disable certificate verification.

Create a private, ignored .env.supabase at the repository root. It is Java Properties, with unquoted values, not a shell script; do not source it. Escape Java Properties backslashes in passwords if needed. Use these fields:

~~~properties
SUPABASE_URL=https://mjzycnjhtwyqcblwbjvy.supabase.co
SUPABASE_SECRET_KEY=<backend sb_secret_ key>
SUPABASE_SSL_ROOT_CERT=/absolute/path/to/.supabase/prod-ca-2021.crt
SUPABASE_MIGRATION_URL=jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&currentSchema=public,extensions
SUPABASE_MIGRATION_USERNAME=postgres.mjzycnjhtwyqcblwbjvy
SUPABASE_MIGRATION_PASSWORD=<current project database password>
DATABASE_URL=jdbc:postgresql://<session-pooler-host>:5432/postgres?sslmode=verify-full&currentSchema=public,extensions
DATABASE_USERNAME=ai_interview_runtime.mjzycnjhtwyqcblwbjvy
DATABASE_PASSWORD=<new runtime password>
DATABASE_MAX_POOL_SIZE=4
REDIS_KEY_PREFIX=ai-interview:supabase-mjzycnjhtwyqcblwbjvy:
S3_BUCKET=ai-interview-supabase-mjzycnjhtwyqcblwbjvy
SQS_QUEUE_NAME=ai-interview-supabase-mjzycnjhtwyqcblwbjvy-jobs
SQS_DLQ_NAME=ai-interview-supabase-mjzycnjhtwyqcblwbjvy-jobs-dlq
~~~

Keep this file mode 600. Both `.env.supabase` and `.supabase/` are ignored. The standalone migration runner reads this file directly; Spring serving processes do not import it. The Supabase Compose overlay uses it only for interpolation and explicitly passes runtime DB settings to API and worker, while the SDK key goes only to API. It passes no migration settings to API, worker, or web. Do not add this file as a service `env_file` or mount it into a serving container.

## Initialize without starting application processes

Before initialization inspect the selected target and confirm it contains no application objects. The runner also rejects initial application objects without owned Flyway history. Flyway is the sole migration system: preserve applied V1–V8 byte-for-byte and checksum-validate them; V9 additively expands the restricted job-status view and its backend column grants. Provider-created public objects trigger explicit baseline version 0, so V1 still executes. Extensions resolve through public,extensions.

~~~sh
./gradlew supabaseMigrate --no-daemon
./gradlew supabaseMigrate --args=bootstrap-runtime --no-daemon
~~~

The second command validates existing migrations, creates or updates the restricted ai_interview_runtime role, and grants application DML and vector access. It rejects existing privileged roles, role memberships, and application ownership. It does not start Spring, API servers, or schedulers. Supabase API and worker processes do not migrate automatically. Existing migrations are checksum-validated; no clean operation is allowed.

In [Data API settings](https://supabase.com/dashboard/project/mjzycnjhtwyqcblwbjvy/integrations/data_api/settings), keep the Data API enabled and set Exposed schemas to only ai_interview_api. Remove public, graphql_public, and any application schema. V8 creates the job-status view; V9 preserves its existing columns and appends the frontend contract fields, while retaining service_role-only view access and narrow source-column grants. Anon and authenticated receive no access. Do not apply broad anon/authenticated grants from generic custom-schema examples. Application tables and public.vector_store must remain unexposed.

For host-run startup, set `SPRING_PROFILES_ACTIVE=supabase` and provide the shared runtime settings through the process environment or a secure environment manager. Set `SUPABASE_SECRET_KEY` only in the API process. Do not import `.env.supabase`; it includes bootstrap credentials. Provision the new S3/SQS resources and Redis namespace before submitting work.

Workers require JDBC and CA settings but no SDK secret key. Preserve the 1,024-dimensional vector column, HNSW cosine index and gemini-embedding-001 model.

For Supabase Compose, keep the serving-time AI settings in a separate file. To generate results with OpenAI instead, also set `AI_CHAT_PROVIDER=openai` and `OPENAI_API_KEY` there (the model is `gpt-4.1-mini` unless `OPENAI_MODEL` names another; a reasoning model such as `gpt-6-luna` also needs `OPENAI_REASONING_EFFORT`, for example `none`); embeddings stay on Gemini. Copy the API key, Gemini model IDs and embedding dimension from `.env` without displaying them, then explicitly select the Gemini provider; the local `.env` may set both AI selectors to `none`:

~~~sh
if [ ! -e .env.ai-serving ]; then
  umask 077
  grep -E '^(GEMINI_API_KEY|GEMINI_CHAT_MODEL|GEMINI_EMBEDDING_MODEL|RAG_EMBEDDING_DIMENSIONS)=' .env > .env.ai-serving
  printf '%s\n' 'AI_CHAT_MODEL=google-genai' 'AI_EMBEDDING_MODEL=google-genai' >> .env.ai-serving
fi
chmod 600 .env.ai-serving
docker compose --env-file .env.supabase --env-file .env.ai-serving \
  -f docker-compose.yml -f docker-compose.supabase.yml \
  --profile supabase up --build
~~~

If you do not already have these settings, create `.env.ai-serving` from `.env.ai-serving.example`, set the Gemini API key and model IDs, retain `google-genai` for both AI selectors, and keep the file mode 600. This ignored file contains only the Gemini key, provider/model overrides and embedding dimension; never pass the full `.env`, which also has old local DB, S3 and SQS settings. Compose interpolates `.env.supabase` and `.env.ai-serving` but sends only the explicit service environment allowlist. Migration URL, username and password are not passed to API, worker or web; the SDK key is API-only. Local Compose continues to use `.env` created from `.env.example` with `docker compose --profile app up --build`.

The overlay uses host ports 13000 (web), 14566 (LocalStack), and 16380 (Redis) by default. It does not start bundled PostgreSQL, and its project-scoped Redis and LocalStack volumes are separate from the local stack. Stop it with the same files and `down` instead of `up --build`.

## Helm configuration

Build and publish the backend image from this migration branch first. Replace the example API and worker image tags with that immutable commit tag; the chart defaults predate this migration. The default chart still deploys bundled PostgreSQL. For Supabase use deploy/ai-interview/values-supabase.example.yaml and create referenced secrets in the intended namespace. A private runtime.env must contain only DATABASE_URL, DATABASE_USERNAME, DATABASE_PASSWORD; a private sdk.env must contain only SUPABASE_SECRET_KEY. Never create a pod secret from the complete .env.supabase file, which contains migration administrator credentials.

~~~sh
kubectl -n <namespace> create secret generic ai-interview-supabase-runtime --from-env-file=/private/path/runtime.env
kubectl -n <namespace> create secret generic ai-interview-supabase-sdk --from-env-file=/private/path/sdk.env
kubectl -n <namespace> create secret generic ai-interview-supabase-ca --from-file=ca.crt=/absolute/path/to/.supabase/prod-ca-2021.crt
helm lint deploy/ai-interview
node scripts/check-supabase-chart.cjs
helm lint deploy/ai-interview -f deploy/ai-interview/values-supabase.example.yaml
helm template interview deploy/ai-interview -f deploy/ai-interview/values-supabase.example.yaml
~~~

API and worker receive only three named runtime DB secret keys and a read-only CA mounted at /etc/supabase/ca.crt. Only API receives the SDK key. Missing external DB, SDK or CA references and contradictory bundled-PostgreSQL/Supabase settings fail rendering. Disabling bundled PostgreSQL removes its workload/service/development secret and its dependency wait. The PostgreSQL PVC remains rendered when persistence is enabled and has Helm's keep policy; preserve it for rollback.

At pool size 4, one API and two workers permit 12 application connections. Default rolling-update surges can permit five processes, or 20 connections. Verify the project's Supavisor per-role session-pool capacity, PostgreSQL reserved connections, Supabase services and deployment overlap before deploying. Observing max_connections alone is insufficient. Keep old submissions/workers stopped during this controlled development cutover.

## Cutover verification and rollback

1. Save the complete old configuration bundle, Helm values, secret references and resource names. Preserve old database volumes, queues/DLQ, Redis state and S3 objects.
2. Stop old submissions and consumers; let active work finish where possible. Do not mix old queues or cached workflow state with the empty database.
3. Start the new environment using the example's new Redis prefix, SQS/DLQ names and S3 bucket. Do not run scripts/reset-local-environment.sh for cutover or rollback.
4. Clear only this application's saved browser workflow keys: ai-interview:job:resume, ai-interview:job:analysis, ai-interview:job:feedback, and their session-storage <base>:context:<generation> keys. Do not clear all browser storage.
5. Verify upload, extraction, analysis, question generation, feedback and reload recovery. Confirm JDBC-written transitions appear through SDK polling; missing jobs remain JOB_NOT_FOUND, upstream failures become sanitized 503 and browser polling retains active work. Verify vector retrieval respects index IDs and claim versions.
6. Verify anonymous/authenticated Data API reads fail and backend SDK reads succeed. Verify runtime JDBC cannot create application tables. Repeat permission checks through the actual Data API after changing exposed schemas.
7. Rehearse rollback: stop new submissions and workers, then restore the complete old DB/profile/Redis/SQS/DLQ/S3 configuration bundle and start the old environment. Verify old saved workflows against the old resources. Retain new Supabase data separately; rollback does not merge post-cutover writes.

Watch readiness, SDK 503 rates, job failures/retries, queue age, JDBC pool exhaustion and cross-environment resource use during the first complete workflow and reload. Roll back for persistent unexpected polling failures, failed durable transitions, incorrect ownership, or namespace mixing. The development operator owns validation. Do not switch production traffic based only on local test results.

## Verification status (2026-09-30)

Verified on the selected live project: certificate-verified session-pooler connection; Flyway baseline 0 and V1–V9, with V1–V8 preserved; vector(1024); HNSW vector_cosine_ops index; security_invoker view; restricted runtime role; runtime JDBC reads; application-table DDL rejection (42501). Effective database grants deny anon/authenticated view reads and allow service_role reads. V9 migration and runtime-role bootstrap completed; the SDK smoke passed on retry after a brief runtime-password propagation delay.

Before integration, the Supabase source branch reported passing backend/build checks (184 unit and 38 integration tests), frontend polling (8 tests), typecheck, lint, and focused bootstrap checks. Those counts are historical and do not describe the integrated branch. On the integrated branch, `./gradlew check --no-daemon`, the U16 local and Supabase Compose configuration and isolation checks, the chart contract check, Helm lint/render, targeted frontend API/config tests, typecheck, and lint passed. The isolated Supabase Compose stack also built and started with healthy API, worker, web, Redis, and LocalStack containers. The web origin returned HTTP 200 for `/` and `/api/status`; a missing job returned HTTP 404 `JOB_NOT_FOUND` through SDK-backed polling. Inspection of the running containers' environment key names confirmed that no serving container received migration credentials and only API received the SDK key. This is a startup smoke check; U17's complete user workflow and rollback rehearsal remain unverified.

The Data API now exposes only ai_interview_api: backend reads return HTTP 200, while public, ai_interview_app and graphql_public requests return HTTP 406/PGRST106. The opt-in live SDK smoke passed JDBC-written status transitions, nested result JSON, nullable/error mapping, input-reference fallback, wrong-owner filtering, missing jobs, and live permission assertions. Its random terminal-job/user fixtures were deleted afterward. The configured backend secret key works.

Run the live check explicitly against the selected development project (ordinary checks skip it):

~~~sh
SUPABASE_LIVE_SMOKE=true ./gradlew integrationTest --tests '*SupabaseLiveSmokeTests' --rerun-tasks --no-daemon
~~~

The following 2026-10-01 section supersedes this earlier verification snapshot.

## Verification status (2026-10-01, U17)

Live project mjzycnjhtwyqcblwbjvy: before migrating it held baseline 0 and V1–V11 with one user row and no resumes, jobs or vectors, so no backup was taken (owner's decision). The standalone runner then applied V12–V17 with checksums validated, and `bootstrap-runtime` refreshed the runtime role. The opt-in live smoke failed once while the new runtime password propagated, then passed.

Local Compose, mocks off, through the browser: paste, score, target job, fit, experience, suggestions, practice questions, two answer attempts with a score delta, and history all completed and survived reloads and a full API/worker/web restart. A job claimed by a worker that was then restarted was redelivered after the 300-second visibility timeout and finished. Gemini `gemini-3.6-flash` returned sustained `503 UNAVAILABLE` (high demand) and the key is free-tier (5 requests/minute), so the AI steps after scoring ran with the optional OpenAI provider (`AI_CHAT_PROVIDER=openai`, gpt-4.1-mini); embeddings stayed on Gemini.

Supabase Compose (`--profile supabase`), mocks off, through the API the web app uses: the same journey completed against the live database, with SDK job polling showing every JDBC transition and an unknown job returning `404 JOB_NOT_FOUND`. Data survived an API/worker restart. The worker received neither the SDK key nor migration credentials; the API received only the SDK key.

Rollback rehearsal: with the Supabase stack's API, worker and web stopped, the old local environment kept serving its own data with none of the new Supabase rows merged in. Queues (`ai-interview-jobs` vs `ai-interview-supabase-jobs`, separate LocalStacks) and Redis prefixes were separate, so no old process can consume new work. On 2026-10-01, stopping only those new app processes left the old `/api/resumes` and `/api/history` responses byte-for-byte unchanged and reachable at HTTP 200; the new web origin was unavailable. After restarting the new app, both environments' saved resume and history responses matched their pre-stop digests, and both returned HTTP 200. Redis, LocalStack, and all volumes were preserved.

Direct Data API checks on 2026-10-01: anonymous `job_status` SELECT returned HTTP 401 / SQLSTATE 42501; a valid temporary authenticated user's SELECT returned HTTP 403 / SQLSTATE 42501; the backend secret-key SELECT returned HTTP 200. The temporary Auth user was deleted. The opt-in live SDK/JDBC smoke passed again after V18.

Connection capacity on 2026-10-01: the project's Database Settings page showed a shared Supavisor **Pool Size of 20** for each user/database combination and a 200-client pooler limit. A read-only SQL Editor query of `pg_stat_activity` reported `max_connections = 60`, 17 current connections, including 3 for `ai_interview_runtime`. The chart runs one API and two workers with Hikari maximum 4 and minimum idle 0, so the steady application ceiling is 12. Replacing **one pod at a time** raises it to at most 16, leaving four runtime-role pool slots; at the observed non-runtime baseline of 14 connections, the corresponding total is at most 30 of 60. Concurrent API and worker surges could raise the application ceiling to 20, consuming the entire per-role pool, so **do not roll out both Deployments together**. Upgrade the API image, wait until it is ready and its old pod has terminated, then upgrade the worker image and wait until both workers are ready. Recheck Pool Size, `pg_stat_activity`, replicas, and Hikari settings immediately before a real cutover; the 17-connection reading is a snapshot, not a guaranteed service reserve. [Supabase connection limits](https://supabase.com/docs/guides/database/connecting-to-postgres/pooling-and-limits) count Supabase services against the same PostgreSQL maximum.

## Legacy flow removal (V18, U13)

V18 removes the old analysis and interview flow from the application schema. It deletes `ANALYSIS` jobs and resume-backed `ANSWER_FEEDBACK` jobs together with their effects, keeps attempt-backed feedback jobs, narrows `background_job_effect_type_check` to `ANSWER_FEEDBACK`, `RESUME_SCORE`, `JOB_FIT`, `EXPERIENCE_SUGGESTIONS` and `PRACTICE_QUESTIONS`, and drops `question_embeddings`, `answer_embeddings`, `interview_answers`, `interview_questions`, `interview_sessions` and `resume_assessments` from `ai_interview_app`. The legacy `public.*` tables and `public.vector_store` (1,024 dimensions, HNSW cosine index, gemini-embedding-001) are untouched.

Apply it only to the integration environment, after stopping every API and worker that runs the old code, since they write the dropped tables. Then re-run the runtime-role bootstrap (KTD17) before starting the new API and worker:

~~~sh
./gradlew supabaseMigrate --no-daemon
./gradlew supabaseMigrate --args=bootstrap-runtime --no-daemon
~~~

V18 cannot be undone in place: the dropped tables and deleted jobs are gone, so rolling back to the old flow means restoring the preserved old environment. Status: applied to mjzycnjhtwyqcblwbjvy on 2026-10-01 with its consumers stopped (the legacy tables were empty and no legacy jobs existed), followed by `bootstrap-runtime`. The live smoke passed once the new runtime password propagated, and the full journey passed on the Supabase Compose stack at V18, including duplicate saves, a re-score kept in history, and further attempts with correct deltas.

## Spoken interviews (V19, V20)

V19 adds the private `voice_sessions` table, with the runtime-role grant guarded like V12–V15. V20 adds the per-run token mint count. Both are additive, so running consumers need not stop. Apply them with the same two commands, then turn voice on only through an untracked override (see `docs/runbooks/voice-development.md`).

Status: applied to mjzycnjhtwyqcblwbjvy on 2026-10-05 with the owner's confirmation (v18 → v20, checksums validated), followed by `bootstrap-runtime`. The live smoke passed on its first run. On the Supabase Compose stack with voice on:

- A typed voice run on a fresh practice set saved, and a repeated Save returned 200. The `VOICE_REPORT` job showed `PROCESSING/SCORING_ANSWER` then `SUCCEEDED/COMPLETED` through SDK polling. It finished too quickly for `QUEUED` to be observed.
- The saved report reopened through the API and in the browser (25/100, 2 of 5 answered) with no console errors, and it appears in history. An unknown job still returned `404 JOB_NOT_FOUND`.
- The application-endpoint Live proof passed 8/8 on `v1beta` with `gemini-3.8-live`. That covers synthetic speech, single-use and expired-start rejection, locked instruction and modality, and the draft discard, which shows the runtime role can delete voice drafts.

Real-microphone acceptance in Chrome and Safari remains human-run.
