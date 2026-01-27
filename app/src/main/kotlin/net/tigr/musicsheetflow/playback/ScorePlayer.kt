package net.tigr.musicsheetflow.playback

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import net.tigr.musicsheetflow.audio.NativeMidiEngine
import net.tigr.musicsheetflow.score.model.Note
import net.tigr.musicsheetflow.score.model.Score

/**
 * Playback state for UI observation.
 */
data class PlaybackState(
    val isPlaying: Boolean = false,
    val currentNoteIndex: Int = 0,
    val totalNotes: Int = 0,
    val currentMeasure: Int = 1,
    val currentBeat: Float = 0f,
    val elapsedMs: Long = 0,
    val playingMidiNotes: Set<Int> = emptySet()  // MIDI notes currently sounding
)

/**
 * Internal note scheduling data - timing stored in beats for tempo flexibility.
 */
private data class ScheduledNote(
    val index: Int,
    val midiNote: Int,
    val durationBeats: Float,
    val timestampBeats: Float
)

/**
 * Group of notes that should play simultaneously.
 */
private data class NoteGroup(
    val timestampBeats: Float,
    val notes: List<ScheduledNote>
)

/**
 * Plays back a musical score using the MIDI engine.
 *
 * Features:
 * - Tempo-aware playback
 * - Note scheduling based on score timing
 * - Current position tracking for UI
 */
