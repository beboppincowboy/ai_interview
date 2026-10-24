import { describe, expect, it } from "vitest";
import { ApiError } from "@/lib/api/client";
import { errorCode, friendlyError } from "@/lib/errorMessages";

describe("friendlyError", () => {
  it("maps job errors by code instead of provider wording", () => {
    expect(friendlyError({ code: "GEMINI_RATE_LIMITED", message: "changeable provider wording", retryable: false }))
      .toBe("The AI service is at its usage limit. Try again later.");
  });

  it("does not show backend wording for unknown codes", () => {
    expect(friendlyError(new ApiError("internal details", "HTTP", 502, "UNKNOWN_BACKEND_CODE"))).toBe("The request failed.");
  });

  it("extracts only known codes", () => {
    expect(errorCode(new ApiError("x", "HTTP", 409, "EXPERIENCE_DUPLICATE"))).toBe("EXPERIENCE_DUPLICATE");
    expect(errorCode({ code: "RETRIES_EXHAUSTED_STORAGE_UNAVAILABLE", message: "x", retryable: false })).toBeNull();
    expect(errorCode({ code: "toString", message: "x", retryable: false })).toBeNull();
  });

  it("names no vendors in any message", () => {
    for (const code of ["GEMINI_NOT_CONFIGURED", "GEMINI_RATE_LIMITED", "GEMINI_SAFETY", "GEMINI_TIMEOUT"]) {
      expect(friendlyError({ code, message: "x", retryable: false })).not.toMatch(/gemini|spring|object storage/i);
    }
  });

  it("maps ApiError by code and kind", () => {
    expect(friendlyError(new ApiError("x", "HTTP", 429, "RATE_LIMITED", true))).toContain("Too many requests");
    expect(friendlyError(new ApiError("x", "NETWORK"))).toBe("The server is not reachable. Check your connection and try again.");
    expect(friendlyError(new ApiError("x", "TIMEOUT"))).toBe("The request timed out.");
  });
});
