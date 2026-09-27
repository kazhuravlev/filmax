package com.filmax.app.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn

/**
 * Переходы между экранами TV-графа. Не симметричный кроссфейд, а «уйти сразу — прийти быстро»:
 *
 *  - уход старого экрана — короткий fade [NAV_EXIT_MS]: отклик на нажатие должен быть виден в
 *    первые же кадры, а старый экран, который ещё полсекунды тает под новым, читается как
 *    задержка, даже если новый уже нарисован;
 *  - приход нового — fade [NAV_ENTER_MS] с лёгким scale от [NAV_ENTER_SCALE] к 1 (decelerate):
 *    движение «на зрителя» глаз считывает как быстрый отклик, а не как затемнение, и оно
 *    короче, чем прежний кроссфейд, вдвое.
 *
 * Раньше здесь был симметричный кроссфейд 350 мс: плавно, но каждое переключение вкладки и
 * каждое открытие карточки ощущались с лагом.
 */
internal const val NAV_ENTER_MS = 180
internal const val NAV_EXIT_MS = 90
private const val NAV_ENTER_SCALE = 0.98f

val navEnter: EnterTransition =
    fadeIn(tween(NAV_ENTER_MS, easing = LinearOutSlowInEasing)) +
        scaleIn(tween(NAV_ENTER_MS, easing = LinearOutSlowInEasing), initialScale = NAV_ENTER_SCALE)

val navExit: ExitTransition = fadeOut(tween(NAV_EXIT_MS, easing = FastOutLinearInEasing))
