package com.romankryvolapov.dialogaitranslator.capture

import android.annotation.SuppressLint
import android.app.Application
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.Process
import android.util.Log
import com.romankryvolapov.dialogaitranslator.core.FRAME_BYTES
import com.romankryvolapov.dialogaitranslator.core.SAMPLE_RATE
import java.io.IOException
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Started by Shizuku in a process of the shell user, which holds the permissions an ordinary app cannot get: to hear
 * what is played as voice communication (messenger calls), and the line of a phone call. The other side is taken
 * from what the phone plays, so neither the loudspeaker nor the microphone is involved, and the call keeps its sound.
 *
 * The playback tap goes through the platform's audio-policy classes, which are not public API: reflection, checked on
 * Android 16 (Xiaomi 2304FPN6DC). Processes started this way are not subject to the hidden-API restrictions.
 */
@SuppressLint("PrivateApi", "DiscouragedPrivateApi", "SoonBlockedPrivateApi", "BlockedPrivateApi", "MissingPermission")
class CaptureService : ICaptureService.Stub() {

    private companion object {
        const val TAG = "CallCapture"
        const val FRAME_SAMPLES = FRAME_BYTES / 2
        const val SHELL_PACKAGE = "com.android.shell"
        // android.media.audiopolicy constants, not public.
        const val MIX_ROLE_PLAYERS = 0
        const val RULE_MATCH_ATTRIBUTE_USAGE = 1
        const val ROUTE_FLAG_LOOP_BACK_RENDER = 3
        val CALL_USAGES = intArrayOf(AudioAttributes.USAGE_VOICE_COMMUNICATION)
        val MEDIA_USAGES = intArrayOf(AudioAttributes.USAGE_MEDIA, AudioAttributes.USAGE_GAME, AudioAttributes.USAGE_UNKNOWN)
    }

    private val context: Context by lazy(::shellContext)
    private var running: Capture? = null

    @Synchronized
    override fun start(call: Boolean, withMicrophone: Boolean): ParcelFileDescriptor {
        stop()
        val (reader, writer) = ParcelFileDescriptor.createPipe().let { it[0] to it[1] }
        // The audio service must see the shell user asking, not the app whose call this is.
        val caller = Binder.clearCallingIdentity()
        try {
            running = Capture(call, withMicrophone, writer)
        } catch (e: Throwable) {
            Log.e(TAG, "capture did not start", e)
            reader.close()
            writer.close()
            // Binder carries only a few exception types to the app; this is one of them.
            throw IllegalStateException("${(e.cause ?: e).javaClass.simpleName}: ${(e.cause ?: e).message}")
        } finally {
            Binder.restoreCallingIdentity(caller)
        }
        return reader
    }

    @Synchronized
    override fun stop() {
        running?.close()
        running = null
    }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    private inner class Capture(private val call: Boolean, withMicrophone: Boolean, writer: ParcelFileDescriptor) {
        @Volatile private var open = true
        private val playback = Playback(if (call) CALL_USAGES else MEDIA_USAGES, call)
        private val microphone = try {
            if (call && withMicrophone) record(MediaRecorder.AudioSource.VOICE_RECOGNITION) else null
        } catch (e: Throwable) {
            playback.close()
            throw e
        }
        /** The phone-call line; open only while there is a phone call, since it is routed when it is opened. */
        private var line: AudioRecord? = null
        private var inPhoneCall = false
        private val audio = context.getSystemService(AudioManager::class.java)

        private val worker = thread(name = "call-capture") {
            val mix = IntArray(FRAME_SAMPLES)
            val part = ShortArray(FRAME_SAMPLES)
            val bytes = ByteArray(FRAME_BYTES)
            var frames = 0
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(writer).use { out ->
                    while (open) {
                        mix.fill(0)
                        add(playback.record, part, mix)
                        microphone?.let { add(it, part, mix) }
                        if (call && frames++ % 5 == 0) followPhoneCall()
                        line?.let { add(it, part, mix) }
                        for (i in mix.indices) {
                            val sample = mix[i].coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
                            bytes[2 * i] = sample.toByte()
                            bytes[2 * i + 1] = (sample shr 8).toByte()
                        }
                        out.write(bytes)
                    }
                }
            } catch (e: IOException) {
                Log.i(TAG, "the app stopped listening: ${e.message}")
            } catch (e: Throwable) {
                Log.e(TAG, "capture broke off", e)
            } finally {
                playback.close()
                microphone?.release()
                line?.release()
            }
        }

        /** Reads a whole frame, which also paces the loop: every source delivers in real time. */
        private fun add(record: AudioRecord, part: ShortArray, mix: IntArray) {
            var got = 0
            while (got < part.size) {
                val read = record.read(part, got, part.size - got)
                check(read > 0) { "audio source ended ($read)" }
                got += read
            }
            for (i in part.indices) mix[i] += part[i]
        }

