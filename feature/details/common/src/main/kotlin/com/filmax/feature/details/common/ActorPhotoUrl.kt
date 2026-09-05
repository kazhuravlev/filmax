// Угадывание фото актёра на CDN kino.watch по имени (см. resolveCast в DetailsFormat.kt).
// Отдельный файл — иначе DetailsFormat.kt упирается в TooManyFunctions ради двух приватных
// хелперов одной узкой задачи.

package com.filmax.feature.details.common

/**
 * Угадывает адрес фото актёра на CDN kino.watch: `md5(имя).jpg`. Так делает референсный веб-клиент
 * kpapp.link — `MD5(unescape(encodeURIComponent(name)))` по одному имени из `cast.split(', ')`.
 * Для строк из чистого UTF-8 (без суррогатных пар/полусимволов) `unescape(encodeURIComponent(x))`
 * эквивалентно хешированию исходных UTF-8-байт — то есть ровно тому, что делает
 * `name.toByteArray(Charsets.UTF_8)` ниже. У kino.watch нет API с ID актёров или прямыми ссылками
 * на фото — только этот угаданный хеш, поэтому ссылка может не существовать (404) для части имён.
 *
 * Сам CDN (`m.staticpop.net`) — за прокси-воркером: напрямую с части сетей отдаёт не всегда
 * стабильно, а `kwip` отдаёт то же изображение надёжнее.
 */
internal fun actorPhotoUrl(name: String): String =
    "$IMAGE_PROXY_URL${staticpopActorUrl(name)}"

private fun staticpopActorUrl(name: String): String = "https://m.staticpop.net/actors/${md5Hex(name)}.jpg"

private const val IMAGE_PROXY_URL = "https://kwip.dev-services.workers.dev/img?url="

/** MD5 в виде строки из 32 hex-символов (нижний регистр) — формат имени файла на CDN kino.watch. */
private fun md5Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
