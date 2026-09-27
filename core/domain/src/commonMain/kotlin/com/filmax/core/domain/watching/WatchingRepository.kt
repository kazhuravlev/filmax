package com.filmax.core.domain.watching

import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.watching.model.Notification
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchingItem
import com.filmax.core.domain.watching.model.WatchingListType

@Suppress("TooManyFunctions")
interface WatchingRepository {
    suspend fun getHistory(forceRefresh: Boolean = false): RequestResult<List<WatchHistory>>

    suspend fun getWatchingTitles(
        type: WatchingListType,
        subscribed: Boolean = true,
    ): RequestResult<List<WatchingItem>>

    suspend fun saveProgress(itemId: Int, videoId: Int, timeSeconds: Int): RequestResult<Unit>

    suspend fun saveProgressSerial(
        itemId: Int,
        season: Int,
        videoId: Int,
        timeSeconds: Int,
    ): RequestResult<Unit>

    suspend fun toggleWatched(itemId: Int): RequestResult<Boolean>

    suspend fun markWatched(itemId: Int, season: Int, videoId: Int): RequestResult<Unit>

    suspend fun toggleWatchlist(itemId: Int): RequestResult<Boolean>

    suspend fun clearHistory(itemId: Int): RequestResult<Unit>

    suspend fun getNotifications(): RequestResult<List<Notification>>

    suspend fun markNotificationRead(id: Int): RequestResult<Unit>

    suspend fun markAllNotificationsRead(): RequestResult<Unit>
}
