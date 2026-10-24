import { useEffect, useState } from "react";
import { AddResumeDialog } from "@/components/library/AddResumeDialog";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { ResumeRow } from "@/components/library/ResumeRow";
import { UploadWatcher } from "@/components/library/UploadWatcher";
import { PageHeader } from "@/components/shell/AppShell";
import { useResumes } from "@/lib/query/library";
import { readResumeUploadJobs, RESUME_UPLOAD_JOBS_KEY, saveResumeUploadJobs } from "@/lib/resumeUploadJobs";

export default function ResumesPage() {
  const resumes = useResumes();
  // Upload jobs to watch: an upload whose text matches a saved resume turns into a duplicate notice.
  const [uploadJobs, setUploadJobs] = useState<string[]>(readResumeUploadJobs);
  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === RESUME_UPLOAD_JOBS_KEY) setUploadJobs(readResumeUploadJobs());
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);
  const addUploadJob = (jobId: string) => setUploadJobs((jobs) => {
    const next = jobs.includes(jobId) ? jobs : [...jobs, jobId];
    saveResumeUploadJobs(next);
    return next;
  });
  const dismissUploadJob = (jobId: string) => setUploadJobs((jobs) => {
    const next = jobs.filter((id) => id !== jobId);
    saveResumeUploadJobs(next);
    return next;
  });
  const add = (
    <AddResumeDialog onAdded={(resume) => resume.latestJob && addUploadJob(resume.latestJob.jobId)} />
  );

  return (
    <>
      <PageHeader title="Resumes" description="Named resumes you can score and tailor." action={add} />
      <div className="space-y-3">
        {uploadJobs.map((jobId) => (
          <UploadWatcher key={jobId} jobId={jobId} onDismiss={() => dismissUploadJob(jobId)} />
        ))}
        {resumes.isPending ? <ListSkeleton /> : null}
        {resumes.isError ? <ErrorState error={resumes.error} onRetry={() => resumes.refetch()} /> : null}
        {resumes.data?.items.length === 0 ? (
          <EmptyState title="No resumes yet" description="Upload a file or paste your resume text to get a score." action={add} />
        ) : null}
        {resumes.data?.items.length ? (
          <ul className="space-y-3">{resumes.data.items.map((resume) => <ResumeRow key={resume.id} resume={resume} />)}</ul>
        ) : null}
      </div>
    </>
  );
}
