package com.filmax.core.ui.components

import com.filmax.core.domain.catalog.model.Collection
import com.filmax.core.domain.watching.model.WatchProgress

/**
 * Подпись карточки «продолжить»: «S2 E20 · осталось 18 мин». Одна на все экраны — раньше жила
 * тремя одинаковыми копиями.
 *
 * Для фильмов сезон и номер эпизода не выводятся. У сериалов `videoId` — номер серии.
 */
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

/** Сколько минут осталось до конца трека; null — прогресса нет или уже досмотрено. */
private fun remainingMinutes(progress: WatchProgress): Int? {
    val watched = progress.timeSeconds
    val total = progress.durationSeconds?.takeIf { it > 0 }
    if (watched == null || total == null) return null
    return ((total - watched) / SECONDS_IN_MINUTE).takeIf { it > 0 }
}

/** «1 ч 20 мин» / «45 мин» — часы показываем, только когда они есть. */
fun durationLabel(totalMinutes: Int): String {
    val hours = totalMinutes / MINUTES_IN_HOUR
    val minutes = totalMinutes % MINUTES_IN_HOUR
    return when {
        hours > 0 && minutes > 0 -> "$hours ч $minutes мин"
        hours > 0 -> "$hours ч"
        else -> "$minutes мин"
    }
}

/**
 * Постер подборки: сначала средний, затем большой. null — картинки нет вовсе, такую подборку
 * ряды не показывают (в монохроме карточку держит только изображение).
 */
fun Collection.posterUrl(): String? =
    posters?.let { it.medium.ifEmpty { it.big } }?.takeIf { it.isNotBlank() }

private const val MINUTES_IN_HOUR = 60
private const val SECONDS_IN_MINUTE = 60
