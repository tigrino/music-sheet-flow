package net.tigr.musicsheetflow.tracking

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import net.tigr.musicsheetflow.score.model.Note
import net.tigr.musicsheetflow.score.model.Score

/**
 * Represents a beat tick event.
 */
data class BeatTick(
    val beatNumber: Int,
    val measureNumber: Int,
    val beatInMeasure: Int,
    val timestampNs: Long
)

/**
 * Represents the expected timing for a note.
 */
data class NoteTiming(
    val noteIndex: Int,
    val expectedBeat: Float,
    val expectedTimestampNs: Long,
    val midiNote: Int?
)

/**
 * Beat clock state.
 */
data class BeatClockState(
    val isRunning: Boolean,
    val tempo: Float,
    val currentBeat: Int,
    val currentMeasure: Int,
    val beatsPerMeasure: Int
)

/**
 * Provides timing information for practice mode.
 *
 * The beat clock runs at the specified tempo and tracks:
 * - Current beat position
 * - Expected timestamps for each note
 * - Timing offsets when notes are played
 */
class BeatClock {

    companion object {
        private const val DEFAULT_TEMPO = 120f  // BPM
        private const val NS_PER_MINUTE = 60_000_000_000L
    }

    private var tempo: Float = DEFAULT_TEMPO
    private var beatsPerMeasure: Int = 4
    private var divisions: Int = 4  // Divisions per quarter note
    private var startTimeNs: Long = 0
    private var isRunning: Boolean = false
    private var clockJob: Job? = null

    // Note timing information
    private var noteTimings: List<NoteTiming> = emptyList()
    private var currentNoteIndex: Int = 0

    private val _state = MutableStateFlow(BeatClockState(
        isRunning = false,
        tempo = DEFAULT_TEMPO,
        currentBeat = 0,
        currentMeasure = 1,
        beatsPerMeasure = 4
    ))
    val state: StateFlow<BeatClockState> = _state.asStateFlow()

    private val _beatTicks = MutableSharedFlow<BeatTick>(extraBufferCapacity = 16)
    val beatTicks: SharedFlow<BeatTick> = _beatTicks.asSharedFlow()

    /**
     * Load a score and calculate expected timing for each note.
     */
    fun loadScore(score: Score) {
        val part = score.parts.firstOrNull() ?: return
        val firstMeasure = part.measures.firstOrNull() ?: return

        // Get tempo from score or use default
        tempo = (firstMeasure.tempo ?: 120).toFloat()
        beatsPerMeasure = firstMeasure.attributes?.timeBeats ?: 4
        divisions = firstMeasure.attributes?.divisions ?: 4

        // Calculate timing for each playable note
        val timings = mutableListOf<NoteTiming>()
        var globalNoteIndex = 0
        var cumulativeBeats = 0f

        part.measures.forEachIndexed { measureIndex, measure ->
            val measureDivisions = measure.attributes?.divisions ?: divisions
            val measureBeats = measure.attributes?.timeBeats ?: beatsPerMeasure

            // Update tempo if changed
            measure.tempo?.let { tempo = it.toFloat() }

            val playableNotes = measure.notes.filter { !it.isRest && !it.isChord && !it.isTiedStop }

            playableNotes.forEach { note ->
                val beatInMeasure = note.positionInMeasure.toFloat() / measureDivisions
                val absoluteBeat = cumulativeBeats + beatInMeasure

                timings.add(NoteTiming(
                    noteIndex = globalNoteIndex,
                    expectedBeat = absoluteBeat,
                    expectedTimestampNs = beatToTimestamp(absoluteBeat),
                    midiNote = note.midiNote()
                ))
                globalNoteIndex++
            }

            cumulativeBeats += measureBeats
        }

        noteTimings = timings
        currentNoteIndex = 0

        _state.value = _state.value.copy(
            tempo = tempo,
            beatsPerMeasure = beatsPerMeasure
        )
    }

    /**
     * Set the tempo in BPM.
     */
    fun setTempo(bpm: Float) {
        tempo = bpm.coerceIn(20f, 300f)

        // Recalculate note timings
        noteTimings = noteTimings.map { timing ->
            timing.copy(expectedTimestampNs = beatToTimestamp(timing.expectedBeat))
        }

        _state.value = _state.value.copy(tempo = tempo)
    }

