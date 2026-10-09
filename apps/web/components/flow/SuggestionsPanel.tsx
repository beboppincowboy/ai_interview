import { RefreshCw } from "lucide-react";
import { LinkedInDialog } from "@/components/library/LinkedInDialog";
import { ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { ProjectFormDialog } from "@/components/library/ProjectFormDialog";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { useRunSuggestions, useSuggestions, scoringKeys } from "@/lib/query/scoring";
import { useFollowJob } from "@/lib/query/useJob";
import { JobProgress } from "./JobProgress";
import { useAutoStart } from "./useAutoStart";

export function SuggestionsPanel({ resumeId, targetJobId }: { resumeId: string; targetJobId: string }) {
  const view = useSuggestions(resumeId, targetJobId);
  const run = useRunSuggestions(resumeId, targetJobId);
  const { job, timedOut, checkAgain } = useFollowJob(view.data?.latestJob, [scoringKeys.suggestions(resumeId, targetJobId)]);
  const data = view.data;
  // Run once when sources exist and nothing has been tried; no request at all without sources (R11).
  useAutoStart(Boolean(data?.sourcesAvailable && !data.result && !data.latestJob), `${resumeId}:${targetJobId}`, () => run.mutate());

  return (
    <section aria-labelledby="suggestions-heading" className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 id="suggestions-heading" className="text-lg font-semibold">From your past experience</h2>
        {data?.stale && job?.status !== "PROCESSING" ? (
          <Button size="sm" variant="outline" onClick={() => run.mutate()} disabled={run.isPending}>
            <RefreshCw /> Refresh
          </Button>
        ) : null}
      </div>
      {data?.stale ? <p className="text-sm text-muted-foreground">Your resumes or experiences changed since these suggestions were made.</p> : null}
      {view.isPending ? <ListSkeleton rows={2} /> : null}
      {view.isError ? <ErrorState error={view.error} onRetry={() => view.refetch()} /> : null}
      {data && !data.sourcesAvailable ? (
        <div className="space-y-3 rounded-lg border border-dashed p-6">
          <p className="font-medium">Add past work to get suggestions</p>
          <p className="text-sm text-muted-foreground">
            Suggestions come from your other resumes and saved experience. Add a project or paste your LinkedIn experience.
          </p>
          <div className="flex flex-wrap gap-2">
            <ProjectFormDialog />
            <LinkedInDialog />
          </div>
        </div>
      ) : null}
      {data?.sourcesAvailable ? (
        <JobProgress job={job} timedOut={timedOut} onCheckAgain={checkAgain} onRetry={() => run.mutate()} retrying={run.isPending} startError={run.error} />
      ) : null}
      {data?.result && data.result.items.length === 0 ? (
        <p className="rounded-lg border p-4 text-sm text-muted-foreground">
          No strong matches for this job in your other resumes or experiences. Adding more past work can help.
        </p>
      ) : null}
      {data?.result?.items.length ? (
        <ul className="space-y-3">
          {data.result.items.map((item, index) => (
            <li key={index} className="space-y-2 rounded-lg border p-4 text-sm">
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-medium">{item.requirement}</span>
                <Badge variant="outline">{item.source.type === "RESUME" ? "Resume" : "Experience"}: {item.source.name}</Badge>
              </div>
              <p>{item.match}</p>
              <p className="text-muted-foreground"><span className="font-medium text-foreground">Why it fits: </span>{item.whyItFits}</p>
              <p className="text-muted-foreground"><span className="font-medium text-foreground">How to present it: </span>{item.guidance}</p>
            </li>
          ))}
        </ul>
      ) : null}
    </section>
  );
}
