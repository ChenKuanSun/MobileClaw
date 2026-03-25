package ai.affiora.mobileclaw.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val PrimaryBlue = Color(0xFF1A73E8)
private val PrimaryBlueLight = Color(0xFF4A9AF5)
private val PrimaryBlueDark = Color(0xFF1565C0)
private val SecondaryTeal = Color(0xFF00897B)
private val SecondaryTealLight = Color(0xFF4DB6AC)
private val SecondaryTealDark = Color(0xFF00695C)
private val ErrorRed = Color(0xFFD32F2F)
private val ErrorRedLight = Color(0xFFEF5350)

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueLight,
    onPrimary = Color.White,
    primaryContainer = PrimaryBlueDark,
    onPrimaryContainer = Color(0xFFD6E3FF),
    secondary = SecondaryTealLight,
    onSecondary = Color.White,
    secondaryContainer = SecondaryTealDark,
    onSecondaryContainer = Color(0xFFA7F3EC),
    background = Color(0xFF0F1419),
    onBackground = Color(0xFFE2E2E6),
    surface = Color(0xFF1A1F25),
    onSurface = Color(0xFFE2E2E6),
    surfaceVariant = Color(0xFF2C333A),
    onSurfaceVariant = Color(0xFFC2C8CE),
    error = ErrorRedLight,
    onError = Color.White,
    outline = Color(0xFF8C9198),
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = PrimaryBlueDark,
    secondary = SecondaryTeal,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFA7F3EC),
    onSecondaryContainer = SecondaryTealDark,
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE7E8EC),
    onSurfaceVariant = Color(0xFF44474E),
    error = ErrorRed,
    onError = Color.White,
    outline = Color(0xFF74777F),
)

private val AppTypography = Typography()

@Composable
fun MobileClawTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        content = content,
    )
}
