package com.romankryvolapov.dialogaitranslator.speech

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import com.romankryvolapov.dialogaitranslator.core.ApplicationInfo
import com.romankryvolapov.dialogaitranslator.core.SAMPLE_RATE
import com.romankryvolapov.dialogaitranslator.core.SentenceSplitter
import com.romankryvolapov.dialogaitranslator.core.SpeechRecognizer
import com.romankryvolapov.dialogaitranslator.core.textAfter
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread
import android.speech.SpeechRecognizer as AndroidRecognizer

/**
 * Android's own speech recognition, on the phone where the language pack is installed. It is given our audio instead
 * of opening the microphone, in one segmented session: each pause settles a phrase and the session goes on.
 *
 * Without the pack on the phone, the phone's default recogniser (which may be online) takes over for the rest of the
 * dialog, and the pack is asked for, so a later dialog can stay on the phone.
 */
class SystemRecognizer(private val context: Context, private val packs: SpeechPacks) : SpeechRecognizer {

    private companion object {
        /** About ten seconds; more means the recogniser no longer reads. */
        const val MAX_QUEUED_FRAMES = 100
        const val MAX_PHRASE_WORDS = 18
        /**
         * Heard text that has not changed for this long is taken as a finished phrase. The recogniser itself waits for
         * silence, and background noise kept a segment open three seconds past the last word.
         */
        const val SETTLED_AFTER_MILLIS = 800L
        /** Errors that mean "nothing was said", after which a new session goes on listening. */
        val QUIET_ERRORS = setOf(AndroidRecognizer.ERROR_NO_MATCH, AndroidRecognizer.ERROR_SPEECH_TIMEOUT)
        val NO_PACK_ERRORS = setOf(AndroidRecognizer.ERROR_LANGUAGE_UNAVAILABLE, AndroidRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED)
    }

    private val log = LoggerFactory.getLogger("AndroidSpeech")
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: Session? = null
    /** The language the phone's own recogniser turned down in this dialog; each dialog tries it again. */
    @Volatile private var noPackFor: String? = null

    override val feedsContinuously = true

    override fun start(info: ApplicationInfo, listener: SpeechRecognizer.Listener) {
        stop()
        noPackFor = null
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
        private var onDevice = false
        /** The recogniser may write no punctuation, so a phrase without a pause is cut by its length. */
        private var splitter = SentenceSplitter(MAX_PHRASE_WORDS)
        /** The segment's latest partial text, whole, and its start that was already given as a phrase. */
        private var heardInSegment = ""
        private var givenText = ""
        /** When the previous phrase's last word was heard, the next phrase's first, and the heard text last changed. */
        private var lastPhraseEndAt: Long? = null
        private var phraseStartAt: Long? = null
        private var changedAt = 0L
        private val settle = Runnable { settleEarly() }
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
            onDevice = noPackFor != language && AndroidRecognizer.isOnDeviceRecognitionAvailable(context)
            val made = when {
                onDevice -> AndroidRecognizer.createOnDeviceSpeechRecognizer(context)
                AndroidRecognizer.isRecognitionAvailable(context) -> AndroidRecognizer.createSpeechRecognizer(context)
                else -> return listener.onFailure("This phone has no speech recognition")
            }
            recognizer = made
            splitter = SentenceSplitter(MAX_PHRASE_WORDS)
            heardInSegment = ""
            givenText = ""
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
            main.removeCallbacks(settle)
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

        /** [text] without the start of this segment already given as a phrase. */
        private fun notGiven(text: String): String = textAfter(givenText, text)

        override fun onPartialResults(partialResults: Bundle?) {
            val heard = text(partialResults).takeIf { current && it.isNotEmpty() } ?: return
            val now = SystemClock.elapsedRealtime()
            if (phraseStartAt == null) phraseStartAt = now
            if (heard != heardInSegment) changedAt = now
            heardInSegment = heard
            val grown = splitter.grow(notGiven(heard))
            grown.phrases.forEach(::give)
            listener.onPartial(grown.rest)
            main.removeCallbacks(settle)
            if (grown.rest.isNotBlank()) main.postDelayed(settle, SETTLED_AFTER_MILLIS)
        }

        /** Nothing new heard for a while: what is left of the segment goes out as a phrase, without waiting for its end. */
        private fun settleEarly() {
            if (!current) return
            val rest = splitter.finish(notGiven(heardInSegment))
            givenText = heardInSegment
            if (rest.isNotBlank()) give(rest)
        }

        /** A phrase, with the silence before it: from the previous phrase's last word to this one's first. */
        private fun give(phrase: String) {
            val pause = lastPhraseEndAt?.let { end -> phraseStartAt?.let { (it - end).coerceAtLeast(0) } }
            listener.onFinal(phrase, pause)
            lastPhraseEndAt = changedAt
            phraseStartAt = null
        }

        /** The segment's own end: only what was not given early is left. */
        private fun endSegment(final: String) {
            main.removeCallbacks(settle)
            val rest = splitter.finish(notGiven(final))
            givenText = ""
            heardInSegment = ""
            if (rest.isNotBlank()) give(rest) else listener.onFinal("")
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            if (current) endSegment(text(segmentResults))
        }

        /** A recogniser that does not know segmented sessions ends with a plain result instead. */
        override fun onResults(results: Bundle?) {
            if (!current) return
            endSegment(text(results))
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
                // What was heard but not settled yet still goes out: the segment ended without a result.
                endSegment(heardInSegment)
                return reopen()
            }
            if (error in NO_PACK_ERRORS && onDevice && AndroidRecognizer.isRecognitionAvailable(context)) {
                log.warn("No {} pack for recognition on the phone: the phone's default recogniser, which may be online, takes over for this dialog; the pack is asked for", language)
                packs.download(language)
                noPackFor = language
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
