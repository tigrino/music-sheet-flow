package net.tigr.musicsheetflow.ui.score

import android.graphics.Typeface
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import net.tigr.musicsheetflow.score.model.*
import net.tigr.musicsheetflow.tracking.NoteState
import net.tigr.musicsheetflow.ui.theme.MusicSheetFlowColors

/**
 * Configuration for score rendering.
 */
data class ScoreRenderConfig(
    val staffHeight: Float = 70f,           // Height of 4 spaces between 5 lines
    val staffSpacing: Float = 115f,         // Space between treble and bass staves
    val systemSpacing: Float = 35f,         // Space between systems (rows) - portrait only
    val leftMargin: Float = 25f,            // Left margin
    val topMargin: Float = 80f,             // Top margin for ledger lines above staff
    val measureWidth: Float = 280f,         // Width per measure
    val noteSpacing: Float = 45f,           // Minimum spacing between notes
    val clefWidth: Float = 55f,             // Space for clef
    val keySignatureWidth: Float = 45f,     // Space for key signature
    val timeSignatureWidth: Float = 45f,    // Space for time signature
    val staffLineColor: Color = Color.Black,
    val noteColor: Color = Color.Black,
    val currentNoteColor: Color = MusicSheetFlowColors.CurrentNote,
    val playedNoteColor: Color = MusicSheetFlowColors.CorrectOnTime
)

/**
 * Composable that renders a musical score.
 * - Landscape: Single horizontal scroll (original layout)
 * - Portrait: Multiple systems (rows) for more visibility
 */
@Composable
fun ScoreRenderer(
    score: Score,
    currentNoteIndex: Int = -1,
    playbackBeat: Float? = null,
    playedNoteIndices: Set<Int> = emptySet(),
    noteStateMap: Map<Int, NoteState> = emptyMap(),
    showNoteNames: Boolean = false,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem = net.tigr.musicsheetflow.util.NoteNaming.getNamingSystem(),
    config: ScoreRenderConfig = ScoreRenderConfig(),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val density = LocalDensity.current

    // Load Bravura font
    val bravuraTypeface = remember {
        try {
            Typeface.createFromAsset(context.assets, "fonts/bravura.otf")
        } catch (e: Exception) {
            try {
                context.resources.getFont(
                    context.resources.getIdentifier("bravura", "font", context.packageName)
                )
            } catch (e2: Exception) {
                Typeface.DEFAULT
            }
        }
    }

    val part = score.parts.firstOrNull() ?: return
    val totalMeasures = part.measures.size
    val beatsPerMeasure = part.measures.firstOrNull()?.attributes?.timeBeats ?: 4

    BoxWithConstraints(modifier = modifier) {
        val availableWidth = with(density) { maxWidth.toPx() }
        val availableHeight = with(density) { maxHeight.toPx() }
        // Use aspect ratio to detect orientation - landscape if width is 1.3x height or more
        val isLandscape = availableWidth > availableHeight * 1.3f

        if (isLandscape) {
            // LANDSCAPE: Original horizontal scroll layout
            ScoreRendererLandscape(
                score = score,
                part = part,
                totalMeasures = totalMeasures,
                beatsPerMeasure = beatsPerMeasure,
                bravuraTypeface = bravuraTypeface,
                currentNoteIndex = currentNoteIndex,
                playbackBeat = playbackBeat,
                noteStateMap = noteStateMap,
                showNoteNames = showNoteNames,
                namingSystem = namingSystem,
                config = config,
                density = density
            )
        } else {
            // PORTRAIT: Multi-system layout with 3 rows
            ScoreRendererPortrait(
                score = score,
                part = part,
                totalMeasures = totalMeasures,
                beatsPerMeasure = beatsPerMeasure,
                bravuraTypeface = bravuraTypeface,
                currentNoteIndex = currentNoteIndex,
                playbackBeat = playbackBeat,
                noteStateMap = noteStateMap,
                showNoteNames = showNoteNames,
                namingSystem = namingSystem,
                config = config,
                density = density,
                availableWidth = availableWidth,
                availableHeight = availableHeight
            )
        }
    }
}

/**
 * Landscape layout: Single horizontal scroll with larger notes.
 */
