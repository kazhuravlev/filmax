package com.filmax.feature.search.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.filmax.feature.search.common.navigation.FilmographyRoute
import com.filmax.feature.search.tv.TvFilmographyScreen

fun NavGraphBuilder.tvFilmographyScreen(onBack: () -> Unit, onOpenItem: (Int) -> Unit) {
    composable<FilmographyRoute> {
        TvFilmographyScreen(onBack = onBack, onOpenItem = onOpenItem)
    }
}
