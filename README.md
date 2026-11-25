# Dialog AI Translator for Android

The app listens to a conversation — around you, in a call on the same phone, or in a video — writes down what is said,
shows a streamed translation, and can suggest answers to the questions the other side asks. It is the Android version of
[Dialog AI Helper for PC](https://github.com/RomanKryvolapov/Dialog_AI_Helper_PC).

> **Status.** Version 0.1, tried on one phone (Xiaomi 13 Ultra, Android 16). Calls were tested with the call simulator in
> this repository, which acts like a messenger; a call in a real messenger and an ordinary phone call have not been
> tested yet. There is no Google Play release: you build and install it yourself.

## Contents

- [What it does](#what-it-does)
- [What you need](#what-you-need)
- [Install](#install)
- [Set up Shizuku (for calls)](#set-up-shizuku-for-calls)
- [First dialog](#first-dialog)
- [Sources of sound](#sources-of-sound)
- [Translation methods and reserves](#translation-methods-and-reserves)
- [Questions](#questions)
- [Overlay](#overlay)
- [Settings](#settings)
- [Privacy and cost](#privacy-and-cost)
- [Troubleshooting](#troubleshooting)
- [How it is built](#how-it-is-built)
- [Development](#development)
- [Known limits](#known-limits)

## What it does

- **Hears** the microphone, calls on the same phone, or the media the phone plays.
- **Recognises** speech as it is said, with text on the screen while the speaker is still talking.
- **Translates** each phrase with a language model that knows the conversation so far: a running summary and the last
  few phrases go with every request. The built-in prompts translate from English into Russian; all of them can be edited.
- **Suggests answers** to questions asked in the conversation (off by default).
- **Switches by itself** to a reserve method when the one in use fails or hits its limit.
- **Shows the result over other apps**, so you can stay on the call's screen.

## What you need

- Android 13 or newer, and a computer to build the app.
- At least one API key:

| Provider | Used for | Where to get it |
|---|---|---|
| Google | Default: realtime recognition (Gemini Transcribe Live) and translation (Gemini 3.5 Flash-Lite, Gemma 4) | [AI Studio](https://aistudio.google.com/app/apikey), has a free tier |
| OpenAI | Suggested answers by default (GPT-6.1 Terra), translation, voice model (GPT-Audio-1.5) | [OpenAI](https://platform.openai.com/api-keys), paid |
| Anthropic | Translation, answers (Claude Sonnet 5.5) | [Anthropic](https://console.anthropic.com/settings/keys), paid |

- [Shizuku](https://shizuku.rikka.app/) for calls and the phone's own sound. The microphone works without it.

## Install

1. Install a current JDK (27 was used; Gradle itself runs on Java 25 and downloads it if it is missing) and the Android
   SDK (platform 37.2), for example with Android Studio.
2. Clone the repository and tell Gradle where the SDK is, in `local.properties`:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. Switch on **USB debugging** on the phone, connect it, then build and install:

   ```bash
   export JAVA_HOME=/path/to/jdk
   ./gradlew :app:assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

   On Xiaomi also switch on **Install via USB** in the developer options.

## Set up Shizuku (for calls)

Android does not let an ordinary app hear a call. Shizuku starts a small helper process with the rights of the `adb`
shell, which may; the app runs its call capture there. No root is needed.

1. Install [Shizuku](https://github.com/RikkaApps/Shizuku/releases) (13.6.0 was used).
2. Start it, either on the phone through **wireless debugging** (Shizuku shows the steps) or from a computer:

   ```bash
   P=$(adb shell pm path moe.shizuku.privileged.api | cut -d: -f2 | tr -d '\r')
   adb shell "$(dirname "$P")/lib/arm64/libshizuku.so"
   ```

3. Choose **Call on this phone** in the app, press **Start dialog**, and allow the app in Shizuku's question.

Shizuku has to be started again after the phone restarts.

## First dialog

1. **Settings → API keys**: paste the keys, or import a `.env` file (the PC app's format):

   ```properties
   DIALOG_GOOGLE_API_KEY=...
   DIALOG_OPENAI_API_KEY=...
   DIALOG_CLAUDE_API_KEY=...
   ```

2. **Settings → General**: check the language of the conversation.
3. On **Messages** choose the source of sound and press **Start dialog**. The first time, allow the microphone and,
   if you like, notifications.

The bar above the buttons shows how loud the sound is; a flat bar means nothing is heard. **Clear dialog** empties
both screens and forgets the conversation, the answers included.

## Sources of sound

| Source | What is heard | Needs |
|---|---|---|
| **Microphone** | A conversation nearby | Microphone permission |
| **Call on this phone** | The other side of a messenger call, taken digitally: no loudspeaker needed, and any call volume will do. In an ordinary phone call, the line (not tested) | Shizuku, microphone permission |
| **Sound of this phone** | Videos, music, games, the browser | Shizuku, microphone permission |

**Settings → General → In a call, hear me as well** adds your own voice through the microphone.

## Translation methods and reserves

**Settings → General** holds up to three methods. A dialog starts on method 1. When it fails — a rate limit, a rejected
key, a server or network error, an answer that breaks off, a recogniser that stops — the next one takes over until the
dialog is started again. The last method stays and tries again.

A method is chosen on two levels: how the speech is heard, and (for a recogniser) which model translates.

| How the speech is heard | Where | Notes |
|---|---|---|
| **Gemini Transcribe Live** (default) | Google's cloud | Text while the speaker talks, with punctuation. Long speech without pauses is cut at sentence ends |
| **Android speech recognition** | Google's recogniser on the phone, where the language pack is installed | Free; may have no punctuation, so phrases are cut by length |
| **Voice: Gemini 3.5 Flash-Lite** | Google's cloud | The model hears each recorded phrase and writes both the text and the translation |
| **Voice: GPT-Audio-1.5** | OpenAI's cloud | The same, paid |

| Model that translates | Provider |
|---|---|
| Gemini 3.5 Flash-Lite (default), Gemma 4 31B, Gemma 4 26B A4B | Google |
| GPT-6.1 Terra | OpenAI |
| Claude Sonnet 5.5 | Anthropic |

The app paces itself by limits built in for each model, whatever your plan. For Flash-Lite they follow Google's free
tier: about 12 requests a minute and 500 a day, summaries included (and questions, if it answers them), so about 40
minutes of fast speech a day. Beyond that phrases wait or are marked as skipped: add a reserve on another provider.

With **Translation** off, phrases are heard and shown but not sent for translation. If the method is a voice model,
Android speech recognition hears them instead.

## Questions

With **Questions** on, every phrase also goes to a model with the last phrases, the summary and the questions answered so
far. When the other side has asked a question in full, a short answer appears on the **Questions** screen. A question
asked again is not answered twice, and one that goes on after its answer is answered again in the same row.
**Settings → Questions** holds the model, the languages the question and the answer are shown in, how much of the
conversation goes with a request, and the prompt. **Clear answers** empties the screen and forgets the
questions answered so far.

## Overlay

**Overlay** shows the latest translations and the latest answer over other apps while a dialog runs. Android asks for
the "display over other apps" permission the first time. Drag it up or down with a finger.

## Settings

| Page | What is there |
|---|---|
| General | Translation methods, language, streaming, calls, sharing the last dialog |
| Phrases | Silence threshold, the pause that ends a phrase, the longest phrase for voice models |
| LLM | Each model's parameters: temperature, top P and top K, output tokens, thinking, verbosity (only those it accepts) |
| API keys | The keys: saved ones are shown only as a length |
| Main prompt | Translating recognised speech, and the conversation summary |
| Voice prompt | Models that hear the audio themselves |
| Questions | Everything about suggested answers |
| Console | What the app logged |

A prompt left at its built-in text follows the app's updates; **Reset to default** brings it back. The last request of
each kind is shown on its page exactly as it was sent.

## Privacy and cost

- **Where the sound goes.** Gemini Transcribe Live and the voice models receive the audio. Android speech recognition is
  asked for on the phone first; if the phone offers no on-device recognition, its default recogniser is used, which may
  be online.
- **Where the text goes.** To the provider of the model that translates, answers, or writes the summary; when the method
  in use cannot read text, that is the first reserve that can.
- **Google's free tier** may use what you send to improve Google's products.
- **Keys and settings** are one file encrypted with a key kept in the Android Keystore. Cloud backup is switched
  off; some phones may still copy app data when you move to a new phone.
- **Dialog logs** (what was said and translated) are unencrypted files in the app's private storage, one per day; the
  app sends them nowhere unless you share them. It has no screen for deleting them: clear the app's data.
- **System log.** The debug build, the one described above, also writes the requests and answers (never the keys) to the
  phone's log, which a computer connected by `adb` can read and a bug report can include.
- **Cost.** You pay the providers directly. Google's paid price for Transcribe Live was about $0.009 a minute in November
  2026; check the current prices.
- **The law.** In many places everyone in a call must agree to its being recorded or processed. Ask them first.

## Troubleshooting

| What you see | What to do |
|---|---|
| "Shizuku is not running" | Start Shizuku; needed after every restart of the phone |
| The level bar stays flat in a call | Check that **Call on this phone** is chosen and that the other side is speaking |
| "Cannot start — … no Google API key" | Add the key, or choose methods that do not need it |
| "its rate limit would hold the phrase" | The model's limit is reached: wait, or add a reserve method |
| "the language pack … is not installed" | Install it in the phone's speech settings, or use Gemini Transcribe Live |
| Quiet speech is missed | Lower **Settings → Phrases → Silence threshold** (not used by Android speech recognition) |
| Noise is taken for speech | Raise the same threshold |
| The dialog stops in the background | Switch off battery restrictions for the app (on Xiaomi also allow autostart) |
| Anything else | **Settings → Console** says what happened |

## How it is built

| Module | What it is |
|---|---|
| `core` | The dialog without Android: settings, prompts, context, methods and their switching, the scheduler, questions, AI providers. Pure Kotlin/JVM with unit tests, ported from the PC app |
| `app` | Android: sound capture, system recognition, encrypted settings, the foreground service, the overlay, the screens |
| `callsim` | A stand-in for a messenger call, for testing capture without a second person |
| `pc-reference` | A read-only snapshot of the PC app; not built |

```text
microphone ─┐
call ───────┼─► phrase cutter ─► recogniser or voice model ─► scheduler ─► model ─► Messages, overlay
phone sound ┘                                │
                                             └─► questions assistant ─► model ─► Questions, overlay
```

- **AI through libraries:** [LangChain4j](https://docs.langchain4j.dev/) on OkHttp for the chat models, Google's
  [google-genai](https://github.com/googleapis/java-genai) for realtime recognition.
- **Call capture** registers an audio-policy tap on voice communication from the Shizuku process and passes 16 kHz mono
  sound to the app. These platform classes are not public API and can change with an Android version.
- **Screens** are Jetpack Compose; settings are DataStore with Tink encryption.

Decisions, what was checked and what was rejected: [docs/RESEARCH.md](docs/RESEARCH.md).

## Development

```bash
./gradlew assembleDebug test          # build everything and run the unit tests
```

The unit tests run on the computer. Android's regular-expression engine is stricter than the desktop one (it refuses a
bare `}`), so try a new pattern on a device too.

**Call simulator.** Start a dialog on **Call on this phone**, then:

```bash
adb install -r callsim/build/outputs/apk/debug/callsim-debug.apk
adb shell "am start -n com.romankryvolapov.callsim/.MainActivity --es text 'The garden closes at six.'"
```

Close the simulator before sending another text. It says the text once, at minimum volume (or one of its own texts without `--es text`). `--es usage media` plays it as
media, `--ez speaker true` uses the loudspeaker, `--ez loud true` leaves the volume alone, `--es lang de-DE` sets the
voice's language. It lowers the volume and puts it back when closed normally; a forced stop leaves it lowered.

**Keys over a cable** (debug build): the file is taken in at the next start and deleted.

```bash
adb shell "run-as com.romankryvolapov.dialogaitranslator sh -c 'mkdir -p files && cat > files/import.env'" < .env
```

## Known limits

- Not tested: a call in a real messenger, an ordinary phone call, GPT-Audio and Claude on the phone, other phones.
- A phrase recorded for a voice model cannot be handed over to a method that reads text; its row says so.
- No speaker labels: in a call with both sides heard, the app does not tell who spoke.
- After a lost connection, the words said meanwhile can be missing.
- The interface is in English.
- The PC app's Whisper, Sherpa-ONNX and Speechmatics recognisers are not here.