class ScorePlayer(
    private val midiEngine: NativeMidiEngine
) {
    companion object {
        private const val DEFAULT_TEMPO = 120f
        private const val MS_PER_MINUTE = 60_000f
        private const val MIN_NOTE_DURATION_MS = 50L
    }

    private var tempo: Float = DEFAULT_TEMPO
    private var beatsPerMeasure: Int = 4
    private var divisions: Int = 4
    private var scheduledNotes: List<ScheduledNote> = emptyList()
    private var noteGroups: List<NoteGroup> = emptyList()
    private var playbackJob: Job? = null
    private var startTimeMs: Long = 0
    private var activeScope: CoroutineScope? = null

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    /**
     * Load a score for playback.
     * Collects notes from ALL parts and sorts by timestamp for simultaneous playback.
     */
    fun loadScore(score: Score) {
        stop()

        if (score.parts.isEmpty()) return
        val firstPart = score.parts.first()
        val firstMeasure = firstPart.measures.firstOrNull() ?: return

        tempo = (firstMeasure.tempo ?: 120).toFloat()
        beatsPerMeasure = firstMeasure.attributes?.timeBeats ?: 4
        divisions = firstMeasure.attributes?.divisions ?: 4

        val notes = mutableListOf<ScheduledNote>()
        var globalNoteIndex = 0

        // Collect notes from ALL parts
        score.parts.forEach { part ->
            var cumulativeBeats = 0f

            part.measures.forEachIndexed { measureIndex, measure ->
                val measureDivisions = measure.attributes?.divisions ?: divisions
                val measureBeats = measure.attributes?.timeBeats ?: beatsPerMeasure

                measure.tempo?.let { tempo = it.toFloat() }

                // Include chord notes (isChord) - they should play with the main note
                val playableNotes = measure.notes.filter { !it.isRest && !it.isTiedStop }

                playableNotes.forEach { note ->
                    val midiNote = note.midiNote()
                    if (midiNote != null) {
                        val beatInMeasure = note.positionInMeasure.toFloat() / measureDivisions
                        val absoluteBeat = cumulativeBeats + beatInMeasure
                        val durationBeats = note.duration.toFloat() / measureDivisions

                        notes.add(ScheduledNote(
                            index = globalNoteIndex,
                            midiNote = midiNote,
                            durationBeats = durationBeats,
                            timestampBeats = absoluteBeat
                        ))
                    }
                    globalNoteIndex++
                }

                cumulativeBeats += measureBeats
            }
        }

        // Sort by timestamp so notes from different parts play at the correct time
        val sortedNotes = notes.sortedBy { it.timestampBeats }

        // Re-index notes based on sorted order (so index correlates with playback time)
        scheduledNotes = sortedNotes.mapIndexed { idx, note ->
            note.copy(index = idx)
        }

        // Group notes by timestamp (within 0.01 beats tolerance for simultaneous notes)
        val groups = mutableListOf<NoteGroup>()
        var currentGroup = mutableListOf<ScheduledNote>()
        var currentTimestamp = -1f

        scheduledNotes.forEach { note ->
            if (currentTimestamp < 0 || kotlin.math.abs(note.timestampBeats - currentTimestamp) < 0.01f) {
                currentGroup.add(note)
                if (currentTimestamp < 0) currentTimestamp = note.timestampBeats
            } else {
                if (currentGroup.isNotEmpty()) {
                    groups.add(NoteGroup(currentTimestamp, currentGroup.toList()))
                }
                currentGroup = mutableListOf(note)
                currentTimestamp = note.timestampBeats
            }
        }
        if (currentGroup.isNotEmpty()) {
            groups.add(NoteGroup(currentTimestamp, currentGroup.toList()))
        }
        noteGroups = groups

        _state.value = PlaybackState(
            isPlaying = false,
            currentNoteIndex = 0,
            totalNotes = scheduledNotes.size
        )
    }

    /**
     * Set playback tempo in BPM.
     * Note: Timing is stored in beats, so tempo changes apply automatically.
     */
    fun setTempo(bpm: Float) {
        val wasPlaying = _state.value.isPlaying
        val savedScope = activeScope
        if (wasPlaying) {
            stop()
        }

        tempo = bpm.coerceIn(20f, 300f)

        if (wasPlaying && savedScope != null) {
            start(savedScope)
        }
    }

    /**
     * Start playback from current position.
     * Groups simultaneous notes together to reduce mutex contention.
     */
    fun start(scope: CoroutineScope) {
        if (_state.value.isPlaying) return
        if (noteGroups.isEmpty()) return

        activeScope = scope
        _state.value = _state.value.copy(isPlaying = true)
        startTimeMs = System.currentTimeMillis()

        playbackJob = scope.launch {
            if (noteGroups.isEmpty()) {
                stop()
                return@launch
            }

            val baseTimestampBeats = noteGroups.first().timestampBeats

            noteGroups.forEach { group ->
                val groupTimestampMs = beatToMs(group.timestampBeats - baseTimestampBeats)
                val delayMs = groupTimestampMs - (System.currentTimeMillis() - startTimeMs)

                if (delayMs > 0) {
                    delay(delayMs)
                }

                if (!isActive) return@launch

                // Play all notes in this group using batch method (single mutex lock)
                // This significantly reduces audio dropouts from mutex contention
                val midiNotes = group.notes.map { it.midiNote }
                val notesToPlay = midiNotes.map { it to 0.8f }
                midiEngine.batchNoteOn(notesToPlay)

                // Update UI state with currently playing notes
                _state.value = _state.value.copy(
                    currentBeat = group.timestampBeats,
                    elapsedMs = System.currentTimeMillis() - startTimeMs,
                    playingMidiNotes = midiNotes.toSet()
                )
            }

            // Wait a bit for final notes to sound, then stop
            val lastGroup = noteGroups.lastOrNull()
            if (lastGroup != null) {
                val avgDuration = lastGroup.notes.map { it.durationBeats }.average().toFloat()
                delay(beatToMs(avgDuration).coerceAtLeast(500L))
            }

            stop()
        }
    }

    /**
     * Pause playback (preserves position).
     */
    fun pause() {
        playbackJob?.cancel()
        playbackJob = null
        midiEngine.allNotesOff()
        _state.value = _state.value.copy(
            isPlaying = false,
            playingMidiNotes = emptySet()
        )
    }

    /**
     * Stop playback and reset to beginning.
     */
    fun stop() {
        activeScope = null
        playbackJob?.cancel()
        playbackJob = null
        midiEngine.allNotesOff()
        _state.value = _state.value.copy(
            isPlaying = false,
            currentNoteIndex = 0,
            elapsedMs = 0,
            playingMidiNotes = emptySet()
        )
    }

    /**
     * Seek to a specific note index.
     */
    fun seekToNote(index: Int) {
        val wasPlaying = _state.value.isPlaying
        if (wasPlaying) {
            pause()
        }
        _state.value = _state.value.copy(
            currentNoteIndex = index.coerceIn(0, scheduledNotes.size - 1)
        )
    }

    /**
     * Toggle play/pause.
     */
    fun togglePlayback(scope: CoroutineScope) {
        if (_state.value.isPlaying) {
            pause()
        } else {
            start(scope)
        }
    }

    private fun beatToMs(beats: Float): Long {
        return ((beats / tempo) * MS_PER_MINUTE).toLong()
    }
}
