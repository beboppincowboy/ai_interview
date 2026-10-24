import { Plus } from "lucide-react";
import { useState } from "react";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle, DialogTrigger } from "@/components/ui/dialog";
import type { Experience } from "@/lib/api/types";
import { friendlyError } from "@/lib/errorMessages";
import { useCreateExperience } from "@/lib/query/library";
import { DuplicateNotice } from "./DuplicateNotice";
import { TextField } from "./TextField";

/** Creates a manual experience; new items call optional `onAdded`, duplicates are shown in the dialog, and title/description have 120/2,000 character limits. */
export function ProjectFormDialog({ onAdded, trigger }: { onAdded?: (experience: Experience) => void; trigger?: React.ReactNode }) {
  const [open, setOpen] = useState(false);
  const [title, setTitle] = useState("");
  const [organization, setOrganization] = useState("");
  const [description, setDescription] = useState("");
  const [submitted, setSubmitted] = useState(false);
  const [duplicate, setDuplicate] = useState<Experience | null>(null);
  const create = useCreateExperience();

  const titleError = submitted && !title.trim() ? "Add a title." : null;
  const descriptionError = submitted && !description.trim() ? "Describe what you did." : null;

  function reset() {
    setTitle("");
    setOrganization("");
    setDescription("");
    setSubmitted(false);
    setDuplicate(null);
    create.reset();
  }

  return (
    <Dialog open={open} onOpenChange={(next) => { setOpen(next); if (!next) reset(); }}>
      <DialogTrigger asChild>{trigger ?? <Button variant="outline"><Plus /> Add a project</Button>}</DialogTrigger>
      <DialogContent className="sm:max-w-xl">
        <DialogHeader>
          <DialogTitle>Add a project</DialogTitle>
          <DialogDescription>Past work that is not on your resume can still be suggested when it fits a job.</DialogDescription>
        </DialogHeader>
        <form
          className="space-y-4"
          noValidate
          onSubmit={(event) => {
            event.preventDefault();
            setSubmitted(true);
            setDuplicate(null);
            if (!title.trim() || title.length > 120 || !description.trim() || description.length > 2_000) return;
            create.mutate(
              { title: title.trim(), organization: organization.trim() || null, startDate: null, endDate: null, description: description.trim() },
              {
                onSuccess: ({ experience, duplicate: isDuplicate }) => {
                  if (isDuplicate) return setDuplicate(experience);
                  toast.success(`Saved “${experience.title}”.`);
                  onAdded?.(experience);
                  setOpen(false);
                  reset();
                }
              }
            );
          }}
        >
          <TextField label="Title" value={title} onChange={setTitle} max={120} error={titleError} />
          <TextField label="Organization" optional value={organization} onChange={setOrganization} max={120} />
          <TextField label="What you did" multiline rows={6} value={description} onChange={setDescription} max={2_000} error={descriptionError} />
          {duplicate ? <DuplicateNotice kind="experiences" id={duplicate.id} name={duplicate.title} max={120} /> : null}
          {create.isError ? <p role="alert" className="text-sm text-destructive">{friendlyError(create.error)}</p> : null}
          <div className="flex justify-end gap-2">
            <Button type="button" variant="ghost" onClick={() => setOpen(false)}>Cancel</Button>
            <Button type="submit" disabled={create.isPending}>Save project</Button>
          </div>
        </form>
      </DialogContent>
    </Dialog>
  );
}
