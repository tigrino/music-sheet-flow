package net.tigr.musicsheetflow.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.tigr.musicsheetflow.score.model.Note
import net.tigr.musicsheetflow.score.model.Score

/**
 * Result of matching detected pitch against expected notes.
 */
enum class MatchResult {
    CORRECT_ON_TIME,    // Correct pitch, good timing (±100ms)
    CORRECT_EARLY,      // Correct pitch, played early (>100ms early)
    CORRECT_LATE,       // Correct pitch, played late (>100ms late)
    WRONG_PITCH,        // Incorrect pitch
    SKIPPED,            // Note was skipped
    NO_MATCH            // No note expected or detection failed
}

/**
 * Represents the current state of a note in the score.
 */
enum class NoteState {
    UPCOMING,           // Not yet reached
    CURRENT,            // Currently expected
    LOOKAHEAD,          // In lookahead window
    PLAYED_CORRECT,     // Played correctly
    PLAYED_WRONG,       // Wrong pitch played
    SKIPPED             // Skipped (manually or by lookahead)
}

/**
 * Performance event recorded when a note is played.
 */
data class PerformanceEvent(
    val noteIndex: Int,
    val expectedMidi: Int?,
    val detectedMidi: Int?,
    val detectedFrequency: Float,
    val confidence: Float,
    val result: MatchResult,
    val timingOffsetMs: Int,
    val timestampNs: Long
)

/**
 * Snapshot of the current tracking state.
 */
data class TrackingState(
    val currentIndex: Int,
    val noteStates: Map<Int, NoteState>,
    val lastMatchResult: MatchResult?,
    val sessionStats: SessionStats
)

/**
 * Session statistics.
 */
data class SessionStats(
    val totalNotes: Int = 0,
    val correctNotes: Int = 0,
    val wrongNotes: Int = 0,
    val skippedNotes: Int = 0,
    val onTimeCount: Int = 0,
    val earlyCount: Int = 0,
    val lateCount: Int = 0
) {
    val accuracy: Float
        get() = if (totalNotes > 0) correctNotes.toFloat() / totalNotes else 0f

    val onTimePercent: Float
        get() = if (correctNotes > 0) onTimeCount.toFloat() / correctNotes * 100f else 0f
}

/**
 * Tracks the player's position in the score with 2-note lookahead.
 *
 * State machine:
 * - WAITING_N: Waiting for note N
 * - If pitch matches N: advance to WAITING_N+1
 * - If pitch matches N+1 (lookahead): tentatively skip N, go to TENTATIVE
 * - TENTATIVE_N+1: Tentatively at N+1, N marked as potentially skipped
 * - If pitch matches N+2: confirm skip, advance to WAITING_N+2
 * - If pitch matches N: undo skip, advance to WAITING_N+1
 * - Otherwise: undo skip, stay at WAITING_N
 */
class PositionTracker {

    companion object {
        private const val LOOKAHEAD_WINDOW = 2
        private const val TIMING_TOLERANCE_MS = 100
        private const val PITCH_TOLERANCE_SEMITONES = 1  // Allow ±1 semitone for matching
    }

    private var playableNotes: List<Note> = emptyList()
    private var currentIndex = 0
    private var tentativeIndex: Int? = null  // Non-null when in tentative state
    private var skippedInTentative: Int? = null

    private val noteStates = mutableMapOf<Int, NoteState>()
    private val performanceEvents = mutableListOf<PerformanceEvent>()
    private var stats = SessionStats()

    private val _trackingState = MutableStateFlow(createState())
    val trackingState: StateFlow<TrackingState> = _trackingState.asStateFlow()

    /**
     * Load a score and prepare for tracking.
     */
    fun loadScore(score: Score) {
        // Extract playable notes (non-rests, non-chord notes for simplicity)
        // In v1.0, we only track monophonic - take the first note at each position
        // Sort by measure number first, then by position within measure
        playableNotes = score.parts.flatMap { part ->
            part.measures.flatMap { measure ->
                measure.notes.filter { !it.isRest && !it.isChord && !it.isTiedStop }
            }
        }.sortedWith(compareBy({ it.measureNumber }, { it.positionInMeasure }))

        reset()
    }

    /**
     * Reset to beginning of score.
     */
    fun reset() {
        currentIndex = 0
        tentativeIndex = null
        skippedInTentative = null
        noteStates.clear()
        performanceEvents.clear()
        stats = SessionStats()

        // Initialize all notes as upcoming
        playableNotes.indices.forEach { noteStates[it] = NoteState.UPCOMING }

        // Mark current and lookahead notes
        updateCurrentAndLookahead()
        emitState()
    }

