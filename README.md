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
| Google | Realtime recognition (Gemini Transcribe Live), translation (Gemini 3.5 Flash-Lite, Gemma 4) | [AI Studio](https://aistudio.google.com/app/apikey), has a free tier |
| OpenAI | Translation and suggested answers by default (GPT-6.1 Terra), voice model (GPT-Audio-1.5) | [OpenAI](https://platform.openai.com/api-keys), paid |
| Anthropic | Translation, answers (Claude Sonnet 5.5) | [Anthropic](https://console.anthropic.com/settings/keys), paid |

- [Shizuku](https://shizuku.rikka.app/) for calls and the phone's own sound. The microphone works without it.

## Install

1. Install a current JDK (27 was used; Gradle itself runs on Java 25 and downloads it if it is missing) and the Android
   SDK (platform 37.2), for example with Android Studio.
2. Clone the repository and tell Gradle where the SDK is, in `local.properties`:

   ```properties
   sdk.dir=/path/to/Android/Sdk
   ```

3. Put the keys into `.env` in the project folder (copy `.env.example`; the PC app's `.env` works as it is).
4. Switch on **USB debugging** on the phone, connect it, then build and install:

   ```bash
   export JAVA_HOME=/path/to/jdk
   ./gradlew :app:installDebug
   ```

   The install also sends the keys from `.env`, and your own texts from `personal/` (see Questions), to the phone, as
   the PC app reads its `.env`: the app takes them into its encrypted settings at its next start and deletes the files. A key in `.env` replaces the one saved in the app;
   one missing there leaves it alone. With several devices connected, set `ANDROID_SERIAL` to choose one.

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

3. Choose **Settings → General → What to listen to → Call on this phone**, press **Start dialog**, and allow the app in Shizuku's question.

Shizuku has to be started again after the phone restarts.

## First dialog

1. Keys: installed from `.env` (see above), or pasted in **Settings → API keys**, or imported there from a `.env`
   file (the PC app's format):

   ```properties
   DIALOG_GOOGLE_API_KEY=...
   DIALOG_OPENAI_API_KEY=...
   DIALOG_CLAUDE_API_KEY=...
   ```

2. **Settings → General**: choose what to listen to, switch on what you need (**Translation**, **Questions**,
   **Overlay**), check the language of the conversation (English by default; the translation goes into Russian). Below it the
   app says whether Android has that language on the phone, with a button to download it when it does not.
3. On **Messages** press **Start dialog**. The first time, allow the microphone and, if you like, notifications.

The thin strip under the tabs shows how loud the sound is while a dialog runs; a flat strip means nothing is heard.
Below the tabs there are only the two buttons, and a line when something goes wrong; **Settings → General** shows the
method in use and the tokens spent. **Clear dialog** empties
both screens and forgets the conversation, the answers included. While the app is in front, the screen does not turn
off by itself.

## Sources of sound

| Source | What is heard | Needs |
|---|---|---|
| **Microphone** | A conversation nearby | Microphone permission |
| **Call on this phone** | The other side of a messenger call, taken digitally: no loudspeaker needed, and any call volume will do. In an ordinary phone call, the line (not tested) | Shizuku, microphone permission |
| **Sound of this phone** | Videos, music, games, the browser | Shizuku, microphone permission |

**Settings → General → In a call, hear me as well** adds your own voice through the microphone.

**Settings → General → Microphone** holds what the phone offers of noise suppression, automatic gain and echo
cancellation (only those it has are shown), and a gain the app applies itself, 25–400%. They apply to the microphone as
the source and take effect when a dialog starts.

## Translation methods and reserves

**Settings → General** holds up to three methods. A dialog starts on method 1. When it fails — a rate limit, a rejected
key, a server or network error, an answer that breaks off, a recogniser that stops — the next one takes over until the
dialog is started again. The last method stays and tries again.

A method is chosen on two levels: how the speech is heard, and (for a recogniser) which model translates.

| How the speech is heard | Where | Notes |
|---|---|---|
| **Android speech recognition** (default) | Google's recogniser on the phone, where the language pack is installed; without the pack, the phone's default recogniser (which may be online) for that dialog, and the pack is asked for | Free; may have no punctuation, so phrases are cut by length. Not every language is offered on the phone (Bulgarian was not, on the tablet tried) |
| **Gemini Transcribe Live** | Google's cloud | Text while the speaker talks, with punctuation. Long speech without pauses is cut at sentence ends |
| **Voice: Gemini 3.5 Flash-Lite** | Google's cloud | The model hears each recorded phrase and writes both the text and the translation |
| **Voice: GPT-Audio-1.5** | OpenAI's cloud | The same, paid |

| Model that translates | Provider |
|---|---|
| Gemini 3.5 Flash-Lite, Gemma 4 31B, Gemma 4 26B A4B | Google |
| GPT-6.1 Terra (default) | OpenAI |
| Claude Sonnet 5.5 | Anthropic |

The default needs only an OpenAI key. The app paces itself by limits built in for each model, whatever your plan. For Flash-Lite they follow Google's free
tier: about 12 requests a minute and 500 a day, summaries included (and questions, if it answers them), so about 40
minutes of fast speech a day. Beyond that phrases wait or are marked as skipped: add a reserve on another provider.

The answer appears as it is written; **Settings → General → Show the answer as it is written** turns that off.

With **Translation** off, phrases are heard and shown but not sent for translation. If the method is a voice model,
Android speech recognition hears them instead.

## Questions

With **Questions** on, every phrase also goes to a model with the last phrases, the summary and the questions answered so
far. When the other side has asked a technical question in full (what something is, how it works, code to write, how to
build or debug something), the question, restated in full, and a short answer appear on the **Context** screen: the
gist in one sentence, a few points, and a code block when code helps. Questions about the user's own work, past,
personal facts, money, dates and plans, and small talk are left to the user. A follow-up ("And why
is that slower?") is understood from the questions before it. A question asked again is not answered twice, and one
that goes on after its answer is answered again in the same card.

- **A tap on a card** opens, under the answer, the follow-up questions the other side may ask next (asked for as soon as
  the answer is complete, so each answer is one more request to the Questions model and counts against its limit);
  picking one writes a fuller answer under the short one.
- **Answer now**, under the screen while a dialog runs, answers the latest question that was let pass, even with
  **Questions** off.
- **✕** takes a card off the screen; the question stays answered.
- **Clear answers** empties the screen and forgets the questions answered so far.

**Settings → Questions** holds the two models (one finds and answers the questions, the other writes the fuller
answers), the languages the question and the answer are shown in (Russian by default), **About me** (background and
skills that answers are pitched at, kept encrypted on the phone and sent with every Questions request), **Topic of the calls** (such as the programming language code is
written in), how much of the conversation goes with a request, and the prompts.

**Your own texts** go in `personal/` in the project folder, which git ignores, and reach the phone with `installDebug`,
like the keys: `about_user.md`, `topic.txt`, and any Questions prompt you want in place of the built-in one
(`questions_instructions.md`, `questions_message.md`, `follow_ups_instructions.md`, `follow_ups_message.md`,
`expansion_instructions.md`, `expansion_message.md`). A prompt that misses a placeholder it needs is left out, and the
Console says why.

## Overlay

**Overlay** shows the latest translations and the latest answer over other apps while a dialog runs. Android asks for
the "display over other apps" permission the first time. Drag it up or down with a finger.

## Settings

| Page | What is there |
|---|---|
| General | What to listen to, the Translation, Questions and Overlay switches, the microphone, translation methods, language and its speech pack, streaming, calls, sharing the last dialog |
| Phrases | Silence threshold, the pause that ends a phrase, the longest phrase for voice models |
| LLM | Each model's parameters: temperature, top P and top K, output tokens, thinking, verbosity (only those it accepts) |
| API keys | The keys: saved ones are shown only as a length |
| Main prompt | Translating recognised speech, and the conversation summary |
| Voice prompt | Models that hear the audio themselves |
| Questions | Everything about suggested answers |
| Console | What the app logged |

As in the PC app, the pages are always in view: in a column at the side on a wide screen (a tablet, a phone turned
sideways), in a row on top on a narrow one.

A prompt left at its built-in text follows the app's updates; **Reset to default** brings it back. The last request of
each kind is shown on its page exactly as it was sent.

## Privacy and cost

- **Where the sound goes.** Gemini Transcribe Live and the voice models receive the audio. Android speech recognition is
  asked for on the phone first; if the phone offers no on-device recognition, or has no pack for the language, its
  default recogniser is used, which may be online. **Settings → General** says which it will be.
- **Where the text goes.** To the provider of the model that translates, answers, or writes the summary; when the method
  in use cannot read text, that is the first reserve that can. The Questions requests also carry **About me** and the
  topic.
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
| The level strip stays flat in a call | Check that **Settings → General → What to listen to** is **Call on this phone** and that the other side is speaking |
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

**Keys over a cable** without reinstalling (debug build): the file is taken in at the next start and deleted.

```bash
adb shell "run-as com.romankryvolapov.dialogaitranslator sh -c 'mkdir -p files && cat > files/import.env'" < .env
```

## Known limits

- Not tested: a call in a real messenger, an ordinary phone call, GPT-Audio and Claude on the phone, other phones.
- On a Xiaomi tablet (25079RPDCG, Android 16) Shizuku could not start the capture process, so calls and the phone's own
  sound were not heard there; the microphone worked.
- A phrase recorded for a voice model cannot be handed over to a method that reads text; its row says so.
- No speaker labels: in a call with both sides heard, the app does not tell who spoke.
- After a lost connection, the words said meanwhile can be missing.
- The interface is in English.
- The PC app's Whisper, Sherpa-ONNX and Speechmatics recognisers are not here.
