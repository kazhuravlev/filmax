package com.filmax.core.domain.watching.model

import com.filmax.core.domain.catalog.model.WatchStatus

data class WatchHistory(
    val itemId: Int,
    val title: String,
    val posterSmall: String?,
    val progress: WatchProgress?,
    val posterWide: String? = null,
    val episodeThumbnail: String? = null,
) {
    val wideOrPoster: String
        get() = episodeThumbnail?.takeIf { it.isNotBlank() }
            ?: posterWide?.takeIf { it.isNotBlank() }
            ?: posterSmall.orEmpty()
}

data class WatchProgress(
    val status: WatchStatus,
    val timeSeconds: Int?,
    val durationSeconds: Int?,
    val videoId: Int?,
    val season: Int?,
) {
    val fraction: Float
        get() {
            val t = timeSeconds ?: return 0f
            val d = durationSeconds?.takeIf { it > 0 } ?: return 0f
            return (t.toFloat() / d).coerceIn(0f, 1f)
        }
}

data class WatchingItem(
    val itemId: Int,
    val title: String,
    val isSeries: Boolean,
    val posterUrl: String,
    val totalEpisodes: Int? = null,
    val watchedEpisodes: Int? = null,
    val newEpisodes: Int? = null,
)

data class Notification(
    val id: Int,
    val title: String?,
    val text: String?,
    val createdAt: Long?,
    val read: Boolean,
    val itemId: Int?,
)

enum class WatchingListType(val apiValue: String, val isSeries: Boolean) {
    Movies("movies", isSeries = false),
    Serials("serials", isSeries = true),
}
