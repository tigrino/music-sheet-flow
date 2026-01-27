package net.tigr.musicsheetflow.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// App color palette from requirements
object MusicSheetFlowColors {
    val CurrentNote = Color(0xFF2196F3)      // Blue - note currently expected
    val CorrectOnTime = Color(0xFF4CAF50)    // Green - correct pitch, good timing
    val CorrectEarlyLate = Color(0xFFFFEB3B) // Yellow - correct pitch, timing off
    val WrongPitch = Color(0xFFF44336)       // Red - wrong pitch attempted
    val Skipped = Color(0xFF9E9E9E)          // Gray - note manually skipped
    val Upcoming = Color(0xFF000000)         // Black - notes not yet reached
    val Played = Color(0xFF81C784)           // Light Green - previously played
    val PageTurn = Color(0xFFFF9800)         // Orange - page turn zone
    val ScoreBackground = Color(0xFFFFFEF8) // Warm white for score
}

private val LightColorScheme = lightColorScheme(
    primary = MusicSheetFlowColors.CurrentNote,
    secondary = MusicSheetFlowColors.CorrectOnTime,
    tertiary = MusicSheetFlowColors.PageTurn,
    background = Color(0xFFFFFBFE),
    surface = Color(0xFFFFFBFE),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFF1C1B1F),
    onSurface = Color(0xFF1C1B1F),
)

private val DarkColorScheme = darkColorScheme(
    primary = MusicSheetFlowColors.CurrentNote,
    secondary = MusicSheetFlowColors.CorrectOnTime,
    tertiary = MusicSheetFlowColors.PageTurn,
    background = Color(0xFF1C1B1F),
    surface = Color(0xFF1C1B1F),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = Color(0xFFE6E1E5),
    onSurface = Color(0xFFE6E1E5),
)

@Composable
fun MusicSheetFlowTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        content = content
    )
}
