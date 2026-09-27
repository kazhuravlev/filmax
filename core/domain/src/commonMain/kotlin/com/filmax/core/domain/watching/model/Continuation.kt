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

/**
 * Последние 90 секунд ЛЮБОЙ серии считаем завершением, а не точкой continuation: это титры и
 * «в следующей серии», досматривать их никто не хочет. Тот же порог применяет плеер, чтобы
 * отметить серию досмотренной на сервере (см. `PlayerScreenModel.saveProgress`).
 */
const val CONTINUATION_FINISH_THRESHOLD_SECONDS = 90

/**
 * Единственный результат расчёта continuation для всех экранов.
 *
 * [savedPositionSeconds] приходит из истории: у досмотренной серии сериала это уже позиция
 * СЛЕДУЮЩЕЙ недосмотренной серии (0, если её не начинали), а не хвост титров предыдущей.
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
 *
 * Эталон — веб-клиент kino.watch: для «продолжить» он берёт первый эпизод с `watching.status <= 0`,
 * то есть серверная отметка «досмотрено» (`status == 1`, её ставит `watching/toggle?status=1`)
 * побеждает позицию из `/history` — та остаётся на сервере и после завершения серии. Сверх
 * эталона считаем досмотренной и серию, у которой осталось меньше
 * [CONTINUATION_FINISH_THRESHOLD_SECONDS]: остановка на титрах — не повод предлагать «досмотреть».
 * В обоих случаях у сериала continuation переезжает на следующую недосмотренную серию.
 */
fun calculateContinuation(item: Item, history: WatchHistory? = null): Continuation? {
    val tracks = item.tracklist.sortedWith(
        compareBy<MediaTrack> { it.seasonNumber }.thenBy { it.number }.thenBy { it.id },
    )
    val anchor = findAnchor(item, tracks, history) ?: return null
    val isSeries = item.isSeriesForContinuation()
    // Досмотренная серия — продолжаем со следующей, которую сервер ещё не отметил досмотренной.
    // У фильма «следующей» нет: досмотренный фильм просто не предлагаем продолжить.
    val next = if (anchor.finished && isSeries) {
        tracks.drop(tracks.indexOf(anchor.track) + 1).firstOrNull { it.watchStatus != WatchStatus.Finished }
    } else {
        null
    }
    val track = next ?: anchor.track
    val savedPosition = if (next != null) next.resumableSeconds() else anchor.positionSeconds
    val duration = if (next != null) next.durationSeconds else anchor.durationSeconds
    val isLastEpisode = isSeries && track == tracks.last()
    // Переезд на следующую серию — всегда актуальное продолжение, даже с нулевой позиции: именно
    // это и просят от кнопки «Продолжить» после досмотренной серии. Без переезда — только
    // незавершённая позиция из истории или явного status == 0.
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
        // Кадр истории снят на хвосте ДОСМОТРЕННОЙ серии — карточке следующей серии он не подходит,
        // берём её собственный кадр (или постер тайтла, если кадра нет).
        history = if (next != null) {
            history?.copy(episodeThumbnail = next.thumbnail.takeIf { it.isNotBlank() })
        } else {
            history
        },
    )
}

/**
 * Серия, от которой считаем continuation: адресат истории, иначе первая «в процессе», иначе
 * последняя досмотренная. [positionSeconds]/[durationSeconds] — из истории, если якорь взят
 * из неё (сервер там точнее), иначе из самой дорожки.
 */
private data class Anchor(
    val track: MediaTrack,
    val positionSeconds: Int,
    val durationSeconds: Int,
    /** Позиция пришла из истории или явного status == 0 — завершённое не предлагаем пересматривать. */
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
        // У сериала нельзя переносить позицию/кадр history на другую серию, найденную лишь по
        // watchStatus. История адресует конкретный эпизод, поэтому несовпадение означает
        // устаревшие данные. У фильма findHistoryTrack безопасно выбирает первую/единственную
        // дорожку даже когда /history не прислал media.number: плеер для фильма использует ту же
        // первую дорожку.
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

/** Позиция дорожки по `items/{id}` — только у явного status == 0; у прочих начинаем с нуля. */
private fun MediaTrack.resumableSeconds(): Int =
    if (watchStatus == WatchStatus.InProgress) watchedSeconds.coerceAtLeast(0) else 0

/** Позиция уже в «хвосте» серии: осталось не больше [CONTINUATION_FINISH_THRESHOLD_SECONDS]. */
fun isFinishedByPosition(positionSeconds: Int, durationSeconds: Int): Boolean =
    positionSeconds > 0 && durationSeconds > 0 &&
        durationSeconds - positionSeconds <= CONTINUATION_FINISH_THRESHOLD_SECONDS

private fun findHistoryTrack(item: Item, tracks: List<MediaTrack>, progress: WatchProgress): MediaTrack? =
    tracks.firstOrNull { track ->
        track.number == progress.videoId && (progress.season == null || track.seasonNumber == progress.season)
    } ?: tracks.firstOrNull().takeIf { !item.isSeriesForContinuation() }

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
