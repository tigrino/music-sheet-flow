package net.tigr.musicsheetflow.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import net.tigr.musicsheetflow.audio.NativeAudioEngine
import net.tigr.musicsheetflow.audio.NativeMidiEngine
import net.tigr.musicsheetflow.audio.PitchEvent
import net.tigr.musicsheetflow.score.ScoreRepository
import net.tigr.musicsheetflow.score.model.Score
import net.tigr.musicsheetflow.tracking.BeatClock
import net.tigr.musicsheetflow.tracking.BeatClockState
import net.tigr.musicsheetflow.tracking.MatchFeedback
import net.tigr.musicsheetflow.tracking.MatchResult
import net.tigr.musicsheetflow.tracking.NoteMatcher
import net.tigr.musicsheetflow.tracking.NoteState
import net.tigr.musicsheetflow.tracking.PositionTracker
import net.tigr.musicsheetflow.tracking.TrackingState
import net.tigr.musicsheetflow.playback.ScorePlayer
import net.tigr.musicsheetflow.playback.PlaybackState
import net.tigr.musicsheetflow.ui.score.ScoreRenderer
import net.tigr.musicsheetflow.ui.theme.MusicSheetFlowColors
import net.tigr.musicsheetflow.ui.theme.MusicSheetFlowTheme

/**
 * Statistics collected during a practice session.
 */
data class SessionStats(
    val correctOnTime: Int = 0,
    val correctEarly: Int = 0,
    val correctLate: Int = 0,
    val wrongPitch: Int = 0,
    val skipped: Int = 0
) {
    val totalNotes: Int get() = correctOnTime + correctEarly + correctLate + wrongPitch + skipped
    val totalCorrect: Int get() = correctOnTime + correctEarly + correctLate
    val accuracyPercent: Float get() = if (totalNotes > 0) (totalCorrect.toFloat() / totalNotes) * 100f else 0f
    val onTimePercent: Float get() = if (totalCorrect > 0) (correctOnTime.toFloat() / totalCorrect) * 100f else 0f
    val earlyPercent: Float get() = if (totalCorrect > 0) (correctEarly.toFloat() / totalCorrect) * 100f else 0f
    val latePercent: Float get() = if (totalCorrect > 0) (correctLate.toFloat() / totalCorrect) * 100f else 0f

    fun addResult(result: MatchResult): SessionStats = when (result) {
        MatchResult.CORRECT_ON_TIME -> copy(correctOnTime = correctOnTime + 1)
        MatchResult.CORRECT_EARLY -> copy(correctEarly = correctEarly + 1)
        MatchResult.CORRECT_LATE -> copy(correctLate = correctLate + 1)
        MatchResult.WRONG_PITCH -> copy(wrongPitch = wrongPitch + 1)
        MatchResult.SKIPPED -> copy(skipped = skipped + 1)
        MatchResult.NO_MATCH -> this  // No change for non-matching events
    }
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()
        setContent {
            MusicSheetFlowTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    // Track microphone permission state
                    var hasMicPermission by remember {
                        mutableStateOf(
                            ContextCompat.checkSelfPermission(
                                this@MainActivity,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        )
                    }

                    val permissionLauncher = rememberLauncherForActivityResult(
                        ActivityResultContracts.RequestPermission()
                    ) { isGranted ->
                        hasMicPermission = isGranted
                        if (!isGranted) {
                            android.util.Log.e("MainActivity", "Microphone permission denied")
                        } else {
                            android.util.Log.i("MainActivity", "Microphone permission granted")
                        }
                    }

                    // Request permission on first launch
                    LaunchedEffect(Unit) {
                        if (!hasMicPermission) {
                            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }

                    AppNavigation(hasMicPermission = hasMicPermission)
                }
            }
        }
    }
}

/**
 * Screen navigation state.
 */
enum class Screen {
    LIBRARY,
    PRACTICE
}

/**
 * Main app navigation.
 */
@Composable
fun AppNavigation(hasMicPermission: Boolean = false) {
    var currentScreen by remember { mutableStateOf(Screen.PRACTICE) }
    var selectedScoreFilename by remember { mutableStateOf("Bach_Minuet_in_G_Major_BWV_Anh._114.mxl") }
    var isScoreImported by remember { mutableStateOf(false) }
    val scoreRepository = remember { ScoreRepository() }

    when (currentScreen) {
        Screen.LIBRARY -> {
            LibraryScreen(
                scoreRepository = scoreRepository,
                onScoreSelected = { filename, isImported ->
                    selectedScoreFilename = filename
                    isScoreImported = isImported
                    currentScreen = Screen.PRACTICE
                },
                onBack = {
                    currentScreen = Screen.PRACTICE
                }
            )
        }
        Screen.PRACTICE -> {
            MainScreen(
                scoreRepository = scoreRepository,
                scoreFilename = selectedScoreFilename,
                isImported = isScoreImported,
                hasMicPermission = hasMicPermission,
                onOpenLibrary = {
                    currentScreen = Screen.LIBRARY
                }
            )
        }
    }
}