@Composable
private fun ScoreRendererLandscape(
    score: Score,
    part: Part,
    totalMeasures: Int,
    beatsPerMeasure: Int,
    bravuraTypeface: Typeface,
    currentNoteIndex: Int,
    playbackBeat: Float?,
    noteStateMap: Map<Int, NoteState>,
    showNoteNames: Boolean,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem,
    config: ScoreRenderConfig,
    density: androidx.compose.ui.unit.Density
) {
    val totalWidth = config.leftMargin + config.clefWidth + config.keySignatureWidth +
            config.timeSignatureWidth + (totalMeasures * config.measureWidth) + 50f

    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Calculate note positions for auto-scroll (using sorted order to match PositionTracker)
    val notePositions = remember(score) {
        val positions = mutableMapOf<Int, Float>()
        val xOffset = config.leftMargin + config.clefWidth + config.keySignatureWidth + config.timeSignatureWidth

        // Build sorted note list matching PositionTracker order
        // Must use same filter: no rests, no chords, no tied stops
        val allNotes = part.measures.flatMap { measure ->
            measure.notes.filter { !it.isRest && !it.isChord && !it.isTiedStop }
        }.sortedWith(compareBy({ it.measureNumber }, { it.positionInMeasure }))

        allNotes.forEachIndexed { sortedIndex, note ->
            val measureIndex = note.measureNumber - 1  // measureNumber is 1-based
            if (measureIndex >= 0 && measureIndex < part.measures.size) {
                val measure = part.measures[measureIndex]
                val measureX = xOffset + measureIndex * config.measureWidth
                val divisions = measure.attributes?.divisions ?: 2
                val measureBeats = measure.attributes?.timeBeats ?: 4
                val beatPosition = note.positionInMeasure.toFloat() / divisions
                val noteX = measureX + 20f + (beatPosition / measureBeats) * (config.measureWidth - 40f)
                positions[sortedIndex] = noteX
            }
        }
        positions
    }

    // Auto-scroll to current note (practice mode)
    // Keep current note about 1/3 from left edge to show past notes
    LaunchedEffect(currentNoteIndex) {
        if (currentNoteIndex >= 0 && playbackBeat == null) {
            val noteX = notePositions[currentNoteIndex]
            if (noteX != null) {
                val scrollOffset = config.measureWidth * 1.2f  // Show ~1 measure of past notes
                val targetScroll = (noteX - scrollOffset).coerceAtLeast(0f).toInt()
                coroutineScope.launch {
                    scrollState.animateScrollTo(targetScroll, tween(durationMillis = 300))
                }
            }
        }
    }

    // Auto-scroll based on playback beat
    LaunchedEffect(playbackBeat) {
        if (playbackBeat != null && playbackBeat >= 0f) {
            val xOffset = config.leftMargin + config.clefWidth + config.keySignatureWidth + config.timeSignatureWidth
            val measureIndex = (playbackBeat / beatsPerMeasure).toInt()
            val beatInMeasure = playbackBeat % beatsPerMeasure
            val measureX = xOffset + measureIndex * config.measureWidth
            val targetX = measureX + 20f + (beatInMeasure / beatsPerMeasure) * (config.measureWidth - 40f)
            val scrollOffset = config.measureWidth * 1.2f
            val targetScroll = (targetX - scrollOffset).coerceAtLeast(0f).toInt()
            coroutineScope.launch {
                scrollState.animateScrollTo(targetScroll, tween(durationMillis = 200))
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().horizontalScroll(scrollState)) {
        Canvas(
            modifier = Modifier
                .width(with(density) { totalWidth.toDp() })
                .fillMaxHeight()
        ) {
            val canvasHeight = size.height
            // Center vertically but ensure minimum topMargin for ledger lines above staff
            val naturalCenter = (canvasHeight - config.staffHeight * 2 - config.staffSpacing) / 2
            val staffY = maxOf(naturalCenter, config.topMargin)

            // Draw grand staff
            drawGrandStaff(config, staffY, staffY + config.staffHeight + config.staffSpacing, totalWidth)
            drawClefs(bravuraTypeface, config, staffY, staffY + config.staffHeight + config.staffSpacing)

            val firstMeasure = part.measures.firstOrNull()
            val attributes = firstMeasure?.attributes

            var xOffset = config.leftMargin + config.clefWidth
            if (attributes != null && attributes.keyFifths != 0) {
                drawKeySignature(bravuraTypeface, config, attributes.keyFifths, staffY, staffY + config.staffHeight + config.staffSpacing, xOffset)
            }
            xOffset += config.keySignatureWidth

            if (attributes != null) {
                drawTimeSignature(bravuraTypeface, config, attributes.timeBeats, attributes.timeBeatType, staffY, staffY + config.staffHeight + config.staffSpacing, xOffset)
            }
            xOffset += config.timeSignatureWidth

            // Build sorted note list for consistent indexing with PositionTracker
            // Must use same filter as PositionTracker: no rests, no chords, no tied stops
            val allNotes = part.measures.flatMap { measure ->
                measure.notes.filter { !it.isRest && !it.isChord && !it.isTiedStop }
            }.sortedWith(compareBy({ it.measureNumber }, { it.positionInMeasure }))

            // Create a map from note identity to its sorted index
            val noteToIndex = allNotes.mapIndexed { index, note ->
                Triple(note.measureNumber, note.positionInMeasure, note.midiNote()) to index
            }.toMap()

            // Draw notes
            var cumulativeBeats = 0f

            part.measures.forEachIndexed { measureIndex, measure ->
                val measureX = xOffset + measureIndex * config.measureWidth

                if (measureIndex > 0) {
                    drawBarLine(config, staffY, staffY + config.staffHeight + config.staffSpacing, measureX)
                }

                val measureNotes = measure.notes.filter { !it.isChord }
                val divisions = measure.attributes?.divisions ?: 2
                val measureBeats = measure.attributes?.timeBeats ?: beatsPerMeasure

                measureNotes.forEach { note ->
                    val beatPosition = note.positionInMeasure.toFloat() / divisions
                    val noteX = measureX + 20f + (beatPosition / measureBeats) * (config.measureWidth - 40f)
                    val absoluteBeat = cumulativeBeats + beatPosition
                    val isPlaybackCurrent = playbackBeat != null && kotlin.math.abs(absoluteBeat - playbackBeat) < 0.1f

                    // Look up the note's index in the sorted order
                    val noteKey = Triple(note.measureNumber, note.positionInMeasure, note.midiNote())
                    val sortedIndex = noteToIndex[noteKey] ?: -1
                    val noteState = noteStateMap[sortedIndex]

                    val noteColor = when {
                        isPlaybackCurrent -> MusicSheetFlowColors.CorrectEarlyLate
                        noteState == NoteState.CURRENT -> MusicSheetFlowColors.CurrentNote
                        noteState == NoteState.LOOKAHEAD -> MusicSheetFlowColors.CurrentNote.copy(alpha = 0.5f)
                        noteState == NoteState.PLAYED_CORRECT -> MusicSheetFlowColors.CorrectOnTime
                        noteState == NoteState.PLAYED_WRONG -> MusicSheetFlowColors.WrongPitch
                        noteState == NoteState.SKIPPED -> MusicSheetFlowColors.Skipped
                        else -> config.noteColor
                    }

                    drawNote(bravuraTypeface, config, note, staffY, staffY + config.staffHeight + config.staffSpacing, noteX, noteColor, showNoteNames, namingSystem)
                }
                cumulativeBeats += measureBeats
            }

            drawBarLine(config, staffY, staffY + config.staffHeight + config.staffSpacing, xOffset + totalMeasures * config.measureWidth, isDouble = true)
        }
    }
}

/**
 * Portrait layout: Multiple systems (3 rows) with vertical scroll.
 */
@Composable
private fun ScoreRendererPortrait(
    score: Score,
    part: Part,
    totalMeasures: Int,
    beatsPerMeasure: Int,
    bravuraTypeface: Typeface,
    currentNoteIndex: Int,
    playbackBeat: Float?,
    noteStateMap: Map<Int, NoteState>,
    showNoteNames: Boolean,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem,
    config: ScoreRenderConfig,
    density: androidx.compose.ui.unit.Density,
    availableWidth: Float,
    availableHeight: Float
) {
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    // Portrait config: exactly 2 measures per line, filling the width
    val measuresPerSystem = 2

    // Calculate prefix width first (clef, key sig, time sig)
    val baseClefWidth = 65f
    val baseKeySignatureWidth = 50f
    val baseTimeSignatureWidth = 50f
    val systemPrefixWidth = config.leftMargin + baseClefWidth + baseKeySignatureWidth + baseTimeSignatureWidth

    // Calculate measure width to fill remaining space with exactly 2 measures
    val usableWidth = availableWidth - systemPrefixWidth - 15f
    val calculatedMeasureWidth = usableWidth / measuresPerSystem

    val portraitConfig = config.copy(
        staffHeight = 95f,
        staffSpacing = 130f,
        systemSpacing = 55f,
        topMargin = 100f,  // Extra space for high notes with ledger lines
        measureWidth = calculatedMeasureWidth,
        clefWidth = baseClefWidth,
        keySignatureWidth = baseKeySignatureWidth,
        timeSignatureWidth = baseTimeSignatureWidth
    )

    // Calculate system height (includes topMargin for ledger lines above treble staff)
    val systemHeight = portraitConfig.topMargin + portraitConfig.staffHeight * 2 + portraitConfig.staffSpacing + portraitConfig.systemSpacing

    val numSystems = ((totalMeasures + measuresPerSystem - 1) / measuresPerSystem).coerceAtLeast(1)
    val totalHeight = numSystems * systemHeight + 50f

    // Auto-scroll to current system
    val currentSystem = if (playbackBeat != null) {
        (playbackBeat / beatsPerMeasure).toInt() / measuresPerSystem
    } else if (currentNoteIndex >= 0) {
        var noteCount = 0
        var measureForNote = 0
        for ((idx, measure) in part.measures.withIndex()) {
            val notesInMeasure = measure.notes.count { !it.isRest && !it.isChord }
            if (noteCount + notesInMeasure > currentNoteIndex) {
                measureForNote = idx
                break
            }
            noteCount += notesInMeasure
        }
        measureForNote / measuresPerSystem
    } else 0

    LaunchedEffect(currentSystem) {
        val targetScroll = (currentSystem * systemHeight - 10f).coerceAtLeast(0f).toInt()
        coroutineScope.launch {
            scrollState.animateScrollTo(targetScroll, tween(durationMillis = 300))
        }
    }

    // Build sorted note list for consistent indexing with PositionTracker
    // Must use same filter: no rests, no chords, no tied stops
    val allNotes = part.measures.flatMap { measure ->
        measure.notes.filter { !it.isRest && !it.isChord && !it.isTiedStop }
    }.sortedWith(compareBy({ it.measureNumber }, { it.positionInMeasure }))

    // Create a map from note identity to its sorted index
    val noteToIndex = allNotes.mapIndexed { index, note ->
        Triple(note.measureNumber, note.positionInMeasure, note.midiNote()) to index
    }.toMap()

    Box(modifier = Modifier.fillMaxSize().verticalScroll(scrollState)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(with(density) { totalHeight.toDp() })
        ) {
            var cumulativeBeats = 0f

            for (systemIndex in 0 until numSystems) {
                val startMeasure = systemIndex * measuresPerSystem
                val endMeasure = minOf(startMeasure + measuresPerSystem, totalMeasures)

                val systemY = systemIndex * systemHeight
                val trebleY = systemY + portraitConfig.topMargin  // Leave space for ledger lines above
                val bassY = trebleY + portraitConfig.staffHeight + portraitConfig.staffSpacing

                val systemMeasures = endMeasure - startMeasure
                val systemWidth = systemPrefixWidth + systemMeasures * portraitConfig.measureWidth + 15f

                drawGrandStaff(portraitConfig, trebleY, bassY, systemWidth)
                drawClefs(bravuraTypeface, portraitConfig, trebleY, bassY)

                var xOffset = portraitConfig.leftMargin + portraitConfig.clefWidth
                val firstMeasure = part.measures.firstOrNull()
                val attributes = firstMeasure?.attributes

                if (systemIndex == 0) {
                    if (attributes != null && attributes.keyFifths != 0) {
                        drawKeySignature(bravuraTypeface, portraitConfig, attributes.keyFifths, trebleY, bassY, xOffset)
                    }
                    xOffset += portraitConfig.keySignatureWidth
                    if (attributes != null) {
                        drawTimeSignature(bravuraTypeface, portraitConfig, attributes.timeBeats, attributes.timeBeatType, trebleY, bassY, xOffset)
                    }
                    xOffset += portraitConfig.timeSignatureWidth
                } else {
                    xOffset += portraitConfig.keySignatureWidth + portraitConfig.timeSignatureWidth
                }

                for (measureIndex in startMeasure until endMeasure) {
                    val localMeasureIndex = measureIndex - startMeasure
                    val measureX = xOffset + localMeasureIndex * portraitConfig.measureWidth
                    val measure = part.measures[measureIndex]

                    if (localMeasureIndex > 0) {
                        drawBarLine(portraitConfig, trebleY, bassY, measureX)
                    }

                    val measureNotes = measure.notes.filter { !it.isChord }
                    val divisions = measure.attributes?.divisions ?: 2
                    val measureBeats = measure.attributes?.timeBeats ?: beatsPerMeasure

                    measureNotes.forEach { note ->
                        val beatPosition = note.positionInMeasure.toFloat() / divisions
                        val noteX = measureX + 12f + (beatPosition / measureBeats) * (portraitConfig.measureWidth - 24f)
                        val absoluteBeat = cumulativeBeats + beatPosition
                        val isPlaybackCurrent = playbackBeat != null && kotlin.math.abs(absoluteBeat - playbackBeat) < 0.1f

                        // Look up the note's index in the sorted order
                        val noteKey = Triple(note.measureNumber, note.positionInMeasure, note.midiNote())
                        val sortedIndex = noteToIndex[noteKey] ?: -1
                        val noteState = noteStateMap[sortedIndex]

                        val noteColor = when {
                            isPlaybackCurrent -> MusicSheetFlowColors.CorrectEarlyLate
                            noteState == NoteState.CURRENT -> MusicSheetFlowColors.CurrentNote
                            noteState == NoteState.LOOKAHEAD -> MusicSheetFlowColors.CurrentNote.copy(alpha = 0.5f)
                            noteState == NoteState.PLAYED_CORRECT -> MusicSheetFlowColors.CorrectOnTime
                            noteState == NoteState.PLAYED_WRONG -> MusicSheetFlowColors.WrongPitch
                            noteState == NoteState.SKIPPED -> MusicSheetFlowColors.Skipped
                            else -> portraitConfig.noteColor
                        }

                        drawNote(bravuraTypeface, portraitConfig, note, trebleY, bassY, noteX, noteColor, showNoteNames, namingSystem)
                    }
                    cumulativeBeats += measureBeats
                }

                val endBarX = xOffset + systemMeasures * portraitConfig.measureWidth
                drawBarLine(portraitConfig, trebleY, bassY, endBarX, isDouble = (endMeasure == totalMeasures))
            }
        }
    }
}

/**
 * Draw a grand staff (treble + bass clef staves with brace).
 */
private fun DrawScope.drawGrandStaff(
    config: ScoreRenderConfig,
    trebleY: Float,
    bassY: Float,
    width: Float
) {
    val lineSpacing = config.staffHeight / 4

    // Draw treble staff (5 lines)
    for (i in 0..4) {
        val y = trebleY + i * lineSpacing
        drawLine(
            color = config.staffLineColor,
            start = Offset(config.leftMargin, y),
            end = Offset(width - 20f, y),
            strokeWidth = 1f
        )
    }

    // Draw bass staff (5 lines)
    for (i in 0..4) {
        val y = bassY + i * lineSpacing
        drawLine(
            color = config.staffLineColor,
            start = Offset(config.leftMargin, y),
            end = Offset(width - 20f, y),
            strokeWidth = 1f
        )
    }

    // Draw connecting line on left
    drawLine(
        color = config.staffLineColor,
        start = Offset(config.leftMargin, trebleY),
        end = Offset(config.leftMargin, bassY + config.staffHeight),
        strokeWidth = 2f
    )
}

/**
 * Draw clefs at the beginning of the staff.
 */
private fun DrawScope.drawClefs(
    typeface: Typeface,
    config: ScoreRenderConfig,
    trebleY: Float,
    bassY: Float
) {
    val lineSpacing = config.staffHeight / 4
    val fontSize = config.staffHeight * 1.0f

    val paint = android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = fontSize
        color = android.graphics.Color.BLACK
        isAntiAlias = true
    }

    // Treble clef - centered on G line (2nd line from bottom)
    val trebleClefY = trebleY + lineSpacing * 3  // G line
    drawContext.canvas.nativeCanvas.drawText(
        SMuFLGlyphs.TREBLE_CLEF.toString(),
        config.leftMargin + 5f,
        trebleClefY + fontSize * 0.1f,
        paint
    )

    // Bass clef - centered on F line (2nd line from top)
    val bassClefY = bassY + lineSpacing * 1  // F line
    drawContext.canvas.nativeCanvas.drawText(
        SMuFLGlyphs.BASS_CLEF.toString(),
        config.leftMargin + 5f,
        bassClefY + fontSize * 0.35f,
        paint
    )
}

