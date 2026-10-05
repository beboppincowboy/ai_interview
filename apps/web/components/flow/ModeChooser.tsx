import { Mic, MessageSquareText } from "lucide-react";
import { useNavigate } from "@tanstack/react-router";
import { VOICE_ENABLED } from "@/lib/api/config";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { friendlyError } from "@/lib/errorMessages";
import { useCreatePracticeSet } from "@/lib/query/practice";

export function ModeChooser({ resumeId, targetJobId, fitReady }: { resumeId: string; targetJobId: string; fitReady: boolean }) {
  const navigate = useNavigate();
  const create = useCreatePracticeSet();
  return (
    <section aria-labelledby="mode-heading" className="space-y-4">
      <h2 id="mode-heading" className="text-lg font-semibold">How do you want to practice?</h2>
      <div className="grid gap-4 md:grid-cols-2">
        <div className="space-y-3 rounded-xl border bg-card p-5">
          <div className="flex items-center gap-2 font-medium"><MessageSquareText className="size-4 text-primary" aria-hidden /> Practice (chat)</div>
          <p className="text-sm text-muted-foreground">Answer questions chosen for this job, get feedback, and improve each answer.</p>
          <Button
            disabled={!fitReady || create.isPending}
            onClick={() => create.mutate({ resumeId, targetJobId }, { onSuccess: (set) => void navigate({ to: "/practice/$setId", params: { setId: set.id } }) })}
          >
            Practice this job
          </Button>
          {!fitReady ? <p className="text-xs text-muted-foreground">Available once the job fit is ready.</p> : null}
          {create.isError ? <p role="alert" className="text-sm text-destructive">{friendlyError(create.error)}</p> : null}
        </div>
        <div className="space-y-3 rounded-xl border bg-muted/40 p-5" aria-disabled={!VOICE_ENABLED}>
          <div className="flex items-center gap-2 font-medium text-muted-foreground">
            <Mic className="size-4" aria-hidden /> Mock interview (voice) {!VOICE_ENABLED ? <Badge variant="secondary">Coming soon</Badge> : null}
          </div>
          <p className="text-sm text-muted-foreground">A spoken interview with a report on your weakest answers.</p>
          {VOICE_ENABLED ? <Button disabled={!fitReady || create.isPending} onClick={() => create.mutate({ resumeId, targetJobId }, { onSuccess: (set) => void navigate({ to: "/voice/$setId", params: { setId: set.id } }) })}>Start voice practice</Button> : null}
        </div>
      </div>
    </section>
  );
}
