package com.filmax.feature.details.common

import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.watching.model.Continuation

private const val SECONDS_IN_MINUTE = 60
private const val MINUTES_IN_HOUR = 60

fun playLabel(
    continuation: Continuation?,
    resume: MediaTrack?,
    target: MediaTrack?,
    resolving: Boolean = false,
): String = when {
    resolving -> "Смотреть"
    continuation?.isActualContinuation == true -> buildString {
        append("Продолжить")
        continuation.savedPositionSeconds.takeIf { it > 0 }?.let { append(" с ${formatResumePosition(it)}") }
        resume?.let { append(" · ${episodeTag(it)}") }
    }
    target != null -> "Смотреть · ${episodeTag(target)}"
    else -> "Смотреть"
}

fun formatResumePosition(positionSeconds: Int): String {
    val totalMinutes = positionSeconds / SECONDS_IN_MINUTE
    val seconds = positionSeconds % SECONDS_IN_MINUTE
    if (totalMinutes < MINUTES_IN_HOUR) return "$totalMinutes:${seconds.twoDigits()}"

    val hours = totalMinutes / MINUTES_IN_HOUR
    val minutes = totalMinutes % MINUTES_IN_HOUR
    return "$hours:${minutes.twoDigits()}:${seconds.twoDigits()}"
}

private fun Int.twoDigits(): String = toString().padStart(length = 2, padChar = '0')

fun episodeTag(track: MediaTrack): String =
    if (track.seasonNumber > 0) "S${track.seasonNumber}E${track.number}" else "Серия ${track.number}"