/**
 * Draw key signature accidentals.
 */
private fun DrawScope.drawKeySignature(
    typeface: Typeface,
    config: ScoreRenderConfig,
    keyFifths: Int,
    trebleY: Float,
    bassY: Float,
    x: Float
) {
    val lineSpacing = config.staffHeight / 4
    val fontSize = config.staffHeight * 0.8f

    val paint = android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = fontSize
        color = android.graphics.Color.BLACK
        isAntiAlias = true
    }

    // Sharp positions (F, C, G, D, A, E, B) on treble staff
    val sharpPositions = listOf(0f, 1.5f, -0.5f, 1f, 2.5f, 0.5f, 2f)
    // Flat positions (B, E, A, D, G, C, F) on treble staff
    val flatPositions = listOf(2f, 0.5f, 2.5f, 1f, 3f, 1.5f, 3.5f)

    if (keyFifths > 0) {
        // Sharps
        for (i in 0 until keyFifths.coerceAtMost(7)) {
            val posY = trebleY + sharpPositions[i] * lineSpacing
            drawContext.canvas.nativeCanvas.drawText(
                SMuFLGlyphs.ACCIDENTAL_SHARP.toString(),
                x + i * 10f,
                posY + fontSize * 0.3f,
                paint
            )
        }
    } else if (keyFifths < 0) {
        // Flats
        for (i in 0 until (-keyFifths).coerceAtMost(7)) {
            val posY = trebleY + flatPositions[i] * lineSpacing
            drawContext.canvas.nativeCanvas.drawText(
                SMuFLGlyphs.ACCIDENTAL_FLAT.toString(),
                x + i * 10f,
                posY + fontSize * 0.3f,
                paint
            )
        }
    }
}

