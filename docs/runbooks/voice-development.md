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
| `VOICE_MODEL`, `VOICE_API_VERSION`, `VOICE_SILENCE_MS` | API environment | defaults `gemini-3.8-live`, `v1beta`, `4500`; the API rejects any voice version other than `v1beta` |

Local example:

```sh
VOICE_ENABLED=true SERVER_ADDRESS=127.0.0.1 ./gradlew bootRun
```

### Isolated Compose stack

`docker-compose.voice.yml` runs the full app with voice on, beside the default stack:

```sh
docker compose -p ai-interview-voice -f docker-compose.yml -f docker-compose.voice.yml --profile app up --build -d --wait
```

- **Isolation:** its own project name, volumes and container names.
- **Ports:** all on `127.0.0.1` (web `33000`, PostgreSQL `35432`, Redis `36380`, LocalStack `34566`). Check they are free first.
- **API binding:** the API container listens on `0.0.0.0` only because its port is never published; nginx in the web container is the one way in.
- **Default stack:** `docker-compose.yml` alone stays voice-off, and the web image builds with `VITE_VOICE_ENABLED=false` unless this override sets it.

Stop it with the same `-p` and `-f` flags and `stop`. Use `down -v` only when you mean to delete its database.

### Supabase

Apply nothing here until the owner has confirmed V19 and V20 on the Supabase development project and `bootstrap-runtime` has run. Then set `VOICE_ENABLED=true` and `SERVER_ADDRESS` on the API, and `VITE_VOICE_ENABLED=true` on the web build, in your own untracked override layered over `docker-compose.supabase.yml`. Do not edit that Compose file for voice.

### Turning it off

Set `VOICE_ENABLED=false` (or drop the override) and rebuild the web image without `VITE_VOICE_ENABLED`. New sessions and tokens then answer `503 VOICE_DISABLED`, and the voice card reads "Coming soon". Saved interviews stay readable in history, and Delete still works. Text practice is unaffected. Unsaved drafts live only in the open page, so turning voice off loses nothing that was saved; abandoned drafts are removed once they expire after 24 hours.

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

To prove the application's token endpoint instead, point it at a running voice stack and a practice set with generated questions. This mode needs no key in the script's environment. It creates a draft, mints every token through `POST /api/voice-sessions/{id}/tokens`, and discards the draft at the end. Its expired-token check waits out the app's 60-second start window.

```sh
cd apps/web
VOICE_APP_URL=http://127.0.0.1:33000 VOICE_PRACTICE_SET_ID=<practice set id> node scripts/voice-live-proof.mjs
```

### Agent-run application-endpoint proof, 2026-10-05

Against the isolated Compose stack, with tokens minted only by the API, all eight checks passed on `v1beta`:
- draft creation;
- minting;
- synthetic speech producing both transcriptions and 24 kHz PCM;
- single-use rejection;
- expired-start rejection after the 60-second window;
- locked instruction and modality;
- a wrong client model still producing the locked question;
- discarding the draft.

The interviewer read the session's canonical first question.

### Agent-run local journey, 2026-10-05

On the isolated stack, in a browser with the microphone blocked, these passed:
- **Voice session:** voice entry; Start showing microphone recovery with the transcript kept; typed answers for two of five questions; early End; correcting an answer in review; Save.
- **Worker restart:** with the worker stopped, Save queued the report job. After a restart the job completed and the report showed 65/100, the mean of 80 and 50, scored by `gpt-4.1-mini`.
- **After saving:** history listed it; it reloaded; Delete removed it with no console errors.
- **Voice off:** the same stack refused new sessions and tokens with `503 VOICE_DISABLED` and kept the saved report readable.
- **Live API suite:** 7 of 7 with voice on. With voice off, the 6 text checks passed and the voice scenario was skipped.

**Worker restart delay:** a worker killed while long-polling SQS can take the next message with it. That message stays invisible for the 300-second visibility timeout, then is redelivered (`receiveCount=2`). A report saved just after a hard worker stop can therefore take about five minutes to arrive.

### Agent-run beta proof, 2026-10-04

