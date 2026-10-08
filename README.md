# AI Interview Coach

An AI-powered interview coach for technical candidates. Upload a resume, add an optional job description, and receive evidence-grounded resume assessment, tailored interview questions, and feedback on practice answers.

Built as a reliable modular monolith: Spring Boot handles the API and asynchronous jobs, while a React single-page app (Vite, TanStack Router) provides the candidate workflow.

## What it does

| Capability | How it works |
| --- | --- |
| Resume ingestion | Accepts PDF, DOC, DOCX, TXT, and Markdown; Apache Tika/PDFBox extracts and normalizes text. |
| Grounded assessment | Scores technical depth, impact, clarity, relevance, and ATS alignment using Resume and JD evidence. |
| Interview practice | Generates role-specific questions at Warmup, Core, and Deep Dive difficulty, then evaluates submitted answers. |
| Reliable AI jobs | Runs extraction, analysis, and feedback asynchronously with PostgreSQL leases, checkpoints, retries, and a DLQ. |
| Document-level RAG | Uses section-aware chunking, pgvector retrieval, per-source Resume/JD recall, deterministic RRF ranking, and source evidence IDs. |

## Architecture

```mermaid
flowchart TB
  Candidate["Candidate"] --> Web["React web app"]
  Web --> API["Spring Boot API\napi or all mode"]
  API --> Guard["Redis request guard\nrate limit + idempotency"]

  API -->|"upload original file"| S3["S3 / LocalStack"]
  API -->|"resolve supplied IDs or text"| Refs["Document reference resolver\nready resources + content hashes"]
  Refs --> Store[("PostgreSQL + pgvector\ndocuments · jobs · checkpoints · effects")]
  API -->|"create or reuse job in one transaction"| Store
  API -->|"after commit: jobId only"| Queue["SQS / LocalStack"]

  Queue --> Worker["Spring Boot worker\nworker or all mode"]
  Worker -->|"claim PostgreSQL lease"| Store
  Worker --> Handler{"Typed job handler"}

  Handler --> Extract["RESUME_EXTRACTION\nread · extract · normalize · chunk"]
  Extract -->|"read pending file"| S3
  Extract -->|"mark resume ready"| Store

  Handler --> Coaching["RESUME_SCORE · JOB_FIT · EXPERIENCE_SUGGESTIONS\nPRACTICE_QUESTIONS · EXPERIENCE_SPLIT"]
  Handler --> Feedback["ANSWER_FEEDBACK\nscore practice answer"]
  Coaching -->|"strict document references"| Refs
  Feedback -->|"strict document references"| Refs
  Coaching --> RAG["RAG index + context builder\nsection-block-v3 · per-source RRF"]
  Feedback --> RAG
  RAG <-->|"embeddings and retrieval"| Store
  RAG -->|"grounded context"| Gemini["Gemini 3.6 Flash\nstructured generation"]
  Coaching -->|"scores, fit, suggestions and questions"| Gemini
  Feedback -->|"answer feedback"| Gemini
  Gemini -->|"structured output"| Worker

  Worker -->|"stages · checkpoints · results"| Store
  Web -->|"poll job status"| API
  API -->|"return result or error"| Store
```

### Request flow

1. The API rate-limits and deduplicates requests, then resolves a ready resume and optional job description to validated, content-hashed document references.
2. It atomically creates or reuses a durable job in PostgreSQL; only after that transaction commits does the dispatcher send its `jobId` to SQS.
3. A worker claims the PostgreSQL lease and invokes the handler for its job type: extraction, resume score, job fit, experience suggestions, practice questions, experience split, or answer feedback. It records stages and reusable checkpoints as it runs.
4. AI handlers reload their document references strictly, build or reuse RAG indexes, retrieve Resume and JD evidence independently, and merge candidates with deterministic Reciprocal Rank Fusion (RRF).
5. Gemini receives selected, traceable evidence and returns structured output; the worker persists effects and results while the frontend polls job status until it is complete or failed.

PostgreSQL is the source of truth for job state. SQS wakes workers; it does not carry document content or determine job completion.

## RAG design

