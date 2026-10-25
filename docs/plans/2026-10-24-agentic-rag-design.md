# Agentic RAG — design interview

The interview decisions are incorporated into the canonical [Agentic Job Fit implementation plan](2026-10-24-1621-feat-agentic-job-fit-plan.md). Use that plan for implementation; this document preserves the interview context.

## Confirmed scope

- Make existing workflows agentic, starting with Job Fit. Extend afterward to experience suggestions, practice questions, and answer feedback.
- Address questions requiring evidence from several parts of the candidate's documents.
- Support inspecting retrieval results and searching again when evidence is missing.
- Search all the user's saved past experiences plus one selected resume and a required target job. The user accepted this evidence scope.
- When searches do not establish an answer, say so explicitly; absence from the documents does not establish absence of experience.
- Start with hosted Jev judging evidence relevance and sufficiency, subject to evaluation. This revises the earlier local-Laya choice. Local Laya remains a possible later alternative. The existing generation model writes follow-up searches and the final assessment.
- Code permits one initial retrieval and at most two follow-up retrieval rounds. Follow-ups target unresolved requirements. Stop earlier when evidence is sufficient or retrieval adds no new evidence.
- Each requirement has one evidence status: Supported, Partially supported, or Not established.
- Supported and partially supported requirements cite the specific resume passage or saved experience that supports them.
- Kotlin owns the retrieval loop inside the existing Job Fit background job, calls Jev for judgments, and uses the existing generation model for follow-up queries and the assessment.
- If Jev is unavailable, the generation model takes over sufficiency judgments within the same loop and search budget. This supersedes the earlier fallback to single-pass retrieval. Record the judging model and visibly indicate fallback use.
- The generation model may challenge Jev's sufficient judgment by naming a specific unsupported part and requesting targeted retrieval within the shared budget.
- Judge each requirement separately, accepting only what the records establish; related technology alone does not establish the requested skill.
- Separate judgment provenance from evidence status. An unchecked item must not imply sufficient evidence. Proposed JSON fields include `judgedBy`, `status`, `evidenceIds`, and `missingEvidence`; exact schema remains open.
- Before saving the final assessment, code rejects unknown citation IDs and the active judge checks cited evidence against generated claims. Remove unsupported claims or rewrite them as evidence gaps. Repair and verification must be bounded; the repair limit remains open.
- Show brief user-facing progress (such as finding evidence and checking gaps), plus an expandable explanation of sources and fallback use.
- Each run uses a snapshot of its selected resume, required target job and saved experiences. Changes to those sources mark the saved result outdated and prompt reassessment; they do not silently mutate it.
- Introduce agentic Job Fit behind a configuration flag. Keep the existing flow available until evaluation passes.
- Default-enablement gate: valid citations, no unsupported Supported judgments in the reviewed test set, and better evidence coverage than current Job Fit. Report response time and model-call cost. Passing this set does not guarantee correctness on every input.
- Permit at most one final-assessment repair attempt. Verification of that repair is required; if verification still fails, fail the run rather than save an unverified assessment.
- Enforce an overall deadline and model-call limit. Choose numerical limits from measured runs and the existing worker's lease constraints before default enablement.
- Flag conflicts between resume and saved experiences, cite both records, and do not mark the disputed claim fully supported.
- Keep the overall fit score and explain it through requirement-level evidence statuses. It assesses documented fit, not hiring probability.
- Before default enablement, compare against current Job Fit on clear matches, partial matches, missing evidence, and conflicting records. Assess evidence accuracy, citation accuracy, unnecessary repeat searches, and response time.

## Research checked October 24

- [TypeSafe's Jev documentation](https://docs.typesafe.ai/introduction): Jev returns typed choices, scores, and yes/no probabilities rather than generated prose. The docs recommend decomposing complex judgments into focused questions composed in code.
- [Laya's upstream repository](https://github.com/NandhaKishorM/laya): Laya is an Apache-2.0 decision engine with downloadable checkpoints and typed choice, score, and yes/no outputs. Context limits depend on checkpoint; its authors recommend domain fine-tuning and evaluating accuracy on the intended data.
- Laya supports local inference on CPU, CUDA and MPS, and a self-hosted HTTP service (`laya[serve]`, `laya-serve`). Local checkpoints allow offline inference. These are upstream-supported capabilities, not verified performance on this user's machine.
- Neither Jev nor Laya has been evaluated on this project's evidence in this interview.

## Prior proposal

The separate `feat/agentic-rag` branch contains an October 7 Ask design and implementation plan. It proposes a Python agent, Kotlin document tools over MCP, Jev judgments, and Gemini generation. Those architectural choices are reference material, not newly confirmed decisions in this interview.

## Implementation planning follow-through

- Specify versioned result and judgment schemas, citation identities, and compatibility with existing saved Job Fit results.
- Specify judging prompts, requirement decomposition and evidence coverage evaluation fixtures under the confirmed behavior above.
- Measure numerical probability thresholds, overall deadline and provider-call budgets; respect worker leases, cancellation, and retry behavior. Do not equate a judge's confidence with demonstrated correctness.
- Specify source snapshot identities and freshness detection, including experience additions, edits and deletions.
- Detail progress, source inspection and visible fallback presentation.
- Existing Ask/MCP/Python-agent proposals are superseded as the initial product direction. Kotlin owns the Job Fit loop; no separate Ask feature or Python orchestration service has been approved.
- The user requested Compound Engineering implementation planning after reviewing this design. Implementation itself remains a separate action.