/**
 * Draw time signature.
 */
private fun DrawScope.drawTimeSignature(
    typeface: Typeface,
    config: ScoreRenderConfig,
    beats: Int,
    beatType: Int,
    trebleY: Float,
    bassY: Float,
    x: Float
) {
    val lineSpacing = config.staffHeight / 4
    val fontSize = config.staffHeight * 0.5f

    val paint = android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = fontSize
        color = android.graphics.Color.BLACK
        isAntiAlias = true
    }

    // Draw on treble staff
    // Top number (beats)
    drawContext.canvas.nativeCanvas.drawText(
        SMuFLGlyphs.timeSignatureDigit(beats).toString(),
        x,
        trebleY + lineSpacing * 1.5f,
        paint
    )
    // Bottom number (beat type)
    drawContext.canvas.nativeCanvas.drawText(
        SMuFLGlyphs.timeSignatureDigit(beatType).toString(),
        x,
        trebleY + lineSpacing * 3.5f,
        paint
    )

    // Draw on bass staff
    drawContext.canvas.nativeCanvas.drawText(
        SMuFLGlyphs.timeSignatureDigit(beats).toString(),
        x,
        bassY + lineSpacing * 1.5f,
        paint
    )
    drawContext.canvas.nativeCanvas.drawText(
        SMuFLGlyphs.timeSignatureDigit(beatType).toString(),
        x,
        bassY + lineSpacing * 3.5f,
        paint
    )
}

