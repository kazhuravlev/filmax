package com.filmax.feature.search.common.navigation

import kotlinx.serialization.Serializable

@Serializable
data class FilmographyRoute(val name: String, val isDirector: Boolean)
