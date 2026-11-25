# Dialog AI Translator for Android — Claude Code guide

Android version of the PC app in `D:\IdeaProjects\Dialog_AI_Translator`: it listens to a conversation, recognizes
speech, and shows a streamed translation and suggested answers. The features must match the PC app.

**State (2026-11-25): a working app**, tried on a Xiaomi 2304FPN6DC (Android 16): microphone, calls and the phone's
sound, realtime recognition, translation, suggested answers, overlay. Read `docs/RESEARCH.md` first: decisions, what
was checked, what is open.

## Response style

Rule `response-style` is mandatory: the user's language everywhere, short plain prose, no code identifiers by default.

## Code work

Use Serena for Kotlin (rule `serena`, skill `dev-serena`).

## Layout

- `core/` — the dialog without Android types (pure JVM, unit-tested): settings, prompts, context, methods and their
  switching, the scheduler (`DialogEngine`), questions, the AI providers (LangChain4j on OkHttp, google-genai for Live).
  Ported from the PC app; keep its names where the code is the same.
- `app/` — Android: audio (`capture/`: `CaptureService` runs in a Shizuku process as the shell user), system speech
  recognition, encrypted settings, the foreground service and overlay, the Compose screens.
- `callsim/` — a stand-in for a messenger call, to test capture without a second person; not shipped. It says one of
  several texts once, at minimum volume (`--es text`, `--es usage media`, `--ez speaker`, `--ez loud`).
- `pc-reference/` — a read-only snapshot of the PC app (commit b9985b2): code, tests, README (the feature
  description), `PC_GUIDE.md` (its architecture). Not built here; never edit it; the newest code is in the PC
  repository.
- Rule `project-tracking` refers to a `management/` folder; neither project has it yet.

## Branch and runtime

Working branch `master`; public GitHub remote `origin` (account RomanKryvolapov). Kotlin 2.4.21, AGP 9.5.2,
Gradle 9.9.2, compileSdk 37.2, minSdk 33. Built with JDK 27 (`~/.jdks/jdk-27+35`); on the command line set `JAVA_HOME`, the Java
on PATH is 8.

## Gates

`./gradlew assembleDebug test`. The tests run on the JVM: Android's regex engine is stricter (a bare `}` is refused), so
a new pattern is also run on a device.

## Where decisions are recorded

`docs/RESEARCH.md`.

## Working rules

- Keys never go into the source, the logs or git; show secrets only as lengths.
- Keys reach a device through the app's `.env` import (or `run-as … files/import.env`, deleted once read).
- Phrase recordings and voice samples are never committed.
- The questions mode is described neutrally: suggesting answers to questions the other side asks in a call.