@Composable
fun MainScreen(
    scoreRepository: ScoreRepository,
    scoreFilename: String,
    isImported: Boolean = false,
    hasMicPermission: Boolean = false,
    onOpenLibrary: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // Initialize MIDI engine
    val midiEngine = remember { NativeMidiEngine() }
    var midiReady by remember { mutableStateOf(false) }

    // Initialize Audio engine for pitch detection
    val audioEngine = remember { NativeAudioEngine() }
    var audioStarted by remember { mutableStateOf(false) }
    var currentPitch by remember { mutableStateOf<PitchEvent?>(null) }

    // Score loading
    var currentScore by remember { mutableStateOf<Score?>(null) }

    // Position tracking and note matching
    val positionTracker = remember { PositionTracker() }
    val noteMatcher = remember { NoteMatcher(positionTracker) }
    val trackingState by noteMatcher.getTrackingStateFlow().collectAsState()
    val beatClockState by noteMatcher.getBeatClock().state.collectAsState()
    var lastFeedback by remember { mutableStateOf<MatchFeedback?>(null) }
    var isPracticeMode by remember { mutableStateOf(false) }
    var isMetronomeEnabled by remember { mutableStateOf(false) }
    var showNoteNames by remember { mutableStateOf(false) }

    // Note naming system (language) - defaults to device locale
    var namingSystem by remember { mutableStateOf(net.tigr.musicsheetflow.util.NoteNaming.getNamingSystem()) }

    // Keyboard highlighting state
    var lastCorrectMidiNote by remember { mutableStateOf<Int?>(null) }
    var lastWrongMidiNote by remember { mutableStateOf<Int?>(null) }

    // Session statistics
    var sessionStats by remember { mutableStateOf(SessionStats()) }
    var showSessionSummary by remember { mutableStateOf(false) }

    // Count-in settings and state
    var countInMeasures by remember { mutableStateOf(1) }  // 0 = disabled, 1-4 measures
    var isCountingIn by remember { mutableStateOf(false) }
    var countInBeat by remember { mutableStateOf(0) }  // Current count-in beat (1-based)
    val countInJobRef = remember { mutableMapOf<String, kotlinx.coroutines.Job?>() }

    // Pitch detection settings
    var showPitchSettings by remember { mutableStateOf(false) }
    var confidenceThreshold by remember { mutableFloatStateOf(0.3f) }
    var silenceThreshold by remember { mutableFloatStateOf(-50f) }
    var noiseGateThreshold by remember { mutableFloatStateOf(-46f) }

    // Score playback
    val scorePlayer = remember { ScorePlayer(midiEngine) }
    val playbackState by scorePlayer.state.collectAsState()

    // Initialize MIDI engine (doesn't need mic permission)
    LaunchedEffect(Unit) {
        // Load SoundFont and start MIDI engine
        midiReady = midiEngine.loadBundledSoundFont(context)
        if (midiReady) {
            midiEngine.start()
            android.util.Log.i("MainScreen", "MIDI engine started")
        }
    }

    // Start audio engine only when mic permission is granted
    LaunchedEffect(hasMicPermission) {
        if (hasMicPermission && !audioStarted) {
            // Small delay to ensure audio subsystem is ready after permission grant
            kotlinx.coroutines.delay(100)
            android.util.Log.i("MainScreen", "Starting audio engine (permission granted)")
            val started = audioEngine.start()
            audioStarted = started
            android.util.Log.i("MainScreen", "Audio engine started: $started")
            if (!started) {
                // Retry once after a longer delay
                kotlinx.coroutines.delay(500)
                android.util.Log.i("MainScreen", "Retrying audio engine start...")
                val retryStarted = audioEngine.start()
                audioStarted = retryStarted
                android.util.Log.i("MainScreen", "Audio engine retry result: $retryStarted")
            }
        }
    }

    // Load score when filename or import status changes
    LaunchedEffect(scoreFilename, isImported) {
        val score = if (isImported) {
            scoreRepository.loadImportedScore(context, scoreFilename)
        } else {
            scoreRepository.loadScore(context, scoreFilename)
        }
        currentScore = score

        // Load score into note matcher and playback
        if (score != null) {
            noteMatcher.loadScore(score)
            scorePlayer.loadScore(score)
        }
    }

    // Collect pitch events and process through note matcher
    // Only start collecting when audio engine is confirmed started
    LaunchedEffect(audioStarted) {
        if (audioStarted) {
            android.util.Log.i("MainScreen", "Starting pitch events collection")
            audioEngine.pitchEvents.collect { event ->
                currentPitch = event
                // Clear wrong note highlight when new pitch is detected (will be re-evaluated by feedback)
                if (isPracticeMode && event.midiNote >= 0) {
                    lastWrongMidiNote = null
                }
                if (isPracticeMode) {
                    noteMatcher.processPitchEvent(event, scope)
                }
            }
        }
    }

    // Collect match feedback and update session stats
    LaunchedEffect(Unit) {
        noteMatcher.feedback.collect { feedback ->
            lastFeedback = feedback
            if (isPracticeMode) {
                sessionStats = sessionStats.addResult(feedback.result)

                // Update keyboard highlighting based on result
                val detectedMidi = feedback.detectedMidi
                when (feedback.result) {
                    MatchResult.CORRECT_ON_TIME, MatchResult.CORRECT_EARLY, MatchResult.CORRECT_LATE -> {
                        lastCorrectMidiNote = detectedMidi
                        lastWrongMidiNote = null  // Clear wrong note on success
                    }
                    MatchResult.WRONG_PITCH -> {
                        lastWrongMidiNote = detectedMidi
                        lastCorrectMidiNote = null  // Clear correct note
                    }
                    else -> { /* NO_MATCH, SKIPPED - no keyboard highlight change */ }
                }
            }
        }
    }

    // Clear correct note highlight after a brief flash (300ms)
    LaunchedEffect(lastCorrectMidiNote) {
        if (lastCorrectMidiNote != null) {
            kotlinx.coroutines.delay(300)
            lastCorrectMidiNote = null
        }
    }

    // Collect beat ticks for metronome
    LaunchedEffect(isMetronomeEnabled, midiReady) {
        if (isMetronomeEnabled && midiReady) {
            noteMatcher.getBeatClock().beatTicks.collect { tick ->
                val isDownbeat = tick.beatInMeasure == 1
                midiEngine.playMetronomeClick(isDownbeat)
            }
        }
    }

    // Keep screen on during practice or playback mode
    val activity = context as? android.app.Activity
    DisposableEffect(isPracticeMode, playbackState.isPlaying, isCountingIn) {
        val keepScreenOn = isPracticeMode || playbackState.isPlaying || isCountingIn
        if (keepScreenOn) {
            activity?.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose {
            activity?.window?.clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            scorePlayer.stop()
            noteMatcher.stop()
            midiEngine.stop()
            audioEngine.stop()
        }
    }

    // Start/stop practice mode handler
    fun togglePracticeMode() {
        // Stop playback if active
        if (playbackState.isPlaying) {
            scorePlayer.stop()
        }

        // If currently counting in or practicing, stop
        if (isCountingIn || isPracticeMode) {
            countInJobRef["job"]?.cancel()
            countInJobRef["job"] = null
            isCountingIn = false
            countInBeat = 0
            isPracticeMode = false
            noteMatcher.stop()
            // Show session summary if any notes were played
            if (sessionStats.totalNotes > 0) {
                showSessionSummary = true
            }
            return
        }

        // Reset session stats for new practice
        sessionStats = SessionStats()

        // Start count-in if enabled
        if (countInMeasures > 0 && midiReady) {
            isCountingIn = true
            noteMatcher.reset()

            val beatsPerMeasure = currentScore?.parts?.firstOrNull()?.measures?.firstOrNull()
                ?.attributes?.timeBeats ?: 4
            val tempo = beatClockState?.tempo ?: 120f
            val beatDurationMs = (60_000f / tempo).toLong()
            val totalBeats = countInMeasures * beatsPerMeasure

            countInJobRef["job"] = scope.launch {
                try {
                    for (beat in 1..totalBeats) {
                        countInBeat = beat
                        val isDownbeat = (beat - 1) % beatsPerMeasure == 0
                        midiEngine.playMetronomeClick(isDownbeat)
                        kotlinx.coroutines.delay(beatDurationMs)
                    }

                    // Count-in finished, start practice
                    isCountingIn = false
                    countInBeat = 0
                    isPracticeMode = true
                    noteMatcher.start(scope)
                } finally {
                    countInJobRef["job"] = null
                }
            }
        } else {
            // No count-in, start practice immediately
            noteMatcher.reset()
            isPracticeMode = true
            noteMatcher.start(scope)
        }
    }

    // Start/stop playback handler
    fun togglePlayback() {
        // Stop practice mode if active
        if (isPracticeMode) {
            isPracticeMode = false
            noteMatcher.stop()
        }
        scorePlayer.togglePlayback(scope)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .systemBarsPadding()
    ) {
        // Header bar
        HeaderBar(
            title = currentScore?.title ?: "Loading...",
            tempo = currentScore?.parts?.firstOrNull()?.measures?.firstOrNull()?.tempo ?: 120,
            namingSystem = namingSystem,
            onOpenLibrary = onOpenLibrary,
            onToggleLanguage = { namingSystem = net.tigr.musicsheetflow.util.NoteNaming.nextSystem(namingSystem) }
        )

        // Main content - stacked layout: score on top, keyboard below
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            val isLandscape = maxWidth > maxHeight
            // Landscape: taller keyboard (0.5), Portrait: compact height (0.27)
            val keyboardWeight = if (isLandscape) 0.5f else 0.27f
            val scoreWeight = 1f - keyboardWeight

            Column(modifier = Modifier.fillMaxSize()) {
                // Score area with overlay
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(scoreWeight)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    ScoreDisplay(
                        score = currentScore,
                        trackingState = trackingState,
                        playbackState = playbackState,
                        showNoteNames = showNoteNames,
                        namingSystem = namingSystem,
                        modifier = Modifier.fillMaxSize()
                    )

                    // Compact info overlay - top-right in landscape, bottom-right in portrait
                    CompactInfoOverlay(
                        modifier = Modifier
                            .align(if (isLandscape) Alignment.TopEnd else Alignment.BottomEnd)
                            .padding(12.dp),
                        currentPitch = currentPitch,
                        lastFeedback = lastFeedback,
                        isPracticeMode = isPracticeMode,
                        expectedMidiNote = noteMatcher.getExpectedMidiNote(),
                        namingSystem = namingSystem
                    )
                }

                // Full-width piano keyboard with black keys
                FullPianoKeyboard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(keyboardWeight)
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    midiEngine = midiEngine,
                    midiReady = midiReady,
                    expectedMidiNote = if (isPracticeMode) noteMatcher.getExpectedMidiNote() else null,
                    correctMidiNote = if (isPracticeMode) lastCorrectMidiNote else null,
                    wrongMidiNote = if (isPracticeMode) lastWrongMidiNote else null,
                    playingMidiNotes = playbackState.playingMidiNotes,
                    namingSystem = namingSystem
                )
            }
        }

        // Control bar
        ControlBar(
            isPracticeMode = isPracticeMode,
            isCountingIn = isCountingIn,
            countInBeat = countInBeat,
            countInMeasures = countInMeasures,
            beatClockState = beatClockState,
            isMetronomeEnabled = isMetronomeEnabled,
            playbackState = playbackState,
            onTogglePractice = { togglePracticeMode() },
            onTogglePlayback = { togglePlayback() },
            onSkipNote = { noteMatcher.skipCurrentNote(scope) },
            onRestart = {
                scorePlayer.stop()
                noteMatcher.reset()
                lastFeedback = null
            },
            onTempoChange = { newTempo ->
                noteMatcher.getBeatClock().setTempo(newTempo)
                scorePlayer.setTempo(newTempo)
            },
            onToggleMetronome = { isMetronomeEnabled = !isMetronomeEnabled },
            showNoteNames = showNoteNames,
            onToggleNoteNames = { showNoteNames = !showNoteNames },
            onCountInChange = { countInMeasures = it },
            onOpenSettings = { showPitchSettings = true }
        )

        // Session Summary Dialog
        if (showSessionSummary) {
            SessionSummaryDialog(
                stats = sessionStats,
                onDismiss = {
                    showSessionSummary = false
                    sessionStats = SessionStats()
                }
            )
        }

        // Pitch Detection Settings Dialog
        if (showPitchSettings) {
            PitchSettingsDialog(
                confidenceThreshold = confidenceThreshold,
                silenceThreshold = silenceThreshold,
                noiseGateThreshold = noiseGateThreshold,
                onConfidenceChange = { value ->
                    confidenceThreshold = value
                    audioEngine.setConfidenceThreshold(value)
                },
                onSilenceChange = { value ->
                    silenceThreshold = value
                    audioEngine.setSilenceThreshold(value)
                },
                onNoiseGateChange = { value ->
                    noiseGateThreshold = value
                    audioEngine.setNoiseGateThreshold(value)
                },
                onDismiss = { showPitchSettings = false }
            )
        }
    }
}

