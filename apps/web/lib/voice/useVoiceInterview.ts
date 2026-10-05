import { useEffect, useRef, useState } from "react";
import { useBlocker, useNavigate } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import type { VoiceAnswer, VoiceQuestion, VoiceSession, VoiceTranscript } from "@/lib/api/types";
import { isNotFound } from "@/lib/api/isNotFound";
import { friendlyError } from "@/lib/errorMessages";
import { createVoiceSession, discardVoiceDraft, getVoiceSession, saveVoiceSession, voiceKeys } from "@/lib/query/voice";
import { keys } from "@/lib/query/library";
import { DISCONNECTED_MESSAGE, LiveVoiceAdapter } from "./liveClient";

export type InterviewPhase = "ready" | "connecting" | "voice" | "recoverable" | "typed" | "ending" | "review";
export type SaveState = "idle" | "saving" | "uncertain" | "checking" | "removed";
/** Mirrors the API's limits (VoiceSessionService), so the page can explain a rejection before sending it. */
export const MAX_VOICE_QUESTIONS = 6;
export const MAX_ANSWER_CHARS = 4_000;
const MAX_TRANSCRIPT_BYTES = 65_536;
const CONNECTED_MESSAGE = "Voice connected. Answer when you are ready.";
const TYPING_MESSAGE = "Typing. Your microphone is off.";
const RUN_ENDED_MESSAGE = "The 20-minute interview has ended. Review and save your answers.";
const hasText = (answers: VoiceAnswer[]) => answers.some((answer) => answer.answerText.trim() || answer.interviewerText.trim());

export const transcriptBytes = (transcript: VoiceTranscript) => new TextEncoder().encode(JSON.stringify(transcript)).byteLength;
export function transcriptProblem(transcript: VoiceTranscript) {
  if (transcript.answers.some((answer) => answer.answerText.length > MAX_ANSWER_CHARS)) return "Each answer must be 4,000 characters or fewer. Shorten the answer before saving.";
  if (transcriptBytes(transcript) > MAX_TRANSCRIPT_BYTES) return "The transcript exceeds 64 KiB. Shorten the transcript before saving.";
  if (!transcript.answers.some((answer) => answer.answerText.trim())) return "Add at least one answer before saving.";
  return null;
}
const emptyAnswer = (questionId: string): VoiceAnswer => ({ questionId, interviewerText: "", answerText: "", incomplete: false });

