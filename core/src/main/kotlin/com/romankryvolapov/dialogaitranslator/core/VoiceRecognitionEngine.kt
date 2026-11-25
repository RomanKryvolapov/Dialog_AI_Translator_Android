package com.romankryvolapov.dialogaitranslator.core

/**
 * The speech recogniser of a text translation method (the first level of a [TranslationMethod]; a voice
 * method has none — its model hears the phrase itself).
 */
enum class VoiceRecognitionEngine(
    val title: String,
    /** For tight places: the status line. */
    val shortTitle: String,
    /** How it appears in a saved method key: "<slug>:<model id>". */
    val slug: String,
) {
    GEMINI_LIVE("Gemini Transcribe Live (realtime, cloud)", "Gemini Live", "gemini-live"),
    SYSTEM("Android speech recognition (on the phone)", "Android speech", "system");

    companion object {
        fun bySlug(slug: String): VoiceRecognitionEngine? = entries.firstOrNull { it.slug == slug }
    }
}
