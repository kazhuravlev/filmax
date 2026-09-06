package com.filmax.data.catalog.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ItemsResponseDto(
    val items: List<ItemDto>,
    val pagination: PaginationDto? = null,
)

@Serializable
data class MovieInfoDto(
    val item: ItemDto,
    @SerialName("blocked_countries") val blockedCountries: List<String>? = null,
)

@Serializable
data class ItemDto(
    val id: Int,
    val title: String,
    @SerialName("type") val type: String = "",
    val year: Int = 0,
    val plot: String = "",
    val cast: String = "",
    val director: String = "",
    val voice: String = "",
    val rating: Int = 0,
    @SerialName("rating_percentage") val ratingPercentage: Double = 0.0,
    @SerialName("imdb_rating") val imdbRating: Double? = null,
    @SerialName("kinopoisk_rating") val kinopoiskRating: Double? = null,
    // Числовой IMDb-id (не рейтинг!) — для сопоставления с TMDB ради фото актёров.
    @SerialName("imdb") val imdb: Int? = null,
    val finished: Boolean = false,
    // Флаг «в видео есть реклама» — карточка постера рисует по нему предупреждающий бейдж.
    val advert: Boolean = false,
    // Максимальное доступное качество — высота кадра в пикселях (2160/1080/720/480…), как в
    // конфиге kino.watch (`quality_list`/`quality_list_w`: 4K/FHD/HD/SD). 0 — сервер не прислал
    // (тайтл без загруженного видео) — бейдж качества на карточке в этом случае не рисуем.
    val quality: Int = 0,
    @SerialName("in_watchlist") val inWatchlist: Boolean = false,
    @SerialName("posters") val posters: PostersDto? = null,
    val duration: DurationDto = DurationDto(),
    val views: Int = 0,
    val genres: List<GenreDto> = emptyList(),
    val countries: List<CountryDto> = emptyList(),
    // Фильмы отдают список видео в `videos`, сериалы — сезоны с эпизодами в `seasons`.
    val videos: List<MediaTrackDto>? = null,
    val seasons: List<SeasonDto>? = null,
    val trailer: TrailerDto? = null,
)

@Serializable
data class SeasonDto(
    val number: Int = 0,
    val title: String = "",
    val episodes: List<MediaTrackDto> = emptyList(),
)

@Serializable
data class PostersDto(
    val small: String = "",
    val medium: String = "",
    val big: String = "",
    val wide: String? = null,
)

@Serializable
data class DurationDto(
    val average: Double? = null,
    val total: Int? = null,
)

@Serializable
data class GenreDto(
    val id: Int,
    val title: String,
    // Есть только в ответе api/v1/genres (жанры всех типов одним списком); внутри тайтла — нет.
    val type: String? = null,
)

@Serializable
data class CountryDto(
    val id: Int,
    val title: String,
)

@Serializable
data class MediaTrackDto(
    val id: Int,
    val number: Int = 0,
    val snumber: Int = 0,
    val title: String = "",
    val thumbnail: String = "",
    val duration: Int = 0,
    val files: List<VideoFileDto> = emptyList(),
    val audios: List<AudioDto> = emptyList(),
    val subtitles: List<SubtitleDto> = emptyList(),
    val watching: WatchingStateDto? = null,
)

@Serializable
data class WatchingStateDto(
    val status: Int = -1,
    val time: Int = 0,
)

@Serializable
data class VideoFileDto(
    val quality: String = "",
    val url: UrlDto? = null,
    val urls: UrlsDto? = null,
)

@Serializable
data class UrlDto(
    val http: String? = null,
    val hls: String? = null,
    val hls4: String? = null,
    val hls2: String? = null,
    val hls1: String? = null,
)

@Serializable
data class UrlsDto(
    val http: String? = null,
    val hls: String? = null,
)

@Serializable
data class AudioDto(
    val id: Int,
    val index: Int = 0,
    val codec: String? = null,
    val channels: Int = 2,
    val lang: String? = null,
    val title: String? = null,
    // Тип озвучки («Многоголосый», «Оригинал») и студия («BaibaKo») — из них оригинальный
    // клиент kino.watch собирает подписи дорожек в плеере.
    val type: AudioMetaDto? = null,
    val author: AudioMetaDto? = null,
)

@Serializable
data class AudioMetaDto(
    val id: Int = 0,
    val title: String = "",
)

@Serializable
data class SubtitleDto(
    val lang: String,
    // url бывает null/отсутствует у части тайтлов (видели в проде на serials) — обязательное
    // поле роняло парсинг ВСЕГО ответа items/{id}, и детали не открывались. Маппер такие
    // субтитры отбрасывает: без ссылки дорожка бесполезна.
    val url: String? = null,
    val shift: Int = 0,
)

@Serializable
data class TrailerDto(
    val id: Int,
    val type: Int = 0,
    val url: String? = null,
    val file: String? = null,
)

@Serializable
data class PaginationDto(
    // kino.watch: `total` — число страниц, `perpage` — элементов на странице.
    val total: Int = 0,
    val current: Int = 1,
    @SerialName("perpage") val perPage: Int = 50,
)
