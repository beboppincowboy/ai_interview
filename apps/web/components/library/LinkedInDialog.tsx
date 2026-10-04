import { Loader2 } from "lucide-react";
import { useEffect, useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import { STAGE_LABELS } from "@/lib/api/jobLabels";
import { isTerminal, type ExperienceDraft, type ExperienceSplitResult } from "@/lib/api/types";
import { ApiError } from "@/lib/api/client";
import { errorCode, friendlyError } from "@/lib/errorMessages";
import { clearLinkedInRecovery, readLinkedInRecovery, writeLinkedInRecovery } from "@/lib/linkedInRecovery";
import { useSaveExperiences, useSplitLinkedIn } from "@/lib/query/library";
import { useJob } from "@/lib/query/useJob";
import { TextField } from "./TextField";

/** Paste LinkedIn Experience text, let the AI split it, review the items, then save the ones kept. */
export function LinkedInDialog({ onSaved, trigger }: { onSaved?: () => void; trigger?: React.ReactNode }) {
  const [recovery] = useState(readLinkedInRecovery);
  const [open, setOpen] = useState(Boolean(recovery?.text || recovery?.jobId));
  const [text, setText] = useState(recovery?.text ?? "");
  const [jobId, setJobId] = useState<string | null>(recovery?.jobId ?? null);
  const [removed, setRemoved] = useState<number[]>(recovery?.removed ?? []);
  const split = useSplitLinkedIn();
  const save = useSaveExperiences();
  const { job, error: pollError, timedOut, checkAgain } = useJob<ExperienceSplitResult>(jobId);

  const items: ExperienceDraft[] = job?.status === "SUCCEEDED" ? job.result?.items ?? [] : [];
  const kept = items.filter((item, index) => !item.duplicateOf && !removed.includes(index));
  const failed = job?.status === "FAILED";
  const lostJob = jobId !== null && ["JOB_NOT_FOUND", "NOT_FOUND"].includes(errorCode(pollError) ?? "");
  const working = split.isPending || (jobId !== null && !job?.status && (!pollError || pollError instanceof ApiError && pollError.retryable)) || (job && !isTerminal(job.status));
  const tooShort = text.trim().length < 50;

  useEffect(() => writeLinkedInRecovery({ jobId, text, removed }), [jobId, text, removed]);

  function start() {
    setRemoved([]);
    split.mutate(text, { onSuccess: (accepted) => setJobId(accepted.jobId) });
  }

  function discard() {
    clearLinkedInRecovery();
    setOpen(false);
    setText("");
    setJobId(null);
    setRemoved([]);
    split.reset();
    save.reset();
  }

  return (
    <Dialog open={open} onOpenChange={(nextOpen) => {
      if (!nextOpen && save.isPending) return;
      setOpen(nextOpen);
    }}>
      <DialogTrigger asChild>{trigger ?? <Button variant="outline">Paste LinkedIn experience</Button>}</DialogTrigger>
      <DialogContent className="max-h-[90dvh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Paste LinkedIn experience</DialogTitle>
          <DialogDescription>Copy the Experience section from your LinkedIn profile. You review every item before it is saved.</DialogDescription>
        </DialogHeader>

        {job?.status !== "SUCCEEDED" ? (
          <div className="space-y-4">
            <TextField label="Experience text" multiline value={text} onChange={setText} max={20_000} />
            {working ? (
              <p className="flex items-center gap-2 text-sm text-muted-foreground" aria-live="polite">
                <Loader2 className="size-4 animate-spin" aria-hidden />
                {job ? STAGE_LABELS[job.stage] : "Starting"}…
                {job?.status === "RETRYING" ? ` (attempt ${job.attempts} of ${job.maxAttempts})` : ""}
              </p>
            ) : null}
            {failed || split.isError || pollError ? (
              <p role="alert" className="text-sm text-destructive">{friendlyError(job?.error ?? split.error ?? pollError)}</p>
            ) : null}
            {lostJob ? (
              <p role="status" className="text-sm">This split expired or is no longer available. Your saved text is still available.</p>
            ) : null}
            {timedOut ? (
              <p className="text-sm">This is taking longer than usual. <Button variant="link" className="h-auto p-0" onClick={checkAgain}>Check again</Button></p>
            ) : null}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
              <Button variant="ghost" onClick={discard}>Discard</Button>
              <Button onClick={start} disabled={tooShort || Boolean(working)}>{failed || split.isError || lostJob ? "Split again" : "Split into items"}</Button>
            </div>
          </div>
        ) : items.length === 0 ? (
          <div className="space-y-4">
            <p>No experience items were found in that text. Check that you copied the Experience section, then try again.</p>
            <div className="flex justify-end gap-2">
              <Button variant="outline" onClick={() => setJobId(null)}>Edit the text</Button>
              <Button variant="ghost" onClick={discard}>Discard</Button>
            </div>
          </div>
        ) : (
          <div className="space-y-4">
            <ul className="space-y-2">
              {items.map((item, index) => {
                if (removed.includes(index)) return null;
                return (
                  <li key={index} className="rounded-lg border px-4 py-3">
                    <div className="flex items-start gap-3">
                      <div className="min-w-0 flex-1 space-y-1">
                        <p className="font-medium">{item.title}</p>
                        <p className="line-clamp-3 text-sm text-muted-foreground">{item.description}</p>
                        {item.duplicateOf ? (
                          <p className="text-sm text-muted-foreground">Already saved as “{item.duplicateOf.title}”; it will be skipped.</p>
                        ) : null}
                      </div>
                      {!item.duplicateOf ? (
                        <Button variant="ghost" size="sm" onClick={() => setRemoved((list) => [...list, index])} disabled={save.isPending} aria-label={`Remove ${item.title}`}>
                          Remove
                        </Button>
                      ) : null}
                    </div>
                  </li>
                );
              })}
            </ul>
            {save.isError ? <p role="alert" className="text-sm text-destructive">{friendlyError(save.error)}</p> : null}
            <div className="flex justify-end gap-2">
              <Button variant="ghost" onClick={() => setJobId(null)} disabled={save.isPending}>Back</Button>
              <Button variant="ghost" onClick={discard} disabled={save.isPending}>Discard</Button>
              <Button
                disabled={kept.length === 0 || save.isPending}
                onClick={() => save.mutate(
                  kept.map(({ duplicateOf: _duplicate, ...item }) => item),
                  {
                    onSuccess: (result) => {
                      const skipped = result.skipped.length ? ` ${result.skipped.length} already saved.` : "";
                      toast.success(`Saved ${result.created.length} ${result.created.length === 1 ? "item" : "items"}.${skipped}`);
                      onSaved?.();
                      discard();
                    }
                  }
                )}
              >
                Save {kept.length} {kept.length === 1 ? "item" : "items"}
              </Button>
            </div>
          </div>
        )}
      </DialogContent>
    </Dialog>
  );
}
