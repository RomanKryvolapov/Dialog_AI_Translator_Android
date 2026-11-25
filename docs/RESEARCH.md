# Decisions and research for the Android version (2026-11-25)

Marks: **[ran]** seen working on the user's phone (Xiaomi 2304FPN6DC, Snapdragon 8 Gen 2, Android 16, HyperOS);
**[docs]** read at the source; **[inferred]** follows from the above; **[recalled]** not checked.

## What the user decided

- The meaning and features of the PC app stay (translate and answer questions); the stack may change.
- Calls happen in messengers on the same phone; personal use, installed from a file, no Google Play.
- Remove ONNX; Whisper only if nothing better; AI through a library, so API changes are not followed by hand.
- Shizuku may be installed on the phone; the PC app's `.env` keys may be put into the app.

## Hearing a call on the same phone

| Option | State |
|---|---|
| **Shizuku: a process of the shell user (chosen)** | The shell holds `CAPTURE_AUDIO_OUTPUT`, `CAPTURE_VOICE_COMMUNICATION_OUTPUT`, `CALL_AUDIO_INTERCEPTION` **[ran: dumpsys]**. An audio-policy tap on voice communication gives the other side's voice digitally, earpiece, loudspeaker or minimum volume alike, while another app holds the call and the microphone **[ran with callsim]**. The shell's microphone is heard at the same time **[ran]**. Cost: Shizuku is started again after a reboot; the policy classes are not public API. |
| Accessibility service + loudspeaker | Not built: worse audio, depends on echo cancellation. Remains the fallback without Shizuku. |
| MediaProjection | Media only, never calls **[docs]**; media comes through Shizuku instead. |
| Calls inside our own app | Rejected by the user. |

Not checked yet: a real WhatsApp / Telegram / Viber call; a cellular call (`VOICE_DOWNLINK`, opened only while the mode
is in-call; outside a call it delivers zeros **[ran]**).

The caller's identity must be cleared before registering the policy, or the audio service sees the app's uid and refuses
**[ran]**. Media capture stayed silent with the voice-communication flag set on its rule, and worked without it **[ran]**.

## Recognition

| Option | State |
|---|---|
| **Gemini Transcribe Live (default)** | Partials every half second, punctuation, en and ru, free tier (audio may be used by Google), paid about $0.009 a minute **[docs]**; translation shows 1–2 s after a sentence **[ran]**. A session lives 10 minutes. It settles a phrase only at a pause, so `SentenceSplitter` takes sentences earlier **[ran]**. |
| **Android speech recognition** | On the phone, free, takes our audio as a segmented session **[ran]**; no punctuation, so phrases are cut by length. |
| **Voice models** (Flash-Lite, GPT-Audio) | As on the PC. Flash-Lite **[ran]**; GPT-Audio not run here. |
| ML Kit GenAI speech | Advanced mode needs newer chips and a locked bootloader (this phone's is unlocked); the basic mode is the system recogniser, in alpha **[docs]**. Rejected. |
| Whisper, Sherpa-ONNX | Removed: not streaming / weaker, hundreds of megabytes, no gain over the above. |
| Moonshine | No Russian recognition **[docs]**. Rejected. |
| Nemotron 3.5 streaming | Has Russian, but no Android runtime **[docs]**. Rejected. |
| Speechmatics | Not ported: needs a hand-written protocol; the call's other side is already heard apart. Speaker labels are the loss. |

## AI library

- **LangChain4j 1.24.0 (chosen)**, as on the PC, with `langchain4j-http-client-okhttp` (beta): its default client needs
  `java.net.http`, absent on Android. Streaming Gemini (text and audio) and OpenAI **[ran]**; Claude not run, no key.
  The PC code, its tests and the error classification carried over. A stream that breaks off is reported as complete
  without a finish reason **[ran]**: such an answer is a failure and is asked for again.
- **google-genai 2.6.0** for Live, which LangChain4j does not cover; OkHttp-based, works on Android **[ran]**. It reports
  no closed session except in its log (`LiveSessionLog`).
- Koog 1.3.0: targets Android, but its Google client is beta and nothing would carry over from the PC. Rejected.
- Firebase AI Logic: needs a Firebase project. Rejected. Vendor SDKs one by one: three APIs to follow. Rejected.

## Structure

The platform-free core grows here (`core/`); the PC app may move onto it later. Alternative (extract it from the PC app
first) would have delayed the phone; two copies exist meanwhile. Follows from the user's "start implementing".

Settings: one JSON object in DataStore, encrypted (Tink, key in the Android Keystore), without fields left at their
defaults. Separate DataStore per area, SharedPreferences: rejected (one object is how the PC app has it).

## Sources

- https://developer.android.com/media/platform/sharing-audio-input
- https://developer.android.com/media/platform/av-capture
- https://developer.android.com/reference/android/speech/RecognizerIntent
- https://developers.google.com/ml-kit/genai/speech-recognition/android
- https://aistudio.google.com/docs/live-api/live-transcribe
- https://docs.langchain4j.dev/tutorials/customizable-http-client
- https://docs.koog.ai/prompts/llm-clients/
- https://github.com/moonshine-ai/moonshine
- https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b
- https://github.com/RikkaApps/Shizuku
