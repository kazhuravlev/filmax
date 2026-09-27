package com.filmax.core.ui.components

import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.watching.model.WatchProgress

fun continueMeta(progress: WatchProgress?): String? {
    if (progress == null) return null
    val parts = buildList {
        progress.season?.takeIf { it > 0 }?.let { season ->
            val episode = progress.videoId?.takeIf { it > 0 }?.let { " E$it" }.orEmpty()
            add("S$season$episode")
        }
        remainingMinutes(progress)?.let { add("осталось ${durationLabel(it)}") }
    }
    return parts.joinToString(" · ").ifBlank { null }
}

private fun remainingMinutes(progress: WatchProgress): Int? {
    val watched = progress.timeSeconds
    val total = progress.durationSeconds?.takeIf { it > 0 }
    if (watched == null || total == null) return null
    return ((total - watched) / SECONDS_IN_MINUTE).takeIf { it > 0 }
}

fun durationLabel(totalMinutes: Int): String {
    val hours = totalMinutes / MINUTES_IN_HOUR
    val minutes = totalMinutes % MINUTES_IN_HOUR
    return when {
        hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
        hours > 0 -> "$hours ч"
        else -> "$minutes мин"
    }
}

fun Collection.posterUrl(): String? =
    posters?.let { it.medium.ifEmpty { it.big } }?.takeIf { it.isNotBlank() }

private const val MINUTES_IN_HOUR = 60
private const val SECONDS_IN_MINUTE = 60
