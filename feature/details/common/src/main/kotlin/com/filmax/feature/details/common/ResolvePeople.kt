
package com.filmax.feature.details.common

import com.filmax.core.domain.person.CastMember

fun resolveCast(cast: List<CastMember>, rawCast: String): List<CastMember> =
    cast.ifEmpty { guessedCastMembers(rawCast) }

fun resolveDirectors(rawDirector: String): List<CastMember> = guessedCastMembers(rawDirector)

private fun guessedCastMembers(rawNames: String): List<CastMember> =
    rawNames.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { name -> CastMember(name = name, character = null, photoUrl = actorPhotoUrl(name)) }
