import { JobProgress } from "@/components/flow/JobProgress";
import type { Attempt } from "@/lib/api/types";
import { useRetryAttempt } from "@/lib/query/practice";
import { Delta, Feedback } from "./Feedback";

function FailedAttempt({ setId, attempt }: { setId: string; attempt: Attempt }) {
  const retry = useRetryAttempt(setId);
  return (
    <div className="space-y-2">
      <JobProgress job={attempt.latestJob} onRetry={() => retry.mutate(attempt.id)} retrying={retry.isPending} startError={retry.error} />
      <blockquote className="border-l-2 pl-3 text-sm text-muted-foreground">{attempt.text}</blockquote>
    </div>
  );
}

/** The latest attempt in full, then earlier attempts collapsed to their score and change. */
export function AttemptList({ setId, attempts }: { setId: string; attempts: Attempt[] }) {
  const latest = attempts.at(-1);
  if (!latest) return null;
  const earlier = attempts.slice(0, -1).reverse();
  return (
    <div className="space-y-4">
      {latest.status === "PENDING" ? <JobProgress job={latest.latestJob} /> : null}
      {latest.status === "FAILED" ? <FailedAttempt setId={setId} attempt={latest} /> : null}
      {latest.status === "SCORED" ? <Feedback attempt={latest} /> : null}
      {earlier.length ? (
        <section className="space-y-2">
          <h3 className="text-sm font-medium text-muted-foreground">Earlier attempts</h3>
          <ul className="space-y-2">
            {earlier.map((attempt) => (
              <li key={attempt.id}>
                <details className="rounded-lg border px-4 py-2 text-sm">
                  <summary className="flex cursor-pointer items-center gap-2">
                    <span>Attempt {attempt.number}</span>
                    {attempt.feedback ? <span className="font-medium">{attempt.feedback.score}</span> : <span className="text-muted-foreground">{attempt.status === "FAILED" ? "Not scored" : "Scoring"}</span>}
                    <Delta value={attempt.scoreDelta} />
                  </summary>
                  <div className="space-y-3 pt-3">
                    <blockquote className="border-l-2 pl-3 text-muted-foreground">{attempt.text}</blockquote>
                    {attempt.status === "FAILED" ? <FailedAttempt setId={setId} attempt={attempt} /> : null}
                    {attempt.feedback ? <p>{attempt.feedback.summary}</p> : null}
                  </div>
                </details>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </div>
  );
}
