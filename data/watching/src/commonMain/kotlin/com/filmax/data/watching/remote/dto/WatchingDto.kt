package com.filmax.data.watching.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WatchingListResponseDto(
    val items: List<WatchingItemDto> = emptyList(),
)

/** Ответ `watching/toggle` — помимо статуса конкретного видео несёт итоговый флаг тайтла. */
@Serializable
data class ToggleWatchedResponseDto(
    val watched: Int = 0,
)

/**
 * Ответ `api/v1/history` — точный таймкод (`time` по каждому видео), но по СЕРИЯМ, а не тайтлам:
 * один сериал — десятки записей. Источник для `Continuation` (точная позиция одного тайтла),
 * не для списка «в процессе» целиком — для него см. [WatchingListResponseDto].
 */
@Serializable
data class HistoryListResponseDto(
    val history: List<HistoryEntryDto> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
data class HistoryEntryDto(
    /** Просмотрено секунд — по конкретному [media], а не по тайтлу целиком. */
    val time: Int = 0,
    val item: HistoryEntryItemDto,
    val media: HistoryMediaDto? = null,
)

@Serializable
data class HistoryEntryItemDto(
    val id: Int,
    val title: String = "",
    val type: String = "",
    val posters: PostersDto? = null,
    val duration: HistoryDurationDto? = null,
)

/** Конкретное видео: серия сериала или единственная дорожка фильма. */
@Serializable
data class HistoryMediaDto(
    /** Номер видео — им же kino.watch принимает и отдаёт прогресс (`marktime?video=`). */
    val number: Int = 0,
    /** Номер сезона; 0 — у фильма. */
    val snumber: Int = 0,
    /** Кадр серии 16:9 — лучшая картинка для широкой карточки. */
    val thumbnail: String = "",
    val duration: Int = 0,
)

@Serializable
data class HistoryDurationDto(
    /** Средняя длительность серии; у фильма — его длительность. */
    val average: Double = 0.0,
    val total: Int = 0,
)

/**
 * Элемент `watching/{type}` — облегчённая карточка тайтла, БЕЗ точного таймкода (сервер его тут
 * не отдаёт вовсе). `total`/`watched`/`new` — только у сериалов (`type=serials`), для фильмов
 * сервер их не считает и не присылает.
 */
@Serializable
data class WatchingItemDto(
    val id: Int,
    val title: String = "",
    val type: String = "",
    val posters: PostersDto? = null,
    val total: Int? = null,
    val watched: Int? = null,
    @SerialName("new") val newEpisodes: Int? = null,
)

@Serializable
data class PostersDto(
    val small: String = "",
    val medium: String = "",
    val big: String = "",
    // Кадр 16:9. Карточки «Продолжить»/«История» — широкие, и вертикальный постер 2:3 в них
    // обрезается по центру в кашу. Пустая строка — если бэкенд кадра не отдал.
    val wide: String = "",
)

@Serializable
data class PaginationDto(
    val total: Int = 0,
    val current: Int = 1,
    @SerialName("per_page") val perPage: Int = 20,
)

@Serializable
data class NotificationsDto(
    val notifications: List<NotificationDto>? = null,
    val unread: Int = 0,
)

@Serializable
data class NotificationDto(
    val id: Int,
    val title: String? = null,
    val text: String? = null,
    @SerialName("created_at") val createdAt: Int? = null,
    val read: Boolean = false,
    val type: String? = null,
    @SerialName("item_id") val itemId: Int? = null,
)
