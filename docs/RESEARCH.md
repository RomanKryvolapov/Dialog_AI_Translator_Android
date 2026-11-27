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
| **Gemini Transcribe Live** | Partials every half second, punctuation, en and ru, free tier (audio may be used by Google), paid about $0.009 a minute **[docs]**; translation shows 1–2 s after a sentence **[ran]**. A session lives 10 minutes. It settles a phrase only at a pause, so `SentenceSplitter` takes sentences earlier **[ran]**. |
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

Default method (user, 2026-11-27): Android speech recognition with GPT-6.1 Terra, answers streamed; it was Gemini
Transcribe Live with Flash-Lite. Android's recogniser is listed first. Settings without a saved method follow the new
default.

Screens (user, 2026-11-27): what to listen to and the Translation, Questions and Overlay switches live in
Settings → General, not on the Messages screen; the settings pages stay in view beside the open page, as in the PC app
(a side column from 600 dp, a row of tabs on top below it). The sound level is a thin strip under the tabs. Keys come
from the project's `.env` with every `installDebug`, through `run-as`; putting them into the APK was rejected: any
app on the phone can read an installed APK.

Microphone (user, 2026-11-27): the phone's noise suppression, automatic gain and echo cancellation, each shown only
where the phone has it, plus a gain the app applies itself; for the microphone source only (the call capture runs in the
Shizuku process). The tablet tried has noise suppression and echo cancellation, no automatic gain **[ran]**. Its own
speaker is not heard through its microphone, with echo cancellation off too; a speaker beside it is heard and translated **[ran]**.

Android speech packs: without the pack, the on-device recogniser fails with "language unavailable" on every try; the app
now asks for the pack and uses the default recogniser for that dialog **[ran]** (en-US, 62 MB, downloaded). Settings →
General shows whether the pack is there and has a download button. The tablet offers no Bulgarian on the device **[ran]**.
Its recogniser may report an error and then the answer to the same question, so the answer wins **[ran]**.

On the Xiaomi tablet (Android 16) Shizuku 13.6.0 could not start the capture process: the platform failed inside
`LoadedApk.makeApplicationInner` **[ran]**. Open; the phone was not affected.

Bottom bar (user, 2026-11-27): only Start/Stop and Clear, and a line when something went wrong; the method in use and
the tokens moved to Settings → General.

Questions mode (user, 2026-11-27):
- Answers only technical questions: knowledge, code tasks, approach. The user's own work and past, personal facts,
  money, dates and plans, and small talk get no answer, even when About me holds them; About me only pitches the answer at the user's
  level. The rule lists both sides in the instructions rather than adding exceptions **[ran: 4 test calls]**.
- Answer shape: the gist, 2-4 dense points, a fenced code block when code helps (rendered as code on screen).
- Three calls, each sized for its wait: the questions request on every phrase (streamed, the static part first so the
  provider can cache it: instructions, About me, topic; then summary and answered; the speech last); the follow-ups,
  asked for as soon as an answer is complete, so the list opens at once; the fuller answer, on demand, streamed, with a
  model of its own. Rejected: one call returning follow-ups with every answer (longer wait for the answer itself).
- Answer now sends the latest phrases with a mark asking for the latest question not answered yet.
- Speed: the Android recogniser settles a segment only at silence, and noise kept it open about 3 s past the last
  word; a partial unchanged for 0.8 s is now taken as the phrase, the recogniser's own end adding only what is new.
  From the end of a question to the request: about 1 s instead of 3 s; to the full answer, 5-6 s **[ran]**.
- The user's own texts (About me, topic, any Questions prompt) live in `personal/`, git-ignored, sent with
  `installDebug` like `.env`; the built-in prompts stay neutral because the repository is public.
- The follow-ups open inside the card: a pop-up menu covered the answer when the card reached the bottom.
- A card taken off the screen stays in the list, hidden: the mode addresses rows by their place.
- Both sides are often heard (always through the microphone). The request says so (<heard>), and each phrase carries
  the silence before it, measured by the Android recogniser from the previous phrase's last word to its first: a short
  one means the same speaker goes on. The user's answers get no answer; a technical question whose speaker is unclear
  is answered (user, 2026-11-27: an extra card is one tap to remove, a missed answer costs more) **[ran]**.

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
