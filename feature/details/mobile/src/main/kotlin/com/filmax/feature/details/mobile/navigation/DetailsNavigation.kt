package com.filmax.feature.details.mobile.navigation

import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.filmax.feature.details.common.navigation.DetailsRoute
import com.filmax.feature.details.mobile.DetailsScreen

fun NavGraphBuilder.detailsScreen() {
    composable<DetailsRoute> {
        DetailsScreen()
    }
}
