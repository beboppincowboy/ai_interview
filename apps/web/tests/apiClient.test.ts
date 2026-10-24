import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "@/lib/api/client";

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { "content-type": "application/json" } });
}

afterEach(() => vi.unstubAllGlobals());

describe("apiRequest", () => {
  it("calls /api on the page origin, returns parsed JSON and sends no-store", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(200, { items: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiRequest("/api/resumes")).resolves.toEqual({ items: [] });
    expect(fetchMock.mock.calls[0][0]).toBe("/api/resumes");
    expect(fetchMock.mock.calls[0][1].cache).toBe("no-store");
  });

  it("returns undefined for 204", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(null, { status: 204 })));
    await expect(apiRequest("/api/resumes/1", { method: "DELETE" })).resolves.toBeUndefined();
  });

  it("maps a fetch TypeError to a retryable network error", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")));
    const error = await apiRequest("/api/resumes", { retries: 0 }).catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ kind: "NETWORK", retryable: true, status: null });
  });

  it("treats 429 as retryable and keeps its code", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(jsonResponse(429, { code: "RATE_LIMITED", message: "slow down" })));
    const error = await apiRequest("/api/resumes", { retries: 0 }).catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 429, code: "RATE_LIMITED", retryable: true });
  });

  it("treats 400 as not retryable, keeps the code and does not retry", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(400, { code: "ANSWER_EMPTY", message: "empty" }));
    vi.stubGlobal("fetch", fetchMock);
    const error = await apiRequest("/api/x", { method: "POST", body: {}, retryDelayMs: 0 }).catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 400, code: "ANSWER_EMPTY", retryable: false, message: "empty" });
    expect(fetchMock).toHaveBeenCalledTimes(1);
  });

  it("reuses one idempotency key across automatic retries", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(jsonResponse(503, { code: "SERVICE_UNAVAILABLE", message: "down" }))
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(jsonResponse(202, { jobId: "j1" }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiRequest("/api/resumes/r1/score", { method: "POST", body: {}, retryDelayMs: 0 }))
      .resolves.toEqual({ jobId: "j1" });
    const keys = fetchMock.mock.calls.map(([, init]) => new Headers(init.headers).get("Idempotency-Key"));
    expect(keys).toHaveLength(3);
    expect(keys[0]).toBeTruthy();
    expect(new Set(keys).size).toBe(1);
  });

  it("sends a new idempotency key for a new user action", async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(jsonResponse(202, { jobId: "j" })));
    vi.stubGlobal("fetch", fetchMock);

    await apiRequest("/api/attempts/a1/retry", { method: "POST", body: {} });
    await apiRequest("/api/attempts/a1/retry", { method: "POST", body: {} });
    const [first, second] = fetchMock.mock.calls.map(([, init]) => new Headers(init.headers).get("Idempotency-Key"));
    expect(first).not.toBe(second);
  });

  it.each(["PATCH", "DELETE"] as const)("sends %s once with no idempotency key and surfaces a transient failure", async (method) => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(503, { code: "SERVICE_UNAVAILABLE", message: "down" }));
    vi.stubGlobal("fetch", fetchMock);

    const error = await apiRequest("/api/resumes/r1", { method, body: { name: "x" }, retries: 2, retryDelayMs: 0 }).catch((e: unknown) => e);
    expect(error).toMatchObject({ status: 503, retryable: true });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect(new Headers(fetchMock.mock.calls[0][1].headers).has("Idempotency-Key")).toBe(false);
  });

  it("retries GET without an idempotency key", async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValueOnce(jsonResponse(503, { code: "SERVICE_UNAVAILABLE", message: "down" }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [] }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(apiRequest("/api/resumes", { retryDelayMs: 0 })).resolves.toEqual({ items: [] });
    expect(fetchMock).toHaveBeenCalledTimes(3);
    expect(fetchMock.mock.calls.every(([, init]) => !new Headers(init.headers).has("Idempotency-Key"))).toBe(true);
  });

  it("sends FormData without a JSON content type", async () => {
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse(202, {}));
    vi.stubGlobal("fetch", fetchMock);
    const form = new FormData();
    form.append("name", "Backend");
    await apiRequest("/api/resumes", { method: "POST", body: form });
    const init = fetchMock.mock.calls[0][1];
    expect(init.body).toBe(form);
    expect(new Headers(init.headers).has("content-type")).toBe(false);
  });

  it("times out as a retryable error", async () => {
    vi.stubGlobal("fetch", vi.fn((_url: string, init: RequestInit) => new Promise((_resolve, reject) => {
      init.signal?.addEventListener("abort", () => reject(new DOMException("aborted", "AbortError")));
    })));
    const error = await apiRequest("/api/resumes", { timeoutMs: 5, retries: 0 }).catch((e: unknown) => e);
    expect(error).toMatchObject({ kind: "TIMEOUT", retryable: true });
  });
});
