package net.tigr.musicsheetflow.util

import android.content.Context
import net.tigr.musicsheetflow.R
import java.util.Locale

/**
 * Centralized utility for localized musical note naming.
 * Supports English (C, D, E...) and Russian solfège (До, Ре, Ми...) systems.
 */
object NoteNaming {

    /**
     * Note naming system used in different locales.
     */
    enum class NamingSystem {
        ENGLISH,  // C, D, E, F, G, A, B
        SOLFEGE   // До, Ре, Ми, Фа, Соль, Ля, Си
    }

    // English note names (chromatic scale from C)
    private val englishNotes = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val englishNotesFlat = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    // Russian solfège note names (chromatic scale from C)
    private val solfegeNotes = arrayOf("До", "До#", "Ре", "Ре#", "Ми", "Фа", "Фа#", "Соль", "Соль#", "Ля", "Ля#", "Си")
    private val solfegeNotesFlat = arrayOf("До", "Реb", "Ре", "Миb", "Ми", "Фа", "Сольb", "Соль", "Ляb", "Ля", "Сиb", "Си")

    // Step-to-index mapping (natural notes only)
    private val stepToIndex = mapOf(
        'C' to 0, 'D' to 2, 'E' to 4, 'F' to 5,
        'G' to 7, 'A' to 9, 'B' to 11
    )

    // English natural note names by step
    private val englishSteps = mapOf(
        'C' to "C", 'D' to "D", 'E' to "E", 'F' to "F",
        'G' to "G", 'A' to "A", 'B' to "B"
    )

    // Russian solfège note names by step
    private val solfegeSteps = mapOf(
        'C' to "До", 'D' to "Ре", 'E' to "Ми", 'F' to "Фа",
        'G' to "Соль", 'A' to "Ля", 'B' to "Си"
    )

    /**
     * Determine the naming system based on locale.
     */
    fun getNamingSystem(locale: Locale = Locale.getDefault()): NamingSystem {
        return when (locale.language) {
            "ru", "uk", "be" -> NamingSystem.SOLFEGE  // Russian, Ukrainian, Belarusian
            else -> NamingSystem.ENGLISH
        }
    }

    /**
     * Get note name from MIDI note number.
     *
     * @param midiNote MIDI note number (0-127, 60 = middle C)
     * @param system Naming system to use (defaults to locale-based)
     * @param includeOctave Whether to include octave number
     * @return Localized note name (e.g., "C4" or "До4")
     */
    fun fromMidi(
        midiNote: Int,
        system: NamingSystem = getNamingSystem(),
        includeOctave: Boolean = true
    ): String {
        if (midiNote < 0 || midiNote > 127) return "--"

        val noteIndex = midiNote % 12
        val octave = (midiNote / 12) - 1

        val noteName = when (system) {
            NamingSystem.ENGLISH -> englishNotes[noteIndex]
            NamingSystem.SOLFEGE -> solfegeNotes[noteIndex]
        }

        return if (includeOctave) "$noteName$octave" else noteName
    }

    /**
     * Get note name from pitch step and alteration.
     *
     * @param step Note step (A-G)
     * @param octave Octave number
     * @param alter Alteration (-1 = flat, 0 = natural, 1 = sharp)
     * @param system Naming system to use
     * @param includeOctave Whether to include octave number
     * @return Localized note name
     */
    fun fromPitch(
        step: Char,
        octave: Int,
        alter: Int = 0,
        system: NamingSystem = getNamingSystem(),
        includeOctave: Boolean = true
    ): String {
        val baseName = when (system) {
            NamingSystem.ENGLISH -> englishSteps[step] ?: step.toString()
            NamingSystem.SOLFEGE -> solfegeSteps[step] ?: step.toString()
        }

        val accidental = when (alter) {
            -1 -> "b"
            1 -> "#"
            else -> ""
        }

        return if (includeOctave) "$baseName$accidental$octave" else "$baseName$accidental"
    }

    /**
     * Get the base note name for a white key (no accidental).
     * Used for keyboard labels.
     *
     * @param noteInOctave Note position in octave (0 = C, 2 = D, 4 = E, etc.)
     * @param system Naming system to use
     * @return Note name without octave
     */
    fun whiteKeyName(noteInOctave: Int, system: NamingSystem = getNamingSystem()): String {
        val step = when (noteInOctave) {
            0 -> 'C'
            2 -> 'D'
            4 -> 'E'
            5 -> 'F'
            7 -> 'G'
            9 -> 'A'
            11 -> 'B'
            else -> return ""
        }

        return when (system) {
            NamingSystem.ENGLISH -> englishSteps[step] ?: ""
            NamingSystem.SOLFEGE -> solfegeSteps[step] ?: ""
        }
    }

    /**
     * Get localized accidental symbol.
     *
     * @param alter -1 for flat, 1 for sharp
     * @param useWords If true, use words (диез/бемоль) instead of symbols for Russian
     * @return Accidental symbol or word
     */
    fun accidentalSymbol(alter: Int, useWords: Boolean = false): String {
        return when (alter) {
            -1 -> if (useWords) "бемоль" else "b"
            1 -> if (useWords) "диез" else "#"
            else -> ""
        }
    }

    /**
     * Get localized "rest" text.
     */
    fun restText(context: Context): String {
        return context.getString(R.string.note_rest)
    }

    /**
     * Get the display label for a naming system (e.g., "EN", "RU").
     */
    fun getLanguageLabel(system: NamingSystem): String {
        return when (system) {
            NamingSystem.ENGLISH -> "EN"
            NamingSystem.SOLFEGE -> "RU"
        }
    }

    /**
     * Get the next naming system (for cycling through options).
     */
    fun nextSystem(current: NamingSystem): NamingSystem {
        return when (current) {
            NamingSystem.ENGLISH -> NamingSystem.SOLFEGE
            NamingSystem.SOLFEGE -> NamingSystem.ENGLISH
        }
    }
}
