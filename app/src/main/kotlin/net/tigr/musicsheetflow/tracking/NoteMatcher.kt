package net.tigr.musicsheetflow.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import net.tigr.musicsheetflow.audio.PitchEvent
import net.tigr.musicsheetflow.score.model.Score

/**
 * Feedback event emitted when a note match occurs.
 */
data class MatchFeedback(
    val result: MatchResult,
    val expectedMidi: Int?,
    val detectedMidi: Int,
    val detectedNoteName: String,
    val expectedNoteName: String?,
    val centDeviation: Int,
    val timingOffsetMs: Int,
    val noteIndex: Int,
    val isComplete: Boolean
)

/**
 * Configuration for note matching.
 */
data class MatcherConfig(
    val minConfidence: Float = 0.7f,        // Minimum confidence to accept pitch
    val pitchStabilityMs: Long = 50,        // Time pitch must be stable before matching
    val debounceMs: Long = 100              // Minimum time between matches
)

/**
 * Connects pitch detection with position tracking to provide real-time feedback.
 *
 * Responsibilities:
 * - Filter pitch events by confidence threshold
 * - Apply pitch stability (debounce rapid changes)
 * - Forward valid pitches to PositionTracker
 * - Calculate timing offset using BeatClock
 * - Emit feedback events for UI updates
 */
class NoteMatcher(
    private val positionTracker: PositionTracker,
    private val beatClock: BeatClock = BeatClock(),
    private val config: MatcherConfig = MatcherConfig()
) {
    private val _feedback = MutableSharedFlow<MatchFeedback>(extraBufferCapacity = 64)
    val feedback: SharedFlow<MatchFeedback> = _feedback.asSharedFlow()

    private var lastMatchTimeNs: Long = 0
    private var lastDetectedMidi: Int = -1
    private var stableSinceNs: Long = 0
    private var isActive: Boolean = false

    /**
     * Load a score into the matcher.
     */
    fun loadScore(score: Score) {
        positionTracker.loadScore(score)
        beatClock.loadScore(score)
        reset()
    }

    /**
     * Reset matching state.
     */
    fun reset() {
        positionTracker.reset()
        beatClock.reset()
        lastMatchTimeNs = 0
        lastDetectedMidi = -1
        stableSinceNs = 0
    }

    /**
     * Start accepting pitch events.
     */
    fun start(scope: CoroutineScope) {
        isActive = true
        beatClock.start(scope)
    }

    /**
     * Stop accepting pitch events.
     */
    fun stop() {
        isActive = false
        beatClock.stop()
    }

    /**
     * Get the beat clock for UI observation.
     */
    fun getBeatClock(): BeatClock = beatClock

    /**
     * Process a pitch event from the audio engine.
     *
     * @param event The detected pitch event
     * @param scope Coroutine scope for emitting feedback
     */
    fun processPitchEvent(event: PitchEvent, scope: CoroutineScope) {
        if (!isActive) return

        // Filter by confidence
        if (event.confidence < config.minConfidence) {
            return
        }

        // Filter invalid MIDI notes
        if (event.midiNote < 0 || event.midiNote > 127) {
            return
        }

        val now = event.timestampNs

        // Check pitch stability
        if (event.midiNote != lastDetectedMidi) {
            lastDetectedMidi = event.midiNote
            stableSinceNs = now
            return  // Wait for stability
        }

        val stableDurationMs = (now - stableSinceNs) / 1_000_000
        if (stableDurationMs < config.pitchStabilityMs) {
            return  // Not stable enough yet
        }

        // Check debounce
        val timeSinceLastMatch = (now - lastMatchTimeNs) / 1_000_000
        if (timeSinceLastMatch < config.debounceMs) {
            return  // Too soon after last match
        }

        // Process the pitch
        val currentNote = positionTracker.getCurrentNote()
        val expectedMidi = currentNote?.midiNote()
        val expectedNoteName = currentNote?.noteName()
        val noteIndex = positionTracker.trackingState.value.currentIndex

        // Calculate timing offset using beat clock
        val timingOffsetMs = if (beatClock.isRunning()) {
            beatClock.calculateTimingOffset(noteIndex, event.timestampNs)
        } else {
            0
        }

        val result = positionTracker.processPitch(
            midiNote = event.midiNote,
            frequency = event.frequency,
            confidence = event.confidence,
            timestampNs = event.timestampNs,
            beatTimestampNs = event.timestampNs - (timingOffsetMs * 1_000_000L)
        )

        lastMatchTimeNs = now

        // Sync beat clock with position tracker
        if (result != MatchResult.WRONG_PITCH && result != MatchResult.NO_MATCH) {
            beatClock.advanceNote()
        }

        // Emit feedback with timing information
        val feedback = MatchFeedback(
            result = result,
            expectedMidi = expectedMidi,
            detectedMidi = event.midiNote,
            detectedNoteName = event.noteName(),
            expectedNoteName = expectedNoteName,
            centDeviation = event.centDeviation,
            timingOffsetMs = timingOffsetMs,
            noteIndex = noteIndex,
            isComplete = positionTracker.isComplete()
        )

        scope.launch {
            _feedback.emit(feedback)
        }
    }

    /**
     * Manually skip the current note.
     */
    fun skipCurrentNote(scope: CoroutineScope) {
        val currentNote = positionTracker.getCurrentNote() ?: return
        val expectedMidi = currentNote.midiNote()
        val expectedNoteName = currentNote.noteName()
        val noteIndex = positionTracker.trackingState.value.currentIndex

        positionTracker.skipCurrent()

        val feedback = MatchFeedback(
            result = MatchResult.SKIPPED,
            expectedMidi = expectedMidi,
            detectedMidi = -1,
            detectedNoteName = "--",
            expectedNoteName = expectedNoteName,
            centDeviation = 0,
            timingOffsetMs = 0,
            noteIndex = noteIndex,
            isComplete = positionTracker.isComplete()
        )

        scope.launch {
            _feedback.emit(feedback)
        }
    }

    /**
     * Get the current expected note's MIDI number.
     */
    fun getExpectedMidiNote(): Int? {
        return positionTracker.getCurrentNote()?.midiNote()
    }

    /**
     * Get the current expected note's name.
     */
    fun getExpectedNoteName(): String? {
        return positionTracker.getCurrentNote()?.noteName()
    }

    /**
     * Get lookahead MIDI notes.
     */
    fun getLookaheadMidiNotes(): List<Int> {
        return positionTracker.getLookaheadNotes().mapNotNull { it.midiNote() }
    }

    /**
     * Check if all notes have been played.
     */
    fun isComplete(): Boolean = positionTracker.isComplete()

    /**
     * Get current session statistics.
     */
    fun getStats(): SessionStats = positionTracker.getStats()

    /**
     * Get the tracking state flow for UI observation.
     */
    fun getTrackingStateFlow() = positionTracker.trackingState
}
