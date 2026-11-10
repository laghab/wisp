package app.wisp.keyboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import android.app.Activity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import app.wisp.keyboard.settings.WispSettings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.wisp.keyboard.R

// The settings app wears the Claude Design palette: the Midnight keyboard theme in the dark, the Modern one in the light. Voice and rewrite use amber.
private val Dark = darkColorScheme(
    primary = Color(0xFF8FA8FF), onPrimary = Color(0xFF0E1116), primaryContainer = Color(0xFF1A2033), onPrimaryContainer = Color(0xFFB5C5FF),
    secondary = Color(0xFFFFB86B), onSecondary = Color(0xFF1A1206), secondaryContainer = Color(0xFF2A2418), onSecondaryContainer = Color(0xFFFFCF99),
    tertiary = Color(0xFF6FD3A6), onTertiary = Color(0xFF0E1116),
    background = Color(0xFF0E1116), onBackground = Color(0xFFE9EDF4),
    surface = Color(0xFF151920), onSurface = Color(0xFFE9EDF4), surfaceVariant = Color(0xFF1B1F27), onSurfaceVariant = Color(0xFFA3ACBA),
    surfaceContainerLowest = Color(0xFF0E1116), surfaceContainerLow = Color(0xFF12151B), surfaceContainer = Color(0xFF151920), surfaceContainerHigh = Color(0xFF1B1F27), surfaceContainerHighest = Color(0xFF1B1F27),
    outline = Color(0xFF3A4253), outlineVariant = Color(0xFF232934), error = Color(0xFFF28D8D), onError = Color(0xFF0E1116),
)
private val Light = lightColorScheme(
    primary = Color(0xFF3B5BFF), onPrimary = Color.White, primaryContainer = Color(0xFFE1E6FF), onPrimaryContainer = Color(0xFF0E1A66),
    secondary = Color(0xFF8A5A00), onSecondary = Color.White, secondaryContainer = Color(0xFFFFE9CC), onSecondaryContainer = Color(0xFF4A2F00),
    tertiary = Color(0xFF1F7A56), onTertiary = Color.White,
    background = Color(0xFFF3F3F5), onBackground = Color(0xFF111216),
    surface = Color.White, onSurface = Color(0xFF111216), surfaceVariant = Color(0xFFE9EAF0), onSurfaceVariant = Color(0xFF4A4D57),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF8F8FA), surfaceContainer = Color.White, surfaceContainerHigh = Color(0xFFEDEEF3), surfaceContainerHighest = Color(0xFFE9EAF0),
    outline = Color(0xFF9AA0AE), outlineVariant = Color(0xFFD5D7DE), error = Color(0xFFBA1A1A), onError = Color.White,
)

private val Heading = FontFamily(Font(R.font.space_grotesk_w700, FontWeight.Bold), Font(R.font.space_grotesk_w700, FontWeight.SemiBold))
private val Body = FontFamily(Font(R.font.inter_w400, FontWeight.Normal), Font(R.font.inter_w500, FontWeight.Medium), Font(R.font.inter_w500, FontWeight.SemiBold), Font(R.font.inter_w500, FontWeight.Bold))

/** Space Grotesk for headings, Inter for everything else, as in the handoff. */
private val Type = Typography(
    displaySmall = TextStyle(fontFamily = Heading, fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontFamily = Heading, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleLarge = TextStyle(fontFamily = Heading, fontSize = 22.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontFamily = Body, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    titleSmall = TextStyle(fontFamily = Body, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp),
    labelLarge = TextStyle(fontFamily = Body, fontSize = 14.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = Body, fontSize = 12.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontFamily = Body, fontSize = 16.sp),
    bodyMedium = TextStyle(fontFamily = Body, fontSize = 14.sp),
    bodySmall = TextStyle(fontFamily = Body, fontSize = 12.sp),
)

/** True black surfaces for OLED screens. */
private val Black = Dark.copy(
    background = Color.Black, surface = Color.Black, surfaceVariant = Color(0xFF14161B),
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Color(0xFF07080A), surfaceContainer = Color(0xFF0B0D10), surfaceContainerHigh = Color(0xFF14161B), surfaceContainerHighest = Color(0xFF14161B),
)

/** How the settings app looks: follow the phone, or always light, dark or black. Kept in the settings file and read once. */
object AppAppearance {
    val options = listOf("system" to "System", "light" to "Light", "dark" to "Dark", "black" to "Black")
    private var mode by mutableStateOf<String?>(null)
    fun current(context: android.content.Context): String =
        mode ?: (WispSettings.get(context).getString("app_theme", "system") ?: "system").also { mode = it }
    fun set(context: android.content.Context, value: String) { mode = value; WispSettings.get(context).putString("app_theme", value) }
}

@Composable
fun WispTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val mode = AppAppearance.current(context)
    val dark = when (mode) { "light" -> false; "dark", "black" -> true; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    SideEffect {   // status and navigation bar icons stay readable on the chosen background
        (view.context as? Activity)?.window?.let { w -> WindowCompat.getInsetsController(w, view).apply { isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark } }
    }
    MaterialTheme(colorScheme = if (!dark) Light else if (mode == "black") Black else Dark, typography = Type, content = content)
}
