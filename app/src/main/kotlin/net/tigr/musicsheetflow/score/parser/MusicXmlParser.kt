package net.tigr.musicsheetflow.score.parser

import android.content.Context
import android.util.Log
import android.util.Xml
import net.tigr.musicsheetflow.score.model.*
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Parser for MusicXML files (.musicxml and .mxl compressed format).
 */
class MusicXmlParser {

    companion object {
        private const val TAG = "MusicXmlParser"
    }

    /**
     * Parse a MusicXML file from assets.
     */
    fun parseFromAssets(context: Context, filename: String): Score? {
        return try {
            context.assets.open("scores/$filename").use { inputStream ->
                if (filename.endsWith(".mxl")) {
                    parseMxl(inputStream)
                } else {
                    parseMusicXml(inputStream)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse $filename", e)
            null
        }
    }

    /**
     * Parse a MusicXML file from a file path.
     */
    fun parseFromFile(file: File): Score? {
        return try {
            file.inputStream().use { inputStream ->
                if (file.name.endsWith(".mxl")) {
                    parseMxl(inputStream)
                } else {
                    parseMusicXml(inputStream)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse ${file.name}", e)
            null
        }
    }

    /**
     * Parse compressed MusicXML (.mxl) format.
     * MXL files are ZIP archives containing a container.xml and the actual MusicXML file.
     */
    private fun parseMxl(inputStream: InputStream): Score? {
        val zipInputStream = ZipInputStream(inputStream)
        var entry = zipInputStream.nextEntry

        while (entry != null) {
            val name = entry.name
            // Look for .xml file (skip container.xml and META-INF)
            if (name.endsWith(".xml") && !name.contains("container") && !name.startsWith("META-INF")) {
                return parseMusicXml(zipInputStream)
            }
            entry = zipInputStream.nextEntry
        }

        Log.e(TAG, "No MusicXML file found in MXL archive")
        return null
    }

    /**
     * Parse uncompressed MusicXML format.
     */
    private fun parseMusicXml(inputStream: InputStream): Score? {
        return try {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(inputStream, null)
            parser.nextTag()
            readScore(parser)
        } catch (e: XmlPullParserException) {
            Log.e(TAG, "XML parsing error", e)
            null
        } catch (e: IOException) {
            Log.e(TAG, "IO error during parsing", e)
            null
        }
    }

    private fun readScore(parser: XmlPullParser): Score {
        var title = ""
        var composer = ""
        val credits = mutableListOf<String>()
        val parts = mutableListOf<Part>()
        val partNames = mutableMapOf<String, String>()

        parser.require(XmlPullParser.START_TAG, null, "score-partwise")

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue

            when (parser.name) {
                "work" -> {
                    title = readWork(parser)
                }
                "identification" -> {
                    composer = readIdentification(parser)
                }
                "credit" -> {
                    val credit = readCredit(parser)
                    if (credit.isNotEmpty()) {
                        credits.add(credit)
                        // Try to extract title and composer from credits
                        if (title.isEmpty() && credits.size == 1) {
                            title = credit
                        } else if (composer.isEmpty() && credits.size == 2) {
                            composer = credit
                        }
                    }
                }
                "part-list" -> {
                    partNames.putAll(readPartList(parser))
                }
                "part" -> {
                    val partId = parser.getAttributeValue(null, "id") ?: ""
                    val part = readPart(parser, partId, partNames[partId] ?: "Unknown")
                    parts.add(part)
                }
                else -> skip(parser)
            }
        }

        return Score(
            title = title.ifEmpty { "Untitled" },
            composer = composer,
            parts = parts,
            credits = credits
        )
    }

    private fun readWork(parser: XmlPullParser): String {
        var title = ""
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "work-title" -> title = readText(parser)
                else -> skip(parser)
            }
        }
        return title
    }

    private fun readIdentification(parser: XmlPullParser): String {
        var composer = ""
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "creator" -> {
                    val type = parser.getAttributeValue(null, "type")
                    if (type == "composer") {
                        composer = readText(parser)
                    } else {
                        skip(parser)
                    }
                }
                else -> skip(parser)
            }
        }
        return composer
    }

    private fun readCredit(parser: XmlPullParser): String {
        val text = StringBuilder()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "credit-words" -> text.append(readText(parser))
                else -> skip(parser)
            }
        }
        return text.toString().trim()
    }

    private fun readPartList(parser: XmlPullParser): Map<String, String> {
        val parts = mutableMapOf<String, String>()
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "score-part" -> {
                    val id = parser.getAttributeValue(null, "id") ?: ""
                    var name = ""
                    while (parser.next() != XmlPullParser.END_TAG) {
                        if (parser.eventType != XmlPullParser.START_TAG) continue
                        when (parser.name) {
                            "part-name" -> name = readText(parser)
                            else -> skip(parser)
                        }
                    }
                    parts[id] = name
                }
                else -> skip(parser)
            }
        }
        return parts
    }

    private fun readPart(parser: XmlPullParser, partId: String, partName: String): Part {
        val measures = mutableListOf<Measure>()
        var currentAttributes: MeasureAttributes? = null

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "measure" -> {
                    val (measure, newAttributes) = readMeasure(parser, currentAttributes)
                    measures.add(measure)
                    if (newAttributes != null) {
                        currentAttributes = newAttributes
                    }
                }
                else -> skip(parser)
            }
        }

        return Part(id = partId, name = partName, measures = measures)
    }

    private fun readMeasure(
        parser: XmlPullParser,
        previousAttributes: MeasureAttributes?
    ): Pair<Measure, MeasureAttributes?> {
        val measureNumber = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: 0
        val notes = mutableListOf<Note>()
        var attributes: MeasureAttributes? = null
        var tempo: Int? = null
        var currentPosition = 0  // Track position in measure (in divisions)
        val divisions = previousAttributes?.divisions ?: 1

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "attributes" -> {
                    attributes = readAttributes(parser, previousAttributes)
                }
                "note" -> {
                    val note = readNote(parser, measureNumber, currentPosition)
                    notes.add(note)
                    // Advance position unless it's a chord note
                    if (!note.isChord) {
                        currentPosition += note.duration
                    }
                }
                "forward" -> {
                    val duration = readForwardBackward(parser)
                    currentPosition += duration
                }
                "backup" -> {
                    val duration = readForwardBackward(parser)
                    currentPosition -= duration
                }
                "direction" -> {
                    val dirTempo = readDirection(parser)
                    if (dirTempo != null) tempo = dirTempo
                }
                else -> skip(parser)
            }
        }

        return Measure(
            number = measureNumber,
            attributes = attributes ?: previousAttributes,
            notes = notes,
            tempo = tempo
        ) to attributes
    }

    private fun readAttributes(
        parser: XmlPullParser,
        previous: MeasureAttributes?
    ): MeasureAttributes {
        var divisions = previous?.divisions ?: 1
        var keyFifths = previous?.keyFifths ?: 0
        var timeBeats = previous?.timeBeats ?: 4
        var timeBeatType = previous?.timeBeatType ?: 4
        var staves = previous?.staves ?: 1
        val clefs = mutableListOf<Clef>()

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "divisions" -> divisions = readText(parser).toIntOrNull() ?: divisions
                "key" -> keyFifths = readKey(parser)
                "time" -> {
                    val (beats, beatType) = readTime(parser)
                    timeBeats = beats
                    timeBeatType = beatType
                }
                "staves" -> staves = readText(parser).toIntOrNull() ?: staves
                "clef" -> clefs.add(readClef(parser))
                else -> skip(parser)
            }
        }

        return MeasureAttributes(
            divisions = divisions,
            keyFifths = keyFifths,
            timeBeats = timeBeats,
            timeBeatType = timeBeatType,
            staves = staves,
            clefs = if (clefs.isNotEmpty()) clefs else previous?.clefs ?: emptyList()
        )
    }

    private fun readKey(parser: XmlPullParser): Int {
        var fifths = 0
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "fifths" -> fifths = readText(parser).toIntOrNull() ?: 0
                else -> skip(parser)
            }
        }
        return fifths
    }

    private fun readTime(parser: XmlPullParser): Pair<Int, Int> {
        var beats = 4
        var beatType = 4
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "beats" -> beats = readText(parser).toIntOrNull() ?: 4
                "beat-type" -> beatType = readText(parser).toIntOrNull() ?: 4
                else -> skip(parser)
            }
        }
        return beats to beatType
    }

    private fun readClef(parser: XmlPullParser): Clef {
        val number = parser.getAttributeValue(null, "number")?.toIntOrNull() ?: 1
        var sign = "G"
        var line = 2
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "sign" -> sign = readText(parser)
                "line" -> line = readText(parser).toIntOrNull() ?: 2
                else -> skip(parser)
            }
        }
        return Clef(number = number, sign = sign, line = line)
    }

    private fun readNote(parser: XmlPullParser, measureNumber: Int, position: Int): Note {
        var pitch: Pitch? = null
        var duration = 0
        var voice = 1
        var staff = 1
        var type = NoteType.QUARTER
        var isRest = false
        var isChord = false
        var isTiedStart = false
        var isTiedStop = false

        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "pitch" -> pitch = readPitch(parser)
                "rest" -> {
                    isRest = true
                    skip(parser)
                }
                "duration" -> duration = readText(parser).toIntOrNull() ?: 0
                "voice" -> voice = readText(parser).toIntOrNull() ?: 1
                "staff" -> staff = readText(parser).toIntOrNull() ?: 1
                "type" -> type = NoteType.fromString(readText(parser))
                "chord" -> {
                    isChord = true
                    skip(parser)
                }
                "tie" -> {
                    val tieType = parser.getAttributeValue(null, "type")
                    if (tieType == "start") isTiedStart = true
                    if (tieType == "stop") isTiedStop = true
                    skip(parser)
                }
                else -> skip(parser)
            }
        }

        return Note(
            pitch = pitch,
            duration = duration,
            voice = voice,
            staff = staff,
            type = type,
            isRest = isRest,
            isChord = isChord,
            isTiedStart = isTiedStart,
            isTiedStop = isTiedStop,
            measureNumber = measureNumber,
            positionInMeasure = position
        )
    }

    private fun readPitch(parser: XmlPullParser): Pitch {
        var step = 'C'
        var octave = 4
        var alter = 0
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "step" -> step = readText(parser).firstOrNull() ?: 'C'
                "octave" -> octave = readText(parser).toIntOrNull() ?: 4
                "alter" -> alter = readText(parser).toIntOrNull() ?: 0
                else -> skip(parser)
            }
        }
        return Pitch(step = step, octave = octave, alter = alter)
    }

    private fun readDirection(parser: XmlPullParser): Int? {
        var tempo: Int? = null
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "sound" -> {
                    tempo = parser.getAttributeValue(null, "tempo")?.toFloatOrNull()?.toInt()
                    skip(parser)
                }
                else -> skip(parser)
            }
        }
        return tempo
    }

    private fun readForwardBackward(parser: XmlPullParser): Int {
        var duration = 0
        while (parser.next() != XmlPullParser.END_TAG) {
            if (parser.eventType != XmlPullParser.START_TAG) continue
            when (parser.name) {
                "duration" -> duration = readText(parser).toIntOrNull() ?: 0
                else -> skip(parser)
            }
        }
        return duration
    }

    private fun readText(parser: XmlPullParser): String {
        var result = ""
        if (parser.next() == XmlPullParser.TEXT) {
            result = parser.text
            parser.nextTag()
        }
        return result
    }

    private fun skip(parser: XmlPullParser) {
        if (parser.eventType != XmlPullParser.START_TAG) return
        var depth = 1
        while (depth != 0) {
            when (parser.next()) {
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.START_TAG -> depth++
            }
        }
    }
}
