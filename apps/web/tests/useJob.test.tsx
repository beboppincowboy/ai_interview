import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { useJob } from "@/lib/query/useJob";

function job(status: string, stage = "SCORING_RESUME") {
  return {
    jobId: "j1", jobType: "RESUME_SCORE", status, stage, attempts: 1, maxAttempts: 3,
    result: status === "SUCCEEDED" ? { overall: 70 } : null, error: null,
    createdAt: "2026-09-28T00:00:00Z", startedAt: null, completedAt: null,
    inputRefs: { resumeId: "r1", targetJobId: null, practiceSetId: null, attemptId: null, voiceSessionId: null }
  };
}

function wrapper() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>;
  };
}

function respondWith(...statuses: string[]) {
  let call = 0;
  const fetchMock = vi.fn(() => {
    const status = statuses[Math.min(call++, statuses.length - 1)];
    return Promise.resolve(new Response(JSON.stringify(job(status)), { headers: { "content-type": "application/json" } }));
  });
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

afterEach(() => vi.unstubAllGlobals());

describe("useJob", () => {
  it("polls until the job succeeds, then stops", async () => {
    const fetchMock = respondWith("QUEUED", "PROCESSING", "SUCCEEDED");
    const { result } = renderHook(() => useJob("j1", { intervalMs: 5 }), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.job?.status).toBe("SUCCEEDED"));
    const calls = fetchMock.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(fetchMock.mock.calls.length).toBe(calls);
    expect(result.current.isPolling).toBe(false);
    expect(result.current.timedOut).toBe(false);
  });

  it("stops after the time ceiling and resumes on check again", async () => {
    const fetchMock = respondWith("PROCESSING");
    const { result } = renderHook(() => useJob("j1", { intervalMs: 5, ceilingMs: 30 }), { wrapper: wrapper() });

    await waitFor(() => expect(result.current.timedOut).toBe(true));
    const calls = fetchMock.mock.calls.length;
    await new Promise((resolve) => setTimeout(resolve, 40));
    expect(fetchMock.mock.calls.length).toBe(calls);
    expect(result.current.isPolling).toBe(false);

    act(() => result.current.checkAgain());
    expect(result.current.timedOut).toBe(false);
    await waitFor(() => expect(fetchMock.mock.calls.length).toBeGreaterThan(calls));
  });

  it("does nothing without a job id", () => {
    const fetchMock = respondWith("QUEUED");
    const { result } = renderHook(() => useJob(null), { wrapper: wrapper() });
    expect(result.current.job).toBeUndefined();
    expect(fetchMock).not.toHaveBeenCalled();
  });
});