- **Structured chunks:** Experience, Research Experience, and Projects preserve blank-line-separated entries; large chunks use natural boundaries with overlap.
- **Per-source retrieval:** Resume and Job Description indexes are retrieved independently, so a strong Resume match cannot crowd out JD evidence.
- **Deterministic fusion:** All query/source candidates are collected before ranking with RRF (`rrf-k=15`), using stable tie-breaks.
- **Evidence quality controls:** JD evidence is reserved when available, section diversity is capped, and overlapping neighboring chunks are suppressed.
- **Prompt-safe snippets:** section prefixes improve embedding retrieval but original persisted text is restored before being sent to Gemini or returned as evidence.

## Tech stack

| Layer | Technology |
| --- | --- |
| Backend | Java, Spring Boot, Gradle, JdbcTemplate, Flyway |
| Frontend | React, Vite, TanStack Router and Query, TypeScript |
| AI | Gemini 3.6 Flash, structured JSON generation |
| Retrieval | PostgreSQL, pgvector, Gemini embeddings |
| Async workflow | AWS SQS + DLQ, PostgreSQL leases/checkpoints |
| Storage | S3-compatible storage via LocalStack |
| Operational guardrails | Redis rate limits and idempotency keys |
| Testing | JUnit 5, Mockito, MockMvc, Testcontainers, Vitest |

## Quick start

### 1. Configure local environment

```bash
cp .env.example .env
```

Set `DATABASE_URL`, `DATABASE_USERNAME`, and `DATABASE_PASSWORD` for a PostgreSQL instance with pgvector. The default is `interview_guide` on `localhost:5432`.

To enable Gemini locally, add the following to the untracked `.env` file:

```properties
GEMINI_API_KEY=your-api-key
AI_CHAT_MODEL=google-genai
AI_EMBEDDING_MODEL=google-genai
GEMINI_CHAT_MODEL=gemini-3.6-flash
GEMINI_THINKING_LEVEL=medium
GEMINI_MAX_OUTPUT_TOKENS=4096
GEMINI_EMBEDDING_MODEL=gemini-embedding-001
RAG_EMBEDDING_DIMENSIONS=1024
RAG_CHUNK_SCHEMA=section-block-v3
```

Never commit API keys or a populated `.env` file.

### 2. Start local dependencies

```bash
docker compose up -d
```

This starts LocalStack on `localhost:4566` and Redis on `localhost:6380` by default. LocalStack initializes the S3 bucket and the `ai-interview-jobs` / `ai-interview-jobs-dlq` queues.

To run an isolated PostgreSQL container too:

```bash
docker compose --profile managed-postgres up -d
```

The managed PostgreSQL service is exposed on `localhost:55432` by default.

### 3. Start the application

In one terminal:

```bash
./gradlew bootRun
```

In another terminal:

```bash
cd apps/web
npm install
npm run dev
```

Open `http://127.0.0.1:3000`. The backend status endpoint is `http://127.0.0.1:8080/api/status`.

### Or run the full stack in Docker

One command builds and starts PostgreSQL, Redis, LocalStack, the API, the worker and the web app:

```bash
docker compose --profile app up --build
```

The API and worker read `.env` for `GEMINI_API_KEY` and the Gemini settings from step 1; Compose points them at its own PostgreSQL, Redis and LocalStack, so the address settings in `.env` are ignored here. Open `http://127.0.0.1:3000`; nginx forwards `/api` to the API, so the API and worker publish no ports. Every published port (web `3000`, PostgreSQL `55432`, Redis `6380`, LocalStack `4566`) binds to `127.0.0.1` only. Stop with `docker compose --profile app down`.

With this stack running, check that the real API still answers the way the web app's mock API does. The same API scenarios that `npm test` runs against the mocks run against the stack, and a response shape that differs from `apps/web/tests/apiShapes.json` fails:

```bash
cd apps/web && LIVE_API_URL=http://127.0.0.1:3000 npm test -- liveApi
```

This makes real AI calls. One run submits about 14 AI jobs, more than the 12 the API allows per client per minute, so a rate-limited request waits out the window once and retries; a full run takes a few minutes. A `429` that still fails the run means another client shared the budget. The suite never runs in CI, and `npm test` skips it unless `LIVE_API_URL` is set.

For the isolated Supabase-backed full stack, use the separate Compose project and ports in [the migration runbook](docs/supabase-migration.md). It keeps the bundled PostgreSQL volume available for the local stack.

## Container images

