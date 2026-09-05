// Люди для секций «Актёры»/«Режиссёр» экрана деталей. Отдельный файл — иначе DetailsFormat.kt
// упирается в TooManyFunctions ради узкой задачи (тот же приём, что и в ActorPhotoUrl.kt).

package com.filmax.feature.details.common

import com.filmax.core.domain.person.CastMember

/**
 * Люди для секции «Актёры»: если фото из TMDB доехали — берём их (с ролями), иначе строим карточки
 * из строки имён kino.watch (`item.cast`, имена через запятую) и пробуем угадать фото на CDN
 * kino.watch ([actorPhotoUrl]). Так каст кликабелен всегда, а фото — приятное дополнение: угаданная
 * ссылка есть не у каждого актёра (бывает честный 404), поэтому рендер обязан сам откатываться на
 * инициалы при ошибке загрузки — см. `TvActorCard`.
 */
fun resolveCast(cast: List<CastMember>, rawCast: String): List<CastMember> =
    cast.ifEmpty { guessedCastMembers(rawCast) }

/**
 * Люди для секции «Режиссёр»: у kino.watch это та же строка имён через запятую, что и `cast`
 * (`item.director`), а не отдельный объект — TMDB тут не подключаем: `credits` отдаёт только
 * `cast`, без `crew`, угадать реального режиссёра было бы не из чего. Поэтому те же карточки, что
 * и у актёров, с тем же угаданным фото на CDN kino.watch.
 */
fun resolveDirectors(rawDirector: String): List<CastMember> = guessedCastMembers(rawDirector)

private fun guessedCastMembers(rawNames: String): List<CastMember> =
    rawNames.split(",")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .map { name -> CastMember(name = name, character = null, photoUrl = actorPhotoUrl(name)) }