        /** Only the other side: the user is heard by the microphone, when wanted. */
        private fun followPhoneCall() {
            val inCall = audio.mode == AudioManager.MODE_IN_CALL
            if (inCall == inPhoneCall) return
            inPhoneCall = inCall
            line?.release()
            line = if (!inCall) null else try {
                record(MediaRecorder.AudioSource.VOICE_DOWNLINK)
            } catch (e: Exception) {
                Log.w(TAG, "the phone-call line did not open", e)
                null
            }
            Log.i(TAG, "phone call: $inCall, line open: ${line != null}")
        }

        fun close() {
            open = false
            worker.join(1_000)
        }
    }

    private fun format(channels: Int) = AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(SAMPLE_RATE).setChannelMask(channels).build()

    private fun record(source: Int): AudioRecord = AudioRecord.Builder().setContext(context).setAudioSource(source)
        .setAudioFormat(format(AudioFormat.CHANNEL_IN_MONO)).setBufferSizeInBytes(FRAME_BYTES * 10).build()
        .also(::begin)

    private fun begin(record: AudioRecord) {
        record.startRecording()
        if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) return
        record.release()
        error("recording did not start")
    }

    /** A copy of what is played with [usages], while it goes on being heard as usual. */
    private inner class Playback(usages: IntArray, call: Boolean) {
        private val policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
        private val policy: Any
        val record: AudioRecord

        init {
            val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val rule = ruleBuilder.getConstructor().newInstance().let { builder ->
                ruleBuilder.getMethod("setTargetMixRole", Int::class.java).invoke(builder, MIX_ROLE_PLAYERS)
                val addRule = ruleBuilder.getMethod("addRule", AudioAttributes::class.java, Int::class.java)
                usages.forEach { addRule.invoke(builder, AudioAttributes.Builder().setUsage(it).build(), RULE_MATCH_ATTRIBUTE_USAGE) }
                if (call) ruleBuilder.getMethod("voiceCommunicationCaptureAllowed", Boolean::class.java).invoke(builder, true)
                ruleBuilder.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.java).invoke(builder, true)
                ruleBuilder.getMethod("build").invoke(builder)!!
            }
            val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mix = mixBuilder.getConstructor(rule.javaClass).newInstance(rule).let { builder ->
                // Voice communication may only be captured at 16 kHz mono or less.
                mixBuilder.getMethod("setFormat", AudioFormat::class.java).invoke(builder, format(AudioFormat.CHANNEL_OUT_MONO))
                mixBuilder.getMethod("setRouteFlags", Int::class.java).invoke(builder, ROUTE_FLAG_LOOP_BACK_RENDER)
                mixBuilder.getMethod("build").invoke(builder)!!
            }
            val policyBuilder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            policy = policyBuilder.getConstructor(Context::class.java).newInstance(context).let { builder ->
                policyBuilder.getMethod("setLooper", Looper::class.java).invoke(builder, Looper.getMainLooper())
                policyBuilder.getMethod("addMix", mix.javaClass).invoke(builder, mix)
                policyBuilder.getMethod("build").invoke(builder)!!
            }
            val registered = AudioManager::class.java.getDeclaredMethod("registerAudioPolicyStatic", policyClass)
                .apply { isAccessible = true }.invoke(null, policy)
            check(registered == 0) { "audio policy refused ($registered)" }
            try {
                record = policyClass.getMethod("createAudioRecordSink", mix.javaClass).invoke(policy, mix) as AudioRecord
                begin(record)
            } catch (e: Throwable) {
                unregister()
                throw e
            }
        }

        private fun unregister() {
            AudioManager::class.java.getDeclaredMethod("unregisterAudioPolicyAsyncStatic", policyClass)
                .apply { isAccessible = true }.invoke(null, policy)
        }

        fun close() {
            record.release()
            runCatching(::unregister).onFailure { Log.w(TAG, "audio policy not unregistered", it) }
        }
    }

    /** The process runs as the shell user, so its audio must be attributed to the shell's package. */
    private fun shellContext(): Context {
        val threadClass = Class.forName("android.app.ActivityThread")
        val current = threadClass.getDeclaredMethod("currentActivityThread").invoke(null)
            ?: threadClass.getDeclaredMethod("systemMain").invoke(null)!!
        val system = threadClass.getDeclaredMethod("getSystemContext").invoke(current) as Context
        val shell = object : ContextWrapper(system) {
            override fun getPackageName() = SHELL_PACKAGE
            override fun getOpPackageName() = SHELL_PACKAGE
            override fun getApplicationContext(): Context = this
            override fun getAttributionSource(): AttributionSource =
                AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        }
        val application = Application()
        ContextWrapper::class.java.getDeclaredField("mBase").apply { isAccessible = true }.set(application, shell)
        threadClass.getDeclaredField("mInitialApplication").apply { isAccessible = true }.set(current, application)
        return shell
    }
}
