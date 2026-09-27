package com.filmax.feature.player.common.navigation

import kotlinx.serialization.Serializable

@Serializable
data class PlayerRoute(
    val itemId: Int,
    val videoId: Int = -1,
    val season: Int = -1,
    val resumePositionSeconds: Int = 0,
)
