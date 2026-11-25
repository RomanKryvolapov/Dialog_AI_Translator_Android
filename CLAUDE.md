# Dialog AI Translator for Android — Claude Code guide

Android version of the PC app in `D:\IdeaProjects\Dialog_AI_Translator`: it listens to a conversation, recognizes
speech, and shows a streamed translation and suggested answers. The features must match the PC app.

**State (2026-11-25): an empty Compose skeleton.** Read `docs/RESEARCH.md` first: decisions, risks, open questions.

## Response style

Rule `response-style` is mandatory: the user's language everywhere, short plain prose, no code identifiers by default.

## Code work

Use Serena for Kotlin (rule `serena`, skill `dev-serena`).

## Layout

- `app/` — the application; `app/libs/` — the vendored Sherpa-ONNX AAR (a real dependency).
- `pc-reference/` — a read-only snapshot of the PC app (commit b9985b2): code, tests, README (the feature
  description), `PC_GUIDE.md` (its architecture). Not built here; never edit it; the newest code is in the PC
  repository.
- Rule `project-tracking` refers to a `management/` folder; neither project has it yet.

## Branch and runtime

Working branch `master`; private GitHub remote `origin` (account RomanKryvolapov). Kotlin 2.4.21, AGP 9.5.2, Gradle 9.9.2,
compileSdk 37.2, minSdk 29. Built with JDK 27 (`~/.jdks/jdk-27+35`); on the command line set `JAVA_HOME`, the Java
on PATH is 8.

## Gates

`./gradlew assembleDebug`; once there are tests, `./gradlew test`.

## Where decisions are recorded

`docs/RESEARCH.md`.

## Working rules

- Keys never go into the source, the logs or git; show secrets only as lengths.
- Phrase recordings and voice samples are never committed.
- The questions mode is described neutrally: suggesting answers to questions the other side asks in a call.
