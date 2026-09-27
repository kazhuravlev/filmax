package com.filmax.feature.collections.common.navigation

import kotlinx.serialization.Serializable

@Serializable
data class CollectionDetailRoute(val collectionId: Int, val title: String)
