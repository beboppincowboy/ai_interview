# Domain Docs

How the engineering skills should consume this repo's domain documentation when exploring the codebase.

## Before exploring, read these

- **`CONCEPTS.md`** at the repo root: this repo's glossary. It plays the role other repos give `CONTEXT.md`; ce-compound also adds to it.
- **`docs/plans/`** and **`docs/solutions/`**: this repo's decision record (there is no `docs/adr/`). Read the plans and solutions that touch the area you're about to work in. `docs/api/frontend-api-contract.md` is the API contract.

Don't create a `CONTEXT.md`, `CONTEXT-MAP.md` or `docs/adr/` here. When `/domain-modeling` (reached via `/grill-with-docs` and `/improve-codebase-architecture`) resolves a term, it adds the entry to `CONCEPTS.md` in that file's existing format; when it records a hard-to-reverse decision, it writes a dated file under `docs/plans/`.

## File structure

This repo is single-context:

```
/
├── CONCEPTS.md          ← glossary
├── docs/plans/          ← dated plans and specs (decisions)
├── docs/solutions/      ← solved problems and learnings
├── docs/api/            ← API contract
├── src/                 ← Kotlin API
└── apps/web/            ← React web app
```

## Use the glossary's vocabulary

When your output names a domain concept (in an issue title, a refactor proposal, a hypothesis, a test name), use the term as defined in `CONCEPTS.md`. Don't drift to synonyms the glossary explicitly avoids.

If the concept you need isn't in the glossary yet, that's a signal: either you're inventing language the project doesn't use (reconsider) or there's a real gap (note it for `/domain-modeling`).

## Flag decision conflicts

If your output contradicts a decision recorded in `docs/plans/` or `docs/solutions/`, surface it explicitly rather than silently overriding:

> _Contradicts the 2026-10-03 real API checks plan (KTD4), but worth reopening because…_
