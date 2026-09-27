package com.filmax.app.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController

@Composable
internal fun AuthStateNavigation(
    isAuthenticated: Boolean?,
    navController: NavHostController,
    homeRoute: Any,
    onboardingRoute: Any,
) {
    LaunchedEffect(isAuthenticated) {
        val authenticated = isAuthenticated ?: return@LaunchedEffect
        navController.navigate(if (authenticated) homeRoute else onboardingRoute) {
            popUpTo(0) { inclusive = true }
            launchSingleTop = true
        }
    }
}
