@file:Suppress("TooManyFunctions")

package com.filmax.data.catalog.mapper

import com.filmax.core.domain.cache.DiscoveredTitle
import com.filmax.core.domain.cache.ImageCacheKeys
import com.filmax.core.domain.cache.ImageDiscovery
import com.filmax.core.domain.cache.ItemDetailsCacheAccess
import com.filmax.core.domain.cache.ItemDiscovery
import com.filmax.core.domain.cache.PosterSize
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

private const val DEFAULT_PER_PAGE = 20

private const val SECONDS_PER_MINUTE = 60

fun ItemsResponseDto.toDomain(): ItemPage = ItemPage(
    items = items.map { it.toDomain() },
    pagination = pagination?.toDomain() ?: Pagination(0, 1, DEFAULT_PER_PAGE),
)

internal fun itemCacheKey(id: Int): String = "item:$id"

internal fun similarCacheKey(id: Int): String = "similar:$id"

fun ItemDto.toDomain(): Item {
    val item = toDomainOnly()
    val serialized = networkJson.encodeToString(this)
    if (hasFullDetails) {
        ItemDetailsCacheAccess.cache.remember(itemCacheKey(id), serialized)
    }
    ImageDiscovery.discovered(item.posterPrefetchImages())
    ItemDiscovery.discovered(DiscoveredTitle(id = item.id, previewJson = serialized))
    return item
}

internal val ItemDto.hasFullDetails: Boolean
    get() = !videos.isNullOrEmpty() || !seasons.isNullOrEmpty()

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
        imdb = imdbRating?.toString(),
        kinopoisk = kinopoiskRating?.toString(),
    ),
    posters = posters?.toDomain() ?: Posters("", "", "", null),
    duration = duration.toDomain(),
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
    quality = quality,
)

internal fun Item.posterPrefetchImages(): List<PrefetchImage> = buildList {
    posters.medium.takeIf { it.isNotBlank() }?.let { url ->
        add(PrefetchImage(ImageCacheKeys.poster(type, id, PosterSize.Medium), url))
    }
}

fun GenreDto.toDomain() = Genre(id = id, title = title, type = type)

fun CountryDto.toDomain() = Country(id = id, title = title)

fun PostersDto?.toDomain() = Posters(
    small = this?.small ?: "",
    medium = this?.medium ?: "",
    big = this?.big ?: "",
    wide = this?.wide,
)

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