@Composable
fun SessionSummaryDialog(
    stats: SessionStats,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Practice Summary",
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Accuracy
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("Accuracy", fontWeight = FontWeight.Medium)
                    Text(
                        text = "${stats.accuracyPercent.toInt()}%",
                        color = when {
                            stats.accuracyPercent >= 90 -> MusicSheetFlowColors.CorrectOnTime
                            stats.accuracyPercent >= 70 -> MusicSheetFlowColors.CorrectEarlyLate
                            else -> MusicSheetFlowColors.WrongPitch
                        },
                        fontWeight = FontWeight.Bold
                    )
                }

                HorizontalDivider()

                // Notes breakdown
                Text("Notes Played", fontWeight = FontWeight.Medium)
                Column(
                    modifier = Modifier.padding(start = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    StatRow("Correct", stats.totalCorrect, MusicSheetFlowColors.CorrectOnTime)
                    StatRow("Wrong", stats.wrongPitch, MusicSheetFlowColors.WrongPitch)
                    StatRow("Skipped", stats.skipped, Color.Gray)
                }

                HorizontalDivider()

                // Timing breakdown (only if there are correct notes)
                if (stats.totalCorrect > 0) {
                    Text("Timing", fontWeight = FontWeight.Medium)
                    Column(
                        modifier = Modifier.padding(start = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        StatRow("On time", stats.correctOnTime, MusicSheetFlowColors.CorrectOnTime,
                                 "${stats.onTimePercent.toInt()}%")
                        StatRow("Early", stats.correctEarly, MusicSheetFlowColors.CorrectEarlyLate,
                                 "${stats.earlyPercent.toInt()}%")
                        StatRow("Late", stats.correctLate, MusicSheetFlowColors.CorrectEarlyLate,
                                 "${stats.latePercent.toInt()}%")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

@Composable
private fun StatRow(
    label: String,
    count: Int,
    color: Color,
    percentage: String? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 14.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = "$count",
                fontSize = 14.sp,
                color = color,
                fontWeight = FontWeight.Medium
            )
            if (percentage != null) {
                Text(
                    text = "($percentage)",
                    fontSize = 14.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun HeaderBar(
    title: String,
    tempo: Int,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem,
    onOpenLibrary: () -> Unit,
    onToggleLanguage: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MusicSheetFlowColors.CurrentNote
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Library button and title (takes available space, can shrink)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.weight(1f, fill = false)
            ) {
                IconButton(onClick = onOpenLibrary, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = androidx.compose.ui.res.painterResource(
                            id = android.R.drawable.ic_menu_sort_by_size
                        ),
                        contentDescription = "Library",
                        tint = Color.White
                    )
                }
                Text(
                    text = title,
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
            // Right side chips (fixed size, don't shrink)
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "♩=$tempo",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1
                    )
                }
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.White.copy(alpha = 0.2f)
                ) {
                    Text(
                        text = "Wait",
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        maxLines = 1
                    )
                }
                // Language switcher chip
                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.clickable { onToggleLanguage() }
                ) {
                    Text(
                        text = net.tigr.musicsheetflow.util.NoteNaming.getLanguageLabel(namingSystem),
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
fun ScoreDisplay(
    score: Score?,
    trackingState: TrackingState? = null,
    playbackState: PlaybackState = PlaybackState(),
    showNoteNames: Boolean = false,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem = net.tigr.musicsheetflow.util.NoteNaming.getNamingSystem(),
    modifier: Modifier = Modifier
) {
    // Determine current note index from tracking state (not playback - uses beat instead)
    val currentNoteIndex = trackingState?.currentIndex ?: -1
    // Pass playback beat for scroll position during playback
    val playbackBeat = if (playbackState.isPlaying) playbackState.currentBeat else null
    val playedNoteIndices = trackingState?.noteStates?.entries
        ?.filter { it.value == NoteState.PLAYED_CORRECT || it.value == NoteState.PLAYED_WRONG || it.value == NoteState.SKIPPED }
        ?.map { it.key }
        ?.toSet() ?: emptySet()
    val noteStateMap = trackingState?.noteStates ?: emptyMap()

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MusicSheetFlowColors.ScoreBackground,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
    ) {
        if (score == null) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MusicSheetFlowColors.CurrentNote)
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            ) {
                // Score renderer with notation
                ScoreRenderer(
                    score = score,
                    currentNoteIndex = currentNoteIndex,
                    playbackBeat = playbackBeat,
                    playedNoteIndices = playedNoteIndices,
                    noteStateMap = noteStateMap,
                    showNoteNames = showNoteNames,
                    namingSystem = namingSystem,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )
            }
        }
    }
}

@Composable
fun ScoreInfoChip(text: String) {
    Surface(
        shape = RoundedCornerShape(4.dp),
        color = MusicSheetFlowColors.CurrentNote.copy(alpha = 0.1f)
    ) {
        Text(
            text = text,
            fontSize = 12.sp,
            color = MusicSheetFlowColors.CurrentNote,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
fun StaffLines() {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(5) {
            Box(
                modifier = Modifier
                    .width(400.dp)
                    .height(1.dp)
                    .background(Color.DarkGray)
            )
        }
    }
}

fun keySignatureName(fifths: Int): String {
    return when (fifths) {
        -7 -> "Cb Major"
        -6 -> "Gb Major"
        -5 -> "Db Major"
        -4 -> "Ab Major"
        -3 -> "Eb Major"
        -2 -> "Bb Major"
        -1 -> "F Major"
        0 -> "C Major"
        1 -> "G Major"
        2 -> "D Major"
        3 -> "A Major"
        4 -> "E Major"
        5 -> "B Major"
        6 -> "F# Major"
        7 -> "C# Major"
        else -> "Unknown"
    }
}

@Composable
fun SidePanel(
    modifier: Modifier = Modifier,
    midiEngine: NativeMidiEngine? = null,
    midiReady: Boolean = false,
    currentPitch: PitchEvent? = null,
    trackingState: TrackingState? = null,
    lastFeedback: MatchFeedback? = null,
    isPracticeMode: Boolean = false,
    expectedNoteName: String? = null,
    beatClockState: BeatClockState? = null,
    playingMidiNotes: Set<Int> = emptySet()  // Notes playing during playback
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // Expected note and detected pitch display
        PitchDisplay(
            currentPitch = currentPitch,
            expectedNoteName = expectedNoteName,
            lastFeedback = lastFeedback,
            isPracticeMode = isPracticeMode
        )

        // Timing indicator based on feedback
        TimingIndicator(lastFeedback = lastFeedback)

        // Virtual keyboard - interactive!
        InteractiveKeyboard(
            modifier = Modifier.weight(1f),
            midiEngine = midiEngine,
            midiReady = midiReady,
            expectedMidiNote = if (isPracticeMode) {
                trackingState?.noteStates?.entries
                    ?.find { it.value == NoteState.CURRENT }
                    ?.key
            } else null,
            playingMidiNotes = playingMidiNotes
        )

        // Session stats from tracking
        SessionStatsDisplay(trackingState = trackingState)
    }
}

@Composable
fun PitchDisplay(
    currentPitch: PitchEvent? = null,
    expectedNoteName: String? = null,
    lastFeedback: MatchFeedback? = null,
    isPracticeMode: Boolean = false
) {
    // Calculate display values
    val noteName = currentPitch?.noteName() ?: "--"
    val centDeviation = currentPitch?.centDeviation ?: 0
    val frequency = currentPitch?.frequency ?: 0f
    val confidence = currentPitch?.confidence ?: 0f

    // Determine feedback color based on match result
    val feedbackColor = when (lastFeedback?.result) {
        MatchResult.CORRECT_ON_TIME -> MusicSheetFlowColors.CorrectOnTime
        MatchResult.CORRECT_EARLY, MatchResult.CORRECT_LATE -> MusicSheetFlowColors.CorrectEarlyLate
        MatchResult.WRONG_PITCH -> MusicSheetFlowColors.WrongPitch
        MatchResult.SKIPPED -> MusicSheetFlowColors.Skipped
        else -> Color.Gray
    }

    // Determine cent color based on accuracy
    val centColor = when {
        currentPitch == null -> Color.Gray
        kotlin.math.abs(centDeviation) <= 10 -> MusicSheetFlowColors.CorrectOnTime  // In tune
        kotlin.math.abs(centDeviation) <= 25 -> MusicSheetFlowColors.CorrectEarlyLate  // Slightly off
        else -> MusicSheetFlowColors.WrongPitch  // Very off
    }

    // Format cent string
    val centString = when {
        currentPitch == null -> "0¢"
        centDeviation >= 0 -> "+${centDeviation}¢"
        else -> "${centDeviation}¢"
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, if (isPracticeMode) feedbackColor else Color.LightGray)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Expected note (practice mode)
            if (isPracticeMode && expectedNoteName != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Expected",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                    Text(
                        text = expectedNoteName,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        color = MusicSheetFlowColors.CurrentNote
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Detected",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                if (currentPitch != null) {
                    Text(
                        text = "${frequency.toInt()} Hz",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = noteName,
                    fontSize = 32.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isPracticeMode && lastFeedback != null) feedbackColor
                            else if (currentPitch != null) MusicSheetFlowColors.CurrentNote
                            else Color.Gray
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = centString,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = centColor
                    )
                    if (currentPitch != null) {
                        // Confidence indicator
                        LinearProgressIndicator(
                            progress = { confidence },
                            modifier = Modifier.width(60.dp).height(4.dp),
                            color = MusicSheetFlowColors.CorrectOnTime,
                            trackColor = Color.LightGray
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun TimingIndicator(lastFeedback: MatchFeedback? = null) {
    // Determine indicator position and color based on last feedback
    val (indicatorPosition, indicatorColor, timingText) = when (lastFeedback?.result) {
        MatchResult.CORRECT_ON_TIME -> Triple(0.5f, MusicSheetFlowColors.CorrectOnTime, "On Time")
        MatchResult.CORRECT_EARLY -> Triple(0.2f, MusicSheetFlowColors.CorrectEarlyLate, "Early")
        MatchResult.CORRECT_LATE -> Triple(0.8f, MusicSheetFlowColors.CorrectEarlyLate, "Late")
        MatchResult.WRONG_PITCH -> Triple(0.5f, MusicSheetFlowColors.WrongPitch, "Wrong")
        MatchResult.SKIPPED -> Triple(0.5f, MusicSheetFlowColors.Skipped, "Skipped")
        else -> Triple(0.5f, Color.LightGray, "--")
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Timing",
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = timingText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = indicatorColor
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            // Timing bar with early/late regions
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(Color.LightGray)
            ) {
                // Center "on-time" region
                Box(
                    modifier = Modifier
                        .fillMaxWidth(0.3f)
                        .fillMaxHeight()
                        .align(Alignment.Center)
                        .background(MusicSheetFlowColors.CorrectOnTime.copy(alpha = 0.3f))
                )
                // Indicator
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .offset(x = ((indicatorPosition - 0.5f) * 200).dp)
                        .clip(CircleShape)
                        .background(indicatorColor)
                        .align(Alignment.Center)
                )
            }
            // Early/Late labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(text = "Early", fontSize = 10.sp, color = Color.Gray)
                Text(text = "Late", fontSize = 10.sp, color = Color.Gray)
            }
        }
    }
}

@Composable
fun InteractiveKeyboard(
    modifier: Modifier = Modifier,
    midiEngine: NativeMidiEngine? = null,
    midiReady: Boolean = false,
    expectedMidiNote: Int? = null,
    playingMidiNotes: Set<Int> = emptySet()  // Notes currently being played back
) {
    // Calculate dynamic range based on expected note
    // Default to C4-C5 (60-72) range if no expected note
    val numWhiteKeys = 8  // Number of white keys to show

    // Find the starting note for the keyboard range
    // Center the expected note in the visible range
    val targetStart = remember(expectedMidiNote) {
        if (expectedMidiNote == null) {
            60  // Default to middle C
        } else {
            // Calculate which C to start from (round to nearest octave)
            // This ensures we show full octaves and keeps expected note visible
            val targetOctaveStart = ((expectedMidiNote - 60) / 12) * 12 + 60
            // Adjust to center the expected note better
            val adjustedStart = if (expectedMidiNote - targetOctaveStart >= 6) {
                targetOctaveStart + 5  // Start from F of that octave
            } else {
                targetOctaveStart - 7  // Start from F of previous octave
            }
            adjustedStart.coerceIn(36, 84)  // Keep within reasonable piano range (C2-C7)
        }
    }

    // Animate keyboard sliding
    val animatedStart by androidx.compose.animation.core.animateIntAsState(
        targetValue = targetStart,
        animationSpec = androidx.compose.animation.core.tween(durationMillis = 300),
        label = "keyboardStart"
    )

    // Generate white key notes for current range
    val (whiteKeyNotes, whiteKeyNames) = remember(animatedStart) {
        val notes = mutableListOf<Int>()
        val names = mutableListOf<String>()
        var currentNote = animatedStart

        // Find the nearest white key if we landed on a black key
        val isBlackKey = currentNote % 12 in listOf(1, 3, 6, 8, 10)
        if (isBlackKey) currentNote--

        while (notes.size < numWhiteKeys && currentNote <= 108) {
            // Check if it's a white key (not a sharp/flat)
            val noteInOctave = currentNote % 12
            if (noteInOctave !in listOf(1, 3, 6, 8, 10)) {  // Not C#, D#, F#, G#, A#
                notes.add(currentNote)
                val octave = (currentNote / 12) - 1
                val noteName = net.tigr.musicsheetflow.util.NoteNaming.whiteKeyName(noteInOctave)
                names.add("$noteName$octave")
            }
            currentNote++
        }
        notes to names
    }

    var pressedKey by remember { mutableStateOf(-1) }

    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (midiReady) "Tap keys to play!" else "Loading SoundFont...",
                fontSize = 12.sp,
                color = if (midiReady) MusicSheetFlowColors.CorrectOnTime else Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))

            // Piano keys - each key is a separate composable for reliable click handling
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center
            ) {
                whiteKeyNotes.forEachIndexed { index, midiNote ->
                    PianoKey(
                        midiNote = midiNote,
                        keyName = whiteKeyNames.getOrElse(index) { "?" },
                        showLabel = (index == 0 || index == whiteKeyNotes.size - 1),
                        isPressed = (pressedKey == midiNote),
                        isHighlighted = (expectedMidiNote != null && midiNote == expectedMidiNote),
                        isPlaying = midiNote in playingMidiNotes,  // Show playback notes
                        enabled = midiReady,
                        onKeyPress = {
                            // Turn off previous note first
                            if (pressedKey >= 0) {
                                midiEngine?.noteOff(pressedKey)
                            }
                            pressedKey = midiNote
                            midiEngine?.noteOn(midiNote, 0.8f)
                        }
                    )
                }
            }

            // Auto note-off after delay
            LaunchedEffect(pressedKey) {
                if (pressedKey >= 0 && midiEngine != null) {
                    kotlinx.coroutines.delay(800)
                    midiEngine.noteOff(pressedKey)
                    pressedKey = -1
                }
            }
        }
    }
}

