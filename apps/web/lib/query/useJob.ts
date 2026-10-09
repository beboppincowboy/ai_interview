import { useQuery, useQueryClient, type QueryKey } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { ApiError, apiRequest } from "@/lib/api/client";
import { isTerminal, type LatestJob, type JobStatusResponse } from "@/lib/api/types";

const POLL_INTERVAL_MS = 1_500;
const POLL_CEILING_MS = 10 * 60_000;

export function jobQueryKey(jobId: string) {
  return ["job", jobId] as const;
}

/** Polls a job until it is terminal or the time ceiling passes; `checkAgain` restarts polling. */
export function useJob<TResult = unknown>(
  jobId: string | null | undefined,
  { intervalMs = POLL_INTERVAL_MS, ceilingMs = POLL_CEILING_MS } = {}
) {
  // Keyed by job ID so a new job starts with a fresh ceiling.
  const [timedOutJob, setTimedOutJob] = useState<string | null>(null);
  const timedOut = Boolean(jobId) && timedOutJob === jobId;
  const [round, setRound] = useState(0);

  const query = useQuery({
    queryKey: jobQueryKey(jobId ?? ""),
    queryFn: ({ signal }) => apiRequest<JobStatusResponse<TResult>>(`/api/jobs/${jobId}`, { signal }),
    enabled: Boolean(jobId),
    refetchInterval: (q) => {
      const error = q.state.error;
      if (timedOut || isTerminal(q.state.data?.status)) return false;
      if (error instanceof ApiError && !error.retryable) return false;
      return intervalMs;
    }
  });

  const terminal = isTerminal(query.data?.status);
  useEffect(() => {
    if (!jobId || terminal) return;
    const timer = setTimeout(() => setTimedOutJob(jobId), ceilingMs);
    return () => clearTimeout(timer);
  }, [jobId, terminal, ceilingMs, round]);

  return {
    job: query.data,
    error: query.error,
    isPolling: Boolean(jobId) && !terminal && !timedOut,
    timedOut: timedOut && !terminal,
    checkAgain: () => {
      setTimedOutJob(null);
      setRound((value) => value + 1);
      void query.refetch();
    }
  };
}

/**
 * Follows a resource's `latestJob`: polls while it runs and refreshes the resource's queries when it ends.
 * Returns the freshest view of the job (the poll result while running, otherwise the resource's copy).
 */
export function useFollowJob(latestJob: LatestJob | null | undefined, queryKeys: QueryKey[]) {
  const client = useQueryClient();
  const running = Boolean(latestJob) && !isTerminal(latestJob!.status);
  const poll = useJob(running ? latestJob!.jobId : null);
  const polledStatus = poll.job?.status;

  useEffect(() => {
    if (!running || !isTerminal(polledStatus)) return;
    for (const queryKey of queryKeys) void client.invalidateQueries({ queryKey });
    // queryKeys is recreated each render; the status transition is what matters.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [running, polledStatus, client]);

  const job = running && poll.job ? { ...latestJob!, ...poll.job } : latestJob ?? null;
  return { job, timedOut: poll.timedOut, checkAgain: poll.checkAgain };
}
