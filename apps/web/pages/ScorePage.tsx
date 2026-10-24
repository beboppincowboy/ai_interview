import { ArrowRight } from "lucide-react";
import { getRouteApi, Link } from "@tanstack/react-router";
import { useState } from "react";
import { JobProgress } from "@/components/flow/JobProgress";
import { ScoreCard } from "@/components/flow/ScoreCard";
import { Stepper } from "@/components/flow/Stepper";
import { StepHeading } from "@/components/flow/StepHeading";
import { useAutoStart } from "@/components/flow/useAutoStart";
import { DeletedState } from "@/components/library/DeletedState";
import { ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { isNotFound } from "@/lib/api/isNotFound";
import { friendlyError } from "@/lib/errorMessages";
import { keys, useResume, useUpdateResume } from "@/lib/query/library";
import { useScoreResume } from "@/lib/query/scoring";
import { useFollowJob } from "@/lib/query/useJob";

function JobTitleField({ resumeId, jobTitle }: { resumeId: string; jobTitle: string | null }) {
  const [draft, setDraft] = useState(jobTitle ?? "");
  const update = useUpdateResume();
  const changed = draft.trim() !== (jobTitle ?? "");
  return (
    <form
      className="flex flex-wrap items-end gap-2"
      onSubmit={(event) => {
        event.preventDefault();
        if (changed && draft.length <= 100) update.mutate({ id: resumeId, jobTitle: draft.trim() || null });
      }}
    >
      <div className="space-y-2">
        <Label htmlFor="job-title">Job title <span className="font-normal text-muted-foreground">(optional)</span></Label>
        <Input id="job-title" className="w-72" value={draft} maxLength={100} placeholder="For example: Backend Engineer" onChange={(event) => setDraft(event.target.value)} />
      </div>
      <Button type="submit" variant="outline" disabled={!changed || update.isPending}>Save</Button>
      {update.isError ? <p className="w-full text-sm text-destructive">{friendlyError(update.error)}</p> : null}
    </form>
  );
}

const route = getRouteApi("/flow/$resumeId");

export default function ScorePage() {
  const { resumeId } = route.useParams();
  const resume = useResume(resumeId);
  const score = useScoreResume(resumeId);
  const { job, timedOut, checkAgain } = useFollowJob(resume.data?.latestJob, [keys.resume(resumeId), keys.resumes]);

  const data = resume.data;
  // Score automatically only when this resume has never been scored or tried; re-scores are the user's call (R8).
  useAutoStart(Boolean(data && data.status === "READY" && !data.score && data.latestJob?.jobType !== "RESUME_SCORE"), resumeId, () => score.mutate());

  if (isNotFound(resume.error)) return <DeletedState what="resume" />;
  const scoring = job?.jobType === "RESUME_SCORE" && job.status !== "SUCCEEDED" && job.status !== "FAILED";

  return (
    <>
      <Stepper current={2} resumeId={resumeId} />
      <StepHeading
        title={data ? `Score for ${data.name}` : "Score"}
        description="A general score from your resume and optional job title. Add a target job next for job-specific feedback."
        action={data?.score ? (
          <Button asChild><Link to="/flow/$resumeId/jobs" params={{ resumeId }}>Continue to target job <ArrowRight /></Link></Button>
        ) : undefined}
      />
      {resume.isPending ? <ListSkeleton rows={4} /> : null}
      {resume.isError && !isNotFound(resume.error) ? <ErrorState error={resume.error} onRetry={() => resume.refetch()} /> : null}
      {data ? (
        <div className="space-y-6">
          <div className="flex flex-wrap items-end justify-between gap-4">
            <JobTitleField key={data.jobTitle ?? ""} resumeId={resumeId} jobTitle={data.jobTitle} />
            {data.latestScore?.stale && !scoring ? (
              <div className="flex items-center gap-2 text-sm">
                <span className="text-muted-foreground">The job title changed since this score.</span>
                <Button onClick={() => score.mutate()} disabled={score.isPending}>Re-score</Button>
              </div>
            ) : null}
          </div>
          <JobProgress
            job={job}
            timedOut={timedOut}
            onCheckAgain={checkAgain}
            onRetry={() => score.mutate()}
            retrying={score.isPending}
            startError={score.error}
          />
          {data.score ? <ScoreCard score={data.score} stale={data.latestScore?.stale} /> : null}
        </div>
      ) : null}
    </>
  );
}
