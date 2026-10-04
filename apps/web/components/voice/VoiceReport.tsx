import type { VoiceSession } from "@/lib/api/types";

export function VoiceReport({ session }: { session: VoiceSession }) {
  const report = session.report;
  return (
    <div className="space-y-6">
      {report ? <section className="space-y-2 rounded-xl border bg-card p-5">
        <h2 className="text-xl font-semibold">Score {report.overallScore}/100</h2>
        <p>{report.answeredCount} of {report.selectedCount} answered</p>
        <p className="text-sm text-muted-foreground">Feedback assesses answer content, not vocal delivery.</p>
      </section> : null}
      {session.questions.map((question, index) => {
        const answer = session.transcript?.answers.find((entry) => entry.questionId === question.id);
        const feedback = report?.answers.find((entry) => entry.questionId === question.id);
        return <section key={question.id} className="space-y-4 rounded-xl border bg-card p-5">
          <h2 className="font-semibold">{index + 1}. {question.text}</h2>
          {answer?.interviewerText ? <p className="whitespace-pre-wrap break-words text-sm text-muted-foreground"><span className="font-medium">Interviewer: </span>{answer.interviewerText}</p> : null}
          {answer?.answerText ? <div className="space-y-2"><h3 className="text-sm font-medium">Your saved answer</h3><p className="whitespace-pre-wrap break-words">{answer.answerText}</p></div> : <p className="text-muted-foreground">Unanswered</p>}
          {answer?.incomplete ? <p className="text-sm text-amber-700 dark:text-amber-400">Capture may be incomplete</p> : null}
          {feedback ? <div className="space-y-3 border-t pt-4">
            <p className="font-semibold">Answer score {feedback.score}/100{report?.weakestQuestionIds.includes(question.id) ? " · Focus for practice" : ""}</p>
            <p>{feedback.summary}</p>
            {feedback.nextStep ? <p>Next step: {feedback.nextStep}</p> : null}
            {([ ["Strengths", feedback.strengths], ["Gaps", feedback.gaps], ["A stronger answer covers", feedback.betterAnswerOutline] ] as const).map(([title, items]) => items.length ? <div key={title}><h3 className="text-sm font-medium">{title}</h3><ul className="list-disc space-y-1 pl-5 text-sm">{items.map((item, i) => <li key={i}>{item}</li>)}</ul></div> : null)}
            {feedback.followUpQuestion ? <p className="text-sm">Likely follow-up: {feedback.followUpQuestion}</p> : null}
          </div> : null}
        </section>;
      })}
    </div>
  );
}
