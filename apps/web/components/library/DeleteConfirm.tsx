import { useState } from "react";
import { toast } from "sonner";
import {
  AlertDialog, AlertDialogAction, AlertDialogCancel, AlertDialogContent, AlertDialogDescription,
  AlertDialogFooter, AlertDialogHeader, AlertDialogTitle, AlertDialogTrigger
} from "@/components/ui/alert-dialog";
import { Button } from "@/components/ui/button";
import type { DeleteImpact } from "@/lib/api/types";
import { friendlyError } from "@/lib/errorMessages";
import { useDelete, useDeleteImpact, type LibraryKind } from "@/lib/query/library";

const LABELS: [keyof DeleteImpact, string, string][] = [
  ["scores", "score", "scores"],
  ["fits", "job fit result", "job fit results"],
  ["suggestionSets", "suggestion set", "suggestion sets"],
  ["practiceSets", "practice set", "practice sets"],
  ["attempts", "practice answer", "practice answers"],
  ["voiceSessions", "saved voice interview", "saved voice interviews"]
];

export function impactLines(impact: DeleteImpact) {
  const lines = LABELS.filter(([key]) => impact[key] > 0)
    .map(([key, one, many]) => `${impact[key]} ${impact[key] === 1 ? one : many}`);
  return { lines, stale: impact.staleSuggestionSets };
}

export function DeleteConfirm({ kind, id, name, disabledReason }: {
  kind: LibraryKind;
  id: string;
  name: string;
  disabledReason?: string;
}) {
  const [open, setOpen] = useState(false);
  const impact = useDeleteImpact(kind, id, open);
  const remove = useDelete(kind);

  if (disabledReason) {
    return <Button variant="ghost" size="sm" disabled title={disabledReason} aria-label={`Delete ${name}: ${disabledReason}`}>Delete</Button>;
  }

  const summary = impact.data ? impactLines(impact.data) : null;
  return (
    <AlertDialog open={open} onOpenChange={setOpen}>
      <AlertDialogTrigger asChild>
        <Button variant="ghost" size="sm" className="text-destructive hover:text-destructive" aria-label={`Delete ${name}`}>
          Delete
        </Button>
      </AlertDialogTrigger>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete “{name}”?</AlertDialogTitle>
          <AlertDialogDescription asChild>
            <div className="space-y-2 text-sm text-muted-foreground">
              {impact.isPending ? <p>Checking what will be removed…</p> : null}
              {impact.isError ? <p>{friendlyError(impact.error)}</p> : null}
              {summary ? (
                summary.lines.length > 0 ? (
                  <>
                    <p>This also removes:</p>
                    <ul className="list-disc pl-5">{summary.lines.map((line) => <li key={line}>{line}</li>)}</ul>
                  </>
                ) : <p>Nothing else depends on it.</p>
              ) : null}
              {summary && summary.stale > 0 ? (
                <p>{summary.stale} suggestion {summary.stale === 1 ? "set" : "sets"} that used it will offer a refresh.</p>
              ) : null}
              <p>This cannot be undone.</p>
            </div>
          </AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction
            className="bg-destructive text-white hover:bg-destructive/90"
            disabled={!impact.data || remove.isPending}
            onClick={(event) => {
              event.preventDefault();
              remove.mutate(id, {
                onSuccess: () => {
                  setOpen(false);
                  toast.success(`Deleted “${name}”.`);
                },
                onError: (failure) => toast.error(friendlyError(failure))
              });
            }}
          >
            Delete
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  );
}
