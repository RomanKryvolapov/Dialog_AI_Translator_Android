package com.romankryvolapov.dialogaitranslator.core

import com.google.genai.AsyncSession
import com.google.genai.Client
import com.google.genai.types.AudioTranscriptionConfig
import com.google.genai.types.Blob
import com.google.genai.types.HttpOptions
import com.google.genai.types.LiveConnectConfig
import com.google.genai.types.LiveSendRealtimeInputParameters
import com.google.genai.types.LiveServerMessage
import com.google.genai.types.Modality
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.io.IOException

/**
 * Realtime transcription by Gemini Transcribe Live. Audio is sent while someone speaks; a pause ends the turn, and
 * the service answers with the settled phrase. A session lives ten minutes, so it is replaced at a pause before that.
 *
 * The library does not report a session that closed, except in its log: [LiveSessionLog] reads it there. A session
 * that closed is replaced, and one that closes again right after is a failure with the service's reason. A session
 * that answers nothing to [SILENT_AFTER_MILLIS] of audio may be dead without a word or may hear only noise, so it is
 * replaced as well, but that is never a failure. The new session first gets the audio nothing had answered.
 */
class GeminiLiveRecognizer : SpeechRecognizer {

    private companion object {
        const val MODEL = "gemini-3.5-transcribe-live"
        const val MIME = "audio/pcm;rate=$SAMPLE_RATE"
        const val CONNECT_TIMEOUT_MILLIS = 10_000L
        const val SESSION_MILLIS = 8 * 60_000L
        const val SILENT_AFTER_MILLIS = 15_000
        /** A session that closes within this long of replacing one that closed is a failure. */
        const val CLOSED_AGAIN_MILLIS = 20_000L
        /** A minute of audio: what a slow connection may put off before the oldest is lost. */
        const val MAX_QUEUED_FRAMES = 600
    }

    private val log = LoggerFactory.getLogger("GeminiLive")

    /** Null in the queue is the end of an utterance. */
    private var queue: Channel<ByteArray?>? = null
    private var job: Job? = null

    override val feedsContinuously = false

    override fun start(info: ApplicationInfo, listener: SpeechRecognizer.Listener) {
        stop()
        val input = Channel<ByteArray?>(MAX_QUEUED_FRAMES, BufferOverflow.DROP_OLDEST)
        queue = input
        job = backgroundThreadScope.launch {
            try {
                run(info, listener, input)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                log.warn("Gemini Live stopped: {}", e.message, e)
                listener.onFailure("Gemini Live: ${(e.cause ?: e).message ?: e.javaClass.simpleName}")
            }
        }
    }

    override fun feed(pcm: ByteArray) {
        queue?.trySend(pcm)
    }

    override fun endOfUtterance() {
        queue?.trySend(null)
    }

    override fun stop() {
        job?.cancel()
        job = null
        queue = null
    }

    private class Session(val live: AsyncSession) {
        val startedAt = System.nanoTime()
        /** Audio sent since the last message came back; touched by the sender and the session's reader. */
        val unanswered = java.util.concurrent.ConcurrentLinkedQueue<ByteArray>()
        /** The part of the phrase being said that is heard but not taken yet. */
        @Volatile var rest = ""
        /** Why the service closed it. */
        @Volatile var closed: String? = null
        /** The service announced that it is about to close the session. */
        @Volatile var leaving = false
        /** It was given the audio the one before it had not answered. */
        var replayed = false
        /** A replaced session still delivers the phrase it was settling, but nothing about the next one. */
        @Volatile var current = true
        /** Used by the session's own reader thread only. */
        val splitter = SentenceSplitter()

        val worn: Boolean get() = leaving || ageMillis > SESSION_MILLIS
        val ageMillis: Long get() = (System.nanoTime() - startedAt) / 1_000_000
        val silentMillis: Int get() = unanswered.size * FRAME_MILLIS
    }

