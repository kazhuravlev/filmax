package com.filmax.core.designsystem

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color

val FilmaxSurface = Color(0xFF0A0A0A)
val FilmaxSurfaceDim = Color(0xFF0A0A0A)
val FilmaxSurfaceBright = Color(0xFF2E2E2E)
val FilmaxSurfaceContainerLowest = Color(0xFF050505)
val FilmaxSurfaceContainerLow = Color(0xFF111111)

val FilmaxSurfaceContainer = Color(0xFF141414)

val FilmaxSurfaceContainerHigh = Color(0xFF1F1F1F)

val FilmaxSurfaceContainerHighest = Color(0xFF2E2E2E)

val FilmaxOnSurface = Color(0xFFE8E8E8)

val FilmaxOnSurfaceVariant = Color(0xFFA0A0A0)

val FilmaxOnSurfaceDim = Color(0xFF8A8A8A)

val FilmaxOutline = Color(0xFF5A5A5A)
val FilmaxOutlineVariant = Color(0xFF1F1F1F)
val FilmaxInverseSurface = Color(0xFFE8E8E8)
val FilmaxInverseOnSurface = Color(0xFF141414)

val FilmaxAccent = Color(0xFFFFFFFF)

val FilmaxOnAccent = Color(0xFF0A0A0A)

val FilmaxError = Color(0xFFE0736B)
val FilmaxOnError = Color(0xFF3A1512)
val FilmaxErrorContainer = Color(0xFF3A1512)
val FilmaxOnErrorContainer = Color(0xFFFFDAD6)

val FilmaxDarkColorScheme = darkColorScheme(
    primary = FilmaxAccent,
    onPrimary = FilmaxOnAccent,
    primaryContainer = FilmaxSurfaceContainerHigh,
    onPrimaryContainer = FilmaxOnSurface,
    inversePrimary = FilmaxSurfaceContainerHighest,
    secondary = FilmaxOnSurfaceVariant,
    onSecondary = FilmaxOnAccent,
    secondaryContainer = FilmaxSurfaceContainerHigh,
    onSecondaryContainer = FilmaxOnSurface,
    tertiary = FilmaxOnSurfaceVariant,
    onTertiary = FilmaxOnAccent,
    tertiaryContainer = FilmaxSurfaceContainerHigh,
    onTertiaryContainer = FilmaxOnSurface,
    error = FilmaxError,
    onError = FilmaxOnError,
    errorContainer = FilmaxErrorContainer,
    onErrorContainer = FilmaxOnErrorContainer,
    surface = FilmaxSurface,
    onSurface = FilmaxOnSurface,
    surfaceVariant = FilmaxSurfaceContainerHigh,
    onSurfaceVariant = FilmaxOnSurfaceVariant,
    surfaceTint = FilmaxAccent,
    inverseSurface = FilmaxInverseSurface,
    inverseOnSurface = FilmaxInverseOnSurface,
    outline = FilmaxOutline,
    outlineVariant = FilmaxOutlineVariant,
    background = FilmaxSurface,
    onBackground = FilmaxOnSurface,
    surfaceBright = FilmaxSurfaceBright,
    surfaceDim = FilmaxSurfaceDim,
    surfaceContainerLowest = FilmaxSurfaceContainerLowest,
    surfaceContainerLow = FilmaxSurfaceContainerLow,
    surfaceContainer = FilmaxSurfaceContainer,
    surfaceContainerHigh = FilmaxSurfaceContainerHigh,
    surfaceContainerHighest = FilmaxSurfaceContainerHighest,
    scrim = Color(0xFF000000),
)
