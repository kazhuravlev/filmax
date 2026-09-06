package com.filmax.core.domain.watching.model

import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.catalog.model.ItemType
import com.filmax.core.domain.catalog.model.MediaTrack
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.tuning.PerformanceTuning
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Последние 90 секунд финальной серии считаем завершением, а не точкой continuation. */
const val CONTINUATION_FINISH_THRESHOLD_SECONDS = 90

/**
 * Единственный результат расчёта continuation для всех экранов.
 *
 * [savedPositionSeconds] приходит из истории и намеренно не выводится из `watchStatus`: сервер
 * может уже отметить трек завершённым, хотя фактически в истории остаётся время для просмотра.
 */
data class Continuation(
    val item: Item,
    val season: Int,
    val videoId: Int,
    val savedPositionSeconds: Int,
    val isLastEpisode: Boolean,
    val isActualContinuation: Boolean,
    /** Прогресс конкретного выбранного трека, нормализованный по history + tracklist. */
    val progress: WatchProgress,
    /** Исходная запись нужна только для карточки: кадр, постер и серверная длительность. */
    val history: WatchHistory? = null,
) {
    val itemId: Int get() = item.id
    val title: String get() = history?.title ?: item.title
    val wideOrPoster: String get() = history?.wideOrPoster ?: item.posters.wide ?: item.posters.small
}

/**
 * Сводит историю и детали тайтла. Эта функция намеренно не опирается на порядок `tracklist`:
 * последовательность эпизодов всегда определяется парой season/number.
 */
fun calculateContinuation(item: Item, history: WatchHistory? = null): Continuation? {
    val tracks = item.tracklist.sortedWith(
        compareBy<MediaTrack> { it.seasonNumber }.thenBy { it.number }.thenBy { it.id },
    )
    if (tracks.isEmpty()) return null

    val historyProgress = history?.progress
    val fromHistory = historyProgress?.let { progress ->
        tracks.firstOrNull { track ->
            track.number == progress.videoId && (progress.season == null || track.seasonNumber == progress.season)
        }
    }
    // Нельзя переносить позицию/кадр history на другой трек, найденный лишь по watchStatus.
    // История адресует конкретную серию, поэтому несовпадение означает устаревшие данные.
    if (history != null && fromHistory == null) return null
    val inProgress = tracks.firstOrNull { it.watchStatus == WATCH_STATUS_IN_PROGRESS }
    val lastFinished = tracks.lastOrNull { it.watchStatus == WATCH_STATUS_FINISHED }
    val track = fromHistory ?: inProgress ?: lastFinished ?: return null

    val savedPosition = if (track == fromHistory) {
        historyProgress?.timeSeconds?.coerceAtLeast(0) ?: 0
    } else {
        track.watchedSeconds.coerceAtLeast(0)
    }
    val duration = if (track == fromHistory) {
        historyProgress?.durationSeconds?.takeIf { it > 0 } ?: track.durationSeconds
    } else {
        track.durationSeconds
    }
    val isLastEpisode = item.isSeriesForContinuation() && track == tracks.last()
    // История — источник позиции, поэтому её запись продолжает даже при watchStatus == 1.
    // Без истории доверяем только явному status == 0: завершённую серию не предлагаем пересматривать.
    val hasResumablePosition = savedPosition > 0 &&
        (track == fromHistory || track.watchStatus == WATCH_STATUS_IN_PROGRESS)
    val remaining = (duration - savedPosition).coerceAtLeast(0)
    val isActualContinuation = hasResumablePosition &&
        (!isLastEpisode || remaining > CONTINUATION_FINISH_THRESHOLD_SECONDS)

    return Continuation(
        item = item,
        season = track.seasonNumber,
        videoId = track.number,
        savedPositionSeconds = savedPosition,
        isLastEpisode = isLastEpisode,
        isActualContinuation = isActualContinuation,
        progress = WatchProgress(
            status = if (isActualContinuation) WATCH_STATUS_IN_PROGRESS else WATCH_STATUS_FINISHED,
            timeSeconds = savedPosition,
            durationSeconds = duration.takeIf { it > 0 },
            videoId = track.number,
            season = track.seasonNumber.takeIf { it > 0 },
        ),
        history = history,
    )
}

/**
 * Загружает детали только для записей истории и применяет [calculateContinuation] ко всем экранам.
 * Если детали отдельного тайтла не доехали, запись не показываем: без полного tracklist нельзя
 * безопасно решить, является ли её эпизод последним.
 *
 * Запросы ограничены [PerformanceTuning.ForegroundDetailsConcurrency.CONTINUATION_DETAILS]
 * одновременных — на холодном кэше история может содержать до пары десятков записей, и залп из
 * стольких же параллельных запросов к серверу не нужен (тот же приём и то же число, что и у
 * `LibraryScreenModel.loadTitleDetails`). Без ограничения кэш-промах по всей истории означал ещё
 * и до 20 сетевых походов одновременно, что на медленной сети удлиняло появление continuation
 * куда сильнее, чем очередь с разумной шириной.
 */
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

private const val WATCH_STATUS_IN_PROGRESS = 0
private const val WATCH_STATUS_FINISHED = 1
