# Spoken mock interviews: local development

Spoken interviews use Gemini Live for the conversation and the existing scorer for feedback. The feature is off by default and runs only on a developer machine. Its endpoints have no authentication, so it must never be enabled on shared or public infrastructure.

## Turning it on

The API refuses to start with `VOICE_ENABLED=true` in three cases:
- **Cloud platform:** Spring Boot detects a cloud platform such as Kubernetes, so a Helm deployment cannot enable voice.
- **No bind address:** `SERVER_ADDRESS` is unset. Otherwise Tomcat would listen on every interface and anyone on the network could mint tokens on your key.
- **No key:** `GEMINI_API_KEY` is empty.

| Setting | Where | Value |
|---|---|---|
| `VOICE_ENABLED` | API environment | `true` to allow voice sessions and token minting |
| `SERVER_ADDRESS` | API environment | `127.0.0.1` for `./gradlew bootRun` |
| `GEMINI_API_KEY` | untracked root `.env` | the server key; it never reaches the browser |
| `VITE_VOICE_ENABLED` | web build | `true` to show the voice card; only local Compose sets it |
| `VOICE_MODEL`, `VOICE_API_VERSION`, `VOICE_SILENCE_MS` | API environment | defaults `gemini-3.8-live`, `v1alpha`, `4500`; change them only after rerunning the proof below |

Local example:

```sh
VOICE_ENABLED=true SERVER_ADDRESS=127.0.0.1 ./gradlew bootRun
```

## Provider proof

`apps/web/scripts/voice-live-proof.mjs` checks the Live contract with real calls. It makes paid-account or free-tier calls, so it is opt-in and never runs in CI. It does four things:
- mints constrained single-use tokens over REST, the same request the API makes;
- connects with the pinned `@google/genai` SDK;
- streams synthetic speech from macOS `say`, as 16 kHz PCM16, with a four-second mid-answer pause;
- prints transcripts and pass/fail results, never the key or a token.

```sh
cd apps/web
node --env-file=../../.env scripts/voice-live-proof.mjs
```

### Results, 2026-10-04 (`gemini-3.8-live`, `@google/genai` 2.27.0)

| Check | v1beta | v1alpha |
|---|---|---|
| Mint over `POST /{version}/auth_tokens` with `bidiGenerateContentSetup` | pass | pass |
| An opening text turn makes the interviewer read the canonical question first | pass | pass |
| Candidate speech arrives as input transcription; interviewer audio is `audio/pcm;rate=24000` | pass | pass |
| A used single-use token cannot open a second session (`1011 Token has been used too many times`) | pass | pass |
| A token past `newSessionExpireTime` cannot start a session (`1011 new_session_expire_time deadline exceeded`) | pass | not rerun |
| Client-side TEXT modality and a conflicting instruction are ignored; the locked setup still asks the question in audio | pass | not rerun |
| Connecting with a different model opens a session but produces nothing unprompted | observed | not rerun |

What the proof showed:
- **API version:** both versions work for minting and connecting. The API defaults to `v1alpha` because SDK 2.27.0 warns on every connection otherwise. The token response carries the version, so the browser always matches the API.
- **Silence threshold:** at a 2000 ms silence threshold, a four-second thinking pause ended the candidate's turn and drew a mid-answer acknowledgement. At 4500 ms the pause passed, and the interviewer acknowledged once after the answer without asking anything new.
- **Speech recognition:** it is imperfect. "Queue age" came back as "Qage" and "QH".
- **Lost tail:** the last clause of an answer can be missing if the connection closes right after the audio ends. That is why Next and End drain for two seconds and mark the tail uncertain for review.

Still human-run: microphone capture, barge-in and hardware cleanup in desktop Chrome and Safari.

## Privacy and limits

- No raw audio, token or provider resumption handle is stored. Transcript text is saved only when the candidate presses Save.
- No session-resumption handle is ever requested, so the provider keeps no resumable conversation state.
- Free-tier Gemini traffic may be used to improve Google's models. Treat spoken answers as visible to the provider.
- A run asks at most six questions. It lasts at most 20 minutes of recording, and each connection rotates before about eight minutes. Each answer is capped at 4,000 characters, and a saved transcript at 64 KiB.
