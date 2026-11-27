package com.romankryvolapov.dialogaitranslator.speech

import android.content.Context
import android.content.Intent
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import org.slf4j.LoggerFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The language packs of Android's own recogniser on the phone: whether one is there, and asking the system for it.
 * The system downloads it and may show its own window first; the phone's settings offer the same.
 */
class SpeechPacks(private val context: Context) {

    /** [settled]: the answer will not change by itself, so there is no point asking again. */
    enum class State(val text: String, val canDownload: Boolean, val settled: Boolean = true) {
        INSTALLED("on the phone", false),
        DOWNLOADING("being downloaded", false, settled = false),
        AVAILABLE("not on the phone yet", true, settled = false),
        NOT_SAID("the phone did not say whether it is there; download it if it is missing", true, settled = false),
        ONLINE("not offered on the phone; the phone's default recogniser hears it online", false),
        UNSUPPORTED("not offered on the phone, nor by the phone's default recogniser", false),
        UNKNOWN("not offered on the phone; the phone's default recogniser, which may be online, does not say whether it knows it", false),
        NO_ON_DEVICE("this phone has no recognition of its own; its default recogniser, which may be online, is used", false),
    }

    private companion object {
        const val ANSWER_WAIT_MILLIS = 2_000L
    }

    private val log = LoggerFactory.getLogger("SpeechPacks")

    suspend fun state(language: String): State = withContext(Dispatchers.Main) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return@withContext State.NO_ON_DEVICE
        val onDevice = support(SpeechRecognizer.createOnDeviceSpeechRecognizer(context), language)
            ?: return@withContext State.NOT_SAID
        val state = onDevice.stateOf(language)
        if (state != State.UNSUPPORTED || !SpeechRecognizer.isRecognitionAvailable(context)) return@withContext state
        val default = support(SpeechRecognizer.createSpeechRecognizer(context), language)
        when {
            default == null -> State.UNKNOWN
            default.onlineLanguages.has(language) -> State.ONLINE
            else -> State.UNSUPPORTED
        }
    }

    /**
     * What [recognizer] says it can do; null when it would not say. Google's recogniser may report an error and then
     * the answer, so an error alone settles nothing: the answer is waited for a moment longer.
     */
    private suspend fun support(recognizer: SpeechRecognizer, language: String): RecognitionSupport? = try {
        val answer = CompletableDeferred<RecognitionSupport>()
        recognizer.checkRecognitionSupport(intent(language), context.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                answer.complete(support)
            }
            override fun onError(error: Int) = log.debug("The recogniser reported error {} on the languages it has", error)
        })
        withTimeoutOrNull(ANSWER_WAIT_MILLIS) { answer.await() }
    } finally {
        recognizer.destroy()
    }

    /** Asks the system for the pack; it may show its own window. */
    fun download(language: String) {
        if (!SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) return
        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        runCatching { recognizer.triggerModelDownload(intent(language)) }
            .onSuccess { log.info("The {} speech pack is asked for", language) }
            .onFailure { log.warn("The {} speech pack could not be asked for: {}", language, it.message) }
        recognizer.destroy()
    }

    private fun intent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)

    private fun List<String>.has(language: String) = any { it.equals(language, ignoreCase = true) }

    private fun RecognitionSupport.stateOf(language: String): State = when {
        installedOnDeviceLanguages.has(language) -> State.INSTALLED
        pendingOnDeviceLanguages.has(language) -> State.DOWNLOADING
        supportedOnDeviceLanguages.has(language) -> State.AVAILABLE
        else -> State.UNSUPPORTED
    }
}
