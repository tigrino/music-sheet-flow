package net.tigr.musicsheetflow.score.model

/**
 * Represents a complete musical score parsed from MusicXML.
 */
data class Score(
    val title: String,
    val composer: String,
    val parts: List<Part>,
    val credits: List<String> = emptyList()
) {
    /**
     * Get all notes from all parts in chronological order by measure and beat position.
     */
    fun getAllNotes(): List<Note> {
        return parts.flatMap { part ->
            part.measures.flatMap { measure ->
                measure.notes
            }
        }.sortedWith(compareBy({ it.measureNumber }, { it.positionInMeasure }))
    }

    /**
     * Get the total number of measures in the score.
     */
    fun measureCount(): Int {
        return parts.firstOrNull()?.measures?.size ?: 0
    }
}

/**
 * Represents a single part (instrument/hand) in the score.
 */
data class Part(
    val id: String,
    val name: String,
    val measures: List<Measure>
)

/**
 * Represents a single measure in a part.
 */
data class Measure(
    val number: Int,
    val attributes: MeasureAttributes?,
    val notes: List<Note>,
    val tempo: Int? = null
) {
    /**
     * Get notes for a specific staff (1 = treble, 2 = bass for piano).
     */
    fun notesForStaff(staff: Int): List<Note> {
        return notes.filter { it.staff == staff }
    }
}

/**
 * Measure attributes like time signature, key signature, clef.
 */
data class MeasureAttributes(
    val divisions: Int,           // Duration divisions per quarter note
    val keyFifths: Int,           // Key signature (-7 to +7, 0 = C major)
    val timeBeats: Int,           // Time signature numerator
    val timeBeatType: Int,        // Time signature denominator
    val staves: Int,              // Number of staves (usually 2 for piano)
    val clefs: List<Clef>
)

/**
 * Clef definition.
 */
data class Clef(
    val number: Int,              // Staff number (1 or 2)
    val sign: String,             // G, F, C
    val line: Int                 // Line on staff
)

/**
 * Represents a single note or rest.
 */
data class Note(
    val pitch: Pitch?,            // null for rests
    val duration: Int,            // Duration in divisions
    val voice: Int,               // Voice number (for polyphony)
    val staff: Int,               // Staff number (1 = treble, 2 = bass)
    val type: NoteType,           // whole, half, quarter, eighth, etc.
    val isRest: Boolean,
    val isChord: Boolean,         // Part of a chord (sounds with previous note)
    val isTiedStart: Boolean,     // Start of a tie
    val isTiedStop: Boolean,      // End of a tie
    val measureNumber: Int,       // Which measure this note belongs to
    val positionInMeasure: Int    // Position within measure (in divisions)
) {
    /**
     * Get the MIDI note number (60 = middle C).
     */
    fun midiNote(): Int? {
        return pitch?.toMidiNote()
    }

    /**
     * Get note name with octave (e.g., "C4", "F#5").
     */
    fun noteName(): String {
        return pitch?.toNoteName() ?: "rest"
    }
}

/**
 * Pitch representation.
 */
data class Pitch(
    val step: Char,               // A-G
    val octave: Int,              // Octave number (4 = middle C octave)
    val alter: Int = 0            // -1 = flat, 0 = natural, 1 = sharp
) {
    /**
     * Convert to MIDI note number.
     * Middle C (C4) = 60
     */
    fun toMidiNote(): Int {
        val stepValues = mapOf(
            'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5,
            'G' to 7, 'A' to 9, 'B' to 11
        )
        val baseNote = stepValues[step] ?: 0
        return (octave + 1) * 12 + baseNote + alter
    }

    /**
     * Get note name with accidental using locale-aware naming.
     */
    fun toNoteName(): String {
        return net.tigr.musicsheetflow.util.NoteNaming.fromPitch(step, octave, alter)
    }
}

/**
 * Note duration types.
 */
enum class NoteType(val divisions: Int) {
    WHOLE(16),
    HALF(8),
    QUARTER(4),
    EIGHTH(2),
    SIXTEENTH(1),
    THIRTY_SECOND(1),
    SIXTY_FOURTH(1),
    UNKNOWN(4);

    companion object {
        fun fromString(type: String): NoteType {
            return when (type.lowercase()) {
                "whole" -> WHOLE
                "half" -> HALF
                "quarter" -> QUARTER
                "eighth" -> EIGHTH
                "16th" -> SIXTEENTH
                "32nd" -> THIRTY_SECOND
                "64th" -> SIXTY_FOURTH
                else -> UNKNOWN
            }
        }
    }
}
