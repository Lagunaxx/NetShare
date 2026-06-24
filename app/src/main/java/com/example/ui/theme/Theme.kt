package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = GeoPrimary,
    secondary = GeoTextSecondary,
    tertiary = GeoTextTertiary,
    background = Color(0xFF111411),
    surface = Color(0xFF191C19),
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = GeoBackground,
    onSurface = GeoBackground,
    surfaceVariant = Color(0xFF222622),
    onSurfaceVariant = GeoBackground,
    outline = GeoBorderDark
)

private val LightColorScheme = lightColorScheme(
    primary = GeoPrimary,
    secondary = GeoTextSecondary,
    tertiary = GeoTextTertiary,
    background = GeoBackground,
    surface = Color.White,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onBackground = GeoTextPrimary,
    onSurface = GeoTextPrimary,
    surfaceVariant = GeoSurfaceGreen,
    onSurfaceVariant = GeoTextPrimary,
    outline = GeoBorder,
    outlineVariant = GeoBorderMuted
)

@Composable
fun MyApplicationTheme(
  darkTheme: Boolean = isSystemInDarkTheme(),
  // Set to false by default to showcase the precise Geometric Balance theme
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colorScheme =
    when {
      dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
        val context = LocalContext.current
        if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
      }

      darkTheme -> DarkColorScheme
      else -> LightColorScheme
    }

  MaterialTheme(colorScheme = colorScheme, typography = Typography, content = content)
}
