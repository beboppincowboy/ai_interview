import { ChevronDown, Loader2 } from "lucide-react";
import { useState } from "react";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { STAGE_LABELS } from "@/lib/api/jobLabels";
import type { Resume } from "@/lib/api/types";
import { friendlyError } from "@/lib/errorMessages";
import { useResume } from "@/lib/query/library";
import { DeleteConfirm } from "./DeleteConfirm";
import { InlineRename } from "./InlineRename";

export function ResumeRow({ resume }: { resume: Resume }) {
  const [expanded, setExpanded] = useState(false);
  const detail = useResume(resume.id, { enabled: expanded });
  const processing = resume.status === "PROCESSING";

  return (
    <li id={`item-${resume.id}`} className="scroll-mt-20 rounded-lg border bg-card target:ring-2 target:ring-primary">
      <div className="flex flex-wrap items-center gap-3 px-4 py-3">
        <div className="min-w-0 flex-1 space-y-1">
          <div className="flex flex-wrap items-center gap-2">
            <span className="truncate font-medium">{resume.name}</span>
            {resume.latestScore ? <Badge variant="secondary">Score {resume.latestScore.overall}</Badge> : null}
            {resume.status === "FAILED" ? <Badge variant="destructive">Failed</Badge> : null}
          </div>
          <p className="text-xs text-muted-foreground">
            {resume.source === "UPLOAD" ? resume.originalFilename : "Pasted text"}
            {resume.jobTitle ? ` · ${resume.jobTitle}` : ""}
          </p>
          {processing ? (
            <p className="flex items-center gap-2 text-sm text-muted-foreground" aria-live="polite">
              <Loader2 className="size-3 animate-spin" aria-hidden />
              {resume.latestJob ? STAGE_LABELS[resume.latestJob.stage] : "Processing"}…
            </p>
          ) : null}
          {resume.status === "FAILED" ? (
            <p className="text-sm text-destructive">
              {friendlyError(resume.latestJob?.error ?? null, "The text could not be read from this file.")} Delete it and try another file.
            </p>
          ) : null}
        </div>
        <div className="flex items-center gap-1">
          <InlineRename kind="resumes" id={resume.id} name={resume.name} max={80} />
          <DeleteConfirm
            kind="resumes"
            id={resume.id}
            name={resume.name}
            disabledReason={processing ? "Wait until processing finishes" : undefined}
          />
          {resume.status === "READY" ? (
            <Button
              variant="ghost"
              size="icon"
              aria-expanded={expanded}
              aria-label={expanded ? "Hide text" : "Show text"}
              onClick={() => setExpanded((value) => !value)}
            >
              <ChevronDown className={expanded ? "rotate-180 transition-transform" : "transition-transform"} />
            </Button>
          ) : null}
        </div>
      </div>
      {expanded ? (
        <div className="border-t px-4 py-3">
          {detail.data?.text ? (
            <pre className="max-h-80 overflow-auto whitespace-pre-wrap font-sans text-sm text-muted-foreground">{detail.data.text}</pre>
          ) : (
            <p className="text-sm text-muted-foreground">{detail.isError ? friendlyError(detail.error) : "Loading…"}</p>
          )}
        </div>
      ) : null}
    </li>
  );
}