@Composable
fun PianoKey(
    midiNote: Int,
    keyName: String,
    showLabel: Boolean,
    isPressed: Boolean,
    isHighlighted: Boolean,
    isPlaying: Boolean = false,  // Playing during playback
    enabled: Boolean,
    onKeyPress: () -> Unit
) {
    val keyShape = RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
    val keyColor = when {
        isPressed -> MusicSheetFlowColors.CorrectOnTime
        isPlaying -> MusicSheetFlowColors.CorrectEarlyLate  // Yellow for playback
        isHighlighted -> MusicSheetFlowColors.CurrentNote
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .width(36.dp)
            .height(100.dp)
            .clip(keyShape)
            .background(keyColor)
            .border(1.dp, Color.Gray, keyShape)
            .clickable(enabled = enabled, onClick = onKeyPress),
        contentAlignment = Alignment.BottomCenter
    ) {
        if (showLabel) {
            Text(
                text = keyName,
                fontSize = 10.sp,
                color = if (isPressed) Color.White else Color.Gray,
                modifier = Modifier.padding(bottom = 4.dp)
            )
        }
    }
}

/**
 * Full-width piano keyboard with black keys.
 * Displays 2+ octaves with proper piano layout.
 */
@Composable
fun FullPianoKeyboard(
    modifier: Modifier = Modifier,
    midiEngine: NativeMidiEngine? = null,
    midiReady: Boolean = false,
    expectedMidiNote: Int? = null,
    correctMidiNote: Int? = null,   // Just played correctly - flash green
    wrongMidiNote: Int? = null,     // Wrong pitch - show red
    playingMidiNotes: Set<Int> = emptySet(),
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem = net.tigr.musicsheetflow.util.NoteNaming.getNamingSystem()
) {
    // Piano range: C3 to C6 (3 octaves) - MIDI 48 to 84
    val startNote = 48  // C3
    val endNote = 84    // C6

    // Build list of all notes in range
    val allNotes = (startNote..endNote).toList()

    // Separate white and black keys
    val whiteKeyPattern = listOf(0, 2, 4, 5, 7, 9, 11)  // C, D, E, F, G, A, B
    val blackKeyPattern = listOf(1, 3, 6, 8, 10)        // C#, D#, F#, G#, A#

    val whiteKeys = allNotes.filter { (it % 12) in whiteKeyPattern }
    val blackKeys = allNotes.filter { (it % 12) in blackKeyPattern }

    var pressedKey by remember { mutableStateOf(-1) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = Color(0xFF2C2C2C),  // Dark background
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.Gray)
    ) {
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            val availableWidth = maxWidth
            val availableHeight = maxHeight

            // Calculate key dimensions based on available space
            val whiteKeyWidth = availableWidth / whiteKeys.size
            val whiteKeyHeight = availableHeight - 16.dp
            val blackKeyWidth = whiteKeyWidth * 0.6f
            val blackKeyHeight = whiteKeyHeight * 0.6f

            Box(modifier = Modifier.fillMaxSize()) {
                // Draw white keys first
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    whiteKeys.forEachIndexed { index, midiNote ->
                        val noteInOctave = midiNote % 12
                        val octave = (midiNote / 12) - 1
                        val noteName = net.tigr.musicsheetflow.util.NoteNaming.whiteKeyName(noteInOctave, namingSystem)
                        val isC = noteInOctave == 0

                        val isPressed = pressedKey == midiNote
                        val isHighlighted = expectedMidiNote == midiNote
                        val isCorrect = correctMidiNote == midiNote
                        val isWrong = wrongMidiNote == midiNote
                        val isPlaying = midiNote in playingMidiNotes

                        val keyColor = when {
                            isPressed -> MusicSheetFlowColors.CorrectOnTime
                            isPlaying -> MusicSheetFlowColors.CorrectEarlyLate
                            isCorrect -> MusicSheetFlowColors.CorrectOnTime  // Just played correctly - green
                            isWrong -> MusicSheetFlowColors.WrongPitch       // Wrong pitch - red
                            isHighlighted -> MusicSheetFlowColors.CurrentNote  // Expected note - blue
                            else -> Color.White
                        }

                        Box(
                            modifier = Modifier
                                .width(whiteKeyWidth)
                                .height(whiteKeyHeight)
                                .padding(horizontal = 1.dp)
                                .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                                .background(keyColor)
                                .border(
                                    1.dp,
                                    Color.Gray,
                                    RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp)
                                )
                                .clickable(enabled = midiReady) {
                                    if (pressedKey >= 0) midiEngine?.noteOff(pressedKey)
                                    pressedKey = midiNote
                                    midiEngine?.noteOn(midiNote, 0.8f)
                                },
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (isC) {
                                Text(
                                    text = "$noteName$octave",
                                    fontSize = 11.sp,
                                    color = if (isPressed || isPlaying) Color.White else Color.Gray,
                                    modifier = Modifier.padding(bottom = 4.dp)
                                )
                            }
                        }
                    }
                }

                // Draw black keys on top
                whiteKeys.forEachIndexed { index, whiteNote ->
                    val noteInOctave = whiteNote % 12
                    // Black key comes after C, D, F, G, A (not after E or B)
                    val hasBlackKeyAfter = noteInOctave in listOf(0, 2, 5, 7, 9)

                    if (hasBlackKeyAfter) {
                        val blackNote = whiteNote + 1
                        if (blackNote in blackKeys) {
                            val isPressed = pressedKey == blackNote
                            val isHighlighted = expectedMidiNote == blackNote
                            val isCorrect = correctMidiNote == blackNote
                            val isWrong = wrongMidiNote == blackNote
                            val isPlaying = blackNote in playingMidiNotes

                            val keyColor = when {
                                isPressed -> MusicSheetFlowColors.CorrectOnTime
                                isPlaying -> MusicSheetFlowColors.CorrectEarlyLate
                                isCorrect -> MusicSheetFlowColors.CorrectOnTime  // Just played correctly - green
                                isWrong -> MusicSheetFlowColors.WrongPitch       // Wrong pitch - red
                                isHighlighted -> MusicSheetFlowColors.CurrentNote  // Expected note - blue
                                else -> Color(0xFF1A1A1A)  // Very dark gray/black
                            }

                            // Position black key between white keys
                            val xOffset = whiteKeyWidth * (index + 1) - (blackKeyWidth / 2)

                            Box(
                                modifier = Modifier
                                    .offset(x = xOffset)
                                    .width(blackKeyWidth)
                                    .height(blackKeyHeight)
                                    .clip(RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
                                    .background(keyColor)
                                    .border(
                                        1.dp,
                                        Color.DarkGray,
                                        RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp)
                                    )
                                    .clickable(enabled = midiReady) {
                                        if (pressedKey >= 0) midiEngine?.noteOff(pressedKey)
                                        pressedKey = blackNote
                                        midiEngine?.noteOn(blackNote, 0.8f)
                                    }
                            )
                        }
                    }
                }
            }

            // Auto note-off after delay
            LaunchedEffect(pressedKey) {
                if (pressedKey >= 0 && midiEngine != null) {
                    kotlinx.coroutines.delay(800)
                    midiEngine.noteOff(pressedKey)
                    pressedKey = -1
                }
            }
        }
    }
}

