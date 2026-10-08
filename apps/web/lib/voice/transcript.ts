import type { VoiceAnswer } from "@/lib/api/types";
import type { LiveServerContent } from "@google/genai";

/** Input and output transcription are independent streams, not model-turn completion events. */
export class VoiceTranscriptBuffer {
  private answer: VoiceAnswer;
  private readonly recoveredUncertainty: boolean;
  private separateInput: boolean;
  private separateOutput: boolean;
  constructor(questionId: string, seed?: VoiceAnswer) {
    this.answer = seed ? { ...seed } : { questionId, interviewerText: "", answerText: "", incomplete: true };
    this.recoveredUncertainty = Boolean(seed?.incomplete && seed.answerText);
    this.separateInput = Boolean(seed?.answerText);
    this.separateOutput = Boolean(seed?.interviewerText);
  }
  update(content: LiveServerContent): VoiceAnswer {
    const input = content.inputTranscription;
    const output = content.outputTranscription;
    if (input) {
      if (input.text) {
        this.answer.answerText += (this.separateInput ? "\n" : "") + input.text;
        this.separateInput = false;
      }
      this.answer.incomplete = this.recoveredUncertainty || input.finished !== true;
    }
    if (output?.text) {
      this.answer.interviewerText += (this.separateOutput ? "\n" : "") + output.text;
      this.separateOutput = false;
    }
    return { ...this.answer };
  }
  uncertain(): VoiceAnswer { this.answer.incomplete = true; return { ...this.answer }; }
  snapshot(): VoiceAnswer { return { ...this.answer }; }
}
