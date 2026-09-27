package com.filmax.app.navigation

data class RootState(
    val isAuthenticated: Boolean? = null,
    val initials: String = "",
)

sealed interface RootEvent

sealed interface RootSideEffect
