package com.romankryvolapov.dialogaitranslator.capture

import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AudioEffect
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import com.romankryvolapov.dialogaitranslator.core.MicrophoneSettings

/** The phone's own microphone effects: which it has, and switching on the chosen ones for a recording. */
object MicrophoneEffects {
    class Switched(val name: String, val effect: AudioEffect, val on: Boolean)

    const val NOISE = "noise suppression"
    const val GAIN = "automatic gain"
    const val ECHO = "echo cancellation"

    val available: Set<String> by lazy {
        buildSet {
            if (NoiseSuppressor.isAvailable()) add(NOISE)
            if (AutomaticGainControl.isAvailable()) add(GAIN)
            if (AcousticEchoCanceler.isAvailable()) add(ECHO)
        }
    }

    /**
     * Switches the effects on the recording [session] to what [settings] chose, those this phone has, and returns them
     * to be released with the recording: held until then, since a phone may apply an effect on its own and one chosen
     * off has to stay off.
     */
    fun attach(session: Int, settings: MicrophoneSettings): List<Switched> {
        val made = ArrayList<Switched>()
        fun use(name: String, wanted: Boolean, make: (Int) -> AudioEffect?) {
            if (name !in available) return
            val effect = make(session) ?: return
            made += Switched(name, effect, wanted)
            effect.enabled = wanted
        }
        try {
            use(NOISE, settings.noiseSuppression, NoiseSuppressor::create)
            use(GAIN, settings.automaticGain, AutomaticGainControl::create)
            use(ECHO, settings.echoCancellation, AcousticEchoCanceler::create)
        } catch (e: RuntimeException) {
            // An effect this phone lists but will not make: the ones made so far go, and the recording fails loudly.
            made.forEach { it.effect.release() }
            throw e
        }
        return made
    }
}
