package net.tigr.musicsheetflow.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import net.tigr.musicsheetflow.score.ScoreInfo
import net.tigr.musicsheetflow.score.ScoreRepository
import net.tigr.musicsheetflow.ui.theme.MusicSheetFlowColors

/**
 * Library screen showing available scores for selection.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    scoreRepository: ScoreRepository,
    onScoreSelected: (String, Boolean) -> Unit,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val keyboardController = LocalSoftwareKeyboardController.current
    var scores by remember { mutableStateOf<List<ScoreInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var searchQuery by remember { mutableStateOf("") }
    var retryTrigger by remember { mutableStateOf(0) }
    var isImporting by remember { mutableStateOf(false) }
    var importError by remember { mutableStateOf<String?>(null) }

    val snackbarHostState = remember { SnackbarHostState() }

    // File picker launcher
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            isImporting = true
            importError = null
            scope.launch {
                val filename = scoreRepository.importScore(context, uri)
                isImporting = false
                if (filename != null) {
                    retryTrigger++  // Refresh the list
                    snackbarHostState.showSnackbar("Score imported successfully")
                } else {
                    importError = "Failed to import score. Please check the file format."
                    snackbarHostState.showSnackbar("Failed to import score")
                }
            }
        }
    }

    // Load scores on first composition or retry
    LaunchedEffect(retryTrigger) {
        isLoading = true
        try {
            scores = scoreRepository.getAvailableScoresWithMetadata(context)
            loadError = null
        } catch (e: Exception) {
            android.util.Log.e("LibraryScreen", "Failed to load scores", e)
            loadError = "Unable to load score library. Please try again."
        } finally {
            isLoading = false
        }
    }

    // Filter scores by search query
    val filteredScores = remember(scores, searchQuery) {
        if (searchQuery.isBlank()) {
            scores
        } else {
            scores.filter { score ->
                score.displayName.contains(searchQuery, ignoreCase = true) ||
                score.composer.contains(searchQuery, ignoreCase = true)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Score Library") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MusicSheetFlowColors.CurrentNote,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    if (!isImporting) {
                        filePickerLauncher.launch(
                            arrayOf(
                                "application/vnd.recordare.musicxml+xml",
                                "application/vnd.recordare.musicxml",
                                "application/xml",
                                "text/xml",
                                "*/*"
                            )
                        )
                    }
                },
                containerColor = if (isImporting)
                    MusicSheetFlowColors.CurrentNote.copy(alpha = 0.6f)
                else
                    MusicSheetFlowColors.CurrentNote,
                contentColor = Color.White
            ) {
                if (isImporting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color.White,
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(Icons.Default.Add, contentDescription = "Import score")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Search bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                placeholder = { Text("Search by title or composer...") },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { keyboardController?.hide() }
                ),
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                Icons.Default.Clear,
                                contentDescription = "Clear search"
                            )
                        }
                    }
                }
            )

            // Score count
            Text(
                text = "${filteredScores.size} scores available",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                fontSize = 14.sp,
                color = Color.Gray
            )

            // Score list
            if (isLoading) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MusicSheetFlowColors.CurrentNote)
                }
            } else if (loadError != null) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = loadError ?: "Unknown error",
                            color = MusicSheetFlowColors.WrongPitch
                        )
                        TextButton(onClick = { retryTrigger++ }) {
                            Text("Retry")
                        }
                    }
                }
            } else if (filteredScores.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isBlank()) "No scores found" else "No matching scores",
                        color = Color.Gray
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(filteredScores) { score ->
                        ScoreListItem(
                            score = score,
                            onClick = { onScoreSelected(score.filename, score.isImported) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScoreListItem(
    score: ScoreInfo,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Music note icon
            Surface(
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(8.dp),
                color = if (score.isImported)
                    MusicSheetFlowColors.CorrectOnTime.copy(alpha = 0.1f)
                else
                    MusicSheetFlowColors.CurrentNote.copy(alpha = 0.1f),
                contentColor = if (score.isImported)
                    MusicSheetFlowColors.CorrectOnTime
                else
                    MusicSheetFlowColors.CurrentNote
            ) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (score.isImported) "📁" else "♪",
                        fontSize = 24.sp,
                        color = if (score.isImported)
                            MusicSheetFlowColors.CorrectOnTime
                        else
                            MusicSheetFlowColors.CurrentNote
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            // Score info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = score.displayName,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (score.composer.isNotEmpty()) {
                    Text(
                        text = score.composer,
                        fontSize = 14.sp,
                        color = Color.Gray,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "${score.measureCount} measures",
                        fontSize = 12.sp,
                        color = MusicSheetFlowColors.CurrentNote
                    )
                    Text(
                        text = "${score.noteCount} notes",
                        fontSize = 12.sp,
                        color = MusicSheetFlowColors.CorrectOnTime
                    )
                }
            }
        }
    }
}