The strengthened script passed all six checks with `gemini-3.8-live`, SDK `2.27.0` and `v1beta`: constrained minting, synthetic speech with both transcriptions and 24 kHz PCM, single-use rejection, expired-start rejection, locked instruction/modality, and a wrong client model producing the locked question in audio. The four-second mid-answer pause produced no interviewer response. This proves the scripted provider seam; it does not establish browser microphone acceptance. The application-endpoint proof above covers the API's token endpoint.

The SDK still prints its experimental-token and alpha-version warnings. The beta run above passed without an alpha fallback. Speech recognition rendered "queue age" as "QH", so transcript correction remains necessary.

### Historical results, 2026-10-04 (`gemini-3.8-live`, `@google/genai` 2.27.0)

These are observations from the earlier proof run. The current script tests only `v1beta` and requires nonempty transcripts plus validated interviewer audio; its new result is recorded separately above.

| Check | v1beta | v1alpha |
|---|---|---|
| Mint over `POST /{version}/auth_tokens` with `bidiGenerateContentSetup` | pass | pass |
| An opening text turn makes the interviewer read the canonical question first | pass | pass |
| Candidate speech arrives as input transcription; interviewer audio is `audio/pcm;rate=24000` | pass | pass |
| A used single-use token cannot open a second session (`1011 Token has been used too many times`) | pass | pass |
| A token past `newSessionExpireTime` cannot start a session (`1011 new_session_expire_time deadline exceeded`) | pass | not rerun |
| Client-side TEXT modality and a conflicting instruction are ignored; the locked setup still asks the question in audio | pass | not rerun |
| Connecting with a different model opens a session but produces nothing unprompted | observed | not rerun |

What the historical proof showed:
- **API version:** the earlier run observed successful minting and connections on both versions. The current contract pins minting and browser connections to `v1beta`; the proof no longer tries `v1alpha` or falls back to it.
- **Silence threshold:** at a 2000 ms silence threshold, a four-second thinking pause ended the candidate's turn and drew a mid-answer acknowledgement. At 4500 ms the pause passed, and the interviewer acknowledged once after the answer without asking anything new.
- **Speech recognition:** it is imperfect. "Queue age" came back as "Qage" and "QH".
- **Lost tail:** the last clause of an answer can be missing if the connection closes right after the audio ends. That is why Next and End drain for two seconds and mark the tail uncertain for review.

Still human-run: microphone capture, barge-in and hardware cleanup in desktop Chrome and Safari.

### Browser recheck of the final UI, 2026-10-05

Against the voice-enabled mock frontend at `127.0.0.1:33001`, the in-app browser confirmed:

- The mode chooser opens the interview; the idle and typed states show **Microphone off**.
- Next moves focus to the new question heading; End moves focus to **Review transcript**.
- Two typed answers survive early End and correction, then Save opens the saved report. History and reload preserve it. The mock report scored 53/100.
- Start with microphone permission pending can be ended immediately. Adding an answer in review then Save creates the draft on demand and saves it successfully.
- The saved report fits both 1280 px desktop and 390 px mobile viewports without horizontal overflow.
- Delete opens the confirmation dialog and Cancel returns to the report. Permanent deletion was not repeated in this recheck.

No browser console errors were recorded on the successful journey. During an earlier idle mock run, the service worker stopped intercepting requests and they reached the inactive Vite API proxy; refreshing the mock app restored interception. These checks exercise the UI with mocks, not real microphone audio. Microphone-on/muted indicators and hardware cleanup still require the Chrome/Safari human check above.

## Privacy and limits

- No raw audio, token or provider resumption handle is stored. Transcript text is saved only when the candidate presses Save.
- No session-resumption handle is ever requested, so the provider keeps no resumable conversation state.
- Free-tier Gemini traffic may be used to improve Google's models. Treat spoken answers as visible to the provider.
- A run asks at most six questions. It lasts at most 20 minutes of recording, and each connection rotates before about eight minutes. Each answer is capped at 4,000 characters, and a saved transcript at 64 KiB.
