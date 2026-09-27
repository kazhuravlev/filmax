package com.filmax.feature.collections.tv.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.filmax.feature.collections.common.navigation.CollectionDetailRoute
import com.filmax.feature.collections.tv.TvCollectionDetailScreen

fun NavGraphBuilder.tvCollectionDetailScreen(onOpenItem: (Int) -> Unit) {
    composable<CollectionDetailRoute> { entry ->
        val route = entry.toRoute<CollectionDetailRoute>()
        TvCollectionDetailScreen(title = route.title, onOpenItem = onOpenItem)
    }
}
