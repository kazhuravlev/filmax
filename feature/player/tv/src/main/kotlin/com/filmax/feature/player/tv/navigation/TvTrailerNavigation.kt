package com.filmax.feature.player.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.filmax.feature.player.common.navigation.TrailerRoute
import com.filmax.feature.player.tv.TvTrailerScreen

fun NavGraphBuilder.tvTrailerScreen(onBack: () -> Unit) {
    composable<TrailerRoute> { entry ->
        val route = entry.toRoute<TrailerRoute>()
        TvTrailerScreen(url = route.url, title = route.title, onBack = onBack)
    }
}
