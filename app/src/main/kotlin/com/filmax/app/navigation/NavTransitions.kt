package com.filmax.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn

internal const val NAV_ENTER_MS = 180
internal const val NAV_EXIT_MS = 90
private const val NAV_ENTER_SCALE = 0.98f

val navEnter: EnterTransition =
    fadeIn(tween(NAV_ENTER_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(NAV_ENTER_MS, easing = LinearOutSlowInEasing), initialScale = NAV_ENTER_SCALE)

val navExit: ExitTransition = fadeOut(tween(NAV_EXIT_MS, easing = FastOutLinearInEasing))
