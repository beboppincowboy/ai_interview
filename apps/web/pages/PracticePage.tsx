import { getRouteApi } from "@tanstack/react-router";
import { useState } from "react";
import { JobProgress } from "@/components/flow/JobProgress";
import { Stepper } from "@/components/flow/Stepper";
import { StepHeading } from "@/components/flow/StepHeading";
import { DeletedState } from "@/components/library/DeletedState";
import { ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { AddQuestionDialog } from "@/components/practice/AddQuestionDialog";
import { AnswerEditor } from "@/components/practice/AnswerEditor";
import { AttemptList } from "@/components/practice/AttemptList";
import { AttemptWatcher } from "@/components/practice/AttemptWatcher";
import { QuestionTabs } from "@/components/practice/QuestionTabs";
import { Badge } from "@/components/ui/badge";
import { isNotFound } from "@/lib/api/isNotFound";
import { useTargetJob } from "@/lib/query/library";
import { practiceKeys, usePracticeSet, useRetryPracticeSet } from "@/lib/query/practice";
import { useFollowJob } from "@/lib/query/useJob";

const route = getRouteApi("/practice/$setId");

export default function PracticePage() {
  const { setId } = route.useParams();
  const set = usePracticeSet(setId);
  const targetJob = useTargetJob(set.data?.targetJobId);
  const retry = useRetryPracticeSet(setId);
  const generation = useFollowJob(set.data?.latestJob, [practiceKeys.set(setId)]);
  const [selectedId, setSelectedId] = useState<string | null>(null);

  if (isNotFound(set.error)) return <DeletedState what="practice set" />;
  const data = set.data;
  const questions = data?.questions ?? [];
  const selected = questions.find((question) => question.id === selectedId) ?? questions[0];
  const pendingAttempts = questions.flatMap((question) =>
    question.attempts.filter((attempt) => attempt.status === "PENDING" && attempt.latestJob));

  return (
    <>
      <Stepper current={6} resumeId={data?.resumeId} targetJobId={data?.targetJobId} practiceSetId={setId} />
      <StepHeading
        title="Practice"
        description={targetJob.data ? `Questions chosen for ${targetJob.data.name}. Answer, read the feedback, and try again.` : undefined}
        action={data?.status === "READY" ? (
          <AddQuestionDialog
            setId={setId}
            userQuestions={questions.filter((question) => question.origin === "USER").length}
            onAdded={setSelectedId}
          />
        ) : undefined}
      />
      {pendingAttempts.map((attempt) => <AttemptWatcher key={attempt.id} setId={setId} job={attempt.latestJob!} />)}
      {set.isPending ? <ListSkeleton rows={4} /> : null}
      {set.isError && !isNotFound(set.error) ? <ErrorState error={set.error} onRetry={() => set.refetch()} /> : null}
      {data && data.status !== "READY" ? (
        <JobProgress
          job={generation.job}
          timedOut={generation.timedOut}
          onCheckAgain={generation.checkAgain}
          onRetry={() => retry.mutate(undefined)}
          retrying={retry.isPending}
          startError={retry.error}
        />
      ) : null}
      {selected ? (
        <div className="grid gap-8 md:grid-cols-[16rem_1fr]">
          <QuestionTabs questions={questions} selectedId={selected.id} onSelect={setSelectedId} />
          <div id="question-panel" role="tabpanel" aria-labelledby={`tab-${selected.id}`} className="min-w-0 space-y-6">
            <div className="space-y-2">
              <p className="text-lg font-medium">{selected.text}</p>
              {selected.origin === "USER" ? (
                <Badge variant="outline">Added by you</Badge>
              ) : selected.rationale ? (
                <p className="text-sm text-muted-foreground"><span className="font-medium text-foreground">Why this question: </span>{selected.rationale}</p>
              ) : null}
            </div>
            <AnswerEditor key={selected.id} setId={setId} question={selected} />
            <AttemptList setId={setId} attempts={selected.attempts} />
          </div>
        </div>
      ) : null}
    </>
  );
}
