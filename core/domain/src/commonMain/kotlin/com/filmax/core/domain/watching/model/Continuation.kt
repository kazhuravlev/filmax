package com.filmax.core.domain.watching.model

import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.catalog.model.WatchStatus
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.tuning.PerformanceTuning
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

const val CONTINUATION_FINISH_THRESHOLD_SECONDS = 90

data class Continuation(
    val item: Item,
    val season: Int,
    val videoId: Int,
    val savedPositionSeconds: Int,
    val isLastEpisode: Boolean,
    val isActualContinuation: Boolean,
    val progress: WatchProgress,
    val history: WatchHistory? = null,
) {
    val itemId: Int get() = item.id
    val title: String get() = history?.title ?: item.title
    val wideOrPoster: String get() = history?.wideOrPoster ?: item.posters.wide ?: item.posters.small
}

fun calculateContinuation(item: Item, history: WatchHistory? = null): Continuation? {
    val tracks = item.tracklist.sortedWith(
        compareBy<MediaTrack> { it.seasonNumber }.thenBy { it.number }.thenBy { it.id },
    )
    val anchor = findAnchor(item, tracks, history) ?: return null
    val isSeries = item.isSeriesForContinuation()
    val next = if (anchor.finished && isSeries) {
        tracks.drop(tracks.indexOf(anchor.track) + 1).firstOrNull { it.watchStatus != WatchStatus.Finished }
    } else {
        null
    }
    val track = next ?: anchor.track
    val savedPosition = if (next != null) next.resumableSeconds() else anchor.positionSeconds
    val duration = if (next != null) next.durationSeconds else anchor.durationSeconds
    val isLastEpisode = isSeries && track == tracks.last()
    val isActualContinuation = next != null || (!anchor.finished && anchor.resumable)

    return Continuation(
        item = item,
        season = track.seasonNumber,
        videoId = track.number,
        savedPositionSeconds = savedPosition,
        isLastEpisode = isLastEpisode,
        isActualContinuation = isActualContinuation,
        progress = WatchProgress(
            status = if (isActualContinuation) WatchStatus.InProgress else WatchStatus.Finished,
            timeSeconds = savedPosition,
            durationSeconds = duration.takeIf { it > 0 },
            videoId = track.number,
            season = track.seasonNumber.takeIf { it > 0 },
        ),
        history = if (next != null) {
            history?.copy(episodeThumbnail = next.thumbnail.takeIf { it.isNotBlank() })
        } else {
            history
        },
    )
}

private data class Anchor(
    val track: MediaTrack,
    val positionSeconds: Int,
    val durationSeconds: Int,
    val resumable: Boolean,
) {
    val finished: Boolean
        get() = track.watchStatus == WatchStatus.Finished || isFinishedByPosition(positionSeconds, durationSeconds)
}

private fun findAnchor(item: Item, tracks: List<MediaTrack>, history: WatchHistory?): Anchor? {
    val progress = history?.progress
    val fromHistory = progress?.let { findHistoryTrack(item, tracks, it) }
    return when {
        tracks.isEmpty() -> null
        history != null && fromHistory == null -> null
        fromHistory != null -> {
            val position = progress?.timeSeconds?.coerceAtLeast(0) ?: 0
            Anchor(
                track = fromHistory,
                positionSeconds = position,
                durationSeconds = progress?.durationSeconds?.takeIf { it > 0 } ?: fromHistory.durationSeconds,
                resumable = position > 0,
            )
        }
        else -> {
            val track = tracks.firstOrNull { it.watchStatus == WatchStatus.InProgress }
                ?: tracks.lastOrNull { it.watchStatus == WatchStatus.Finished }
            track?.let { Anchor(it, it.resumableSeconds(), it.durationSeconds, resumable = it.resumableSeconds() > 0) }
        }
    }
}

private fun MediaTrack.resumableSeconds(): Int =
    if (watchStatus == WatchStatus.InProgress) watchedSeconds.coerceAtLeast(0) else 0

fun isFinishedByPosition(positionSeconds: Int, durationSeconds: Int): Boolean =
    positionSeconds > 0 && durationSeconds > 0 &&
        durationSeconds - positionSeconds <= CONTINUATION_FINISH_THRESHOLD_SECONDS

private fun findHistoryTrack(item: Item, tracks: List<MediaTrack>, progress: WatchProgress): MediaTrack? =
    tracks.firstOrNull { track ->
        track.number == progress.videoId && (progress.season == null || track.seasonNumber == progress.season)
    } ?: tracks.firstOrNull().takeIf { !item.isSeriesForContinuation() }

class ContinuationResolver(private val catalog: CatalogRepository) {
    suspend fun resolve(history: List<WatchHistory>): List<Continuation> = coroutineScope {
        val limiter = Semaphore(PerformanceTuning.ForegroundDetailsConcurrency.CONTINUATION_DETAILS)
        history.map { entry ->
            async {
                limiter.withPermit { catalog.getItemDetails(entry.itemId).getOrNull() }
                    ?.let { item -> calculateContinuation(item, entry) }
            }
        }.awaitAll().filterNotNull()
    }
}

private fun Item.isSeriesForContinuation(): Boolean =
    type == ItemType.SERIES || type == ItemType.ANIME || type == ItemType.DOCUMENTARY
