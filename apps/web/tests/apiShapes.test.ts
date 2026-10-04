import { expect, it } from "vitest";
import { recordShape, shapeOf } from "./apiScenarios";

const optionalResultPaths = [
  "result",
  "result.items",
  "result.items[].guidance",
  "result.items[].match",
  "result.items[].requirement",
  "result.items[].source",
  "result.items[].source.id",
  "result.items[].source.name",
  "result.items[].source.type",
  "result.items[].whyItFits",
  "result.rewrites",
  "result.rewrites[].original",
  "result.rewrites[].placeholders",
  "result.rewrites[].rewritten",
  "result.rewrites[].section"
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

it("retains declared paths for optional AI result arrays when empty", () => {
  expect(shapeOf({ result: { items: [], rewrites: [] } }).sort()).toEqual(optionalResultPaths);
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
