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