/**
 * Draw a bar line.
 */
private fun DrawScope.drawBarLine(
    config: ScoreRenderConfig,
    trebleY: Float,
    bassY: Float,
    x: Float,
    isDouble: Boolean = false
) {
    drawLine(
        color = config.staffLineColor,
        start = Offset(x, trebleY),
        end = Offset(x, bassY + config.staffHeight),
        strokeWidth = if (isDouble) 2f else 1f
    )
    if (isDouble) {
        drawLine(
            color = config.staffLineColor,
            start = Offset(x - 5f, trebleY),
            end = Offset(x - 5f, bassY + config.staffHeight),
            strokeWidth = 1f
        )
    }
}

/**
 * Draw a single note.
 */
private fun DrawScope.drawNote(
    typeface: Typeface,
    config: ScoreRenderConfig,
    note: Note,
    trebleY: Float,
    bassY: Float,
    x: Float,
    color: Color,
    showNoteName: Boolean = false,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem = net.tigr.musicsheetflow.util.NoteNaming.getNamingSystem()
) {
    if (note.isRest) {
        drawRest(typeface, config, note, trebleY, bassY, x, color)
        return
    }

    val pitch = note.pitch ?: return
    val lineSpacing = config.staffHeight / 4
    val fontSize = config.staffHeight * 0.9f

    val paint = android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = fontSize
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        isAntiAlias = true
    }

    // Calculate Y position based on pitch and staff
    val (staffY, positionOnStaff) = calculateNotePosition(pitch, note.staff, trebleY, bassY, lineSpacing)
    val noteY = staffY + positionOnStaff * lineSpacing / 2

    // Draw ledger lines if needed
    drawLedgerLines(config, staffY, positionOnStaff, x, lineSpacing)

    // Baseline offset for SMuFL noteheads
    // Bravura font baseline is above notehead center
    // This offset adjusts the notehead so its center aligns with the staff line
    val noteheadOffset = lineSpacing * 0.0f

    // Draw accidental if present
    if (pitch.alter != 0) {
        val accidental = SMuFLGlyphs.accidentalForAlter(pitch.alter)
        if (accidental != null) {
            drawContext.canvas.nativeCanvas.drawText(
                accidental.toString(),
                x - 15f,
                noteY + noteheadOffset,
                paint
            )
        }
    }

    // Draw notehead
    val notehead = when (note.type) {
        NoteType.WHOLE -> SMuFLGlyphs.NOTEHEAD_WHOLE
        NoteType.HALF -> SMuFLGlyphs.NOTEHEAD_HALF
        else -> SMuFLGlyphs.NOTEHEAD_BLACK
    }

    drawContext.canvas.nativeCanvas.drawText(
        notehead.toString(),
        x,
        noteY + noteheadOffset,
        paint
    )

    // Draw stem for half notes and shorter
    if (note.type != NoteType.WHOLE) {
        val stemUp = positionOnStaff >= 4  // Stem up if on or above middle line
        val stemLength = config.staffHeight * 0.875f
        val stemX = if (stemUp) x + 11f else x + 1f
        val stemStartY = noteY
        val stemEndY = if (stemUp) noteY - stemLength else noteY + stemLength

        drawLine(
            color = color,
            start = Offset(stemX, stemStartY),
            end = Offset(stemX, stemEndY),
            strokeWidth = 1.5f
        )

        // Draw flag for eighth notes and shorter
        val flag = SMuFLGlyphs.flagForType(note.type.name.lowercase(), stemUp)
        if (flag != null) {
            drawContext.canvas.nativeCanvas.drawText(
                flag.toString(),
                stemX - 1f,
                stemEndY,
                paint
            )
        }
    }

    // Draw note name if enabled
    if (showNoteName) {
        val pitch = note.pitch ?: return
        val noteName = net.tigr.musicsheetflow.util.NoteNaming.fromPitch(pitch.step, pitch.octave, pitch.alter, namingSystem)
        val nameTextPaint = android.graphics.Paint().apply {
            textSize = fontSize * 0.35f
            this.color = android.graphics.Color.argb(
                (color.alpha * 255).toInt(),
                (color.red * 255).toInt(),
                (color.green * 255).toInt(),
                (color.blue * 255).toInt()
            )
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            this.typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

        // Draw name below the note (or above if stem goes down and note is low)
        val nameY = if (positionOnStaff >= 4) {
            noteY + fontSize * 0.45f  // Below note
        } else {
            noteY - fontSize * 0.35f  // Above note
        }

        drawContext.canvas.nativeCanvas.drawText(
            noteName,
            x + 6f,
            nameY,
            nameTextPaint
        )
    }
}

