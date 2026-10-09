import { Link } from "@tanstack/react-router";
import type { ReactNode } from "react";
import { Sparkline } from "@/components/history/Sparkline";
import { EmptyState, ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { Delta } from "@/components/practice/Feedback";
import { PageHeader } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/button";
import { useHistory } from "@/lib/query/library";

function Section({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-lg font-semibold">{title}</h2>
      <ul className="space-y-2">{children}</ul>
    </section>
  );
}

const rowClass = "flex flex-wrap items-center gap-4 rounded-lg border bg-card px-4 py-3 transition-colors hover:border-primary/50";

export default function HistoryPage() {
  const history = useHistory();
  const data = history.data;
  const empty = data && !data.resumes.some((resume) => resume.scores.length) && !data.targetJobs.some((targetJob) => targetJob.fits.length) && data.practiceSets.length === 0 && data.voiceSessions.length === 0;

  return (
    <>
      <PageHeader title="History" description="Your scores, job fits and practice over time." />
      {history.isPending ? <ListSkeleton rows={4} /> : null}
      {history.isError ? <ErrorState error={history.error} onRetry={() => history.refetch()} /> : null}
      {empty ? (
        <EmptyState
          title="Nothing here yet"
          description="Score a resume and practice for a job; your progress shows up here."
          action={<Button asChild><Link to="/flow">Start</Link></Button>}
        />
      ) : null}
      {data && !empty ? (
        <div className="space-y-10">
          <Section title="Spoken interviews">
            {data.voiceSessions.map((session) => (
              <li key={session.id}>
                <Link to="/voice/sessions/$sessionId" params={{ sessionId: session.id }} className={rowClass}>
                  <span className="min-w-0 flex-1 truncate"><span className="font-medium">{session.targetJobName}</span> with {session.resumeName}</span>
                  <span className="text-sm">{session.answeredCount} of {session.selectedCount} answered</span>
                  <span className="font-semibold">{session.overallScore !== null ? `Score ${session.overallScore}` : session.reportStatus === "FAILED" ? "Report failed" : "Report pending"}</span>
                </Link>
              </li>
            ))}
          </Section>
          <Section title="Resume scores">
            {data.resumes.filter((resume) => resume.scores.length).map((resume) => {
              const values = resume.scores.map((score) => score.overall);
              return (
                <li key={resume.id}>
                  <Link to="/flow/$resumeId" params={{ resumeId: resume.id }} className={rowClass}>
                    <span className="min-w-0 flex-1 truncate font-medium">{resume.name}</span>
                    <Sparkline values={values} noun="scores" />
                    <span className="font-semibold">{values.at(-1)}</span>
                  </Link>
                </li>
              );
            })}
          </Section>
          <Section title="Job fit">
            {data.targetJobs.flatMap((targetJob) => targetJob.fits.map((fit) => (
              <li key={`${targetJob.id}:${fit.resumeId}`}>
                <Link to="/flow/$resumeId/jobs/$targetJobId" params={{ resumeId: fit.resumeId, targetJobId: targetJob.id }} className={rowClass}>
                  <span className="min-w-0 flex-1 truncate"><span className="font-medium">{targetJob.name}</span> <span className="text-muted-foreground">with {fit.resumeName}</span></span>
                  <span className="font-semibold">{fit.fitScore}</span>
                </Link>
              </li>
            )))}
          </Section>
          <Section title="Practice">
            {data.practiceSets.map((set) => (
              <li key={set.id} className="rounded-lg border bg-card">
                <Link to="/practice/$setId" params={{ setId: set.id }} className="flex flex-wrap items-center gap-2 px-4 py-3 font-medium hover:text-primary">
                  {set.targetJobName} <span className="font-normal text-muted-foreground">with {set.resumeName}</span>
                </Link>
                <ul className="divide-y border-t">
                  {set.questions.filter((question) => question.scores.length).map((question) => {
                    const delta = question.scores.length > 1 ? question.scores.at(-1)! - question.scores.at(-2)! : null;
                    return (
                      <li key={question.id} className="flex flex-wrap items-center gap-4 px-4 py-2 text-sm">
                        <span className="min-w-0 flex-1 truncate">{question.text}</span>
                        <Sparkline values={question.scores} noun="attempts" />
                        <span className="font-semibold">{question.scores.at(-1)}</span>
                        <Delta value={delta} />
                      </li>
                    );
                  })}
                </ul>
              </li>
            ))}
          </Section>
        </div>
      ) : null}
    </>
  );
}
