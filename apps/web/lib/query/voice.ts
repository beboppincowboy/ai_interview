import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import { keys } from "@/lib/query/library";
import type { VoiceSaveResult, VoiceSession, VoiceToken, VoiceTranscript } from "@/lib/api/types";

/** Voice-session query keys. */
export const voiceKeys = { session: (id: string) => ["voice-session", id] as const };
const path = (id: string) => `/api/voice-sessions/${id}`;

/** Creates a voice session for a practice set; automatic POST retries are disabled to avoid creating another session after an ambiguous reply. */
export const createVoiceSession = (practiceSetId: string) =>
  apiRequest<VoiceSession>("/api/voice-sessions", { method: "POST", body: { practiceSetId }, retries: 0 });

/** Fetches one session and accepts an optional cancellation signal. */
export const getVoiceSession = (id: string, signal?: AbortSignal) =>
  apiRequest<VoiceSession>(path(id), { signal });

/** A failed provisioning attempt spends a server-side slot; timeout is 5 seconds and automatic retries are disabled. */
export const mintVoiceToken = (id: string, questionId: string, signal?: AbortSignal) =>
  apiRequest<VoiceToken>(`${path(id)}/tokens`, { method: "POST", body: { questionId }, signal, retries: 0, timeoutMs: 5_000 });

/** Saves the reviewed transcript without automatic retries; the result reports duplicate saves, so callers reconcile ambiguity with GET. */
export const saveVoiceSession = (id: string, transcript: VoiceTranscript) =>
  apiRequest<VoiceSaveResult>(`${path(id)}/save`, { method: "POST", body: transcript, retries: 0 });

/** Starts one report retry without automatic POST retries and returns the updated session. */
export const retryVoiceReport = (id: string) =>
  apiRequest<VoiceSession>(`${path(id)}/report/retry`, { method: "POST", body: {}, retries: 0 });

/** Deletes an unsaved draft and returns no body. */
export const discardVoiceDraft = (id: string) =>
  apiRequest<void>(`${path(id)}/draft`, { method: "DELETE" });

/** Deletes a saved or draft session and returns no body. */
export const deleteVoiceSession = (id: string) =>
  apiRequest<void>(path(id), { method: "DELETE" });

/** Queries one voice session by ID. */
export function useVoiceSession(id: string) {
  return useQuery({ queryKey: voiceKeys.session(id), queryFn: ({ signal }) => getVoiceSession(id, signal) });
}

const invalidateVoiceSession = (client: QueryClient, id: string) => Promise.all([
  client.invalidateQueries({ queryKey: voiceKeys.session(id) }),
  client.invalidateQueries({ queryKey: keys.history })
]);

/** Retries the report and refreshes the session and history queries after either result. */
export function useRetryVoiceReport(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => retryVoiceReport(id),
    onSuccess: (session) => client.setQueryData(voiceKeys.session(id), session),
    // A lost reply may follow a committed Retry; refresh its current job identity on either outcome.
    onSettled: () => invalidateVoiceSession(client, id)
  });
}

/** Deletes the session; removes its cached detail on success and refreshes it on failure. */
export function useDeleteVoiceSession(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => deleteVoiceSession(id),
    // Removing, not invalidating, keeps the open page from refetching a session that is now gone.
    onSuccess: () => {
      client.removeQueries({ queryKey: voiceKeys.session(id) });
      return client.invalidateQueries({ queryKey: keys.history });
    },
    onError: () => invalidateVoiceSession(client, id)
  });
}