/**
 * Draw a rest.
 */
private fun DrawScope.drawRest(
    typeface: Typeface,
    config: ScoreRenderConfig,
    note: Note,
    trebleY: Float,
    bassY: Float,
    x: Float,
    color: Color
) {
    val lineSpacing = config.staffHeight / 4
    val fontSize = config.staffHeight * 0.9f

    val paint = android.graphics.Paint().apply {
        this.typeface = typeface
        textSize = fontSize
        this.color = android.graphics.Color.argb(
            (color.alpha * 255).toInt(),
            (color.red * 255).toInt(),
            (color.green * 255).toInt(),
            (color.blue * 255).toInt()
        )
        isAntiAlias = true
    }

    val staffY = if (note.staff == 1) trebleY else bassY
    val restY = staffY + lineSpacing * 2  // Center of staff

    val rest = SMuFLGlyphs.restForType(note.type.name.lowercase())
    val restOffset = lineSpacing * 0.0f
    drawContext.canvas.nativeCanvas.drawText(
        rest.toString(),
        x,
        restY + restOffset,
        paint
    )
}

/**
 * Calculate the Y position of a note on the staff.
 * Returns (staffY, positionOnStaff) where positionOnStaff is:
 * 0 = top line, 2 = space below top line, 4 = second line, etc.
 *
 * Position is based on diatonic (natural) note position, not chromatic.
 * Sharps/flats appear on the same line as their natural note.
 */
