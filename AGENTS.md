# Agent instructions

Delegate independent parallel work to subagents when it speeds up the task. Use GPT-6 Luna (`gpt-6-luna`) with `max` reasoning effort for those subagents unless the user explicitly requests a different model or effort.

## Agent skills

### Issue tracker

Issues and specs live in GitHub Issues on beboppincowboy/ai_interview, via the `gh` CLI. See `docs/agents/issue-tracker.md`.

### Triage labels

Default five-role vocabulary (needs-triage, needs-info, ready-for-agent, ready-for-human, wontfix). See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: glossary in `CONCEPTS.md`, decisions in `docs/plans/` and `docs/solutions/`. See `docs/agents/domain.md`.
