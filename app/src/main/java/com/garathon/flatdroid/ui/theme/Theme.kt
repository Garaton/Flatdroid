package com.garathon.flatdroid.ui.theme

import android.app.Activity
import android.app.ActivityManager
import androidx.activity.compose.LocalActivity
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.graphics.toArgb
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import com.garathon.flatdroid.settings.InterfaceTheme
import com.garathon.flatdroid.settings.LiveSettings
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = Green80,
    secondary = GreenGrey80,
    tertiary = Yellow80,
)

private val LightColorScheme = lightColorScheme(
    primary = Green40,
    secondary = GreenGrey40,
    tertiary = Yellow40,
)

@Composable
fun FlatdroidTheme(
    theme: InterfaceTheme = LiveSettings.theme.collectAsState().value,
    // Dynamic color is available on Android 12+
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val darkTheme = theme.isDark(isSystemInDarkTheme())
    val pureBlack = theme == InterfaceTheme.AMOLED
    val scheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = if (darkTheme && pureBlack) {
        scheme.copy(
            background = Color.Black, surface = Color.Black, surfaceDim = Color.Black,
            surfaceContainerLowest = Color.Black, surfaceContainerLow = Color.Black, surfaceContainer = Color.Black,
        )
    } else {
        scheme
    }

    // Recent apps draw a hidden screen (Hide Flatdroid's screen) as the task's background colour:
    // the one shown now, not the XML theme's (settable from Android 13).
    val activity = LocalActivity.current
    val background = colorScheme.background.toArgb()
    LaunchedEffect(background) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            activity?.setTaskDescription(ActivityManager.TaskDescription.Builder().setBackgroundColor(background).build())
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}