package com.romankryvolapov.dialogaitranslator.core

/**
 * One way to turn a phrase into its translation, chosen on two levels.
 *
 * The first level is how the speech is heard: by a model that hears audio itself ([recognizer] null — a
 * voice method: the recorded phrase goes to [model], which writes both what was said and the translation),
 * or by a speech [recognizer]. The second level only exists for a recogniser: the [model] that translates
 * its text.
 *
 * Settings → General holds a chain of up to three; a dialog moves on to the next when the one in use fails.
 */
data class TranslationMethod(val model: LlmModel, val recognizer: VoiceRecognitionEngine?) {

    val voice: Boolean get() = recognizer == null

    /** How the method is saved and named in `.env`: "voice:<model id>" or "<recogniser slug>:<model id>". */
    val key: String get() = (recognizer?.slug ?: VOICE_PREFIX) + ":" + model.id

    val title: String get() = if (recognizer == null) "${model.title} (hears the audio)" else "${recognizer.title} → ${model.title}"

    val shortTitle: String get() = "${model.shortTitle} (${recognizer?.shortTitle ?: "voice"})"

    companion object {
        const val VOICE_PREFIX = "voice"

        /** Method 1 plus up to two reserves. */
        const val MAX_CHAIN = 3
    }
}

/** The first level of a method: a model that hears audio itself, or a speech recogniser. */
sealed interface Hearing {
    val title: String

    data class Voice(val model: LlmModel) : Hearing {
        override val title: String get() = "Voice: ${model.title} hears the audio"
    }

    data class Recognition(val recognizer: VoiceRecognitionEngine) : Hearing {
        override val title: String get() = "Recognition: ${recognizer.title}"
    }
}

val TranslationMethod.hearing: Hearing
    get() = recognizer?.let { Hearing.Recognition(it) } ?: Hearing.Voice(model)

/** Every first-level choice: the recognisers on offer, Android's first, then the models that hear audio. */
fun allHearings(): List<Hearing> =
    VoiceRecognitionEngine.entries.map { Hearing.Recognition(it) } +
        allLlmModels().filter { it.hearsAudio }.map { Hearing.Voice(it) }

/** The second level: models that translate a recogniser's text. */
fun textModels(): List<LlmModel> = allLlmModels().filter { it.readsText }

/** The method a saved [key] names, following a dropped model to its successor; null when it cannot work that way. */
fun resolveTranslationMethod(key: String): TranslationMethod? {
    val prefix = key.trim().substringBefore(':', "")
    val model = resolveLlmModel(key.trim().substringAfter(':')) ?: return null
    if (prefix == TranslationMethod.VOICE_PREFIX) return TranslationMethod(model, null).takeIf { model.hearsAudio }
    val recognizer = VoiceRecognitionEngine.bySlug(prefix) ?: return null
    return TranslationMethod(model, recognizer).takeIf { model.readsText }
}

/** A saved chain as the app runs it: known methods only, each once, at most [TranslationMethod.MAX_CHAIN]. */
fun resolveMethodChain(keys: List<String>): List<TranslationMethod> =
    keys.mapNotNull(::resolveTranslationMethod).distinctBy { it.key }.take(TranslationMethod.MAX_CHAIN)
