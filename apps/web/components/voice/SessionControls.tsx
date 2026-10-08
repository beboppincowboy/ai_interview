import { Button } from "@/components/ui/button";
import type { InterviewPhase } from "@/lib/voice/useVoiceInterview";

type Props = { phase: InterviewPhase; muted: boolean; onVoice: () => void; onType: () => void; onMute: () => void; onNext: () => void; onEnd: () => void };
export function SessionControls({ phase, muted, onVoice, onType, onMute, onNext, onEnd }: Props) {
  const busy = phase === "connecting" || phase === "ending";
  return <div className="flex flex-wrap gap-3">
    {phase !== "voice" ? <Button onClick={onVoice} disabled={busy}>{phase === "ready" ? "Start interview" : "Continue voice"}</Button> : null}
    {phase !== "typed" ? <Button variant="outline" onClick={onType} disabled={busy}>Type instead</Button> : null}
    {phase === "voice" ? <Button variant="outline" aria-pressed={muted} onClick={onMute}>Mute microphone</Button> : null}
    {phase !== "ready" ? <>
      <Button variant="outline" onClick={onNext} disabled={busy}>Next question</Button>
      <Button variant="outline" onClick={onEnd} disabled={phase === "ending"}>End interview</Button>
    </> : null}
  </div>;
}
