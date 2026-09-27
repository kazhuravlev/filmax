package com.filmax.data.watching

import com.filmax.core.domain.cache.ItemDiscovery
import com.filmax.core.domain.catalog.model.WatchStatus
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.safeRequest
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.core.domain.watching.model.Notification
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchProgress
import com.filmax.core.domain.watching.model.WatchingItem
import com.filmax.core.domain.watching.model.WatchingListType
import com.filmax.data.watching.remote.WatchingApi
import com.filmax.data.watching.remote.dto.HistoryEntryDto
import com.filmax.data.watching.remote.dto.PaginationDto
import com.filmax.data.watching.remote.dto.WatchingItemDto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeMark
import kotlin.time.TimeSource

private fun HistoryEntryDto.toDomain(): WatchHistory {
    val duration = media?.duration?.takeIf { it > 0 }
        ?: item.duration?.average?.takeIf { it > 0 }?.toInt()
    ItemDiscovery.discovered(item.id)
    return WatchHistory(
        itemId = item.id,
        title = item.title,
        posterSmall = item.posters?.small,
        posterWide = item.posters?.wide,
        episodeThumbnail = media?.thumbnail,
        progress = WatchProgress(
            status = WatchStatus.InProgress,
            timeSeconds = time,
            durationSeconds = duration,
            videoId = media?.number,
            season = media?.snumber?.takeIf { it > 0 },
        ),
    )
}

private fun WatchingItemDto.toDomain(isSeries: Boolean): WatchingItem {
    ItemDiscovery.discovered(id)
    return WatchingItem(
        itemId = id,
        title = title,
        isSeries = isSeries,
        posterUrl = posters?.medium?.ifBlank { posters.small }.orEmpty(),
        totalEpisodes = total,
        watchedEpisodes = watched,
        newEpisodes = newEpisodes,
    )
}

@Suppress("TooManyFunctions")
internal class WatchingRepositoryImpl(
    private val api: WatchingApi,
) : WatchingRepository {
    private var cachedHistory: CachedHistory? = null
    private var inFlightHistory: Deferred<RequestResult<List<WatchHistory>>>? = null
    private val historyMutex = Mutex()

    private val historyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private class CachedHistory(val history: List<WatchHistory>, val fetchedAt: TimeMark)

    override suspend fun getHistory(forceRefresh: Boolean): RequestResult<List<WatchHistory>> {
        val job = historyMutex.withLock {
            if (!forceRefresh) {
                cachedHistory
                    ?.takeIf { it.fetchedAt.elapsedNow() < HISTORY_TTL }
                    ?.let { return RequestResult.Success(it.history) }
            }
            inFlightHistory?.takeIf { it.isActive } ?: historyScope.async { fetchHistory() }.also { job ->
                inFlightHistory = job
            }
        }
        return job.await()
    }

    private suspend fun fetchHistory(): RequestResult<List<WatchHistory>> {
        val result = safeRequest {
            val distinct = mutableMapOf<Int, WatchHistory>()
            var page = 1
            while (distinct.size < HISTORY_TARGET_TITLES && page <= HISTORY_MAX_PAGES) {
                val response = api.getHistoryList(page, HISTORY_PER_PAGE)
                if (response.history.isEmpty()) break
                for (entry in response.history) {
                    val history = entry.toDomain()
                    if (history.itemId !in distinct) distinct[history.itemId] = history
                }
                val pagination = response.pagination ?: break
                if (!pagination.hasNextPage()) break
                page++
            }
            distinct.values.toList()
        }
        if (result is RequestResult.Success) {
            historyMutex.withLock {
                cachedHistory = CachedHistory(result.data, TimeSource.Monotonic.markNow())
            }
        }
        return result
    }

    private suspend fun invalidateHistory() {
        historyMutex.withLock { cachedHistory = null }
    }

    private fun PaginationDto.hasNextPage(): Boolean = current < total

    override suspend fun getWatchingTitles(
        type: WatchingListType,
        subscribed: Boolean,
    ): RequestResult<List<WatchingItem>> =
        safeRequest { api.getWatchingList(type, subscribed).items.map { it.toDomain(type.isSeries) } }

    override suspend fun saveProgress(itemId: Int, videoId: Int, timeSeconds: Int): RequestResult<Unit> {
        invalidateHistory()
        return safeRequest { api.saveProgress(itemId, videoId, timeSeconds) }
    }

    override suspend fun saveProgressSerial(
        itemId: Int,
        season: Int,
        videoId: Int,
        timeSeconds: Int,
    ): RequestResult<Unit> {
        invalidateHistory()
        return safeRequest { api.saveProgressSerial(itemId, season, videoId, timeSeconds) }
    }

    override suspend fun toggleWatched(itemId: Int): RequestResult<Boolean> {
        invalidateHistory()
        return safeRequest { api.toggleWatched(itemId).watched == 1 }
    }

    override suspend fun markWatched(itemId: Int, season: Int, videoId: Int): RequestResult<Unit> {
        invalidateHistory()
        return safeRequest { api.markWatched(itemId, season, videoId) }
    }

    override suspend fun toggleWatchlist(itemId: Int): RequestResult<Boolean> =
        safeRequest { api.toggleWatchlist(itemId).inWatchlist }

    override suspend fun clearHistory(itemId: Int): RequestResult<Unit> {
        invalidateHistory()
        return safeRequest { api.clearItemHistory(itemId) }
    }

    override suspend fun getNotifications(): RequestResult<List<Notification>> = safeRequest {
        api.getNotifications().notifications?.map { dto ->
            Notification(
                id = dto.id,
                title = dto.title,
                text = dto.text,
                createdAt = dto.createdAt?.toLong()?.times(MILLIS_IN_SECOND),
                read = dto.read,
                itemId = dto.itemId,
            )
        } ?: emptyList()
    }

    override suspend fun markNotificationRead(id: Int): RequestResult<Unit> =
        safeRequest { api.markNotificationRead(id) }

    override suspend fun markAllNotificationsRead(): RequestResult<Unit> =
        safeRequest { api.markAllNotificationsRead() }

    private companion object {
        const val MILLIS_IN_SECOND = 1000

        const val HISTORY_TARGET_TITLES = 20

        const val HISTORY_PER_PAGE = 100

        const val HISTORY_MAX_PAGES = 3

        val HISTORY_TTL = 5.minutes
    }
}
