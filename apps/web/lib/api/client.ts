import { API_BASE_URL, REQUEST_TIMEOUT_MS } from "./config";
import type { ApiErrorBody } from "./types";

export type ApiErrorKind = "HTTP" | "NETWORK" | "TIMEOUT" | "INVALID_RESPONSE";

export class ApiError extends Error {
  /**
   * `retryable`: the client may resend this request (NETWORK, TIMEOUT, HTTP 408/429/5xx).
   * Unrelated to `JobError.retryable`, which says whether the server may rerun a background job.
   */
  constructor(
    message: string,
    readonly kind: ApiErrorKind,
    readonly status: number | null = null,
    readonly code: string | null = null,
    readonly retryable = kind === "NETWORK" || kind === "TIMEOUT"
  ) {
    super(message);
    this.name = "ApiError";
  }
}

export type RequestOptions = {
  method?: "GET" | "POST" | "PATCH" | "DELETE";
  body?: unknown;
  signal?: AbortSignal;
  timeoutMs?: number;
  /** Automatic retries for retryable failures, default 2. GET and POST only: PATCH and DELETE are never retried. */
  retries?: number;
  retryDelayMs?: number;
};

/**
 * Each POST call gets one Idempotency-Key, reused by its automatic retries; a user pressing Retry makes a new
 * call and so sends a new key. GET is retried without a key. PATCH and DELETE carry no key and are sent once,
 * so a transient failure reaches the caller instead of risking a rename or delete applied twice.
 */
export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { retryDelayMs = 400 } = options;
  const retries = options.method === "PATCH" || options.method === "DELETE" ? 0 : options.retries ?? 2;
  const idempotencyKey = options.method === "POST" ? crypto.randomUUID() : null;
  for (let attempt = 0; ; attempt++) {
    try {
      return await send<T>(path, options, idempotencyKey);
    }
    catch (error) {
      const retry = error instanceof ApiError && error.retryable && attempt < retries && !options.signal?.aborted;
      if (!retry) throw error;
      await new Promise((resolve) => setTimeout(resolve, retryDelayMs * (attempt + 1)));
    }
  }
}

async function send<T>(path: string, options: RequestOptions, idempotencyKey: string | null): Promise<T> {
  const headers = new Headers();
  if (idempotencyKey) headers.set("Idempotency-Key", idempotencyKey);
  let body: BodyInit | undefined;
  if (options.body instanceof FormData) {
    body = options.body;
  }
  else if (options.body !== undefined) {
    headers.set("content-type", "application/json");
    body = JSON.stringify(options.body);
  }

  const controller = new AbortController();
  let timedOut = false;
  const timer = setTimeout(() => {
    timedOut = true;
    controller.abort();
  }, options.timeoutMs ?? REQUEST_TIMEOUT_MS);
  const abort = () => controller.abort();
  options.signal?.addEventListener("abort", abort, { once: true });

  let response: Response;
  try {
    response = await fetch(`${API_BASE_URL}${path}`, {
      method: options.method ?? "GET",
      headers,
      body,
      cache: "no-store",
      signal: controller.signal
    });
  }
  catch (error) {
    if (timedOut) throw new ApiError("The request timed out.", "TIMEOUT");
    if (options.signal?.aborted) throw error;
    throw new ApiError(error instanceof Error ? error.message : "Network error", "NETWORK");
  }
  finally {
    clearTimeout(timer);
    options.signal?.removeEventListener("abort", abort);
  }

  if (!response.ok) {
    const { code, message } = await errorBody(response);
    const status = response.status;
    throw new ApiError(message, "HTTP", status, code, status === 408 || status === 429 || status >= 500);
  }
  if (response.status === 204) return undefined as T;
  try {
    return (await response.json()) as T;
  }
  catch {
    throw new ApiError("The server returned invalid JSON.", "INVALID_RESPONSE", response.status);
  }
}

async function errorBody(response: Response): Promise<ApiErrorBody> {
  const fallback = `Request failed with status ${response.status}`;
  try {
    const body = (await response.json()) as Partial<ApiErrorBody>;
    return { code: typeof body.code === "string" ? body.code : null, message: body.message || fallback };
  }
  catch {
    return { code: null, message: fallback };
  }
}
