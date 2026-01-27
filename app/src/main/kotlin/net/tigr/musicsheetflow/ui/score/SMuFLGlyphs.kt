package net.tigr.musicsheetflow.ui.score

/**
 * SMuFL (Standard Music Font Layout) glyph codepoints for Bravura font.
 * Reference: https://w3c.github.io/smufl/latest/
 */
object SMuFLGlyphs {

    // Clefs (U+E050 - U+E07F)
    const val TREBLE_CLEF = '\uE050'          // gClef
    const val BASS_CLEF = '\uE062'            // fClef
    const val ALTO_CLEF = '\uE05C'            // cClef

    // Time signature digits (U+E080 - U+E09F)
    const val TIME_SIG_0 = '\uE080'
    const val TIME_SIG_1 = '\uE081'
    const val TIME_SIG_2 = '\uE082'
    const val TIME_SIG_3 = '\uE083'
    const val TIME_SIG_4 = '\uE084'
    const val TIME_SIG_5 = '\uE085'
    const val TIME_SIG_6 = '\uE086'
    const val TIME_SIG_7 = '\uE087'
    const val TIME_SIG_8 = '\uE088'
    const val TIME_SIG_9 = '\uE089'
    const val TIME_SIG_COMMON = '\uE08A'      // timeSigCommon (4/4)
    const val TIME_SIG_CUT = '\uE08B'         // timeSigCutCommon (2/2)

    // Noteheads (U+E0A0 - U+E0FF)
    const val NOTEHEAD_WHOLE = '\uE0A2'       // noteheadWhole
    const val NOTEHEAD_HALF = '\uE0A3'        // noteheadHalf
    const val NOTEHEAD_BLACK = '\uE0A4'       // noteheadBlack (quarter and shorter)
    const val NOTEHEAD_DOUBLE_WHOLE = '\uE0A0' // noteheadDoubleWhole

    // Flags (U+E240 - U+E25F)
    const val FLAG_8TH_UP = '\uE240'          // flag8thUp
    const val FLAG_8TH_DOWN = '\uE241'        // flag8thDown
    const val FLAG_16TH_UP = '\uE242'         // flag16thUp
    const val FLAG_16TH_DOWN = '\uE243'       // flag16thDown
    const val FLAG_32ND_UP = '\uE244'         // flag32ndUp
    const val FLAG_32ND_DOWN = '\uE245'       // flag32ndDown

    // Accidentals (U+E260 - U+E26F)
    const val ACCIDENTAL_FLAT = '\uE260'      // accidentalFlat
    const val ACCIDENTAL_NATURAL = '\uE261'   // accidentalNatural
    const val ACCIDENTAL_SHARP = '\uE262'     // accidentalSharp
    const val ACCIDENTAL_DOUBLE_SHARP = '\uE263' // accidentalDoubleSharp
    const val ACCIDENTAL_DOUBLE_FLAT = '\uE264'  // accidentalDoubleFlat

    // Rests (U+E4E0 - U+E4FF)
    const val REST_WHOLE = '\uE4E3'           // restWhole
    const val REST_HALF = '\uE4E4'            // restHalf
    const val REST_QUARTER = '\uE4E5'         // restQuarter
    const val REST_8TH = '\uE4E6'             // rest8th
    const val REST_16TH = '\uE4E7'            // rest16th
    const val REST_32ND = '\uE4E8'            // rest32nd

    // Bar lines (U+E030 - U+E03F)
    const val BARLINE_SINGLE = '\uE030'       // barlineSingle
    const val BARLINE_DOUBLE = '\uE031'       // barlineDouble
    const val BARLINE_FINAL = '\uE032'        // barlineFinal

    // Dots (U+E1E7)
    const val AUGMENTATION_DOT = '\uE1E7'     // augmentationDot

    // Articulations
    const val ACCENT = '\uE4A0'               // articAccentAbove
    const val STACCATO = '\uE4A2'             // articStaccatoAbove
    const val TENUTO = '\uE4A4'               // articTenutoAbove

    /**
     * Get the time signature digit glyph.
     */
    fun timeSignatureDigit(digit: Int): Char {
        return when (digit) {
            0 -> TIME_SIG_0
            1 -> TIME_SIG_1
            2 -> TIME_SIG_2
            3 -> TIME_SIG_3
            4 -> TIME_SIG_4
            5 -> TIME_SIG_5
            6 -> TIME_SIG_6
            7 -> TIME_SIG_7
            8 -> TIME_SIG_8
            9 -> TIME_SIG_9
            else -> TIME_SIG_0
        }
    }

    /**
     * Get the notehead glyph for a given note type.
     */
    fun noteheadForType(type: String): Char {
        return when (type.lowercase()) {
            "whole" -> NOTEHEAD_WHOLE
            "half" -> NOTEHEAD_HALF
            else -> NOTEHEAD_BLACK  // quarter, eighth, 16th, etc.
        }
    }

    /**
     * Get the rest glyph for a given note type.
     */
    fun restForType(type: String): Char {
        return when (type.lowercase()) {
            "whole" -> REST_WHOLE
            "half" -> REST_HALF
            "quarter" -> REST_QUARTER
            "eighth" -> REST_8TH
            "16th" -> REST_16TH
            "32nd" -> REST_32ND
            else -> REST_QUARTER
        }
    }

    /**
     * Get the flag glyph for a given note type and stem direction.
     */
    fun flagForType(type: String, stemUp: Boolean): Char? {
        return when (type.lowercase()) {
            "eighth" -> if (stemUp) FLAG_8TH_UP else FLAG_8TH_DOWN
            "16th" -> if (stemUp) FLAG_16TH_UP else FLAG_16TH_DOWN
            "32nd" -> if (stemUp) FLAG_32ND_UP else FLAG_32ND_DOWN
            else -> null  // whole, half, quarter don't have flags
        }
    }

    /**
     * Get accidental glyph.
     */
    fun accidentalForAlter(alter: Int): Char? {
        return when (alter) {
            -2 -> ACCIDENTAL_DOUBLE_FLAT
            -1 -> ACCIDENTAL_FLAT
            1 -> ACCIDENTAL_SHARP
            2 -> ACCIDENTAL_DOUBLE_SHARP
            else -> null  // natural or no accidental
        }
    }
}
