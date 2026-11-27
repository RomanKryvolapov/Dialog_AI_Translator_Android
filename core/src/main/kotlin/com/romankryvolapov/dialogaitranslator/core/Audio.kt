package com.romankryvolapov.dialogaitranslator.core

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

const val SAMPLE_RATE = 16_000
const val FRAME_MILLIS = 100
/** One frame: 100 ms of 16 kHz 16-bit mono PCM. */
const val FRAME_BYTES = SAMPLE_RATE * 2 * FRAME_MILLIS / 1000

/** Delivers frames of [FRAME_BYTES] from [start] until [stop]. */
interface AudioSource {
    /** [onFailure]: the source ended by itself, with the reason for the user. */
    fun start(onFrame: (ByteArray) -> Unit, onFailure: (String) -> Unit)
    fun stop()
}

/** A streaming speech recogniser. The listener may be called on any thread, until [stop]. */
interface SpeechRecognizer {

    /** Takes every frame, silence included, and finds the pauses itself; otherwise it gets speech only. */
    val feedsContinuously: Boolean

    fun start(info: ApplicationInfo, listener: Listener)
    fun feed(pcm: ByteArray)
    /** The speaker paused: settle the phrase. */
    fun endOfUtterance()
    fun stop()

    interface Listener {
        /** The phrase being said, as far as it is heard; replaces the previous partial. */
        fun onPartial(text: String)
        /**
         * The phrase as settled; blank when nothing was said after all. [pauseBeforeMillis]: the silence between the
         * previous phrase's last word and this one's first, where the recogniser can tell.
         */
        fun onFinal(text: String, pauseBeforeMillis: Long? = null)
        fun onFailure(reason: String)
    }
}

/** [pcm] (16-bit little-endian) made [percent] as loud, in place; a sample that would overflow is held at the limit. */
fun amplify(pcm: ByteArray, percent: Int) {
    if (percent == 100) return
    val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    for (i in 0 until samples.limit()) {
        val louder = samples.get(i) * percent / 100
        samples.put(i, louder.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort())
    }
}

/** Loudness of [pcm], 0–100. */
fun voiceLevel(pcm: ByteArray): Int {
    val samples = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
    val count = samples.remaining()
    if (count == 0) return 0
    var sum = 0.0
    while (samples.hasRemaining()) samples.get().toDouble().let { sum += it * it }
    return (sqrt(sum / count) / 3000.0 * 100).coerceIn(0.0, 100.0).toInt()
}

fun wavOf(pcm: ByteArray): ByteArray = ByteBuffer.allocate(44 + pcm.size).order(ByteOrder.LITTLE_ENDIAN)
    .put("RIFF".toByteArray()).putInt(36 + pcm.size).put("WAVEfmt ".toByteArray())
    .putInt(16).putShort(1).putShort(1).putInt(SAMPLE_RATE).putInt(SAMPLE_RATE * 2).putShort(2).putShort(16)
    .put("data".toByteArray()).putInt(pcm.size).put(pcm).array()

/**
 * Finds the speech in a stream of frames: cuts it into recorded phrases, or passes it on as it is said. Time is
 * counted in frames, so it does not depend on when they are delivered. Not thread-safe.
 */
class PhraseCutter(private val listener: Listener) {

    interface Listener {
        fun onSpeech(pcm: ByteArray) {}
        fun onSpeechEnd() {}
        fun onPhrase(wav: ByteArray) {}
    }

    private companion object {
        const val MIN_PHRASE_MILLIS = 500
        /** Frames kept, the first loud one included: the start of a word is quieter than its vowel. */
        const val PRE_ROLL_FRAMES = 3
    }

    var settings = PhraseSettings()

    /** True: whole phrases are recorded; false: speech is passed on frame by frame. */
    var recordPhrases = false
        set(value) {
            if (field != value) reset()
            field = value
        }

    private val preRoll = ArrayDeque<ByteArray>()
    private val phrase = ArrayList<ByteArray>()
    private var speaking = false
    private var silentMillis = 0
    private var spokenMillis = 0

    fun reset() {
        preRoll.clear()
        phrase.clear()
        speaking = false
    }

    fun frame(pcm: ByteArray) {
        val loud = voiceLevel(pcm) > settings.silenceThresholdPercents
        if (!speaking) {
            preRoll.addLast(pcm)
            if (preRoll.size > PRE_ROLL_FRAMES) preRoll.removeFirst()
            if (!loud) return
            speaking = true
            silentMillis = 0
            spokenMillis = FRAME_MILLIS
            preRoll.forEach(::take)
            preRoll.clear()
            return
        }
        take(pcm)
        spokenMillis += FRAME_MILLIS
        silentMillis = if (loud) 0 else silentMillis + FRAME_MILLIS
        val paused = silentMillis >= settings.maxSilenceMillis
        if (recordPhrases && (paused || spokenMillis >= settings.maxPhraseMillis)) {
            val recorded = phrase.fold(ByteArray(0), ByteArray::plus)
            val longEnough = spokenMillis - silentMillis >= MIN_PHRASE_MILLIS
            phrase.clear()
            spokenMillis = 0
            speaking = !paused
            if (longEnough) listener.onPhrase(wavOf(recorded))
        } else if (paused) {
            speaking = false
            listener.onSpeechEnd()
        }
    }

    private fun take(pcm: ByteArray) {
        if (recordPhrases) phrase.add(pcm) else listener.onSpeech(pcm)
    }
}
