package net.tigr.musicsheetflow.audio

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream

class NativeMidiEngine {

    private var isStarted = false

    /**
     * Load a SoundFont file from the given path
     */
    fun loadSoundFont(path: String): Boolean {
        return nativeLoadSoundFont(path)
    }

    /**
     * Load the bundled SoundFont from assets
     */
    fun loadBundledSoundFont(context: Context): Boolean {
        return try {
            // Copy from assets to cache directory (TSF needs file path)
            val cacheFile = File(context.cacheDir, "TimGM6mb.sf2")

            if (!cacheFile.exists()) {
                Log.i(TAG, "Copying SoundFont to cache...")
                context.assets.open("soundfonts/TimGM6mb.sf2").use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "SoundFont copied: ${cacheFile.absolutePath}")
            }

            loadSoundFont(cacheFile.absolutePath)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load bundled SoundFont", e)
            false
        }
    }

    /**
     * Start the audio output stream
     */
    fun start(): Boolean {
        if (isStarted) return true
        isStarted = nativeStart()
        return isStarted
    }

    /**
     * Stop the audio output stream
     */
    fun stop() {
        if (isStarted) {
            nativeStop()
            isStarted = false
        }
    }

    /**
     * Play a note (MIDI note number 0-127)
     * @param note MIDI note number (60 = middle C)
     * @param velocity Note velocity 0.0-1.0
     */
    fun noteOn(note: Int, velocity: Float = 0.8f) {
        nativeNoteOn(note, velocity)
    }

    /**
     * Stop a note
     */
    fun noteOff(note: Int) {
        nativeNoteOff(note)
    }

    /**
     * Stop all currently playing notes
     */
    fun allNotesOff() {
        nativeAllNotesOff()
    }

    /**
     * Play multiple notes simultaneously (single mutex lock for better performance)
     * @param notes List of Pair(midiNote, velocity)
     */
    fun batchNoteOn(notes: List<Pair<Int, Float>>) {
        if (notes.isEmpty()) return
        val noteArray = notes.map { it.first }.toIntArray()
        val velocityArray = notes.map { it.second }.toFloatArray()
        nativeBatchNoteOn(noteArray, velocityArray)
    }

    /**
     * Set the output volume (0.0 - 1.0)
     */
    fun setVolume(volume: Float) {
        nativeSetVolume(volume.coerceIn(0f, 1f))
    }

    /**
     * Play a note on a specific MIDI channel
     * @param channel MIDI channel (0-15, channel 9 is percussion in GM)
     * @param note MIDI note number (60 = middle C)
     * @param velocity Note velocity 0.0-1.0
     */
    fun noteOnChannel(channel: Int, note: Int, velocity: Float = 0.8f) {
        nativeNoteOnChannel(channel, note, velocity)
    }

    /**
     * Stop a note on a specific MIDI channel
     */
    fun noteOffChannel(channel: Int, note: Int) {
        nativeNoteOffChannel(channel, note)
    }

    /**
     * Set the instrument preset for a MIDI channel
     * @param channel MIDI channel (0-15)
     * @param preset GM preset number (0-127)
     * @param bank Bank number (usually 0 for GM)
     */
    fun setChannelPreset(channel: Int, preset: Int, bank: Int = 0) {
        nativeSetChannelPreset(channel, preset, bank)
    }

    /**
     * Play a metronome click using percussion channel
     * Uses woodblock (MIDI note 76/77) for click sound
     * @param isDownbeat True for first beat of measure (accented)
     */
    fun playMetronomeClick(isDownbeat: Boolean = false) {
        val note = if (isDownbeat) HIGH_WOODBLOCK else LOW_WOODBLOCK
        val velocity = if (isDownbeat) 1.0f else 0.7f
        nativeNoteOnChannel(PERCUSSION_CHANNEL, note, velocity)
    }

    companion object {
        private const val TAG = "NativeMidiEngine"
        private const val PERCUSSION_CHANNEL = 9  // GM percussion channel
        private const val LOW_WOODBLOCK = 76      // GM percussion note
        private const val HIGH_WOODBLOCK = 77     // GM percussion note (accented)

        init {
            System.loadLibrary("musicsheetflow_native")
        }
    }

    // Native methods
    private external fun nativeLoadSoundFont(path: String): Boolean
    private external fun nativeStart(): Boolean
    private external fun nativeStop()
    private external fun nativeNoteOn(note: Int, velocity: Float)
    private external fun nativeNoteOff(note: Int)
    private external fun nativeAllNotesOff()
    private external fun nativeSetVolume(volume: Float)
    private external fun nativeNoteOnChannel(channel: Int, note: Int, velocity: Float)
    private external fun nativeNoteOffChannel(channel: Int, note: Int)
    private external fun nativeSetChannelPreset(channel: Int, preset: Int, bank: Int)
    private external fun nativeBatchNoteOn(notes: IntArray, velocities: FloatArray)
}