    /**
     * Process a detected pitch and return the match result.
     *
     * @param midiNote Detected MIDI note number
     * @param frequency Detected frequency in Hz
     * @param confidence Detection confidence (0-1)
     * @param timestampNs Timestamp in nanoseconds
     * @param beatTimestampNs Expected beat timestamp (for timing calculation)
     */
    fun processPitch(
        midiNote: Int,
        frequency: Float,
        confidence: Float,
        timestampNs: Long,
        beatTimestampNs: Long = timestampNs  // Default to no timing offset
    ): MatchResult {
        if (playableNotes.isEmpty() || currentIndex >= playableNotes.size) {
            return MatchResult.NO_MATCH
        }

        val timingOffsetMs = ((timestampNs - beatTimestampNs) / 1_000_000).toInt()

        // Check if we're in tentative state
        if (tentativeIndex != null) {
            return processTentative(midiNote, frequency, confidence, timestampNs, timingOffsetMs)
        }

        // Check current note
        val currentNote = playableNotes[currentIndex]
        val currentMidi = currentNote.midiNote()

        if (currentMidi != null && matchesPitch(midiNote, currentMidi)) {
            // Correct note played
            val result = timingResult(timingOffsetMs)
            recordEvent(currentIndex, currentMidi, midiNote, frequency, confidence, result, timingOffsetMs, timestampNs)
            noteStates[currentIndex] = NoteState.PLAYED_CORRECT
            currentIndex++
            updateCurrentAndLookahead()
            emitState()
            return result
        }

        // Check lookahead notes
        for (offset in 1..LOOKAHEAD_WINDOW) {
            val lookaheadIndex = currentIndex + offset
            if (lookaheadIndex >= playableNotes.size) break

            val lookaheadNote = playableNotes[lookaheadIndex]
            val lookaheadMidi = lookaheadNote.midiNote()

            if (lookaheadMidi != null && matchesPitch(midiNote, lookaheadMidi)) {
                // Lookahead match - enter tentative state
                tentativeIndex = lookaheadIndex
                skippedInTentative = currentIndex

                // Tentatively mark skipped notes
                for (i in currentIndex until lookaheadIndex) {
                    noteStates[i] = NoteState.SKIPPED
                }
                noteStates[lookaheadIndex] = NoteState.CURRENT

                // Record tentative match (may be revised)
                val result = timingResult(timingOffsetMs)
                recordEvent(lookaheadIndex, lookaheadMidi, midiNote, frequency, confidence, result, timingOffsetMs, timestampNs)

                emitState()
                return result
            }
        }

        // Wrong pitch
        if (currentMidi != null) {
            recordEvent(currentIndex, currentMidi, midiNote, frequency, confidence, MatchResult.WRONG_PITCH, timingOffsetMs, timestampNs)
        }
        emitState()
        return MatchResult.WRONG_PITCH
    }

    private fun processTentative(
        midiNote: Int,
        frequency: Float,
        confidence: Float,
        timestampNs: Long,
        timingOffsetMs: Int
    ): MatchResult {
        val tentIdx = tentativeIndex ?: return MatchResult.NO_MATCH
        val skippedIdx = skippedInTentative ?: return MatchResult.NO_MATCH

        // Check if this confirms the skip (matches next after tentative)
        val confirmIndex = tentIdx + 1
        if (confirmIndex < playableNotes.size) {
            val confirmNote = playableNotes[confirmIndex]
            val confirmMidi = confirmNote.midiNote()

            if (confirmMidi != null && matchesPitch(midiNote, confirmMidi)) {
                // Confirm skip - finalize skipped notes
                for (i in skippedIdx until tentIdx) {
                    noteStates[i] = NoteState.SKIPPED
                    stats = stats.copy(
                        totalNotes = stats.totalNotes + 1,
                        skippedNotes = stats.skippedNotes + 1
                    )
                }

                currentIndex = confirmIndex
                tentativeIndex = null
                skippedInTentative = null

                val result = timingResult(timingOffsetMs)
                recordEvent(confirmIndex, confirmMidi, midiNote, frequency, confidence, result, timingOffsetMs, timestampNs)
                noteStates[confirmIndex] = NoteState.PLAYED_CORRECT
                currentIndex++
                updateCurrentAndLookahead()
                emitState()
                return result
            }
        }

        // Check if this undoes the skip (matches originally skipped note)
        val skippedNote = playableNotes[skippedIdx]
        val skippedMidi = skippedNote.midiNote()

        if (skippedMidi != null && matchesPitch(midiNote, skippedMidi)) {
            // Undo skip - player went back and played the skipped note
            noteStates[skippedIdx] = NoteState.PLAYED_CORRECT
            currentIndex = tentIdx  // Resume from tentative position
            tentativeIndex = null
            skippedInTentative = null

            val result = timingResult(timingOffsetMs)
            recordEvent(skippedIdx, skippedMidi, midiNote, frequency, confidence, result, timingOffsetMs, timestampNs)
            updateCurrentAndLookahead()
            emitState()
            return result
        }

        // Something else - undo tentative state and stay at original position
        for (i in skippedIdx until tentIdx) {
            noteStates[i] = NoteState.UPCOMING
        }
        currentIndex = skippedIdx
        tentativeIndex = null
        skippedInTentative = null

        updateCurrentAndLookahead()
        emitState()
        return MatchResult.WRONG_PITCH
    }

