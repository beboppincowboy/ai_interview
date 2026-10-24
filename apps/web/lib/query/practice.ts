import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { apiRequest } from "@/lib/api/client";
import type { Attempt, PracticeSet, PracticeSetCreated, Question } from "@/lib/api/types";

/** Query key factory for practice-set details. */
export const practiceKeys = {
  set: (id: string) => ["practice-set", id] as const
};

/** Creates the pair's practice set, or resolves to the existing one (one set per resume and job); both are used the same way. */
export function useCreatePracticeSet() {
  const client = useQueryClient();
  return useMutation({
    mutationFn: (body: { resumeId: string; targetJobId: string }) =>
      apiRequest<PracticeSetCreated>("/api/practice-sets", { method: "POST", body: { ...body, mode: "PRACTICE" } })
        .then(({ practiceSet }) => practiceSet),
    onSuccess: (set) => client.setQueryData(practiceKeys.set(set.id), set)
  });
}

/** Fetches one practice set by ID and passes React Query's cancellation signal to the request. */
export function usePracticeSet(id: string) {
  return useQuery({
    queryKey: practiceKeys.set(id),
    queryFn: ({ signal }) => apiRequest<PracticeSet>(`/api/practice-sets/${id}`, { signal })
  });
}

function useSetMutation<TArgs, TResult>(setId: string, request: (args: TArgs) => Promise<TResult>) {
  const client = useQueryClient();
  return useMutation({
    mutationFn: request,
    onSettled: () => Promise.all([
      client.invalidateQueries({ queryKey: practiceKeys.set(setId) }),
      client.invalidateQueries({ queryKey: ["history"] })
    ])
  });
}

/** Requests question regeneration after failure, then refreshes the set and history whether it succeeds or fails. */
export function useRetryPracticeSet(setId: string) {
  return useSetMutation(setId, () =>
    apiRequest<PracticeSet>(`/api/practice-sets/${setId}/retry`, { method: "POST", body: {} }));
}

/** Adds a user question from text, then refreshes the set and history on either outcome. */
export function useAddQuestion(setId: string) {
  return useSetMutation(setId, (text: string) =>
    apiRequest<Question>(`/api/practice-sets/${setId}/questions`, { method: "POST", body: { text } }));
}

/** Submits `{ questionId, text }`, then refreshes the set and history on either outcome. */
export function useSubmitAttempt(setId: string) {
  return useSetMutation(setId, ({ questionId, text }: { questionId: string; text: string }) =>
    apiRequest<Attempt>(`/api/practice-sets/${setId}/questions/${questionId}/attempts`, { method: "POST", body: { text } }));
}

/** Retries feedback for the supplied attempt ID, then refreshes the set and history on either outcome. */
export function useRetryAttempt(setId: string) {
  return useSetMutation(setId, (attemptId: string) =>
    apiRequest<Attempt>(`/api/attempts/${attemptId}/retry`, { method: "POST", body: {} }));
}
