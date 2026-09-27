package com.filmax.feature.player.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.filmax.feature.player.common.navigation.PlayerRoute
import com.filmax.feature.player.tv.TvPlayerScreen

fun NavGraphBuilder.tvPlayerScreen(
    onBack: () -> Unit,
    onPlayEpisode: ((itemId: Int, season: Int, videoId: Int) -> Unit)? = null,
) {
    composable<PlayerRoute> { entry ->
        val route = entry.toRoute<PlayerRoute>()

        fun playEpisode(season: Int, videoId: Int) {
            onPlayEpisode?.invoke(route.itemId, season, videoId)
        }
        TvPlayerScreen(
            onBack = onBack,
            onPlayEpisode = onPlayEpisode?.let { ::playEpisode },
        )
    }
}
