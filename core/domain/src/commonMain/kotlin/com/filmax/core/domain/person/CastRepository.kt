package com.filmax.core.domain.person

data class CastMember(
    val name: String,
    val character: String?,
    val photoUrl: String?,
)

interface CastRepository {
    suspend fun getCast(imdbId: String?): List<CastMember>
}
