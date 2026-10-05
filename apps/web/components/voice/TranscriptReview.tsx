import { useEffect, useRef } from "react";
import { Button } from "@/components/ui/button";
import { Textarea } from "@/components/ui/textarea";
import type { VoiceAnswer, VoiceQuestion, VoiceTranscript } from "@/lib/api/types";
import { MAX_ANSWER_CHARS, transcriptProblem, type SaveState } from "@/lib/voice/useVoiceInterview";

type Props = { questions: VoiceQuestion[]; transcript: VoiceTranscript; locked: boolean; saveState: SaveState; discarding: boolean;
  onEdit: (id: string, text: string, interviewerText?: string) => void; onSave: () => void; onDiscard: () => void; onCheck: () => void };
export function TranscriptReview({ questions, transcript, locked, saveState, discarding, onEdit, onSave, onDiscard, onCheck }: Props) {
  const heading = useRef<HTMLHeadingElement>(null);
  useEffect(() => { heading.current?.focus(); }, []);
  const problem = transcriptProblem(transcript);
  const answered = transcript.answers.filter((answer) => answer.answerText.trim()).length;
  const byId = new Map<string, VoiceAnswer>(transcript.answers.map((answer) => [answer.questionId, answer]));
  return <section className="space-y-6" aria-labelledby="review-heading">
    <div className="space-y-2">
      <h2 id="review-heading" ref={heading} tabIndex={-1} className="text-2xl font-semibold outline-none">Review transcript</h2>
      <p>{answered} of {questions.length} answered</p>
      <p className="text-sm text-muted-foreground">Correct anything the microphone missed. Feedback evaluates answer content. Unanswered questions are not scored.</p>
    </div>
    {questions.map((question, index) => {
      const answer = byId.get(question.id);
      return <div key={question.id} className="space-y-3 rounded-xl border p-4">
        <h3 className="font-medium">{index + 1}. {question.text}</h3>
        {answer?.incomplete ? <p className="text-sm text-amber-700 dark:text-amber-400">Capture may be incomplete. Check the answer before saving.</p> : null}
        {answer?.interviewerText ? <details><summary className="cursor-pointer text-sm">Interviewer transcript</summary>
          <label className="sr-only" htmlFor={`interviewer-${question.id}`}>Interviewer transcript {index + 1}</label>
          <Textarea id={`interviewer-${question.id}`} value={answer.interviewerText} disabled={locked} onChange={(event) => onEdit(question.id, answer.answerText, event.target.value)} />
        </details> : null}
        <label className="text-sm font-medium" htmlFor={`answer-${question.id}`}>Answer {index + 1}</label>
        <Textarea id={`answer-${question.id}`} value={answer?.answerText ?? ""} disabled={locked} aria-invalid={(answer?.answerText.length ?? 0) > MAX_ANSWER_CHARS} onChange={(event) => onEdit(question.id, event.target.value)} />
        <p className="text-xs text-muted-foreground">{answer?.answerText.length ?? 0} / 4,000 characters</p>
      </div>;
    })}
    {problem ? <p role="status" className="text-sm text-muted-foreground">{problem}</p> : null}
    <div className="flex flex-wrap gap-3">
      <Button disabled={locked || !!problem} onClick={onSave}>{saveState === "saving" ? "Saving…" : "Save interview"}</Button>
      {saveState === "uncertain" || saveState === "checking" ? <Button variant="outline" disabled={saveState === "checking"} onClick={onCheck}>Check save outcome</Button> : null}
      <Button variant="outline" disabled={discarding || (locked && saveState !== "removed")} onClick={onDiscard}>Discard interview</Button>
    </div>
  </section>;
}