export function useVoiceInterview(setId: string, preparedQuestions: VoiceQuestion[]) {
  const navigate = useNavigate();
  const client = useQueryClient();
  const [session, setSession] = useState<VoiceSession | null>(null);
  const [phase, setPhase] = useState<InterviewPhase>("ready");
  const [index, setIndex] = useState(0);
  const [answers, setAnswers] = useState<VoiceAnswer[]>([]);
  const [muted, setMuted] = useState(false);
  const [message, setMessage] = useState("Ready when you are. Your microphone starts only when you choose voice.");
  const [saveState, setSaveState] = useState<SaveState>("idle");
  const [discarding, setDiscarding] = useState(false);
  const sessionRef = useRef<VoiceSession | null>(null);
  const creating = useRef<Promise<VoiceSession> | null>(null);
  const answersRef = useRef<VoiceAnswer[]>([]);
  const media = useRef<LiveVoiceAdapter | null>(null);
  const recoveryMessage = useRef<string | null>(null);
  const operation = useRef(0);
  const mounted = useRef(true);
  const saveLock = useRef(false);
  const leaving = useRef(false);
  const questions = session?.questions ?? preparedQuestions;
  const transcript = { answers };
  const dirty = hasText(answers);
  const locked = saveState !== "idle" || discarding;

  useBlocker({
    shouldBlockFn: () => !leaving.current && (dirty || saveLock.current) && !window.confirm("Leave this interview? Unsaved text is kept only on this page and will be lost."),
    enableBeforeUnload: () => !leaving.current && (hasText(answersRef.current) || saveLock.current)
  });
  useEffect(() => {
    mounted.current = true;
    // The counter intentionally invalidates whichever start is active when this component unmounts.
    // eslint-disable-next-line react-hooks/exhaustive-deps
    return () => { mounted.current = false; operation.current++; media.current?.close(); media.current = null; };
  }, []);

  function updateAnswer(answer: VoiceAnswer) {
    const found = answersRef.current.some((item) => item.questionId === answer.questionId);
    answersRef.current = found ? answersRef.current.map((item) => item.questionId === answer.questionId ? answer : item) : [...answersRef.current, answer];
    if (mounted.current) setAnswers(answersRef.current);
  }
  function editAnswer(questionId: string, answerText: string, interviewerText?: string) {
    if (saveLock.current || discarding) return;
    const old = answersRef.current.find((answer) => answer.questionId === questionId) ?? emptyAnswer(questionId);
    updateAnswer({ ...old, answerText, interviewerText: interviewerText ?? old.interviewerText });
  }
  async function ensureSession() {
    if (sessionRef.current) return sessionRef.current;
    if (!creating.current) creating.current = createVoiceSession(setId).finally(() => { creating.current = null; });
    const created = await creating.current;
    sessionRef.current = created;
    if (mounted.current) setSession(created);
    return created;
  }
  function closeMedia() { media.current?.close(); media.current = null; }

  // Called by Start / Continue voice itself, so prepare resumes AudioContext in the user gesture.
  async function startVoice() {
    if (saveLock.current || phase === "connecting" || phase === "ending" || phase === "review") return;
    const current = ++operation.current;
    closeMedia();
    recoveryMessage.current = null;
    setPhase("connecting"); setMessage("Preparing microphone…");
    const adapter = new LiveVoiceAdapter({
      onAnswer: (answer) => { if (mounted.current && current === operation.current) updateAnswer(answer); },
      onState: (state, detail) => {
        if (!mounted.current || current !== operation.current) return;
        if (state === "recoverable") { recoveryMessage.current = detail ?? DISCONNECTED_MESSAGE; setPhase("recoverable"); setMessage(recoveryMessage.current); }
        if (state === "speaking") { setPhase("voice"); setMessage("Interviewer speaking. You can interrupt or move to the next question."); }
        if (state === "listening") { setPhase("voice"); setMessage(CONNECTED_MESSAGE); }
      }
    });
    media.current = adapter;
    adapter.setMuted(muted);
    try {
      await adapter.prepare();
      if (!mounted.current || current !== operation.current) { adapter.close(); return; }
      const draft = await ensureSession();
      if (!mounted.current || current !== operation.current) { adapter.close(); return; }
      if (Date.now() >= Date.parse(draft.runDeadline)) { adapter.close(); setPhase("review"); setMessage(RUN_ENDED_MESSAGE); return; }
      const question = draft.questions[index];
      await adapter.start(draft.id, question, answersRef.current.find((answer) => answer.questionId === question.id));
      if (mounted.current && current === operation.current) { setPhase("voice"); setMessage(CONNECTED_MESSAGE); }
    } catch {
      if (!mounted.current || current !== operation.current) return;
      adapter.close(); media.current = null;
      setPhase("recoverable");
      setMessage(recoveryMessage.current ?? "Microphone unavailable or voice could not connect. Your transcript is still here. Try voice again or type instead.");
    }
  }
  async function typeInstead() {
    if (phase === "review" || phase === "ending" || saveLock.current) return;
    if (phase === "voice" && media.current) {
      const current = operation.current;
      setPhase("ending"); setMessage("Finishing this answer…");
      await media.current.drain();
      if (!mounted.current || current !== operation.current) return;
    }
    const current = ++operation.current;
    closeMedia(); setPhase("connecting"); setMessage("Preparing your interview…");
    try {
      const draft = await ensureSession();
      if (!mounted.current || current !== operation.current) return;
      if (Date.now() >= Date.parse(draft.runDeadline)) { setPhase("review"); setMessage(RUN_ENDED_MESSAGE); return; }
      setPhase("typed"); setMessage(TYPING_MESSAGE);
    } catch (error) {
      if (mounted.current && current === operation.current) { setPhase("recoverable"); setMessage(friendlyError(error)); }
    }
  }
  async function advance(end = false) {
    if (phase === "ending" || phase === "review" || saveLock.current) return;
    const current = operation.current;
    const adapter = media.current;
    const typed = phase === "typed";
    setPhase("ending"); setMessage("Finishing this answer…");
    await adapter?.drain();
    if (!mounted.current || current !== operation.current) return;
    if (end || index + 1 >= questions.length) {
      operation.current++; closeMedia(); setPhase("review"); setMessage("Review your transcript. You can correct any answer before saving."); return;
    }
    const nextIndex = index + 1;
    setIndex(nextIndex);
    if (typed) { setPhase("typed"); setMessage(TYPING_MESSAGE); return; }
    if (!adapter || phase === "recoverable" || !sessionRef.current) { setPhase("recoverable"); setMessage("Continue voice or type your next answer."); return; }
    setPhase("connecting"); setMessage("Connecting the next question…");
    recoveryMessage.current = null;
    try {
      await adapter.start(sessionRef.current.id, questions[nextIndex]);
      if (mounted.current && current === operation.current) { setPhase("voice"); setMessage(CONNECTED_MESSAGE); }
    } catch {
      if (mounted.current && current === operation.current) { setPhase("recoverable"); setMessage(recoveryMessage.current ?? "Voice could not connect. Continue by typing or try voice again."); }
    }
  }
  const deadline = session?.runDeadline;
  useEffect(() => {
    if (!deadline || phase === "review") return;
    const timer = setTimeout(() => {
      operation.current++; closeMedia(); setPhase("review"); setMessage(RUN_ENDED_MESSAGE);
    }, Math.max(0, Date.parse(deadline) - Date.now()));
    return () => clearTimeout(timer);
  }, [deadline, phase]);

  function toggleMute() {
    const next = !muted; setMuted(next); media.current?.setMuted(next);
    setMessage(next ? "Microphone muted. Interviewer audio can still play." : "Microphone unmuted.");
  }
  async function openSaved(saved: VoiceSession) {
    client.setQueryData(voiceKeys.session(saved.id), saved);
    void client.invalidateQueries({ queryKey: keys.history });
    leaving.current = true;
    await navigate({ to: "/voice/sessions/$sessionId", params: { sessionId: saved.id } });
  }
  async function checkSave() {
    if (!sessionRef.current) return;
    saveLock.current = true; setSaveState("checking");
    try {
      const outcome = await getVoiceSession(sessionRef.current.id);
      if (!mounted.current) return;
      if (outcome.status === "SAVED") { await openSaved(outcome); return; }
      if (outcome.status === "DRAFT") { saveLock.current = false; setSaveState("idle"); setMessage("The interview is still a draft. Your text is safe here; you can try Save again."); }
      else { setSaveState("removed"); setMessage("This draft expired. Your transcript is still readable; discard it when you are ready."); }
    } catch (error) {
      if (!mounted.current) return;
      if (isNotFound(error)) { setSaveState("removed"); setMessage("This interview or its source was removed. Your transcript is still readable; only Discard is available."); }
      else { setSaveState("uncertain"); setMessage("We could not confirm whether Save completed. Keep this page open and check the save outcome before editing or discarding."); }
    }
  }
  async function save() {
    if (saveLock.current || discarding || transcriptProblem({ answers: answersRef.current })) return;
    saveLock.current = true; setSaveState("saving"); setMessage("Saving interview…");
    // The lock holds this exact payload unchanged until GET establishes the outcome.
    const payload = { answers: answersRef.current.map((answer) => ({ ...answer })) };
    // A denied microphone can reach review before any draft exists; create it now so Save is never a silent no-op.
    let draft: VoiceSession;
    try { draft = await ensureSession(); }
    catch (error) {
      saveLock.current = false;
      if (mounted.current) { setSaveState("idle"); setMessage(friendlyError(error)); }
      return;
    }
    try { const result = await saveVoiceSession(draft.id, payload); if (mounted.current) await openSaved(result.session); }
    catch { if (mounted.current) await checkSave(); }
  }
  async function discard() {
    if (discarding || (saveLock.current && saveState !== "removed")) return;
    setDiscarding(true); operation.current++; closeMedia();
    try {
      if (sessionRef.current && saveState !== "removed") await discardVoiceDraft(sessionRef.current.id);
      leaving.current = true;
      await navigate({ to: "/history" });
    } catch (error) { if (mounted.current) { setMessage(friendlyError(error)); setDiscarding(false); } }
  }
  return { phase, index, questions, answers, muted, message, saveState, locked, discarding, session, transcript,
    startVoice, typeInstead, advance, toggleMute, editAnswer, save, checkSave, discard };
}
