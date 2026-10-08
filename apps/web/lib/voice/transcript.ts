import type { VoiceAnswer } from "@/lib/api/types";
import type { LiveServerContent } from "@google/genai";

export type VoiceTurn = { speaker: "interviewer" | "candidate"; text: string };
/** A live answer also keeps who spoke when, for display only. Save sends just the four VoiceAnswer fields. */
export type LiveVoiceAnswer = VoiceAnswer & { turns?: VoiceTurn[] };

/** Input and output transcription are independent streams, not model-turn completion events. */
export class VoiceTranscriptBuffer {
  private answer: VoiceAnswer;
  private readonly turns: VoiceTurn[];
  private readonly recoveredUncertainty: boolean;
  private separateInput: boolean;
  private separateOutput: boolean;
  constructor(questionId: string, seed?: LiveVoiceAnswer) {
    this.answer = seed
      ? { questionId: seed.questionId, interviewerText: seed.interviewerText, answerText: seed.answerText, incomplete: seed.incomplete }
      : { questionId, interviewerText: "", answerText: "", incomplete: true };
    this.turns = seed?.turns?.map((turn) => ({ ...turn })) ?? [
      ...(seed?.interviewerText ? [{ speaker: "interviewer" as const, text: seed.interviewerText }] : []),
      ...(seed?.answerText ? [{ speaker: "candidate" as const, text: seed.answerText }] : [])
    ];
    this.recoveredUncertainty = Boolean(seed?.incomplete && seed.answerText);
    this.separateInput = Boolean(seed?.answerText);
    this.separateOutput = Boolean(seed?.interviewerText);
  }
  update(content: LiveServerContent): LiveVoiceAnswer {
    const input = content.inputTranscription;
    const output = content.outputTranscription;
    if (input) {
      if (input.text) this.add("candidate", input.text);
      this.answer.incomplete = this.recoveredUncertainty || input.finished !== true;
    }
    if (output?.text) this.add("interviewer", output.text);
    return this.snapshot();
  }
  uncertain(): LiveVoiceAnswer { this.answer.incomplete = true; return this.snapshot(); }
  snapshot(): LiveVoiceAnswer { return { ...this.answer, turns: this.turns.map((turn) => ({ ...turn })) }; }

  // A change of speaker, or the first words after a reconnect, starts a new turn and a new line in the saved text.
  private add(speaker: VoiceTurn["speaker"], text: string) {
    const field = speaker === "candidate" ? "answerText" : "interviewerText";
    const forceNew = speaker === "candidate" ? this.separateInput : this.separateOutput;
    const last = this.turns.at(-1);
    if (last?.speaker === speaker && !forceNew) {
      last.text += text;
      this.answer[field] += text;
    } else {
      this.turns.push({ speaker, text });
      this.answer[field] += (this.answer[field] ? "\n" : "") + text;
    }
    if (speaker === "candidate") this.separateInput = false; else this.separateOutput = false;
  }
}
