package com.filmax.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable

@Composable
fun FilmaxTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = FilmaxDarkColorScheme,
        typography = FilmaxTypography,
        shapes = FilmaxShapes,
        content = content,
    )
}
