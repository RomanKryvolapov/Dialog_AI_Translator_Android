package com.romankryvolapov.dialogaitranslator.core

import kotlinx.serialization.Serializable

/** Where the conversation is heard. */
@Serializable
enum class AudioInput(val title: String) {
    MICROPHONE("Microphone"),
    /** Messenger and phone calls, through Shizuku. */
    CALL("Call on this phone"),
    /** Video, music, browser, through Shizuku. */
    MEDIA("Sound of this phone"),
}

/** Every setting. Stored without the fields left at their defaults, so a better default reaches an untouched field. */
@Serializable
data class ApplicationInfo(
    /** Keys of the translation methods, method 1 first; see [translationMethods]. */
    val methodKeys: List<String> = DEFAULT_METHODS,
    val googleCloudToken: String = "",
    val openAiToken: String = "",
    val claudeToken: String = "",
    val prompts: PromptSettings = PromptSettings(),
    /** The request settings the user chose per model id; a model not here takes its defaults. */
    val modelParameters: Map<String, ModelParameters> = emptyMap(),
    val audioInput: AudioInput = AudioInput.MICROPHONE,
    /** In a call, hear the user as well as the other side. */
    val hearBothSides: Boolean = false,
    /** The language spoken in the conversation, as a BCP-47 tag. */
    val speechLanguage: String = "en-US",
    val phrases: PhraseSettings = PhraseSettings(),
    val useStreamingResponse: Boolean = true,
    val questions: QuestionsSettings = QuestionsSettings(),
    /** Off: phrases are still heard and shown (and checked for questions), but none is sent for translation. */
    val translationEnabled: Boolean = true,
    /** Show the latest translation and answer over other apps while a dialog runs. */
    val overlayEnabled: Boolean = false,
) {
    /** Method 1 first, then its reserves in the order they are tried; never empty. */
    val translationMethods: List<TranslationMethod>
        get() = resolveMethodChain(methodKeys).ifEmpty { resolveMethodChain(DEFAULT_METHODS) }

    fun apiKey(model: LlmModel): String = when (model.engine) {
        LlmModelEngine.GOOGLE -> googleCloudToken
        LlmModelEngine.OPENAI -> openAiToken
        LlmModelEngine.CLAUDE -> claudeToken
    }

    /** Never the keys themselves. */
    override fun toString() =
        "ApplicationInfo($methodKeys, $audioInput, keys of ${googleCloudToken.length}/${openAiToken.length}/${claudeToken.length})"

    companion object {
        val DEFAULT_METHODS = listOf("gemini-live:gemini-3.5-flash-lite")
    }
}

/** Where speech is cut into phrases. */
@Serializable
data class PhraseSettings(
    /** The level below which sound counts as silence, in percent. */
    val silenceThresholdPercents: Int = 5,
    /** A silence this long ends the phrase. */
    val maxSilenceMillis: Long = 500,
    /** The longest phrase recorded for a voice model. */
    val maxPhraseMillis: Long = 5_000,
)
