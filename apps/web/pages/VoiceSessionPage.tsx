import { getRouteApi, Link, useNavigate } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { DeletedState } from "@/components/library/DeletedState";
import { ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { PageHeader } from "@/components/shell/AppShell";
import { Button } from "@/components/ui/button";
import { AlertDialog, AlertDialogContent, AlertDialogHeader, AlertDialogTitle, AlertDialogDescription, AlertDialogFooter, AlertDialogCancel, AlertDialogAction, AlertDialogTrigger } from "@/components/ui/alert-dialog";
import { VoiceReport } from "@/components/voice/VoiceReport";
import { isNotFound } from "@/lib/api/isNotFound";
import { isTerminal } from "@/lib/api/types";
import { useJob } from "@/lib/query/useJob";
import { useDeleteVoiceSession, useRetryVoiceReport, useVoiceSession, voiceKeys } from "@/lib/query/voice";

const route = getRouteApi("/voice/sessions/$sessionId");

export default function VoiceSessionPage() {
  const { sessionId } = route.useParams();
  const session = useVoiceSession(sessionId);
  const client = useQueryClient();
  const retry = useRetryVoiceReport(sessionId);
  const remove = useDeleteVoiceSession(sessionId);
  const navigate = useNavigate();
  const [deleteOpen, setDeleteOpen] = useState(false);
  const data = session.data;
  const job = useJob(data?.report ? null : data?.reportJobId);
  const status = job.job?.status;
  useEffect(() => {
    if (isTerminal(status)) void client.invalidateQueries({ queryKey: voiceKeys.session(sessionId) });
  }, [status, sessionId, client]);

  if (isNotFound(session.error)) return <DeletedState what="voice interview" />;
  return <>
    <PageHeader title="Saved spoken interview" description="Your reviewed transcript and answer feedback." />
    <Button asChild variant="outline"><Link to="/history">Back to history</Link></Button>
    {session.isPending ? <ListSkeleton /> : null}
    {session.isError ? <ErrorState error={session.error} onRetry={() => session.refetch()} /> : null}
    {data?.status === "SAVED" ? <div className="space-y-6">
      {!data.report ? <section className="space-y-3 rounded-xl border p-5" aria-live="polite">
        <p>{status === "FAILED" ? "The report could not be completed. Your saved answers are safe." : "Preparing your report…"}</p>
        {job.error ? <ErrorState error={job.error} onRetry={job.checkAgain} /> : null}
        {job.timedOut ? <Button variant="outline" onClick={job.checkAgain}>Check report again</Button> : null}
        {status === "FAILED" ? <Button disabled={retry.isPending} onClick={() => retry.mutate()}>Retry report</Button> : null}
        {retry.isError ? <ErrorState error={retry.error} /> : null}
      </section> : null}
      <VoiceReport session={data} />
      <AlertDialog open={deleteOpen} onOpenChange={setDeleteOpen}>
        <AlertDialogTrigger asChild><Button variant="destructive">Delete interview</Button></AlertDialogTrigger>
        <AlertDialogContent>
          <AlertDialogHeader><AlertDialogTitle>Delete this interview?</AlertDialogTitle><AlertDialogDescription>This permanently removes its saved transcript, report and report jobs.</AlertDialogDescription></AlertDialogHeader>
          {remove.isError ? <ErrorState error={remove.error} /> : null}
          <AlertDialogFooter><AlertDialogCancel disabled={remove.isPending}>Cancel</AlertDialogCancel><AlertDialogAction disabled={remove.isPending} onClick={(event) => { event.preventDefault(); remove.mutate(undefined, { onSuccess: () => { setDeleteOpen(false); void navigate({ to: "/history" }); } }); }}>Delete permanently</AlertDialogAction></AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div> : data ? <p>This interview was not saved. Unsaved transcript text cannot be restored after reload.</p> : null}
  </>;
}
