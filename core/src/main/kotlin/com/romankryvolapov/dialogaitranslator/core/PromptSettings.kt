package com.romankryvolapov.dialogaitranslator.core

import kotlinx.serialization.Serializable

/**
 * Everything a translation request is made of, as the user edits it on the Main prompt and Voice prompt pages. A
 * field left at its built-in text equals its default here and so is not stored (the settings are written without
 * defaults): a better built-in text reaches it with the next version.
 */
@Serializable
data class PromptSettings(
    /** How many phrases said before the one being translated each request carries, for context only. */
    val previousPhrases: Int = DEFAULT_PREVIOUS_PHRASES,
    /** Translating a phrase a speech recogniser wrote down (Main prompt). */
    val text: TextPrompt = TextPrompt(),
    /** The running summary of the conversation, written by a request of its own (Main prompt). */
    val summary: SummarySettings = SummarySettings(),
    /** Translating a recorded phrase a voice model hears itself (Voice prompt). */
    val voice: VoicePrompt = VoicePrompt(),
)

@Serializable
data class TextPrompt(
    /** The instructions, sent as the system message. */
    val system: String = TEXT_INSTRUCTIONS,
    /** The message itself, with the placeholders filled in. */
    val user: String = TEXT_MESSAGE,
    /** How one earlier phrase is written into {{previous_phrases}}. */
    val previousPhrase: String = PREVIOUS_PHRASE_FORMAT,
)

@Serializable
data class VoicePrompt(
    val system: String = VOICE_INSTRUCTIONS,
    val user: String = VOICE_MESSAGE,
    val previousPhrase: String = PREVIOUS_PHRASE_FORMAT,
)

@Serializable
data class SummarySettings(
    val enabled: Boolean = true,
    /** The summary is rewritten once this many phrases have left the window of previous phrases. */
    val everyPhrases: Int = DEFAULT_SUMMARY_EVERY_PHRASES,
    val system: String = SUMMARY_INSTRUCTIONS,
    /** The phrases to fold in are written with the Main prompt's previous-phrase format. */
    val user: String = SUMMARY_MESSAGE,
)
