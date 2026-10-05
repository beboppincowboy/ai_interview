import { useEffect, useRef, useState } from "react";
import { useBlocker, useNavigate } from "@tanstack/react-router";
import { useQueryClient } from "@tanstack/react-query";
import type { VoiceAnswer, VoiceQuestion, VoiceSession, VoiceTranscript } from "@/lib/api/types";
import { isNotFound } from "@/lib/api/isNotFound";
import { friendlyError } from "@/lib/errorMessages";
import { createVoiceSession, discardVoiceDraft, getVoiceSession, saveVoiceSession, voiceKeys } from "@/lib/query/voice";
import { LiveVoiceAdapter } from "./liveClient";

export type InterviewPhase = "ready" | "connecting" | "voice" | "recoverable" | "typed" | "ending" | "review";
export type SaveState = "idle" | "saving" | "uncertain" | "checking" | "removed";
export const transcriptBytes = (transcript: VoiceTranscript) => new TextEncoder().encode(JSON.stringify(transcript)).byteLength;
export function transcriptProblem(transcript: VoiceTranscript) {
  if (transcript.answers.some((answer) => answer.answerText.length > 4_000)) return "Each answer must be 4,000 characters or fewer. Shorten the answer before saving.";
  if (transcriptBytes(transcript) > 65_536) return "The transcript exceeds 64 KiB. Shorten the transcript before saving.";
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
  const operation = useRef(0);
  const mounted = useRef(true);
  const saveLock = useRef(false);
  const leaving = useRef(false);
  const questions = session?.questions ?? preparedQuestions;
  const transcript = { answers };
  const dirty = answers.some((answer) => answer.answerText.trim() || answer.interviewerText.trim());
  const locked = saveState !== "idle" || discarding;

  useBlocker({
    shouldBlockFn: () => !leaving.current && (dirty || saveLock.current) && !window.confirm("Leave this interview? Unsaved text is kept only on this page and will be lost."),
    enableBeforeUnload: () => !leaving.current && (answersRef.current.some((answer) => answer.answerText.trim() || answer.interviewerText.trim()) || saveLock.current)
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
    setPhase("connecting"); setMessage("Preparing microphone…");
    const adapter = new LiveVoiceAdapter({
      onAnswer: (answer) => { if (mounted.current && current === operation.current) updateAnswer(answer); },
      onState: (state, detail) => {
        if (!mounted.current || current !== operation.current) return;
        if (state === "recoverable") { setPhase("recoverable"); setMessage(detail ?? "Voice disconnected. Your transcript is still here."); }
        if (state === "speaking") { setPhase("voice"); setMessage("Interviewer speaking. You can interrupt or move to the next question."); }
        if (state === "listening") { setPhase("voice"); setMessage("Voice connected. Answer when you are ready."); }
      }
    });
    media.current = adapter;
    adapter.setMuted(muted);
    try {
      await adapter.prepare();
      if (!mounted.current || current !== operation.current) { adapter.close(); return; }
      const draft = await ensureSession();
      if (!mounted.current || current !== operation.current) { adapter.close(); return; }
      if (Date.now() >= Date.parse(draft.runDeadline)) { adapter.close(); setPhase("review"); setMessage("The 20-minute interview has ended. Review and save your answers."); return; }
      const question = draft.questions[index];
      await adapter.start(draft.id, question, answersRef.current.find((answer) => answer.questionId === question.id));
      if (mounted.current && current === operation.current) { setPhase("voice"); setMessage("Voice connected. Answer when you are ready."); }
    } catch {
      if (!mounted.current || current !== operation.current) return;
      adapter.close(); media.current = null;
      setPhase("recoverable");
      setMessage("Microphone unavailable or voice could not connect. Your transcript is still here. Try voice again or type instead.");
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
      if (Date.now() >= Date.parse(draft.runDeadline)) { setPhase("review"); setMessage("The 20-minute interview has ended. Review and save your answers."); return; }
      setPhase("typed"); setMessage("Typing. Your microphone is off.");
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
    if (typed) { setPhase("typed"); setMessage("Typing. Your microphone is off."); return; }
    if (!adapter || phase === "recoverable" || !sessionRef.current) { setPhase("recoverable"); setMessage("Continue voice or type your next answer."); return; }
    setPhase("connecting"); setMessage("Connecting the next question…");
    try {
      await adapter.start(sessionRef.current.id, questions[nextIndex]);
      if (mounted.current && current === operation.current) { setPhase("voice"); setMessage("Voice connected. Answer when you are ready."); }
    } catch {
      if (mounted.current && current === operation.current) { setPhase("recoverable"); setMessage("Voice could not connect. Continue by typing or try voice again."); }
    }
  }
  const deadline = session?.runDeadline;
  useEffect(() => {
    if (!deadline || phase === "review") return;
    const timer = setTimeout(() => {
      operation.current++; closeMedia(); setPhase("review"); setMessage("The 20-minute interview has ended. Review and save your answers.");
    }, Math.max(0, Date.parse(deadline) - Date.now()));
    return () => clearTimeout(timer);
  }, [deadline, phase]);

  function toggleMute() {
    const next = !muted; setMuted(next); media.current?.setMuted(next);
    setMessage(next ? "Microphone muted. Interviewer audio can still play." : "Microphone unmuted.");
  }
  async function openSaved(saved: VoiceSession) {
    client.setQueryData(voiceKeys.session(saved.id), saved);
    void client.invalidateQueries({ queryKey: ["history"] });
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
    if (saveLock.current || discarding || !sessionRef.current || transcriptProblem({ answers: answersRef.current })) return;
    saveLock.current = true; setSaveState("saving"); setMessage("Saving interview…");
    // The lock holds this exact payload unchanged until GET establishes the outcome.
    const payload = { answers: answersRef.current.map((answer) => ({ ...answer })) };
    try { const result = await saveVoiceSession(sessionRef.current.id, payload); if (mounted.current) await openSaved(result.session); }
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
