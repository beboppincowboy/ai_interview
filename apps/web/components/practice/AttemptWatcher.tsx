import type { LatestJob } from "@/lib/api/types";
import { practiceKeys } from "@/lib/query/practice";
import { useFollowJob } from "@/lib/query/useJob";

/** Polls this attempt's nonterminal latest job, invalidates the set on completion, and renders no UI; one watcher can run per attempt. */
export function AttemptWatcher({ setId, job }: { setId: string; job: LatestJob }) {
  useFollowJob(job, [practiceKeys.set(setId)]);
  return null;
}
