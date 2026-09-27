package com.filmax.feature.search.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.filmax.feature.search.tv.TvCatalogScreen
import kotlinx.serialization.Serializable

@Serializable
object TvSearchRoute

fun NavGraphBuilder.tvSearchScreen(onOpenItem: (Int) -> Unit) {
    composable<TvSearchRoute> {
        TvCatalogScreen(onOpenItem = onOpenItem)
    }
}
