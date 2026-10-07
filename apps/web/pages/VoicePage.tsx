import { useEffect, useRef } from "react";
import { getRouteApi } from "@tanstack/react-router";
import { JobProgress } from "@/components/flow/JobProgress";
import { StepHeading } from "@/components/flow/StepHeading";
import { DeletedState } from "@/components/library/DeletedState";
import { ErrorState, ListSkeleton } from "@/components/library/ListStates";
import { SessionControls } from "@/components/voice/SessionControls";
import { TranscriptReview } from "@/components/voice/TranscriptReview";
import { Textarea } from "@/components/ui/textarea";
import { VOICE_ENABLED } from "@/lib/api/config";
import { isNotFound } from "@/lib/api/isNotFound";
import { practiceKeys, usePracticeSet, useRetryPracticeSet } from "@/lib/query/practice";
import { useFollowJob } from "@/lib/query/useJob";
import { MAX_VOICE_QUESTIONS, useVoiceInterview } from "@/lib/voice/useVoiceInterview";

const route = getRouteApi("/voice/$setId");
export default function VoicePage() {
  const { setId } = route.useParams();
  return VOICE_ENABLED ? <VoiceInterview key={setId} setId={setId} /> : <StepHeading title="Voice interviews are unavailable" description="You can continue with text practice." />;
}
function VoiceInterview({ setId }: { setId: string }) {
  const set = usePracticeSet(setId);
  const retry = useRetryPracticeSet(setId);
  const generation = useFollowJob(set.data?.activeJob, [practiceKeys.set(setId)]);
  const interview = useVoiceInterview(setId, (set.data?.questions ?? []).filter((question) => question.origin === "AI").slice(0, MAX_VOICE_QUESTIONS));
  const heading = useRef<HTMLHeadingElement>(null);
  const question = interview.questions[interview.index];
  const answer = interview.answers.find((item) => item.questionId === question?.id);
  useEffect(() => { if (interview.index > 0) heading.current?.focus(); }, [interview.index]);
  if (isNotFound(set.error)) return <DeletedState what="practice set" />;
  return <div className="mx-auto max-w-3xl space-y-6">
    <StepHeading title="Mock interview" description="Practice aloud, then review your transcript and save feedback." />
    {set.isPending ? <ListSkeleton rows={3} /> : null}
    {set.isError ? <ErrorState error={set.error} onRetry={() => set.refetch()} /> : null}
    {set.data && set.data.status !== "READY" ? <JobProgress job={generation.job} timedOut={generation.timedOut} onCheckAgain={generation.checkAgain} onRetry={() => retry.mutate(undefined)} retrying={retry.isPending} startError={retry.error} /> : null}
    {set.data?.status === "READY" && question ? <>
      <p className="text-sm text-muted-foreground">Your unsaved transcript stays only on this page. Leaving or refreshing loses it. Recording ends after 20 minutes; save within 24 hours.</p>
      <p role="status" aria-live="polite" className="rounded-lg bg-muted p-3 text-sm">{interview.message}</p>
      {interview.phase === "review" ? <TranscriptReview questions={interview.questions} transcript={interview.transcript} locked={interview.locked} saveState={interview.saveState} discarding={interview.discarding} onEdit={interview.editAnswer} onSave={() => void interview.save()} onDiscard={() => void interview.discard()} onCheck={() => void interview.checkSave()} /> : <>
        <section className="space-y-4 rounded-xl border bg-card p-5" aria-labelledby="voice-question">
          <p className="text-sm text-muted-foreground">Question {interview.index + 1} of {interview.questions.length}</p>
          <h2 id="voice-question" ref={heading} tabIndex={-1} className="text-xl font-medium outline-none">{question.text}</h2>
          <p className="text-sm">{interview.phase === "voice" ? (interview.muted ? "Microphone muted" : "Microphone on") : interview.phase === "connecting" ? "Preparing microphone or interview" : interview.phase === "ending" ? "Finishing capture" : "Microphone off"}</p>
          {interview.phase !== "typed" && answer?.turns?.length ? <div className="space-y-2">
            {answer.turns.map((turn, index) => <p key={index} className={`whitespace-pre-wrap break-words ${turn.speaker === "interviewer" ? "text-sm" : ""}`}>
              <span className="font-medium">{turn.speaker === "interviewer" ? "Interviewer: " : "You: "}</span>{turn.text}
            </p>)}
          </div> : <>
            {answer?.interviewerText ? <p className="whitespace-pre-wrap break-words text-sm"><span className="font-medium">Interviewer: </span>{answer.interviewerText}</p> : null}
            {interview.phase === "typed" ? <div className="space-y-2">
              <label htmlFor="typed-answer" className="text-sm font-medium">Your answer</label>
              <Textarea id="typed-answer" value={answer?.answerText ?? ""} onChange={(event) => interview.editAnswer(question.id, event.target.value)} />
            </div> : answer?.answerText ? <p className="whitespace-pre-wrap break-words"><span className="font-medium">You: </span>{answer.answerText}</p> : null}
          </>}
          {answer?.incomplete ? <p className="text-sm text-amber-700 dark:text-amber-400">Capture may be incomplete. You can correct this in review.</p> : null}
        </section>
        <SessionControls phase={interview.phase} muted={interview.muted} onVoice={() => void interview.startVoice()} onType={() => void interview.typeInstead()} onMute={interview.toggleMute} onNext={() => void interview.advance()} onEnd={() => void interview.advance(true)} />
      </>}
    </> : null}
    {set.data?.status === "READY" && !question ? <p>No generated interview questions are available. Use text practice to prepare a question set.</p> : null}
  </div>;
}
