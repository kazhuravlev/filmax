package com.filmax.data.watching.remote

import com.filmax.core.domain.watching.model.WatchingListType
import com.filmax.data.watching.remote.dto.HistoryListResponseDto
import com.filmax.data.watching.remote.dto.NotificationsDto
import com.filmax.data.watching.remote.dto.ToggleWatchedResponseDto
import com.filmax.data.watching.remote.dto.ToggleWatchlistResponseDto
import com.filmax.data.watching.remote.dto.WatchingListResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.Parameters

@Suppress("TooManyFunctions")
internal class WatchingApi(private val client: HttpClient) {
    suspend fun getWatchingList(type: WatchingListType, subscribed: Boolean): WatchingListResponseDto =
        client.get("api/v1/watching/${type.apiValue}") {
            parameter("subscribed", if (subscribed) 1 else 0)
        }.body()

    suspend fun getHistoryList(page: Int = 1, perPage: Int? = null): HistoryListResponseDto =
        client.get("api/v1/history") {
            parameter("page", page)
            perPage?.let { parameter("perpage", it) }
        }.body()

    suspend fun saveProgress(id: Int, video: Int, time: Int) {
        client.get("api/v1/watching/marktime") {
            parameter("id", id)
            parameter("video", video)
            parameter("time", time)
        }
    }

    suspend fun saveProgressSerial(id: Int, season: Int, video: Int, time: Int) {
        client.get("api/v1/watching/marktime") {
            parameter("id", id)
            parameter("season", season)
            parameter("video", video)
            parameter("time", time)
        }
    }

    suspend fun toggleWatched(id: Int): ToggleWatchedResponseDto =
        client.get("api/v1/watching/toggle") { parameter("id", id) }.body()

    suspend fun markWatched(id: Int, season: Int, video: Int) {
        client.get("api/v1/watching/toggle") {
            parameter("id", id)
            if (season > 0) parameter("season", season)
            parameter("video", video)
            parameter("status", 1)
        }
    }

    suspend fun toggleWatchlist(id: Int): ToggleWatchlistResponseDto =
        client.get("api/v1/watching/togglewatchlist") { parameter("id", id) }.body()

    suspend fun clearItemHistory(id: Int) {
        client.get("api/v1/history/clear-for-item") { parameter("id", id) }
    }

    suspend fun getNotifications(): NotificationsDto =
        client.get("api2/v1.1/notifications").body()

    suspend fun markNotificationRead(id: Int) {
        client.submitForm(
            url = "api2/v1.1/notifications/read",
            formParameters = Parameters.build { append("id", id.toString()) },
        )
    }

    suspend fun markAllNotificationsRead() {
        client.post("api2/v1.1/notifications/read-all")
    }
}
