package spam.blocker.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import spam.blocker.G


@Composable
fun AppTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = G.palette.background, // bg of SettingsScreen
            surface = G.palette.background, // top/bottom systembar
        ),
        content = content,
    )
}