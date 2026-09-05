// Мапперы DTO->domain для всего каталога держим одним файлом ради единообразия форматов
// (posters/duration/pagination и т.п. переиспользуются между Item/Collection) — дробить ради
// лимита незачем, т.к. добавление toDomainOnly (кэш-хит без побочных эффектов) — тот же маппинг
// ItemDto, а не новая обязанность файла.
@file:Suppress("TooManyFunctions")

package com.filmax.data.catalog.mapper

import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ItemDetailsCacheAccess
import com.filmax.core.domain.cache.PrefetchImage
import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.catalog.model.CollectionPage
import com.filmax.core.domain.catalog.model.Country
import com.filmax.core.domain.catalog.model.Duration
import com.filmax.core.domain.catalog.model.Genre
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemPage
import com.filmax.core.domain.catalog.model.ItemRating
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.Pagination
import com.filmax.core.domain.catalog.model.Posters
import com.filmax.core.network.networkJson
import com.filmax.data.catalog.remote.dto.CollectionDto
import com.filmax.data.catalog.remote.dto.CollectionItemsDto
import com.filmax.data.catalog.remote.dto.CountryDto
import com.filmax.data.catalog.remote.dto.DurationDto
import com.filmax.data.catalog.remote.dto.GenreDto
import com.filmax.data.catalog.remote.dto.ItemDto
import com.filmax.data.catalog.remote.dto.ItemsResponseDto
import com.filmax.data.catalog.remote.dto.PaginationDto
import com.filmax.data.catalog.remote.dto.PostersDto
import kotlinx.serialization.encodeToString

// Размер страницы по умолчанию для фолбэка пагинации, когда API не вернул блок pagination.
private const val DEFAULT_PER_PAGE = 20

// API отдаёт длительность в секундах — делим на это число, чтобы получить минуты.
private const val SECONDS_PER_MINUTE = 60

fun ItemsResponseDto.toDomain(): ItemPage = ItemPage(
    items = items.map { it.toDomain() },
    pagination = pagination?.toDomain() ?: Pagination(0, 1, DEFAULT_PER_PAGE),
)

/** Ключ кэша тайтла (`items/{id}`) — см. [ItemDetailsCacheAccess]. */
internal fun itemCacheKey(id: Int): String = "item:$id"

/** Ключ кэша списка «похожих» на тайтл (`items/similar?id=`) — см. `CatalogRepositoryImpl.getSimilarItems`. */
internal fun similarCacheKey(id: Int): String = "similar:$id"

/**
 * Полный маппинг + «заявки» в фоновые кэши: любой тайтл, прошедший через API (список, поиск,
 * похожее, детали) — кандидат на фоновую закачку постера и на кэш статической информации, даже
 * если экран его ещё не отрисовал. Единственное место, где эти два побочных эффекта запускаются —
 * повторный вызов [toDomainOnly] (кэш-хит в `CatalogRepositoryImpl`) их МИНУЕТ, иначе TTL кэша
 * тайтлов растягивался бы на каждый повторный просмотр вместо честного отсчёта от момента
 * реальной закачки с сервера.
 */
fun ItemDto.toDomain(): Item {
    val item = toDomainOnly()
    ItemDetailsCacheAccess.cache.remember(itemCacheKey(id), networkJson.encodeToString(this))
    ImageDiscovery.discovered(item.posterPrefetchImages())
    return item
}

/** Тот же маппинг, но без побочных эффектов — для чтения уже закэшированного тайтла. */
internal fun ItemDto.toDomainOnly(): Item = Item(
    id = id,
    title = title,
    type = ItemType.from(type),
    year = year,
    plot = plot,
    director = director,
    cast = cast,
    country = countries.firstOrNull()?.title ?: "",
    genres = genres.map { it.toDomain() },
    rating = ItemRating(
        filmax = rating,
        filmaxPercentage = ratingPercentage.toString(),
        // API отдаёт оценки числом или null; `null?.toString()` дал бы строку "null",
        // поэтому маппим только реальные значения, отсутствие — настоящий null.
        imdb = imdbRating?.toString(),
        kinopoisk = kinopoiskRating?.toString(),
    ),
    posters = posters?.toDomain() ?: Posters("", "", "", null),
    duration = duration.toDomain(),
    // Сериал: эпизоды лежат в seasons[].episodes (номер сезона — на родителе). Фильм: в videos.
    tracklist = if (!seasons.isNullOrEmpty()) {
        seasons.flatMap { season -> season.episodes.map { it.toDomain(season.number) } }
    } else {
        videos?.map { it.toDomain() } ?: emptyList()
    },
    trailer = trailer?.toDomain(),
    inWatchlist = inWatchlist,
    finished = finished,
    imdbId = imdb?.toString(),
    views = views,
    advert = advert,
)

private fun Item.posterPrefetchImages(): List<PrefetchImage> = buildList {
    val type = type.apiValue
    posters.medium.takeIf { it.isNotBlank() }?.let { url ->
        add(PrefetchImage(ImageCacheKeys.poster(type, id, ImageCacheKeys.SIZE_MEDIUM), url))
    }
    val backdrop = posters.wide ?: posters.big.takeIf { it.isNotBlank() }
    if (backdrop != null) {
        val subId = if (backdrop == posters.wide) ImageCacheKeys.WALL else ImageCacheKeys.SIZE_BIG
        add(PrefetchImage(ImageCacheKeys.poster(type, id, subId), backdrop))
    }
}

fun GenreDto.toDomain() = Genre(id = id, title = title, type = type)

fun CountryDto.toDomain() = Country(id = id, title = title)

// wide остаётся null, а не "": экраны фолбэчат `wide ?: big` (HeroBackdrop, TvHomeScreen и т.д.) —
// с пустой строкой вместо null этот `?:` никогда бы не срабатывал, и при отсутствии широкого
// кадра герой получал бы пустой url вместо постера.
fun PostersDto?.toDomain() = Posters(
    small = this?.small ?: "",
    medium = this?.medium ?: "",
    big = this?.big ?: "",
    wide = this?.wide,
)

// API отдаёт длительность в секундах — переводим в минуты.
fun DurationDto.toDomain() = Duration(
    averageMinutes = average?.let { it / SECONDS_PER_MINUTE },
    totalMinutes = total?.let { it / SECONDS_PER_MINUTE },
)

fun PaginationDto.toDomain() = Pagination(
    total = total,
    current = current,
    perPage = perPage,
)

fun CollectionDto.toDomain() = Collection(
    id = id,
    title = title,
    description = description,
    posters = posters?.toDomain(),
)

fun CollectionItemsDto.toDomain() = CollectionPage(
    collection = collection?.toDomain(),
    items = items.map { it.toDomain() },
    pagination = pagination?.toDomain() ?: Pagination(0, 1, DEFAULT_PER_PAGE),
)
