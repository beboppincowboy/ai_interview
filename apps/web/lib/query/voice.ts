import { useMutation, useQuery, useQueryClient, type QueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import type { VoiceSaveResult, VoiceSession, VoiceToken, VoiceTranscript } from "@/lib/api/types";

export const voiceKeys = { session: (id: string) => ["voice-session", id] as const };
const path = (id: string) => `/api/voice-sessions/${id}`;

export const createVoiceSession = (practiceSetId: string) =>
  apiRequest<VoiceSession>("/api/voice-sessions", { method: "POST", body: { practiceSetId }, retries: 0 });

export const getVoiceSession = (id: string, signal?: AbortSignal) =>
  apiRequest<VoiceSession>(path(id), { signal });

// A failed provisioning attempt spends a server-side slot. Only an explicit user retry may request another token.
export const mintVoiceToken = (id: string, questionId: string, signal?: AbortSignal) =>
  apiRequest<VoiceToken>(`${path(id)}/tokens`, { method: "POST", body: { questionId }, signal, retries: 0, timeoutMs: 5_000 });

// The caller reconciles ambiguous Save with GET before changing or discarding the reviewed draft.
export const saveVoiceSession = (id: string, transcript: VoiceTranscript) =>
  apiRequest<VoiceSaveResult>(`${path(id)}/save`, { method: "POST", body: transcript, retries: 0 });

export const retryVoiceReport = (id: string) =>
  apiRequest<VoiceSession>(`${path(id)}/report/retry`, { method: "POST", body: {}, retries: 0 });

export const discardVoiceDraft = (id: string) =>
  apiRequest<void>(`${path(id)}/draft`, { method: "DELETE", retries: 0 });

export const deleteVoiceSession = (id: string) =>
  apiRequest<void>(path(id), { method: "DELETE", retries: 0 });

export function useVoiceSession(id: string) {
  return useQuery({ queryKey: voiceKeys.session(id), queryFn: ({ signal }) => getVoiceSession(id, signal) });
}

const invalidateVoiceSession = (client: QueryClient, id: string) => Promise.all([
  client.invalidateQueries({ queryKey: voiceKeys.session(id) }),
  client.invalidateQueries({ queryKey: ["history"] })
]);

export function useRetryVoiceReport(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => retryVoiceReport(id),
    onSuccess: (session) => client.setQueryData(voiceKeys.session(id), session),
    // A lost reply may follow a committed Retry; refresh its current job identity on either outcome.
    onSettled: () => invalidateVoiceSession(client, id)
  });
}

export function useDeleteVoiceSession(id: string) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: () => deleteVoiceSession(id),
    onSettled: () => invalidateVoiceSession(client, id)
  });
}