    /**
     * Start the beat clock.
     */
    fun start(scope: CoroutineScope) {
        if (isRunning) return

        isRunning = true
        startTimeNs = System.nanoTime()
        currentNoteIndex = 0

        _state.value = _state.value.copy(
            isRunning = true,
            currentBeat = 0,
            currentMeasure = 1
        )

        // Start beat tick emitter
        clockJob = scope.launch {
            var lastBeat = -1
            while (isActive && isRunning) {
                val elapsedNs = System.nanoTime() - startTimeNs
                val currentBeat = timestampToBeat(elapsedNs).toInt()

                if (currentBeat > lastBeat) {
                    lastBeat = currentBeat
                    val measure = (currentBeat / beatsPerMeasure) + 1
                    val beatInMeasure = (currentBeat % beatsPerMeasure) + 1

                    _state.value = _state.value.copy(
                        currentBeat = currentBeat,
                        currentMeasure = measure
                    )

                    _beatTicks.tryEmit(BeatTick(
                        beatNumber = currentBeat,
                        measureNumber = measure,
                        beatInMeasure = beatInMeasure,
                        timestampNs = System.nanoTime()
                    ))
                }

                delay(10)  // Check every 10ms
            }
        }
    }

    /**
     * Stop the beat clock.
     */
    fun stop() {
        isRunning = false
        clockJob?.cancel()
        clockJob = null

        _state.value = _state.value.copy(isRunning = false)
    }

    /**
     * Reset to beginning.
     */
    fun reset() {
        stop()
        currentNoteIndex = 0
        startTimeNs = 0

        _state.value = _state.value.copy(
            currentBeat = 0,
            currentMeasure = 1
        )
    }

    /**
     * Get the expected timing for the current note.
     */
    fun getCurrentNoteTiming(): NoteTiming? {
        return noteTimings.getOrNull(currentNoteIndex)
    }

    /**
     * Get the expected timing for a specific note index.
     */
    fun getNoteTiming(noteIndex: Int): NoteTiming? {
        return noteTimings.getOrNull(noteIndex)
    }

    /**
     * Calculate timing offset for a played note.
     *
     * @param noteIndex The index of the note that was played
     * @param playedTimestampNs When the note was actually played
     * @return Timing offset in milliseconds (negative = early, positive = late)
     */
    fun calculateTimingOffset(noteIndex: Int, playedTimestampNs: Long): Int {
        val timing = noteTimings.getOrNull(noteIndex) ?: return 0

        // Calculate expected timestamp relative to start time
        val expectedAbsoluteNs = startTimeNs + timing.expectedTimestampNs
        val offsetNs = playedTimestampNs - expectedAbsoluteNs

        return (offsetNs / 1_000_000).toInt()  // Convert to milliseconds
    }

    /**
     * Advance to the next note.
     */
    fun advanceNote() {
        if (currentNoteIndex < noteTimings.size - 1) {
            currentNoteIndex++
        }
    }

    /**
     * Set current note index (for sync with position tracker).
     */
    fun setCurrentNoteIndex(index: Int) {
        currentNoteIndex = index.coerceIn(0, noteTimings.size - 1)
    }

    /**
     * Get time until next note in milliseconds.
     */
    fun getTimeUntilNextNote(): Long {
        if (!isRunning) return 0

        val timing = noteTimings.getOrNull(currentNoteIndex) ?: return 0
        val expectedAbsoluteNs = startTimeNs + timing.expectedTimestampNs
        val nowNs = System.nanoTime()

        return ((expectedAbsoluteNs - nowNs) / 1_000_000).coerceAtLeast(0)
    }

    /**
     * Check if the clock is running.
     */
    fun isRunning(): Boolean = isRunning

    /**
     * Get current tempo.
     */
    fun getTempo(): Float = tempo

    /**
     * Convert beat number to timestamp in nanoseconds.
     */
    private fun beatToTimestamp(beat: Float): Long {
        val nsPerBeat = NS_PER_MINUTE / tempo
        return (beat * nsPerBeat).toLong()
    }

    /**
     * Convert timestamp to beat number.
     */
    private fun timestampToBeat(timestampNs: Long): Float {
        val nsPerBeat = NS_PER_MINUTE / tempo
        return timestampNs / nsPerBeat
    }
}