/**
 * Compact overlay showing pitch detection and feedback info.
 */
@Composable
fun CompactInfoOverlay(
    modifier: Modifier = Modifier,
    currentPitch: PitchEvent? = null,
    lastFeedback: MatchFeedback? = null,
    isPracticeMode: Boolean = false,
    expectedMidiNote: Int? = null,
    namingSystem: net.tigr.musicsheetflow.util.NoteNaming.NamingSystem = net.tigr.musicsheetflow.util.NoteNaming.getNamingSystem()
) {
    // Only show during practice mode
    if (!isPracticeMode) return

    val noteName = currentPitch?.midiNote?.let {
        net.tigr.musicsheetflow.util.NoteNaming.fromMidi(it, namingSystem)
    } ?: "--"
    val expectedNoteName = expectedMidiNote?.let {
        net.tigr.musicsheetflow.util.NoteNaming.fromMidi(it, namingSystem)
    }
    val feedbackColor = when (lastFeedback?.result) {
        MatchResult.CORRECT_ON_TIME -> MusicSheetFlowColors.CorrectOnTime
        MatchResult.CORRECT_EARLY, MatchResult.CORRECT_LATE -> MusicSheetFlowColors.CorrectEarlyLate
        MatchResult.WRONG_PITCH -> MusicSheetFlowColors.WrongPitch
        else -> Color.Gray
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.95f),
        shadowElevation = 4.dp
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Expected note
            if (expectedNoteName != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Expected", fontSize = 10.sp, color = Color.Gray)
                    Text(
                        expectedNoteName,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = MusicSheetFlowColors.CurrentNote
                    )
                }
            }

            // Detected note
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Detected", fontSize = 10.sp, color = Color.Gray)
                Text(
                    noteName,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (lastFeedback != null) feedbackColor else Color.Gray
                )
            }

            // Feedback indicator
            if (lastFeedback != null) {
                val feedbackText = when (lastFeedback.result) {
                    MatchResult.CORRECT_ON_TIME -> "✓"
                    MatchResult.CORRECT_EARLY -> "←"
                    MatchResult.CORRECT_LATE -> "→"
                    MatchResult.WRONG_PITCH -> "✗"
                    else -> ""
                }
                Text(
                    feedbackText,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = feedbackColor
                )
            }
        }
    }
}