    /**
     * Manually skip the current note.
     */
    fun skipCurrent() {
        if (currentIndex >= playableNotes.size) return

        noteStates[currentIndex] = NoteState.SKIPPED
        stats = stats.copy(
            totalNotes = stats.totalNotes + 1,
            skippedNotes = stats.skippedNotes + 1
        )
        currentIndex++

        // Clear tentative state if any
        tentativeIndex = null
        skippedInTentative = null

        updateCurrentAndLookahead()
        emitState()
    }

    /**
     * Get the current expected note.
     */
    fun getCurrentNote(): Note? {
        return if (currentIndex < playableNotes.size) playableNotes[currentIndex] else null
    }

    /**
     * Get lookahead notes (up to LOOKAHEAD_WINDOW).
     */
    fun getLookaheadNotes(): List<Note> {
        val result = mutableListOf<Note>()
        for (offset in 1..LOOKAHEAD_WINDOW) {
            val idx = currentIndex + offset
            if (idx < playableNotes.size) {
                result.add(playableNotes[idx])
            }
        }
        return result
    }

    /**
     * Get all playable notes with their current states.
     */
    fun getNotesWithStates(): List<Pair<Note, NoteState>> {
        return playableNotes.mapIndexed { index, note ->
            note to (noteStates[index] ?: NoteState.UPCOMING)
        }
    }

    /**
     * Check if tracking is complete (all notes played).
     */
    fun isComplete(): Boolean {
        return currentIndex >= playableNotes.size
    }

    /**
     * Get current session statistics.
     */
    fun getStats(): SessionStats = stats

    /**
     * Get all performance events.
     */
    fun getPerformanceEvents(): List<PerformanceEvent> = performanceEvents.toList()

    private fun matchesPitch(detected: Int, expected: Int): Boolean {
        return kotlin.math.abs(detected - expected) <= PITCH_TOLERANCE_SEMITONES
    }

    private fun timingResult(offsetMs: Int): MatchResult {
        return when {
            kotlin.math.abs(offsetMs) <= TIMING_TOLERANCE_MS -> MatchResult.CORRECT_ON_TIME
            offsetMs < -TIMING_TOLERANCE_MS -> MatchResult.CORRECT_EARLY
            else -> MatchResult.CORRECT_LATE
        }
    }

    private fun recordEvent(
        noteIndex: Int,
        expectedMidi: Int?,
        detectedMidi: Int,
        frequency: Float,
        confidence: Float,
        result: MatchResult,
        timingOffsetMs: Int,
        timestampNs: Long
    ) {
        performanceEvents.add(
            PerformanceEvent(
                noteIndex = noteIndex,
                expectedMidi = expectedMidi,
                detectedMidi = detectedMidi,
                detectedFrequency = frequency,
                confidence = confidence,
                result = result,
                timingOffsetMs = timingOffsetMs,
                timestampNs = timestampNs
            )
        )

        // Update stats
        stats = when (result) {
            MatchResult.CORRECT_ON_TIME -> stats.copy(
                totalNotes = stats.totalNotes + 1,
                correctNotes = stats.correctNotes + 1,
                onTimeCount = stats.onTimeCount + 1
            )
            MatchResult.CORRECT_EARLY -> stats.copy(
                totalNotes = stats.totalNotes + 1,
                correctNotes = stats.correctNotes + 1,
                earlyCount = stats.earlyCount + 1
            )
            MatchResult.CORRECT_LATE -> stats.copy(
                totalNotes = stats.totalNotes + 1,
                correctNotes = stats.correctNotes + 1,
                lateCount = stats.lateCount + 1
            )
            MatchResult.WRONG_PITCH -> stats.copy(
                totalNotes = stats.totalNotes + 1,
                wrongNotes = stats.wrongNotes + 1
            )
            MatchResult.SKIPPED -> stats.copy(
                totalNotes = stats.totalNotes + 1,
                skippedNotes = stats.skippedNotes + 1
            )
            MatchResult.NO_MATCH -> stats
        }
    }

    private fun updateCurrentAndLookahead() {
        // Clear previous current/lookahead states for notes that are still upcoming
        noteStates.entries
            .filter { it.value == NoteState.CURRENT || it.value == NoteState.LOOKAHEAD }
            .forEach { noteStates[it.key] = NoteState.UPCOMING }

        // Set current
        if (currentIndex < playableNotes.size) {
            noteStates[currentIndex] = NoteState.CURRENT
        }

        // Set lookahead
        for (offset in 1..LOOKAHEAD_WINDOW) {
            val idx = currentIndex + offset
            if (idx < playableNotes.size && noteStates[idx] == NoteState.UPCOMING) {
                noteStates[idx] = NoteState.LOOKAHEAD
            }
        }
    }

    private fun createState(): TrackingState {
        return TrackingState(
            currentIndex = currentIndex,
            noteStates = noteStates.toMap(),
            lastMatchResult = performanceEvents.lastOrNull()?.result,
            sessionStats = stats
        )
    }

    private fun emitState() {
        _trackingState.value = createState()
    }
}
