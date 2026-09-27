package com.filmax.data.tmdb.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
internal data class TmdbFindDto(
    @SerialName("movie_results") val movieResults: List<TmdbRefDto> = emptyList(),
    @SerialName("tv_results") val tvResults: List<TmdbRefDto> = emptyList(),
)

@Serializable
internal data class TmdbRefDto(val id: Int)

@Serializable
internal data class TmdbCreditsDto(val cast: List<TmdbCastDto> = emptyList())

@Serializable
internal data class TmdbCastDto(
    val name: String = "",
    val character: String? = null,
    @SerialName("profile_path") val profilePath: String? = null,
)
