package com.filmax.data.watching.remote

import com.filmax.data.watching.remote.dto.HistoryListResponseDto
import com.filmax.data.watching.remote.dto.NotificationsDto
import com.filmax.data.watching.remote.dto.ToggleWatchedResponseDto
import com.filmax.data.watching.remote.dto.WatchingListResponseDto
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.request.post
import io.ktor.http.Parameters

internal class WatchingApi(private val client: HttpClient) {

    /**
     * Список тайтлов «в процессе» одним запросом на тип — без обхода `/history` по сериям.
     * [type] — только `movies` или `serials`: других значений у kino.watch нет, и на «all»
     * эндпоинт молча отдавал пустоту. `subscribed=1` — только отмеченные «Буду смотреть»
     * (для `movies` параметр сервер игнорирует, но передаём всегда — так проще сигнатура).
     *
     * Точного таймкода тут НЕТ — только id/title/posters (+ total/watched/new у сериалов).
     * За позицией конкретного тайтла — отдельным запросом, `CatalogApi.getItemDetails`.
     */
    suspend fun getWatchingList(type: String, subscribed: Int = 1): WatchingListResponseDto =
        client.get("api/v1/watching/$type") {
            parameter("subscribed", subscribed)
        }.body()

    /**
     * История с прогрессом: `time` по каждому просмотренному видео + сам тайтл и `media`
     * (серия, её кадр и длительность). Отсортирована сервером по свежести.
     */
    suspend fun getHistoryList(page: Int = 1): HistoryListResponseDto =
        client.get("api/v1/history") {
            parameter("page", page)
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

    /** Возвращает итоговое `watched` (0/1) — иначе кнопке «Я смотрю» нечем отразить новое состояние. */
    suspend fun toggleWatched(id: Int): ToggleWatchedResponseDto =
        client.get("api/v1/watching/toggle") { parameter("id", id) }.body()

    suspend fun toggleWatchlist(id: Int): Map<String, Int> =
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