The backend image accepts the same environment variables as a local Spring Boot
run. `JOB_RUNTIME_MODE=api` and `JOB_RUNTIME_MODE=worker` can therefore use the
same image when the API and worker are deployed separately. Create `.env` from
`.env.example` and configure `GEMINI_API_KEY` before starting the API container.

Build both images from the repository root:

```bash
docker build -t ai-interview-api:local .
docker build -t ai-interview-web:local apps/web
```

For a manual local container run, first start the managed dependencies:

```bash
docker compose --profile managed-postgres up -d
```

Then run the API. These `host.docker.internal` addresses let the API container
reach the dependencies that Compose exposes on the host; they work with Docker
Desktop on macOS and Windows.

```bash
docker run --rm --name ai-interview-api -p 8080:8080 --env-file .env \
  -e JOB_RUNTIME_MODE=all \
  -e DATABASE_URL=jdbc:postgresql://host.docker.internal:55432/ai_interview \
  -e DATABASE_USERNAME=ai_interview \
  -e DATABASE_PASSWORD=ai_interview \
  -e REDIS_HOST=host.docker.internal \
  -e REDIS_PORT=6380 \
  -e S3_ENDPOINT=http://host.docker.internal:4566 \
  -e SQS_ENDPOINT=http://host.docker.internal:4566 \
  ai-interview-api:local
```

Build the web image with the browser-visible API address, then serve it:

The web image serves the app and forwards `/api` to `API_UPSTREAM`
(default `http://api:8080`), so the browser only ever talks to one origin:

```bash
docker build -t ai-interview-web:local apps/web
docker run --rm --name ai-interview-web -p 3000:3000 \
  -e API_UPSTREAM=http://host.docker.internal:8080 \
  ai-interview-web:local
```

Add `--build-arg VITE_API_MOCKS=all` to build a demo image that runs entirely on
mock data, without the backend.

Open `http://127.0.0.1:3000`, then verify the backend independently with
`curl http://127.0.0.1:8080/api/status`. On Linux, replace
`host.docker.internal` with a host gateway address or place the API and its
dependencies on a shared Docker network.

## Minikube and Helm

The local Helm chart at `deploy/ai-interview` deploys the web app, one API pod,
two worker pods, PostgreSQL with pgvector, Redis, and LocalStack. It is a
development chart: the default PostgreSQL password is intentionally local-only,
while the Gemini key must be supplied separately as a Kubernetes Secret.

Load the Docker images into the running Minikube cluster, create the Secret,
then install the chart:

```bash
minikube image load ai-interview-api:local
minikube image load ai-interview-web:local

kubectl create namespace ai-interview --dry-run=client -o yaml | kubectl apply -f -
kubectl -n ai-interview create secret generic ai-interview-gemini \
  --from-literal=GEMINI_API_KEY='your-api-key'

helm upgrade --install ai-interview ./deploy/ai-interview \
  --namespace ai-interview
```

Check the release and wait for all pods to become ready:

```bash
helm -n ai-interview status ai-interview
kubectl -n ai-interview get pods
kubectl -n ai-interview rollout status deployment/ai-interview-api
kubectl -n ai-interview rollout status deployment/ai-interview-worker
kubectl -n ai-interview rollout status deployment/ai-interview-web
```

The web service forwards `/api` to the API service, so one port-forward is
enough during this local phase:

```bash
kubectl -n ai-interview port-forward service/ai-interview-web 3000:3000
```

Open `http://127.0.0.1:3000`. TLS in front of the web service is the next step
before exposing this deployment publicly.

## Argo CD GitOps

After Argo CD is installed, it can render and continuously reconcile the same
Helm chart from Git. The Application manifest tracks the
`docker-kubernetes-gitops` branch, deploys into `ai-interview`, and uses the
same Helm release name. Its automated policy self-heals drift and prunes
resources removed from the chart.

The chart deliberately references an existing `ai-interview-gemini` Secret;
do not commit the Gemini API key to Git. Create that Secret in Minikube before
the first Argo CD sync, as shown in the Helm section above.

First push this branch so Argo CD can fetch the chart, then bootstrap the
Application:

```bash
git push -u origin docker-kubernetes-gitops
kubectl apply -f deploy/argocd/ai-interview-application.yaml
kubectl -n argocd get application ai-interview
kubectl -n argocd wait --for=jsonpath='{.status.sync.status}'=Synced \
  application/ai-interview --timeout=5m
kubectl -n argocd wait --for=jsonpath='{.status.health.status}'=Healthy \
  application/ai-interview --timeout=5m
```

