import { screen } from "@testing-library/react";
import { http, HttpResponse } from "msw";
import { describe, expect, it } from "vitest";
import type { History } from "@/lib/api/types";
import { renderRoute, setupMockBackend } from "./render";

const { server } = setupMockBackend();
const at = "2026-09-28T12:00:00Z";
const serve = (history: History) => server.use(http.get("*/api/history", () => HttpResponse.json(history)));

describe("history", () => {
  it("links saved voice reports with coverage and preserves a zero score", async () => {
    serve({ resumes: [], targetJobs: [], practiceSets: [], voiceSessions: [{
      id: "v1", practiceSetId: "p1", resumeId: "r1", resumeName: "Backend", targetJobId: "j1", targetJobName: "Acme",
      savedAt: at, selectedCount: 6, answeredCount: 2, overallScore: 0, reportJobId: "job1", reportStatus: "SUCCEEDED"
    }] });
    renderRoute("/history");
    const row = await screen.findByRole("link", { name: /acme.*backend/i });
    expect(row).toHaveAttribute("href", "/voice/sessions/v1");
    expect(row).toHaveTextContent("2 of 6 answered");
    expect(row).toHaveTextContent("Score 0");
  });

  it("shows a first-run state linking to the flow when empty", async () => {
    renderRoute("/history");
    expect(await screen.findByText("Nothing here yet")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Start" })).toHaveAttribute("href", "/flow");
  });

  it("shows a single score without a sparkline", async () => {
    serve({ resumes: [{ id: "r1", name: "Backend", scores: [{ overall: 64, scoredAt: at }] }], targetJobs: [], practiceSets: [], voiceSessions: [] });
    renderRoute("/history");
    const row = await screen.findByRole("link", { name: /backend/i });
    expect(row).toHaveTextContent("64");
    expect(row.querySelector("svg")).toBeNull();
  });

  it("shows a practice trend with its text alternative and latest change, linking to the set", async () => {
    serve({
      resumes: [],
      targetJobs: [],
      voiceSessions: [],
      practiceSets: [{
        id: "p1", resumeId: "r1", resumeName: "Backend", targetJobId: "j1", targetJobName: "Acme", updatedAt: at,
        questions: [{ id: "q1", text: "Tell me about a latency fix.", scores: [62, 70, 74] }]
      }]
    });
    renderRoute("/history");
    expect(await screen.findByRole("img", { name: "Score rose from 62 to 74 over 3 attempts" })).toBeInTheDocument();
    expect(screen.getByLabelText("Change from previous: +4")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /acme/i })).toHaveAttribute("href", "/practice/p1");
  });
});
