import { getRouteApi } from "@tanstack/react-router";
import { FitCard } from "@/components/flow/FitCard";
import { JobProgress } from "@/components/flow/JobProgress";
import { ModeChooser } from "@/components/flow/ModeChooser";
import { Stepper } from "@/components/flow/Stepper";
import { StepHeading } from "@/components/flow/StepHeading";
import { SuggestionsPanel } from "@/components/flow/SuggestionsPanel";
import { useAutoStart } from "@/components/flow/useAutoStart";
import { DeletedState } from "@/components/library/DeletedState";
import { ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { isNotFound } from "@/lib/api/isNotFound";
import { useResume, useTargetJob } from "@/lib/query/library";
import { scoringKeys, useFit, useRunFit } from "@/lib/query/scoring";
import { useFollowJob } from "@/lib/query/useJob";

const route = getRouteApi("/flow/$resumeId/jobs/$jobId");

export default function FitPage() {
  const { resumeId, jobId } = route.useParams();
  const resume = useResume(resumeId);
  const targetJob = useTargetJob(jobId);
  const fit = useFit(resumeId, jobId);
  const run = useRunFit(resumeId, jobId);
  const { job, timedOut, checkAgain } = useFollowJob(fit.data?.latestJob, [scoringKeys.fit(resumeId, jobId)]);
  useAutoStart(Boolean(fit.data && !fit.data.result && !fit.data.latestJob), `${resumeId}:${jobId}`, () => run.mutate());

  if (isNotFound(resume.error)) return <DeletedState what="resume" />;
  if (isNotFound(targetJob.error) || isNotFound(fit.error)) return <DeletedState what="target job" />;
  const fitReady = Boolean(fit.data?.result);

  return (
    <>
      <Stepper current={fitReady ? 5 : 4} resumeId={resumeId} targetJobId={jobId} />
      <StepHeading
        title={targetJob.data ? `Fit for ${targetJob.data.name}` : "Job fit"}
        description={resume.data ? `How ${resume.data.name} matches this job, and what to add.` : undefined}
      />
      <div className="space-y-12">
        <section aria-label="Job fit" className="space-y-4">
          {fit.isPending ? <ListSkeleton rows={3} /> : null}
          {fit.isError && !isNotFound(fit.error) ? <ErrorState error={fit.error} onRetry={() => fit.refetch()} /> : null}
          <JobProgress job={job} timedOut={timedOut} onCheckAgain={checkAgain} onRetry={() => run.mutate()} retrying={run.isPending} startError={run.error} />
          {fit.data?.result ? <FitCard fit={fit.data.result} /> : null}
        </section>
        <SuggestionsPanel resumeId={resumeId} targetJobId={jobId} />
        <ModeChooser resumeId={resumeId} targetJobId={jobId} fitReady={fitReady} />
      </div>
    </>
  );
}