After that initial bootstrap, change `deploy/ai-interview` through Git commits
and pushes. Do not run `helm upgrade` for this release: Argo CD is the owner.

## GitHub Actions image publishing

The `Build and publish images` workflow first runs `./gradlew check`, then
installs the frontend dependencies and runs its lint, TypeScript, Vitest, and
production-build checks. Only when this test gate passes does it build
multi-architecture API and web images, push them to GitHub Container Registry,
and commit the immutable source commit SHA to `deploy/ai-interview/values.yaml`.
Argo CD then observes that values commit and rolls out the corresponding images.

The workflow runs for pushes to `docker-kubernetes-gitops`; its values-only
commit is ignored by the trigger, preventing a rebuild loop. It uses the
repository's `GITHUB_TOKEN`, with package-write permission for publishing and
contents-write permission only for the values update.

For Minikube to pull the resulting GHCR images, make the two GHCR packages
public in GitHub, or create a `ghcr-pull` image-pull Secret and add it to
`imagePullSecrets` in `deploy/ai-interview/values.yaml`:

```yaml
imagePullSecrets:
  - name: ghcr-pull
```

The initial `:local` image references remain in the values file until the first
successful GitHub Actions run writes the real image SHAs.

## Supabase runtime

The Supabase profile keeps application writes, transactions, jobs, and vectors on
JDBC; only job-status polling uses the PostgREST client. The client is enabled by
the `supabase` profile and disabled in worker-only mode. It uses a five-second
timeout, no retries or redirects, and closes at application shutdown.

Keep `.env.supabase` private for the standalone migration/bootstrap command.
Spring serving processes do not import it. The Compose Supabase profile maps an
explicit runtime allowlist, gives the SDK key only to the API, and never sends
migration credentials to API, worker, or web. Keep Gemini settings in the
separate ignored `.env.ai-serving` file; never copy the general `.env` into this
stack. Use the full [Supabase migration runbook](docs/supabase-migration.md) for
startup and credential handling.

## Runtime modes

The same Spring Boot build supports API and worker deployment independently:

```properties
JOB_RUNTIME_MODE=all     # local default: API and worker
JOB_RUNTIME_MODE=api     # submit/query jobs only
JOB_RUNTIME_MODE=worker  # consume jobs only
```

Workers use SQS long polling and PostgreSQL-backed leases. Database retries use full jitter; terminal failures are routed to the DLQ.

## API overview

| Endpoint | Purpose |
| --- | --- |
| `POST /api/resumes` | Upload a resume and create an extraction job. |
| `POST /api/practice-sets/{setId}/questions/{questionId}/attempts` | Submit an answer attempt for feedback. |
| `GET /api/jobs/{jobId}` | Poll job status, stage, attempts, result, and error. |

The [frontend API contract](docs/api/frontend-api-contract.md) lists every endpoint.

Mutation endpoints accept an optional `Idempotency-Key`. Redis handles short-lived HTTP idempotency and rate limits; PostgreSQL allows one running job per resource, so a second submit returns the job already in progress.

## Verification

```bash
./gradlew check
./gradlew integrationTest

cd apps/web
npm test -- --run
npm run typecheck
npm run build
```

Testcontainers covers PostgreSQL/pgvector and LocalStack integration scenarios. Frontend tests use Vitest.

## Local destructive reset

To remove every record in the local `interview_guide` database and this project's LocalStack S3/SQS state, first stop the API and worker, then run:

```bash
RESET_CONFIRM=DROP_INTERVIEW_GUIDE \
RESET_DATABASE_NAME=interview_guide \
RESET_DATABASE_OWNER=ai_interview \
RESET_DATABASE_ADMIN_URL='postgresql://…@localhost:5432/postgres' \
./scripts/reset-local-environment.sh
```

The script refuses non-local hosts, databases other than `interview_guide`, and resources outside this project. It is irreversible: resumes, jobs, chunks, vectors, and interview history must be recreated.

## Further documentation

See [project design](docs/project-design.md) for the architecture, domain model, API surface, and implementation details.

For the opt-in Supabase profile, schema bootstrap, deployment secrets, and rollback, see [Supabase migration](docs/supabase-migration.md).
