/** Empty by default: the browser calls /api on its own origin (the Vite proxy in dev, nginx in the container). */
export const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "";
export const REQUEST_TIMEOUT_MS = 15_000;

export type MockMode = "all" | "off";

/** Mocks default to on in development and off in production builds (KTD5). */
export const API_MOCKS: MockMode = parseMockMode(import.meta.env.VITE_API_MOCKS, import.meta.env.PROD);

export function parseMockMode(value: string | undefined, isProd: boolean): MockMode {
  if (value === "all" || value === "off") return value;
  return isProd ? "off" : "all";
}

/** Spoken interviews are a default-off local development feature; only local Compose builds set the flag (KTD2). */
export const VOICE_ENABLED = import.meta.env.VITE_VOICE_ENABLED === "true";
