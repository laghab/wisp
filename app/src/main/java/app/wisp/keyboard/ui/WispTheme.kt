package app.wisp.keyboard.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import app.wisp.keyboard.style.Brand

// The settings app wears the same colours as the keyboard (style/Brand.kt).
private fun c(argb: Int) = Color(argb)

private val Dark = darkColorScheme(
    primary = c(Brand.CORAL), onPrimary = Color(0xFF3A0E06), background = c(Brand.DARK.background), onBackground = c(Brand.DARK.text),
    surface = c(Brand.DARK.surface), onSurface = c(Brand.DARK.text), surfaceVariant = c(Brand.DARK.surfaceVariant), onSurfaceVariant = c(Brand.DARK.onSurfaceVariant),
    secondaryContainer = Color(0xFF5A3B3C), onSecondaryContainer = Color(0xFFFFDAD3), error = Color(0xFFFFB4A8),
)
private val Light = lightColorScheme(
    primary = Color(0xFFC0432F), onPrimary = Color.White, background = Color(0xFFFFF8F6), onBackground = c(Brand.INK),
    surface = c(Brand.LIGHT.surface), onSurface = c(Brand.INK), surfaceVariant = c(Brand.LIGHT.surfaceVariant), onSurfaceVariant = c(Brand.LIGHT.onSurfaceVariant),
    secondaryContainer = Color(0xFFFFDAD3), onSecondaryContainer = Color(0xFF410B03), error = Color(0xFFBA1A1A),
)

/** One family (the system's), headings a little heavier than body. */
private val Type = Typography(
    displaySmall = TextStyle(fontSize = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
    headlineMedium = TextStyle(fontSize = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.1.sp),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
)

@Composable
fun WispTheme(content: @Composable () -> Unit) =
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, typography = Type, content = content)
