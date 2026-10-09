import type { LatestJob } from "@/lib/api/types";
import { practiceKeys } from "@/lib/query/practice";
import { useFollowJob } from "@/lib/query/useJob";

/** Polls one pending attempt's job and refreshes the set when it ends. Several can run at once (R18). */
export function AttemptWatcher({ setId, job }: { setId: string; job: LatestJob }) {
  useFollowJob(job, [practiceKeys.set(setId)]);
  return null;
}
