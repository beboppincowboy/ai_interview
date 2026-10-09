import { AlertCircle, Loader2 } from "lucide-react";
import { Button } from "@/components/ui/button";
import { STAGE_LABELS } from "@/lib/api/jobLabels";
import type { LatestJob } from "@/lib/api/types";
import { friendlyError } from "@/lib/errorMessages";

type Props = {
  job: LatestJob | null;
  timedOut?: boolean;
  onCheckAgain?: () => void;
  onRetry?: () => void;
  retrying?: boolean;
  /** Error from starting the job (the POST), shown like a failed job. */
  startError?: unknown;
};

/** Progress, retrying, timed-out and failed states for one AI step. Renders nothing once the job succeeded. */
export function JobProgress({ job, timedOut, onCheckAgain, onRetry, retrying, startError }: Props) {
  const failed = job?.status === "FAILED" || Boolean(startError);
  if (failed) {
    const error = startError ?? job?.error;
    return (
      <div role="alert" className="flex flex-wrap items-center gap-3 rounded-lg border border-destructive/30 bg-destructive/5 px-4 py-3 text-sm">
        <AlertCircle className="size-4 text-destructive" aria-hidden />
        <span className="flex-1">{friendlyError(error, "This step failed.")}</span>
        {onRetry ? <Button size="sm" variant="outline" onClick={onRetry} disabled={retrying}>Try again</Button> : null}
      </div>
    );
  }
  if (!job || job.status === "SUCCEEDED") return null;
  if (timedOut) {
    return (
      <div role="status" className="flex flex-wrap items-center gap-3 rounded-lg border px-4 py-3 text-sm">
        <span className="flex-1">This is taking longer than usual.</span>
        <Button size="sm" variant="outline" onClick={onCheckAgain}>Check again</Button>
      </div>
    );
  }
  return (
    <p role="status" aria-live="polite" className="flex items-center gap-2 rounded-lg border px-4 py-3 text-sm text-muted-foreground">
      <Loader2 className="size-4 animate-spin" aria-hidden />
      {STAGE_LABELS[job.stage]}…
      {job.status === "RETRYING" ? <span> Retrying (attempt {job.attempts} of {job.maxAttempts})</span> : null}
    </p>
  );
}
