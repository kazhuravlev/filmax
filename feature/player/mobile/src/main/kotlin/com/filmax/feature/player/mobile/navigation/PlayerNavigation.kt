package com.filmax.feature.player.mobile.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.filmax.feature.player.common.navigation.PlayerRoute
import com.filmax.feature.player.mobile.PlayerScreen

/**
 * Мобильный плеер на том же маршруте [PlayerRoute], что и TV-фича.
 *
 * `itemId` экран получает отсюда, а не из состояния: тайтл нужен ему, чтобы попросить
 * следующую серию, а модель держит маршрут у себя (SavedStateHandle).
 */
fun NavGraphBuilder.playerScreen() {
    composable<PlayerRoute> { entry ->
        val route = entry.toRoute<PlayerRoute>()
        PlayerScreen(itemId = route.itemId)
    }
}
