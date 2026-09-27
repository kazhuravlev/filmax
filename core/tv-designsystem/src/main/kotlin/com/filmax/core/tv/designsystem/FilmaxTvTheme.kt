@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.filmax.core.tv.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.compose.material3.MaterialTheme as ComposeMaterialTheme
import androidx.tv.material3.MaterialTheme as TvMaterialTheme
import androidx.tv.material3.darkColorScheme as tvDarkColorScheme

@Composable
fun FilmaxTvTheme(content: @Composable () -> Unit) {
    val composeScheme = darkColorScheme(
        primary = TvAccent,
        onPrimary = TvOnAccent,
        primaryContainer = TvSurfaceContainerHigh,
        onPrimaryContainer = TvOnSurface,
        surface = TvSurface,
        onSurface = TvOnSurface,
        background = TvSurface,
        onBackground = TvOnSurface,
        surfaceContainer = TvSurfaceContainer,
        surfaceContainerHigh = TvSurfaceContainerHigh,
        surfaceContainerHighest = TvSurfaceContainerHighest,
        onSurfaceVariant = TvOnSurfaceVariant,
        outline = TvSurfaceContainerHighest,
        outlineVariant = TvOutlineVariant,
        error = TvError,
        errorContainer = TvErrorContainer,
    )

    val tvScheme = tvDarkColorScheme(
        primary = TvAccent,
        onPrimary = TvOnAccent,
        primaryContainer = TvSurfaceContainerHigh,
        onPrimaryContainer = TvOnSurface,
        surface = TvSurface,
        onSurface = TvOnSurface,
        surfaceVariant = TvSurfaceContainerHigh,
        onSurfaceVariant = TvOnSurfaceVariant,
        background = TvSurface,
        onBackground = TvOnSurface,
        border = TvFocus,
        error = TvError,
        errorContainer = TvErrorContainer,
    )

    ComposeMaterialTheme(
        colorScheme = composeScheme,
        typography = FilmaxTvTypography,
        shapes = FilmaxTvShapes,
    ) {
        TvMaterialTheme(colorScheme = tvScheme) {
            content()
        }
    }
}
