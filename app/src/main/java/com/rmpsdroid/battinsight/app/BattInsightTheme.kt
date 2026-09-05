package com.rmpsdroid.battinsight.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext

/**
 * The application's Material theme.
 *
 * Added in Phase 8, and it was a real gap rather than a refinement. The screens have always
 * read their colours from `MaterialTheme.colorScheme`, which is the correct thing to do -- but
 * nothing ever supplied a scheme, so Compose fell back to its built-in light default and the
 * app rendered light no matter what the device was set to. Verified on the emulator: with
 * `cmd uimode night yes` the app still came up light.
 *
 * Because every screen already used the colour scheme rather than literals, fixing it needed
 * this file and a wrapper call, and nothing else had to change. That is the payoff for not
 * hard-coding colours even while no dark theme existed.
 *
 * Dynamic colour, unconditionally. It arrived in Android 12 (API 31) and this project's floor
 * is API 33, so a version check here would be dead code -- lint says so, and it is right. The
 * app takes the user's wallpaper palette; inventing a brand palette for a diagnostics tool
 * would be effort spent on the part that matters least.
 */
@Composable
fun BattInsightTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = if (darkTheme) {
        dynamicDarkColorScheme(context)
    } else {
        dynamicLightColorScheme(context)
    }
    MaterialTheme(colorScheme = colors) {
        // The Surface is not decoration. Text that does not name a colour takes
        // LocalContentColor, and outside a Surface that defaults to black -- so in dark mode
        // the screen headings rendered near-black on a dark background while the Cards, which
        // supply their own content colour, looked correct. Measured on the emulator before
        // this was added: legible cards, an almost invisible title above them.
        //
        // The window insets padding is here for the same reason: without it the first heading
        // drew underneath the status bar clock.
        Surface(
            modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            color = MaterialTheme.colorScheme.background,
            content = content,
        )
    }
}
