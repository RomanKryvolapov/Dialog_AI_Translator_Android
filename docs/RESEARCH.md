# Research for the Android version (2026-11-25)

Marks: **[checked]** read at the source or inspected; **[inferred]** follows from the documentation, not stated there;
**[recalled]** general knowledge, not checked. Only the empty skeleton has been built; nothing has run on a device.

## What the user wants

- The meaning and features of the PC app stay; the stack may change.
- **Decided by the user (2026-11-25):** calls happen in messengers (WhatsApp, Zoom and the like), not inside our own
  app (rejected: it needs its own call service); the app is for personal use, installed from a file. Google Play was
  rejected, so the accessibility-service route is allowed. Start with the probe app.
- Still unanswered: the phone model, which call apps exactly, and the fate of the PC version (the fork below).

## Hearing a call on the same phone — the main risk

- Playback capture takes only media, game and unknown usages; voice communication is excluded **[checked]**.
- During a call the call always gets the microphone; the listed exceptions are an accessibility service and a
  privileged pre-installed app **[checked]**. So an ordinary app gets silence **[inferred]**.
- An overlay window ("display over other apps") can show the translation over a call app, and a foreground service
  can listen, but neither gives access to the call's audio **[inferred]**.

| Option | State |
|---|---|
| Accessibility service + loudspeaker | Try first. The user's side should be heard; the other side may be removed by echo cancellation, differently per phone [inferred]. Google Play likely rejects it for call recording [recalled]. |
| Calls inside our own app (VoIP, meeting bot) | All audio is available; another app's call cannot be intercepted. Cellular needs paid telephony. [recalled] |
| Root / system app | Full access, not for ordinary phones. |
| Call on another device over the loudspeaker | Simplest and most reliable. |
| Call in the phone's browser | Might be played as media and be capturable; unknown, needs a test. |
| PC app listens, the phone shows the result | Fallback: keeps the PC pipeline, needs the PC on. |

Sources that need no tricks: a conversation nearby (microphone), and a video on the phone (playback capture, if its
player allows it).

## Background listening

A microphone foreground service must start while the app is visible; it needs the runtime RECORD_AUDIO permission,
FOREGROUND_SERVICE and FOREGROUND_SERVICE_MICROPHONE; no time limit is stated **[checked]**. It keeps recording in
the background afterwards **[inferred]** from the type's purpose; the screen-off case needs a test. Playback
capture needs RECORD_AUDIO, the mediaProjection service type and the user's consent **[checked]**.

## Stack (recommended, not yet confirmed by the user)

| Part | PC | Android |
|---|---|---|
| UI | JavaFX | Jetpack Compose, Material 3 |
| Audio | javax.sound | AudioRecord + foreground service; AudioPlaybackCapture |
| Local speech | Sherpa JNI; Whisper via Python | Sherpa-ONNX AAR 2.0.3 for both |
| Speechmatics | java.net.http WebSocket | Ktor WebSocket |
| LLM | LangChain4j | Ktor streaming (SSE) to Google, OpenAI, Anthropic |
| JSON | Moshi + kotlinx | kotlinx.serialization |
| Settings | java.util.prefs, .env | DataStore 1.3.0 (datastore-tink 1.3.0 for keys); import/export instead of .env |
| DI, async | Koin, coroutines | the same |
| Dialog logs | files | app files + Share |

- Sherpa AAR: downloaded, hash matches the release; it holds OnlineRecognizer, OfflineRecognizer, the Whisper
  config, Vad and speaker diarization, for four ABIs **[checked]**. Whisper is not streaming: use VAD + re-decoding;
  the official demo `android/SherpaOnnxSimulateStreamingAsr` exists (not read). Speed not measured; expect small
  Whisper models.
- LangChain4j: the pages read do not claim Android support; Android bugs were fixed and some are open; an OkHttp
  client module exists in beta **[checked]**. Rejected: the PC code uses little of it (a 232-line cloud repository).
- Koog 1.3.0: rejected. The Google client is 1.3.0-beta, and the OpenAI client appears to request spoken output for
  audio models **[checked in part of the code]**. Its thinking / reasoning parameters exist.
- Flutter / React Native: rejected, the Kotlin logic would be rewritten. Gluon (JavaFX on Android): rejected,
  support for JavaFX 27 not found, complex build.
- Error classification (per-minute and per-day 429, quota, key, 404, 5xx, network) must be rebuilt on HTTP status
  and provider bodies instead of LangChain4j exceptions.

## Order of work (proposal)

1. Probe app: overlay window, accessibility service, microphone; run it during calls in the user's call apps.
2. Platform-free core: context, prompts, questions, method switching, limits, tokens, with the PC tests.
   `MessagesTab` in the PC code mixes the translation scheduler with the UI; the scheduler must be separated.
3. First slice: microphone → Gemini voice method → streamed translation. No recogniser needed.
4. Sherpa-ONNX, Whisper, Speechmatics; then the settings, prompts and questions screens; playback capture.

Open fork: (a, recommended) the core grows here and the PC app moves onto it later — two copies meanwhile;
(b) the core is extracted from the PC app first. The developer decides. A core without Android types would also
keep iOS open.

## Sources

- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/reference/android/media/AudioPlaybackCaptureConfiguration
- https://developer.android.com/develop/background-work/services/fgs/service-types
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://github.com/k2-fsa/sherpa-onnx/releases/tag/v2.0.3
- https://github.com/langchain4j/langchain4j/issues?q=android
- https://github.com/JetBrains/koog
- https://developer.android.com/jetpack/androidx/releases/datastore
