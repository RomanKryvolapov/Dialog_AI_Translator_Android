package com.romankryvolapov.callsim

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import java.io.File
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Acts like a messenger call: communication audio mode, the other side's voice played as voice communication, and
 * the microphone held as a call holds it. Extras: `speaker` (loudspeaker, default false = earpiece), `text`,
 * `usage` ("call" by default, or "media"), `lang` (default en-US). Says its text once.
 */
class MainActivity : Activity(), TextToSpeech.OnInitListener {

    private companion object {
        val DIALOGUES = listOf(
            "Hi, thanks for joining. We moved the release to next Tuesday, because the payment tests failed. Could you check which hotel is closest to the office? Also, Anna will send the new designs tonight.",
            "Good afternoon. The customer in Berlin liked the demo, but wants offline mode. Which screens should work without a connection? We have a budget of forty thousand euros until March.",
            "So, yesterday the train was late and I missed the keynote. Anyway, they announced version three point five. What did you think of the new colours? Then we can discuss the schedule.",
            "Let me share some background first. Our team has nine people in three time zones. When would be a good time for the weekly meeting? By the way, lunch on Friday is at noon, near the old bridge.",
        )
    }

    private lateinit var view: TextView
    private lateinit var audio: AudioManager
    private var tts: TextToSpeech? = null
    private var player: MediaPlayer? = null
    private var restoreVolume: Pair<Int, Int>? = null
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = TextView(this).apply { textSize = 20f; setPadding(48, 160, 48, 48) }
        setContentView(view)
        audio = getSystemService(AudioManager::class.java)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) begin()
        else requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) begin() else finish()
    }

    private fun begin() {
        view.text = "Preparing the other side's voice"
        tts = TextToSpeech(this, this)
    }

    override fun onInit(status: Int) {
        val engine = tts ?: return
        if (status != TextToSpeech.SUCCESS) {
            view.text = "No text-to-speech: $status"
            return
        }
        engine.language = Locale.forLanguageTag(intent.getStringExtra("lang") ?: "en-US")
        val file = File(cacheDir, "voice.wav")
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit
            override fun onDone(utteranceId: String?) = runOnUiThread { call(file) }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = runOnUiThread { view.text = "Text-to-speech failed" }
        })
        engine.synthesizeToFile(intent.getStringExtra("text") ?: DIALOGUES.random(), null, file, "voice")
    }

    private fun call(voice: File) {
        val media = intent.getStringExtra("usage") == "media"
        if (!media) {
            audio.mode = AudioManager.MODE_IN_COMMUNICATION
            route(intent.getBooleanExtra("speaker", false))
        }
        quiet(if (media) AudioManager.STREAM_MUSIC else AudioManager.STREAM_VOICE_CALL)
        player = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(if (media) AudioAttributes.USAGE_MEDIA else AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build(),
            )
            setDataSource(voice.path)
            setOnCompletionListener {
                view.text = "The other side has finished speaking"
                Log.i("CallSim", "finished")
            }
            prepare()
            start()
        }
        if (!media) holdMicrophone()
        view.text = if (media) "Playing as media" else "In a call, mode ${audio.mode}"
        Log.i("CallSim", "call started: media=$media mode=${audio.mode}")
    }

    /** Barely audible, unless asked for (`loud`): playback capture takes the sound before the volume is applied. */
    private fun quiet(stream: Int) {
        if (intent.getBooleanExtra("loud", false)) return
        restoreVolume = stream to audio.getStreamVolume(stream)
        audio.setStreamVolume(stream, audio.getStreamMinVolume(stream), 0)
    }

    private fun route(speaker: Boolean) {
        if (Build.VERSION.SDK_INT < 31) {
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = speaker
            return
        }
        val type = if (speaker) AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
        audio.availableCommunicationDevices.firstOrNull { it.type == type }?.let(audio::setCommunicationDevice)
    }

    @SuppressLint("MissingPermission")
    private fun holdMicrophone() {
        running = true
        thread {
            val size = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT) * 2
            val record = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, size)
            val buffer = ShortArray(1600)
            record.startRecording()
            while (running) record.read(buffer, 0, buffer.size)
            record.release()
        }
    }

    override fun onDestroy() {
        running = false
        player?.release()
        restoreVolume?.let { (stream, volume) -> audio.setStreamVolume(stream, volume, 0) }
        tts?.shutdown()
        audio.mode = AudioManager.MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice()
        super.onDestroy()
    }
}
