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
     * Get note name from MIDI note number using locale-aware naming.
     */
    fun noteName(): String {
        return net.tigr.musicsheetflow.util.NoteNaming.fromMidi(midiNote)
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

    /**
     * Set the minimum confidence threshold for pitch detection (0.0-1.0)
     * Lower values = more detections but possibly more false positives
     */
    fun setConfidenceThreshold(threshold: Float) {
        nativeSetConfidenceThreshold(threshold.coerceIn(0.1f, 0.9f))
    }

    /**
     * Set the silence threshold in dB (e.g., -50.0)
     * Lower values (more negative) = more sensitive to quiet sounds
     */
    fun setSilenceThreshold(thresholdDb: Float) {
        nativeSetSilenceThreshold(thresholdDb.coerceIn(-70f, -20f))
    }

    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeSetNoiseGate(thresholdDb: Float)
    private external fun nativeSetConfidenceThreshold(threshold: Float)
    private external fun nativeSetSilenceThreshold(thresholdDb: Float)
    private external fun nativeSetCallback(callback: PitchCallback?)
}
