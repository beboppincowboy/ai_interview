// Opt-in, so CI never needs an AI key or pays for provider calls: runs the shared scenarios against a real API only
// when LIVE_API_URL names it. The README has the command.
import { describe } from "vitest";
import { pollJobs, registerApiScenarios } from "./apiScenarios";

const base = process.env.LIVE_API_URL?.replace(/\/+$/, "") ?? "";

// Real AI jobs take seconds each, and the delete scenario waits for six of them.
describe.skipIf(!base)("live API", { timeout: 180_000 }, () => {
  registerApiScenarios({ base, settle: pollJobs(base) });
});