@Composable
fun SessionStatsDisplay(trackingState: TrackingState? = null) {
    val stats = trackingState?.sessionStats
    val accuracy = stats?.let { "%.0f%%".format(it.accuracy * 100) } ?: "--"
    val notesPlayed = stats?.totalNotes ?: 0
    val correctNotes = stats?.correctNotes ?: 0
    val wrongNotes = stats?.wrongNotes ?: 0
    val skippedNotes = stats?.skippedNotes ?: 0
    val onTimePercent = stats?.let { "%.0f%%".format(it.onTimePercent) } ?: "--"

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = Color.White,
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.LightGray)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = "Session Stats",
                fontSize = 12.sp,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Accuracy: $accuracy",
                        fontSize = 14.sp,
                        color = if (stats != null && stats.accuracy >= 0.9f)
                            MusicSheetFlowColors.CorrectOnTime else Color.Black
                    )
                    Text(
                        text = "Correct: $correctNotes",
                        fontSize = 14.sp,
                        color = MusicSheetFlowColors.CorrectOnTime
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(text = "On-time: $onTimePercent", fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Wrong: $wrongNotes",
                            fontSize = 14.sp,
                            color = MusicSheetFlowColors.WrongPitch
                        )
                        Text(
                            text = "Skip: $skippedNotes",
                            fontSize = 14.sp,
                            color = MusicSheetFlowColors.Skipped
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ControlBar(
    isPracticeMode: Boolean = false,
    isCountingIn: Boolean = false,
    countInBeat: Int = 0,
    countInMeasures: Int = 1,
    beatClockState: BeatClockState? = null,
    isMetronomeEnabled: Boolean = false,
    playbackState: PlaybackState = PlaybackState(),
    showNoteNames: Boolean = false,
    onTogglePractice: () -> Unit = {},
    onTogglePlayback: () -> Unit = {},
    onSkipNote: () -> Unit = {},
    onRestart: () -> Unit = {},
    onTempoChange: (Float) -> Unit = {},
    onToggleMetronome: () -> Unit = {},
    onToggleNoteNames: () -> Unit = {},
    onCountInChange: (Int) -> Unit = {},
    onOpenSettings: () -> Unit = {}
) {
    val tempo = beatClockState?.tempo?.toInt() ?: 120
    val isPlaying = playbackState.isPlaying
    val currentBeat = beatClockState?.currentBeat ?: 0
    val beatsPerMeasure = beatClockState?.beatsPerMeasure ?: 4
    val beatInMeasure = (currentBeat % beatsPerMeasure) + 1

    // Track tempo slider value locally for smooth interaction
    var sliderTempo by remember { mutableFloatStateOf(tempo.toFloat()) }

    // Sync slider with external tempo changes
    LaunchedEffect(tempo) {
        sliderTempo = tempo.toFloat()
    }

    val isDisabled = isPracticeMode || isPlaying || isCountingIn

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shadowElevation = 8.dp
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            val isLandscape = maxWidth > maxHeight

            Column(modifier = Modifier.fillMaxWidth()) {
                // Row 1: Tempo slider (always separate row)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("♩=", fontSize = 14.sp, color = MusicSheetFlowColors.CurrentNote)
                    Text(
                        "${sliderTempo.toInt()}",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MusicSheetFlowColors.CurrentNote,
                        modifier = Modifier.width(36.dp),
                        textAlign = TextAlign.Center
                    )
                    Slider(
                        value = sliderTempo,
                        onValueChange = { sliderTempo = it },
                        onValueChangeFinished = { onTempoChange(sliderTempo) },
                        valueRange = 40f..240f,
                        modifier = Modifier.weight(1f),
                        enabled = !isDisabled,
                        colors = SliderDefaults.colors(
                            thumbColor = if (isDisabled) Color.Gray else MusicSheetFlowColors.CurrentNote,
                            activeTrackColor = if (isDisabled) Color.Gray else MusicSheetFlowColors.CurrentNote,
                            inactiveTrackColor = if (isDisabled) Color.Gray.copy(alpha = 0.3f)
                                else MusicSheetFlowColors.CurrentNote.copy(alpha = 0.3f),
                            disabledThumbColor = Color.Gray,
                            disabledActiveTrackColor = Color.Gray.copy(alpha = 0.5f),
                            disabledInactiveTrackColor = Color.Gray.copy(alpha = 0.2f)
                        )
                    )
                }

                if (isLandscape) {
                    // LANDSCAPE: Single row with transport + options
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Transport controls
                        IconButton(onClick = onRestart, modifier = Modifier.size(40.dp)) {
                            Text("⏮", fontSize = 20.sp)
                        }

                        SmallFloatingActionButton(
                            onClick = { if (!isCountingIn) onTogglePlayback() },
                            containerColor = when {
                                isCountingIn -> Color.Gray
                                isPlaying -> MusicSheetFlowColors.CorrectEarlyLate
                                else -> MusicSheetFlowColors.CurrentNote.copy(alpha = 0.7f)
                            }
                        ) {
                            Text(
                                if (isPlaying) "⏸" else "🎵",
                                fontSize = 14.sp,
                                color = if (isCountingIn) Color.DarkGray else if (isPlaying) Color.Black else Color.White
                            )
                        }

                        FloatingActionButton(
                            onClick = onTogglePractice,
                            modifier = Modifier.size(48.dp),
                            containerColor = when {
                                isCountingIn -> MusicSheetFlowColors.CorrectEarlyLate
                                isPracticeMode -> MusicSheetFlowColors.CorrectOnTime
                                else -> MusicSheetFlowColors.CurrentNote
                            }
                        ) {
                            if (isCountingIn && countInBeat > 0) {
                                Text("$countInBeat", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            } else if (isCountingIn) {
                                Text("...", fontSize = 14.sp, color = Color.Black)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Practice", tint = Color.White)
                            }
                        }

                        IconButton(onClick = onSkipNote, enabled = isPracticeMode, modifier = Modifier.size(40.dp)) {
                            Text("⏭", fontSize = 20.sp, color = if (isPracticeMode) Color.Black else Color.LightGray)
                        }

                        // Options (compact)
                        MetronomeChip(isMetronomeEnabled, beatClockState, beatInMeasure, beatsPerMeasure, onToggleMetronome, compact = true)
                        CountInChip(countInMeasures, isPracticeMode, isCountingIn, onCountInChange, compact = true)
                        NoteNamesChip(showNoteNames, onToggleNoteNames, compact = true)

                        IconButton(onClick = onOpenSettings, modifier = Modifier.size(40.dp)) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                } else {
                    // PORTRAIT: Two rows (transport, then options)
                    // Row 2: Transport controls
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onRestart) {
                            Text("⏮", fontSize = 24.sp)
                        }

                        SmallFloatingActionButton(
                            onClick = { if (!isCountingIn) onTogglePlayback() },
                            containerColor = when {
                                isCountingIn -> Color.Gray
                                isPlaying -> MusicSheetFlowColors.CorrectEarlyLate
                                else -> MusicSheetFlowColors.CurrentNote.copy(alpha = 0.7f)
                            }
                        ) {
                            Text(
                                if (isPlaying) "⏸" else "🎵",
                                fontSize = 16.sp,
                                color = if (isCountingIn) Color.DarkGray else if (isPlaying) Color.Black else Color.White
                            )
                        }

                        FloatingActionButton(
                            onClick = onTogglePractice,
                            containerColor = when {
                                isCountingIn -> MusicSheetFlowColors.CorrectEarlyLate
                                isPracticeMode -> MusicSheetFlowColors.CorrectOnTime
                                else -> MusicSheetFlowColors.CurrentNote
                            }
                        ) {
                            if (isCountingIn && countInBeat > 0) {
                                Text("$countInBeat", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                            } else if (isCountingIn) {
                                Text("...", fontSize = 16.sp, color = Color.Black)
                            } else {
                                Icon(Icons.Default.PlayArrow, contentDescription = "Practice", tint = Color.White)
                            }
                        }

                        IconButton(onClick = onSkipNote, enabled = isPracticeMode) {
                            Text("⏭", fontSize = 24.sp, color = if (isPracticeMode) Color.Black else Color.LightGray)
                        }
                    }

                    // Row 3: Options
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        MetronomeChip(isMetronomeEnabled, beatClockState, beatInMeasure, beatsPerMeasure, onToggleMetronome, compact = false)
                        CountInChip(countInMeasures, isPracticeMode, isCountingIn, onCountInChange, compact = false)
                        NoteNamesChip(showNoteNames, onToggleNoteNames, compact = false)
                        IconButton(onClick = onOpenSettings) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MetronomeChip(
    isEnabled: Boolean,
    beatClockState: BeatClockState?,
    beatInMeasure: Int,
    beatsPerMeasure: Int,
    onToggle: () -> Unit,
    compact: Boolean
) {
    Surface(
        modifier = Modifier.clickable { onToggle() },
        shape = RoundedCornerShape(8.dp),
        color = if (isEnabled) MusicSheetFlowColors.CorrectOnTime.copy(alpha = 0.2f)
                else MusicSheetFlowColors.CurrentNote.copy(alpha = 0.1f),
        border = if (isEnabled) androidx.compose.foundation.BorderStroke(2.dp, MusicSheetFlowColors.CorrectOnTime) else null
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(if (isEnabled) "🔊" else "🔇", fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    for (i in 1..beatsPerMeasure.coerceAtMost(4)) {
                        Text(
                            if (beatClockState?.isRunning == true && beatInMeasure == i) "●" else "○",
                            fontSize = 10.sp,
                            color = if (beatClockState?.isRunning == true && beatInMeasure == i)
                                MusicSheetFlowColors.CorrectOnTime
                            else if (isEnabled) MusicSheetFlowColors.CorrectOnTime.copy(alpha = 0.5f)
                            else MusicSheetFlowColors.CurrentNote
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(if (isEnabled) "🔊" else "🔇", fontSize = 12.sp)
                    Text("Metronome", fontSize = 10.sp,
                        color = if (isEnabled) MusicSheetFlowColors.CorrectOnTime else MusicSheetFlowColors.CurrentNote)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    for (i in 1..beatsPerMeasure.coerceAtMost(4)) {
                        Text(
                            if (beatClockState?.isRunning == true && beatInMeasure == i) "●" else "○",
                            fontSize = 12.sp,
                            color = if (beatClockState?.isRunning == true && beatInMeasure == i)
                                MusicSheetFlowColors.CorrectOnTime
                            else if (isEnabled) MusicSheetFlowColors.CorrectOnTime.copy(alpha = 0.5f)
                            else MusicSheetFlowColors.CurrentNote
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CountInChip(
    countInMeasures: Int,
    isPracticeMode: Boolean,
    isCountingIn: Boolean,
    onChange: (Int) -> Unit,
    compact: Boolean
) {
    Surface(
        modifier = Modifier.clickable(enabled = !isPracticeMode && !isCountingIn) {
            onChange(if (countInMeasures >= 4) 0 else countInMeasures + 1)
        },
        shape = RoundedCornerShape(8.dp),
        color = if (countInMeasures > 0) MusicSheetFlowColors.CurrentNote.copy(alpha = 0.2f)
                else MusicSheetFlowColors.CurrentNote.copy(alpha = 0.1f),
        border = if (countInMeasures > 0) androidx.compose.foundation.BorderStroke(1.dp, MusicSheetFlowColors.CurrentNote) else null
    ) {
        if (compact) {
            Row(
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    if (countInMeasures > 0) "$countInMeasures" else "–",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MusicSheetFlowColors.CurrentNote
                )
                Text("in", fontSize = 10.sp, color = MusicSheetFlowColors.CurrentNote.copy(alpha = 0.7f))
            }
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    if (countInMeasures > 0) "$countInMeasures" else "–",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MusicSheetFlowColors.CurrentNote
                )
                Text("Count-in", fontSize = 9.sp, color = MusicSheetFlowColors.CurrentNote.copy(alpha = 0.7f))
            }
        }
    }
}

@Composable
private fun NoteNamesChip(
    showNoteNames: Boolean,
    onToggle: () -> Unit,
    compact: Boolean
) {
    Surface(
        modifier = Modifier.clickable { onToggle() },
        shape = RoundedCornerShape(8.dp),
        color = if (showNoteNames) MusicSheetFlowColors.CorrectEarlyLate.copy(alpha = 0.2f)
                else MusicSheetFlowColors.CurrentNote.copy(alpha = 0.1f),
        border = if (showNoteNames) androidx.compose.foundation.BorderStroke(2.dp, MusicSheetFlowColors.CorrectEarlyLate) else null
    ) {
        if (compact) {
            Text(
                "ABC",
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = if (showNoteNames) MusicSheetFlowColors.CorrectEarlyLate else MusicSheetFlowColors.CurrentNote
            )
        } else {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    "ABC",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (showNoteNames) MusicSheetFlowColors.CorrectEarlyLate else MusicSheetFlowColors.CurrentNote
                )
                Text(
                    "Names",
                    fontSize = 9.sp,
                    color = if (showNoteNames) MusicSheetFlowColors.CorrectEarlyLate else MusicSheetFlowColors.CurrentNote
                )
            }
        }
    }
}

@Composable
fun PitchSettingsDialog(
    confidenceThreshold: Float,
    silenceThreshold: Float,
    noiseGateThreshold: Float,
    onConfidenceChange: (Float) -> Unit,
    onSilenceChange: (Float) -> Unit,
    onNoiseGateChange: (Float) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Pitch Detection Settings", fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Confidence threshold
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Confidence", fontSize = 14.sp)
                        Text(
                            "%.0f%%".format(confidenceThreshold * 100),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MusicSheetFlowColors.CurrentNote
                        )
                    }
                    Text(
                        "Lower = more detections (may have false positives)",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = confidenceThreshold,
                        onValueChange = onConfidenceChange,
                        valueRange = 0.1f..0.8f,
                        colors = SliderDefaults.colors(
                            thumbColor = MusicSheetFlowColors.CurrentNote,
                            activeTrackColor = MusicSheetFlowColors.CurrentNote
                        )
                    )
                }

                HorizontalDivider()

                // Silence threshold
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Silence", fontSize = 14.sp)
                        Text(
                            "%.0f dB".format(silenceThreshold),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MusicSheetFlowColors.CurrentNote
                        )
                    }
                    Text(
                        "Lower = more sensitive to quiet sounds",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = silenceThreshold,
                        onValueChange = onSilenceChange,
                        valueRange = -70f..-30f,
                        colors = SliderDefaults.colors(
                            thumbColor = MusicSheetFlowColors.CurrentNote,
                            activeTrackColor = MusicSheetFlowColors.CurrentNote
                        )
                    )
                }

                HorizontalDivider()

                // Noise gate threshold
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("Noise Gate", fontSize = 14.sp)
                        Text(
                            "%.0f dB".format(noiseGateThreshold),
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MusicSheetFlowColors.CurrentNote
                        )
                    }
                    Text(
                        "Lower = filters less background noise",
                        fontSize = 10.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = noiseGateThreshold,
                        onValueChange = onNoiseGateChange,
                        valueRange = -60f..-30f,
                        colors = SliderDefaults.colors(
                            thumbColor = MusicSheetFlowColors.CurrentNote,
                            activeTrackColor = MusicSheetFlowColors.CurrentNote
                        )
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}
