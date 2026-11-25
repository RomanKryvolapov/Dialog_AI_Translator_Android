package com.romankryvolapov.dialogaitranslator.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import com.romankryvolapov.dialogaitranslator.core.ApplicationInfo
import com.romankryvolapov.dialogaitranslator.core.SAMPLE_RATE
import com.romankryvolapov.dialogaitranslator.core.SentenceSplitter
import com.romankryvolapov.dialogaitranslator.core.SpeechRecognizer
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import android.speech.SpeechRecognizer as AndroidRecognizer

/**
 * Android's own speech recognition, on the phone where the language pack is installed. It is given our audio instead
 * of opening the microphone, in one segmented session: each pause settles a phrase and the session goes on.
 */
class SystemRecognizer(private val context: Context) : SpeechRecognizer {

    private companion object {
        /** About ten seconds; more means the recogniser no longer reads. */
        const val MAX_QUEUED_FRAMES = 100
        const val MAX_PHRASE_WORDS = 18
        /** Errors that mean "nothing was said", after which a new session goes on listening. */
        val QUIET_ERRORS = setOf(AndroidRecognizer.ERROR_NO_MATCH, AndroidRecognizer.ERROR_SPEECH_TIMEOUT)
    }

    private val log = LoggerFactory.getLogger("AndroidSpeech")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: Session? = null

    override val feedsContinuously = true

    override fun start(info: ApplicationInfo, listener: SpeechRecognizer.Listener) {
        stop()
        val next = Session(info.speechLanguage, listener)
        session = next
        main.post(next::open)
    }

    override fun feed(pcm: ByteArray) {
        session?.feed(pcm)
    }

    /** It finds the pauses itself. */
    override fun endOfUtterance() = Unit

    override fun stop() {
        val old = session ?: return
        session = null
        main.post(old::close)
    }

    private inner class Session(private val language: String, private val listener: SpeechRecognizer.Listener) : RecognitionListener {
        private val frames = ArrayBlockingQueue<ByteArray>(MAX_QUEUED_FRAMES)
        private var recognizer: AndroidRecognizer? = null
        /** The recogniser may write no punctuation, so a phrase without a pause is cut by its length. */
        private var splitter = SentenceSplitter(MAX_PHRASE_WORDS)
        private var reader: ParcelFileDescriptor? = null
        @Volatile private var output: OutputStream? = null

        private val current: Boolean get() = session === this

        fun feed(pcm: ByteArray) {
            if (!frames.offer(pcm)) {
                frames.poll()
                frames.offer(pcm)
            }
        }

        fun open() {
            if (!current) return
            val made = when {
                AndroidRecognizer.isOnDeviceRecognitionAvailable(context) -> AndroidRecognizer.createOnDeviceSpeechRecognizer(context)
                AndroidRecognizer.isRecognitionAvailable(context) -> AndroidRecognizer.createSpeechRecognizer(context)
                else -> return listener.onFailure("This phone has no speech recognition")
            }
            recognizer = made
            splitter = SentenceSplitter(MAX_PHRASE_WORDS)
            val pipe = ParcelFileDescriptor.createPipe()
            reader = pipe[0]
            val sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
            output = sink
            thread(name = "android-speech-feed") {
                try {
                    while (output === sink) {
                        // Looked at first: a frame that comes after this session closed belongs to the next one.
                        val frame = frames.peek()
                        if (frame == null) Thread.sleep(20)
                        else {
                            sink.write(frame)
                            frames.remove(frame)
                        }
                    }
                } catch (e: IOException) {
                    log.debug("The recogniser closed its end: {}", e.message)
                }
            }
            made.setRecognitionListener(this)
            made.startListening(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    .putExtra(RecognizerIntent.EXTRA_ENABLE_FORMATTING, RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, reader)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, SAMPLE_RATE)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
                    .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
                    .putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE),
            )
        }

        fun close() {
            runCatching { output?.close() }
            output = null
            runCatching { reader?.close() }
            reader = null
            recognizer?.destroy()
            recognizer = null
        }

        private fun reopen() {
            close()
            main.postDelayed(::open, 200)
        }

        private fun text(bundle: Bundle?): String =
            bundle?.getStringArrayList(AndroidRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty().trim()

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = text(partialResults).takeIf { current && it.isNotEmpty() } ?: return
            val grown = splitter.grow(heard)
            grown.phrases.forEach(listener::onFinal)
            listener.onPartial(grown.rest)
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (current) listener.onFinal(splitter.finish(text(segmentResults)))
        }

        /** A recogniser that does not know segmented sessions ends with a plain result instead. */
        override fun onResults(results: Bundle?) {
            if (!current) return
            listener.onFinal(splitter.finish(text(results)))
            reopen()
        }

        override fun onEndOfSegmentedSession() {
            log.debug("The segmented session ended")
            if (current) reopen()
        }

        override fun onError(error: Int) {
            if (!current) return
            log.info("Recogniser error {}", error)
            if (error in QUIET_ERRORS) {
                listener.onFinal("")
                return reopen()
            }
            close()
            listener.onFailure("Android speech recognition: ${describe(error)}")
        }

        private fun describe(error: Int): String = when (error) {
            AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED -> "$language is not supported"
            AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "the language pack for $language is not installed"
            AndroidRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "no microphone permission"
            AndroidRecognizer.ERROR_RECOGNIZER_BUSY, AndroidRecognizer.ERROR_TOO_MANY_REQUESTS -> "busy"
            AndroidRecognizer.ERROR_NETWORK, AndroidRecognizer.ERROR_NETWORK_TIMEOUT -> "network error"
            else -> "error $error"
        }

        override fun onReadyForSpeech(params: Bundle?) = Unit
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit
    }
}