private fun calculateNotePosition(
    pitch: Pitch,
    staff: Int,
    trebleY: Float,
    bassY: Float,
    lineSpacing: Float
): Pair<Float, Int> {
    val staffY = if (staff == 1) trebleY else bassY

    // Convert step (C-B) to index within octave (C=0, D=1, E=2, F=3, G=4, A=5, B=6)
    val stepIndex = when (pitch.step) {
        'C' -> 0
        'D' -> 1
        'E' -> 2
        'F' -> 3
        'G' -> 4
        'A' -> 5
        'B' -> 6
        else -> 0
    }

    // Calculate diatonic note number (C0=0, D0=1, ... C1=7, D1=8, ...)
    val diatonicNote = pitch.octave * 7 + stepIndex

    val position = if (staff == 1) {
        // Treble clef: F5 is top line (position 0), E4 is bottom line (position 8)
        // F5 diatonic = 5*7 + 3 = 38
        val f5Diatonic = 38
        (f5Diatonic - diatonicNote)
    } else {
        // Bass clef: A3 is top line (position 0), G2 is bottom line (position 8)
        // A3 diatonic = 3*7 + 5 = 26
        val a3Diatonic = 26
        (a3Diatonic - diatonicNote)
    }

    return staffY to position
}

/**
 * Draw ledger lines for notes above or below the staff.
 */
private fun DrawScope.drawLedgerLines(
    config: ScoreRenderConfig,
    staffY: Float,
    position: Int,
    x: Float,
    lineSpacing: Float
) {
    val ledgerWidth = 20f

    // Ledger lines above staff (position < 0)
    if (position < 0) {
        var ledgerPos = -2
        while (ledgerPos >= position) {
            val y = staffY + ledgerPos * lineSpacing / 2
            drawLine(
                color = config.staffLineColor,
                start = Offset(x - 5f, y),
                end = Offset(x + ledgerWidth, y),
                strokeWidth = 1f
            )
            ledgerPos -= 2
        }
    }

    // Ledger lines below staff (position > 8)
    if (position > 8) {
        var ledgerPos = 10
        while (ledgerPos <= position) {
            val y = staffY + ledgerPos * lineSpacing / 2
            drawLine(
                color = config.staffLineColor,
                start = Offset(x - 5f, y),
                end = Offset(x + ledgerWidth, y),
                strokeWidth = 1f
            )
            ledgerPos += 2
        }
    }
}
