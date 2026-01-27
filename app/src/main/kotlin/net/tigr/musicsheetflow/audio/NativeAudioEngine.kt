package net.tigr.musicsheetflow.audio

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

data class PitchEvent(
    val frequency: Float,
    val confidence: Float,
    val midiNote: Int,
    val centDeviation: Int,
    val timestampNs: Long
) {
    /**
     * Get note name from MIDI note number.
     */
    fun noteName(): String {
        if (midiNote < 0) return "--"
        val noteNames = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
        val octave = (midiNote / 12) - 1
        val note = noteNames[midiNote % 12]
        return "$note$octave"
    }
}

interface PitchCallback {
    fun onPitchDetected(
        frequency: Float,
        confidence: Float,
        midiNote: Int,
        centDeviation: Int,
        timestampNs: Long
    )
}

@Singleton
class NativeAudioEngine @Inject constructor() {

    companion object {
        private const val TAG = "NativeAudioEngine"

        init {
            System.loadLibrary("musicsheetflow_native")
        }
    }

    private val _pitchEvents = MutableSharedFlow<PitchEvent>(extraBufferCapacity = 64)
    val pitchEvents: SharedFlow<PitchEvent> = _pitchEvents.asSharedFlow()

    private val callback = object : PitchCallback {
        override fun onPitchDetected(
            frequency: Float,
            confidence: Float,
            midiNote: Int,
            centDeviation: Int,
            timestampNs: Long
        ) {
            _pitchEvents.tryEmit(
                PitchEvent(frequency, confidence, midiNote, centDeviation, timestampNs)
            )
        }
    }

    fun start(): Boolean {
        nativeSetCallback(callback)
        return nativeStart()
    }

    fun stop() {
        nativeStop()
        nativeSetCallback(null)
    }

    fun setNoiseGateThreshold(thresholdDb: Float) {
        nativeSetNoiseGate(thresholdDb)
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeSetNoiseGate(thresholdDb: Float)
    private external fun nativeSetCallback(callback: PitchCallback?)
}
