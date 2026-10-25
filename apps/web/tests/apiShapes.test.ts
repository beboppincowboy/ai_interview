import { expect, it } from "vitest";
import type { FitView, JobFitResult } from "@/lib/api/types";
import { recordShape, shapeOf } from "./apiScenarios";

const optionalResultPaths = [
  "result",
  "result.items",
  "result.items[].guidance: string",
  "result.items[].match: string",
  "result.items[].requirement: string",
  "result.items[].source",
  "result.items[].source.id: string",
  "result.items[].source.name: string",
  "result.items[].source.type: string",
  "result.items[].whyItFits: string",
  "result.rewrites",
  "result.rewrites[].original: string",
  "result.rewrites[].placeholders",
  "result.rewrites[].rewritten: string",
  "result.rewrites[].section: string"
].sort();

const suggestion = {
  requirement: "Kafka event streaming",
  source: { type: "EXPERIENCE", id: "experience-id", name: "Payments Engineer" },
  match: "Built event-driven payment services.",
  whyItFits: "The experience includes event processing.",
  guidance: "Describe the scale and outcome."
};

const rewrite = {
  section: "Experience",
  original: "Built backend services.",
  rewritten: "Built Kotlin backend services for payments.",
  placeholders: ["X%"]
};

const agenticFit = {
  fitScore: 80,
  summary: "The frozen records support Kotlin.",
  matchedRequirements: [{ requirement: "Kotlin", evidence: "Built Kotlin services." }],
  missingRequirements: [{ requirement: "Kafka", guidance: "Production ownership is not established." }],
  feedback: [],
  agenticMetadata: {
    schemaVersion: 1,
    snapshotId: "snapshot-1",
    sourceManifest: [{ sourceType: "RESUME", resourceId: "resume-1", sourceLabel: "Resume", contentHash: "hash-1" }],
    assessments: [{
      requirementId: "req-kotlin",
      requirement: "Kotlin",
      status: "SUPPORTED",
      targetJobEvidenceIds: ["job-kotlin"],
      evidenceIds: ["resume-kotlin"],
      conflictIds: [],
      missingEvidenceDescription: null
    }],
    evidenceCatalog: [{
      id: "resume-kotlin",
      snapshotId: "snapshot-1",
      sourceType: "RESUME",
      resourceId: "resume-1",
      contentHash: "hash-1",
      sourceLabel: "Resume",
      passage: "Built Kotlin services.",
      quote: "Built Kotlin services."
    }],
    conflicts: [{ id: "conflict-1", description: "Saved records differ.", evidenceIds: ["experience-a", "experience-b"] }],
    judgments: [{
      requirementId: "req-kotlin",
      state: "JUDGED",
      provenance: { provider: "JEV", model: "jev-1.14.0", rubricVersion: "job-fit-v1" },
      status: "SUPPORTED",
      evidenceIds: ["resume-kotlin"],
      missingEvidence: [],
      providerProbabilities: { SUPPORTED: 0.91 }
    }],
    trace: [{ kind: "FALLBACK", summary: "The generation model judged the available evidence.", round: 1 }],
    fallbackUsed: true,
    fallbackReason: "Jev timed out."
  }
} satisfies JobFitResult;

const fitView = {
  resumeId: "resume-1",
  targetJobId: "job-1",
  result: null,
  createdAt: null,
  latestJob: null,
  freshness: "UNKNOWN"
} satisfies FitView;

it("retains declared paths for optional AI result arrays when empty", () => {
  expect(shapeOf({ result: { items: [], rewrites: [] } }).sort()).toEqual(optionalResultPaths);
});

it("records the type of every value that is not an object or list", () => {
  expect(shapeOf({ id: "a", score: 70, stale: false, error: null, job: { attempts: [1] } }).sort())
    .toEqual(["error: null", "id: string", "job", "job.attempts", "score: number", "stale: boolean"]);
});

it("records model-written text the contract allows to be null as one type, filled or not", () => {
  expect(shapeOf({ category: null, nextStep: "Practise", summary: null })).toEqual(
    ["category: string|null", "nextStep: string|null", "summary: null"]
  );
});

it("holds empty job-fit lists to their declared item fields", () => {
  expect(shapeOf({ result: { matchedRequirements: [], missingRequirements: [], feedback: [] } }).sort()).toEqual([
    "result",
    "result.feedback",
    "result.feedback[].message: string",
    "result.feedback[].priority: string",
    "result.matchedRequirements",
    "result.matchedRequirements[].evidence: string",
    "result.matchedRequirements[].requirement: string",
    "result.missingRequirements",
    "result.missingRequirements[].guidance: string",
    "result.missingRequirements[].requirement: string"
  ]);
  expect(() => shapeOf({ result: { feedback: [{ priority: "HIGH", message: 3 }] } })).toThrow();
});

