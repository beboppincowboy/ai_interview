import { ApiError } from "@/lib/api/client";
import type { JobError } from "@/lib/api/types";

const NETWORK_MESSAGE = "The server is not reachable. Check your connection and try again.";

const ERROR_MESSAGES: Record<string, string> = {
  VOICE_DISABLED: "Spoken interviews are disabled. You can still open saved interviews.",
  VOICE_SESSION_NOT_FOUND: "This interview was deleted.",
  VOICE_SESSION_EXPIRED: "This unsaved interview expired.",
  VOICE_SESSION_NOT_DRAFT: "This interview is already saved. Open its report to continue.",
  VOICE_QUESTION_NOT_FOUND: "This question is not part of the interview.",
  VOICE_RUN_EXPIRED: "Recording time has ended. Review and save your answers.",
  VOICE_TOKEN_BUDGET_EXHAUSTED: "The connection limit was reached. Continue by typing or end the interview.",
  VOICE_TOKEN_TIMEOUT: "The interviewer connection timed out. Retry, type your answer, or end the interview.",
  VOICE_TOKEN_RATE_LIMITED: "The interviewer is at its usage limit. Continue by typing or try again later.",
  VOICE_TOKEN_UNAVAILABLE: "The interviewer could not be reached. Retry, type your answer, or end the interview.",
  VOICE_SESSION_ALREADY_SAVED: "This interview was already saved with different answers. Open its saved transcript.",
  VOICE_REPORT_NOT_RETRYABLE: "This report is not ready to retry. Check its current status.",
  TRANSCRIPT_TOO_LARGE: "The transcript is too large. Shorten your answers before saving.",
  GEMINI_NOT_CONFIGURED: "The AI service is not configured.",
  GEMINI_RATE_LIMITED: "The AI service is at its usage limit. Try again later.",
  GEMINI_TIMEOUT: "The AI request timed out.",
  GEMINI_UPSTREAM_ERROR: "The AI service is temporarily unavailable.",
  GEMINI_SAFETY: "The AI could not complete this request because of its content policy. Edit the text and try again.",
  GEMINI_RECITATION: "The AI stopped because the response repeated source text. Try again.",
  GEMINI_MAX_TOKENS: "The AI response was too long to complete. Try shorter input.",
  GEMINI_EMPTY_RESPONSE: "The AI returned an empty response. Try again.",
  GEMINI_INVALID_RESPONSE: "The AI returned a result that could not be read. Try again.",
  RESUME_NOT_FOUND: "This resume was deleted.",
  RESUME_NOT_READY: "The selected resume has not finished processing.",
  RESUME_EXTRACTION_FAILED: "The resume text could not be extracted.",
  RESUME_PARSER_BUSY: "The resume parser is busy. Try the upload again shortly.",
  JOB_NOT_FOUND: "The background job is no longer available.",
  REQUEST_TIMEOUT: "The request timed out.",
  INVALID_REQUEST: "The request is invalid.",
  UPLOAD_TOO_LARGE: "The uploaded resume exceeds the configured size limit.",
  RATE_LIMITED: "Too many requests were submitted. Try again shortly.",
  SERVICE_UNAVAILABLE: "The backend service is temporarily unavailable.",
  NOT_FOUND: "The requested resource is no longer available.",
  CONFLICT: "The request conflicts with the current resource state.",
  UNPROCESSABLE_CONTENT: "The submitted content could not be processed.",
  PROCESSING_ERROR: "The background job could not be processed.",
  INTERNAL_ERROR: "The backend could not complete the request.",
  REQUEST_FAILED: "The request failed.",
  TARGET_JOB_NOT_FOUND: "This target job was deleted.",
  EXPERIENCE_NOT_FOUND: "This experience was deleted.",
  PRACTICE_SET_NOT_FOUND: "This practice set was deleted.",
  QUESTION_NOT_FOUND: "This question is no longer available.",
  ATTEMPT_NOT_FOUND: "This answer is no longer available.",
  NO_EXPERIENCE_SOURCES: "Add another resume or an experience to get suggestions.",
  PRACTICE_SET_NOT_READY: "Questions are still being generated. Try again in a moment.",
  PRACTICE_SET_NOT_FAILED: "This practice set is not in a failed state.",
  QUESTION_LIMIT_REACHED: "You can add up to 10 of your own questions per practice set.",
  ANSWER_EMPTY: "Write an answer before submitting.",
  ANSWER_TOO_LONG: "Answers can be at most 4,000 characters.",
  ANSWER_UNCHANGED: "This answer is the same as your last attempt. Change it before submitting.",
  ATTEMPT_NOT_FAILED: "This answer is not in a failed state.",
  UNSUPPORTED_FILE_TYPE: "Upload a PDF, DOC, DOCX, TXT or Markdown file."
};

export function errorCode(error: unknown): string | null {
  if (error instanceof ApiError) {
    return error.code;
  }
  if (isJobError(error)) {
    return error.code;
  }
  return null;
}

export function friendlyError(error: unknown, fallback = "The request failed.") {
  const code = errorCode(error);
  if (code && ERROR_MESSAGES[code]) {
    return ERROR_MESSAGES[code];
  }
  if (error instanceof ApiError) {
    if (error.kind === "NETWORK") {
      return NETWORK_MESSAGE;
    }
    if (error.kind === "TIMEOUT") {
      return ERROR_MESSAGES.REQUEST_TIMEOUT;
    }
    return fallback;
  }
  if (isJobError(error)) {
    return fallback;
  }
  return error instanceof Error
    ? error.message || fallback
    : typeof error === "string" ? error : fallback;
}

function isJobError(error: unknown): error is JobError {
  return Boolean(
    error &&
    typeof error === "object" &&
    "message" in error &&
    "code" in error
  );
}