    private suspend fun run(info: ApplicationInfo, listener: SpeechRecognizer.Listener, input: Channel<ByteArray?>) {
        Client.builder().apiKey(info.googleCloudToken).httpOptions(HttpOptions.builder().apiVersion("v1beta").build()).build().use { client ->
            var session = connect(client, info, listener)
            var lastClosed = false
            val watch = LiveSessionLog.onClosed { session.closed = it }
            try {
                for (pcm in input) {
                    val reason = session.closed
                    if (reason != null || session.silentMillis > SILENT_AFTER_MILLIS) {
                        if (reason != null && lastClosed && session.ageMillis < CLOSED_AGAIN_MILLIS) error(reason)
                        log.warn("Connecting again: {}", reason ?: "no answer to the audio sent")
                        lastClosed = reason != null
                        // A closed session settles nothing more: what it had heard of the phrase is all there will be.
                        if (reason != null && session.rest.isNotBlank()) listener.onFinal(session.rest)
                        // Audio that got no answer twice is noise: it is not sent a third time.
                        val unheard = session.unanswered.toList().takeIf { !session.replayed }.orEmpty()
                        session = replace(session, client, info, listener)
                        session.replayed = unheard.isNotEmpty()
                        unheard.forEach { sendAudio(session, it) }
                    }
                    if (pcm != null) {
                        sendAudio(session, pcm)
                    } else {
                        send(session, LiveSendRealtimeInputParameters.builder().audioStreamEnd(true))
                        if (session.worn) session = replace(session, client, info, listener)
                    }
                }
            } finally {
                watch.close()
                session.current = false
                session.live.close()
            }
        }
    }

    private suspend fun sendAudio(session: Session, pcm: ByteArray) {
        session.unanswered.add(pcm)
        send(session, LiveSendRealtimeInputParameters.builder().audio(Blob.builder().data(pcm).mimeType(MIME)))
    }

    /** One send at a time: the library sends on a thread pool, and only the wait keeps the frames in order. */
    private suspend fun send(session: Session, what: LiveSendRealtimeInputParameters.Builder) {
        session.live.sendRealtimeInput(what.build()).await()
    }

    private suspend fun replace(old: Session, client: Client, info: ApplicationInfo, listener: SpeechRecognizer.Listener): Session {
        val next = connect(client, info, listener)
        old.current = false
        backgroundThreadScope.launch {
            // Time for the last phrase to settle.
            delay(5_000)
            old.live.close()
        }
        return next
    }

    private suspend fun connect(client: Client, info: ApplicationInfo, listener: SpeechRecognizer.Listener): Session {
        val transcription = AudioTranscriptionConfig.builder()
        if (info.speechLanguage.isNotBlank()) transcription.languageCodes(info.speechLanguage)
        val config = LiveConnectConfig.builder()
            .responseModalities(Modality.Known.TEXT)
            .inputAudioTranscription(transcription)
            .build()
        // Not withTimeout: its exception is a cancellation and would end the recogniser without a word.
        val live = withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            withContext(Dispatchers.IO) { client.async.live.connect(MODEL, config) }.await()
        } ?: throw IOException("no connection in ${CONNECT_TIMEOUT_MILLIS / 1000} s")
        val session = Session(live)
        live.receive { onMessage(session, it, listener) }.await()
        log.info("Connected to {} for {}", MODEL, info.speechLanguage)
        return session
    }

    private fun onMessage(session: Session, message: LiveServerMessage, listener: SpeechRecognizer.Listener) {
        session.unanswered.clear()
        if (message.goAway().isPresent) session.leaving = true
        val content = message.serverContent().orElse(null) ?: return
        val partial = content.interimInputTranscription().flatMap { it.text() }.orElse(null)
        val final = content.inputTranscription().flatMap { it.text() }.orElse(null)
        if (final != null) {
            // Blank when nothing is left to settle: the row its partials may have opened goes.
            session.rest = ""
            listener.onFinal(session.splitter.finish(final))
        } else if (partial != null && session.current) {
            val grown = session.splitter.grow(partial)
            grown.phrases.forEach(listener::onFinal)
            session.rest = grown.rest
            listener.onPartial(grown.rest)
        }
    }
}
