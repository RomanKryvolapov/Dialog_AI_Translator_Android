package com.romankryvolapov.dialogaitranslator.capture

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelFileDescriptor
import com.romankryvolapov.dialogaitranslator.BuildConfig
import com.romankryvolapov.dialogaitranslator.core.ApplicationInfo
import com.romankryvolapov.dialogaitranslator.core.AudioInput
import com.romankryvolapov.dialogaitranslator.core.AudioSource
import com.romankryvolapov.dialogaitranslator.core.FRAME_BYTES
import com.romankryvolapov.dialogaitranslator.core.SAMPLE_RATE
import org.slf4j.LoggerFactory
import rikka.shizuku.Shizuku
import java.io.DataInputStream
import java.io.IOException
import kotlin.concurrent.thread

fun audioSourceFor(context: Context, info: ApplicationInfo): AudioSource = when (info.audioInput) {
    AudioInput.MICROPHONE -> MicrophoneSource()
    AudioInput.CALL -> PhoneSource(context, call = true, withMicrophone = info.hearBothSides)
    AudioInput.MEDIA -> PhoneSource(context, call = false, withMicrophone = false)
}

class MicrophoneSource : AudioSource {

    @Volatile private var running = false

    @SuppressLint("MissingPermission") // asked for before a dialog starts
    override fun start(onFrame: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
        running = true
        thread(name = "microphone") {
            try {
                val record = AudioRecord.Builder()
                    .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
                    .setAudioFormat(
                        AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build(),
                    )
                    .setBufferSizeInBytes(FRAME_BYTES * 10)
                    .build()
                try {
                    record.startRecording()
                    while (running) {
                        val frame = ByteArray(FRAME_BYTES)
                        var got = 0
                        while (got < FRAME_BYTES) {
                            val read = record.read(frame, got, FRAME_BYTES - got)
                            if (read <= 0) throw IOException("the microphone ended ($read)")
                            got += read
                        }
                        if (running) onFrame(frame)
                    }
                } finally {
                    record.release()
                }
            } catch (e: Exception) {
                if (running) onFailure("Microphone: ${e.message}")
            }
        }
    }

    override fun stop() {
        running = false
    }
}

/** What Shizuku still needs before the phone's own sound can be heard; null when it is ready. */
fun shizukuProblem(): String? = try {
    when {
        !Shizuku.pingBinder() -> SHIZUKU_OFF
        Shizuku.isPreV11() -> "Shizuku is too old"
        Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> "Shizuku permission is not granted"
        else -> null
    }
} catch (e: RuntimeException) {
    // It stopped between two of the questions.
    SHIZUKU_OFF
}

private const val SHIZUKU_OFF = "Shizuku is not running: open Shizuku and start it"
private const val CONNECT_TIMEOUT_MILLIS = 10_000L

/** A call or other sound of this phone, heard by [CaptureService] in a Shizuku process. */
class PhoneSource(
    private val context: Context,
    private val call: Boolean,
    private val withMicrophone: Boolean,
) : AudioSource {

    private val log = LoggerFactory.getLogger("PhoneSource")
    private val args = Shizuku.UserServiceArgs(ComponentName(context, CaptureService::class.java))
        .daemon(false).processNameSuffix("capture").debuggable(BuildConfig.DEBUG).version(BuildConfig.VERSION_CODE)
    @Volatile private var running = false
    @Volatile private var service: ICaptureService? = null
    private var connection: ServiceConnection? = null

    override fun start(onFrame: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
        shizukuProblem()?.let { return onFailure(it) }
        running = true
        val bound = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                val capture = ICaptureService.Stub.asInterface(binder)
                service = capture
                if (running) thread(name = "phone-sound") { read(capture, onFrame, onFailure) }
            }

            override fun onNullBinding(name: ComponentName) = failed("Shizuku started no capture process")

            override fun onServiceDisconnected(name: ComponentName) {
                service = null
                failed("Shizuku stopped")
            }

            private fun failed(reason: String) {
                if (running) onFailure(reason)
            }
        }
        connection = bound
        try {
            Shizuku.bindUserService(args, bound)
        } catch (e: RuntimeException) {
            return onFailure("Shizuku: ${e.message ?: "no connection"}")
        }
        Handler(Looper.getMainLooper()).postDelayed({
            if (running && connection === bound && service == null) onFailure("Shizuku did not start the capture process in time")
        }, CONNECT_TIMEOUT_MILLIS)
    }

    private fun read(capture: ICaptureService, onFrame: (ByteArray) -> Unit, onFailure: (String) -> Unit) {
        try {
            DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(capture.start(call, withMicrophone))).use { input ->
                log.info("Listening to {}", if (call) "calls" else "the phone's sound")
                while (running) {
                    val frame = ByteArray(FRAME_BYTES)
                    input.readFully(frame)
                    if (running) onFrame(frame)
                }
            }
        } catch (e: Exception) {
            if (running) onFailure("Call capture: ${e.message ?: "ended"}")
        }
    }

    override fun stop() {
        running = false
        val bound = connection ?: return
        connection = null
        val capture = service
        thread(name = "phone-sound-stop") {
            runCatching { capture?.stop() }
            runCatching { Shizuku.unbindUserService(args, bound, true) }.onFailure { log.warn("Shizuku service not unbound: {}", it.message) }
        }
    }
}
