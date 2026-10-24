import { ChevronRight } from "lucide-react";
import { getRouteApi, useNavigate } from "@tanstack/react-router";
import { Stepper } from "@/components/flow/Stepper";
import { StepHeading } from "@/components/flow/StepHeading";
import { AddTargetJobDialog } from "@/components/library/AddTargetJobDialog";
import { DeletedState } from "@/components/library/DeletedState";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { isNotFound } from "@/lib/api/isNotFound";
import { saveLastPair } from "@/lib/lastPair";
import { useResume, useTargetJobs } from "@/lib/query/library";

const route = getRouteApi("/flow/$resumeId/jobs");

export default function TargetJobPickerPage() {
  const { resumeId } = route.useParams();
  const navigate = useNavigate();
  const resume = useResume(resumeId);
  const jobs = useTargetJobs();
  const choose = (targetJobId: string) => {
    saveLastPair({ resumeId, targetJobId });
    void navigate({ to: "/flow/$resumeId/jobs/$targetJobId", params: { resumeId, targetJobId } });
  };
  if (isNotFound(resume.error)) return <DeletedState what="resume" />;
  const add = <AddTargetJobDialog onAdded={(job) => choose(job.id)} />;

  return (
    <>
      <Stepper current={3} resumeId={resumeId} />
      <StepHeading
        title="Choose a target job"
        description={resume.data ? `See how ${resume.data.name} fits a job, and practice for it.` : undefined}
        action={add}
      />
      {jobs.isPending ? <ListSkeleton /> : null}
      {jobs.isError ? <ErrorState error={jobs.error} onRetry={() => jobs.refetch()} /> : null}
      {jobs.data?.items.length === 0 ? (
        <EmptyState title="No target jobs yet" description="Paste a job description to check your fit." action={add} />
      ) : null}
      <ul className="space-y-2">
        {jobs.data?.items.map((job) => (
          <li key={job.id}>
            <button
              type="button"
              onClick={() => choose(job.id)}
              className="flex w-full items-center gap-3 rounded-lg border bg-card px-4 py-3 text-left transition-colors hover:border-primary/50 hover:bg-accent/40"
            >
              <span className="flex-1 font-medium">{job.name}</span>
              <ChevronRight className="size-4 text-muted-foreground" aria-hidden />
            </button>
          </li>
        ))}
      </ul>
    </>
  );
}