it("records the versioned agentic evidence contract while retaining legacy fit fields", () => {
  expect(shapeOf(agenticFit).sort()).toEqual([
    "agenticMetadata",
    "agenticMetadata.assessments",
    "agenticMetadata.assessments[].conflictIds",
    "agenticMetadata.assessments[].evidenceIds",
    "agenticMetadata.assessments[].missingEvidenceDescription: null",
    "agenticMetadata.assessments[].requirement: string",
    "agenticMetadata.assessments[].requirementId: string",
    "agenticMetadata.assessments[].status: string",
    "agenticMetadata.assessments[].targetJobEvidenceIds",
    "agenticMetadata.conflicts",
    "agenticMetadata.conflicts[].description: string",
    "agenticMetadata.conflicts[].evidenceIds",
    "agenticMetadata.conflicts[].id: string",
    "agenticMetadata.evidenceCatalog",
    "agenticMetadata.evidenceCatalog[].contentHash: string",
    "agenticMetadata.evidenceCatalog[].id: string",
    "agenticMetadata.evidenceCatalog[].passage: string",
    "agenticMetadata.evidenceCatalog[].quote: string",
    "agenticMetadata.evidenceCatalog[].resourceId: string",
    "agenticMetadata.evidenceCatalog[].snapshotId: string",
    "agenticMetadata.evidenceCatalog[].sourceLabel: string",
    "agenticMetadata.evidenceCatalog[].sourceType: string",
    "agenticMetadata.fallbackReason: string",
    "agenticMetadata.fallbackUsed: boolean",
    "agenticMetadata.judgments",
    "agenticMetadata.judgments[].evidenceIds",
    "agenticMetadata.judgments[].missingEvidence",
    "agenticMetadata.judgments[].providerProbabilities",
    "agenticMetadata.judgments[].providerProbabilities.SUPPORTED: number",
    "agenticMetadata.judgments[].provenance",
    "agenticMetadata.judgments[].provenance.model: string",
    "agenticMetadata.judgments[].provenance.provider: string",
    "agenticMetadata.judgments[].provenance.rubricVersion: string",
    "agenticMetadata.judgments[].requirementId: string",
    "agenticMetadata.judgments[].state: string",
    "agenticMetadata.judgments[].status: string",
    "agenticMetadata.schemaVersion: number",
    "agenticMetadata.snapshotId: string",
    "agenticMetadata.sourceManifest",
    "agenticMetadata.sourceManifest[].contentHash: string",
    "agenticMetadata.sourceManifest[].resourceId: string",
    "agenticMetadata.sourceManifest[].sourceLabel: string",
    "agenticMetadata.sourceManifest[].sourceType: string",
    "agenticMetadata.trace",
    "agenticMetadata.trace[].kind: string",
    "agenticMetadata.trace[].round: number",
    "agenticMetadata.trace[].summary: string",
    "feedback",
    "fitScore: number",
    "matchedRequirements",
    "matchedRequirements[].evidence: string",
    "matchedRequirements[].requirement: string",
    "missingRequirements",
    "missingRequirements[].guidance: string",
    "missingRequirements[].requirement: string",
    "summary: string"
  ].sort());
  expect(shapeOf(fitView).sort()).toEqual([
    "createdAt: null",
    "freshness: string",
    "latestJob: null",
    "resumeId: string",
    "result: null",
    "targetJobId: string"
  ].sort());
});

it("reports which optional AI result arrays arrived with items", () => {
  const populated = new Set<string>();
  shapeOf({ result: { items: [suggestion], rewrites: [] } }, "", populated);
  expect([...populated]).toEqual(["result.items"]);
});

it("records and validates nested fields in populated optional AI result arrays", () => {
  expect(shapeOf({ result: { items: [suggestion], rewrites: [rewrite] } }).sort()).toEqual(optionalResultPaths);

  const incompleteSuggestion = {
    ...suggestion,
    source: { type: "EXPERIENCE", id: "experience-id" }
  };
  const incompleteRewrite = {
    section: "Experience",
    rewritten: "Built Kotlin backend services for payments.",
    placeholders: ["X%"]
  };
  expect(() => shapeOf({ result: { items: [incompleteSuggestion], rewrites: [rewrite] } })).toThrow();
  expect(() => shapeOf({ result: { items: [suggestion], rewrites: [incompleteRewrite] } })).toThrow();
});

it("rejects a repeated scenario label with a different response shape", () => {
  const shapes: Record<string, string[]> = {};
  recordShape(shapes, "same scenario", { first: true });
  const firstShape = shapes["same scenario"];

  expect(() => recordShape(shapes, "same scenario", { last: true })).toThrow();
  expect(shapes["same scenario"]).toEqual(firstShape);
});
