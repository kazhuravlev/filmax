package com.filmax.data.watching.remote.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class WatchingListResponseDto(
    val items: List<WatchingItemDto> = emptyList(),
)

@Serializable
data class ToggleWatchedResponseDto(
    val watched: Int = 0,
)

@Serializable
data class ToggleWatchlistResponseDto(
    val watching: Int = 0,
) {
    val inWatchlist: Boolean get() = watching == 1
}

@Serializable
data class HistoryListResponseDto(
    val history: List<HistoryEntryDto> = emptyList(),
    val pagination: PaginationDto? = null,
)

@Serializable
data class HistoryEntryDto(
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

@Serializable
data class HistoryMediaDto(
    val number: Int = 0,
    val snumber: Int = 0,
    val thumbnail: String = "",
    val duration: Int = 0,
)

@Serializable
data class HistoryDurationDto(
    val average: Double = 0.0,
    val total: Int = 0,
)

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
