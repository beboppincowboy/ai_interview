// Web Audio's worklet globals are separate from the window DOM typings.
declare class AudioWorkletProcessor { readonly port: MessagePort }
declare function registerProcessor(name: string, processor: typeof AudioWorkletProcessor): void;

class VoiceCapture extends AudioWorkletProcessor {
  process(inputs: Float32Array[][]) {
    const frame = inputs[0]?.[0];
    if (frame?.length) this.port.postMessage(frame.slice());
    return true;
  }
}

registerProcessor("voice-capture", VoiceCapture);
export {};
