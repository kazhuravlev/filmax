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

// Один метод на эндпоинт `watching/*`/`history`/`notifications` — см. WatchingRepository.
@Suppress("TooManyFunctions")
internal class WatchingApi(private val client: HttpClient) {

    /**
     * Список тайтлов «в процессе» одним запросом на тип — без обхода `/history` по сериям.
     * [type] — только `movies` или `serials` ([WatchingListType]): других значений у kino.watch
     * нет, и на «all» эндпоинт молча отдавал пустоту. `subscribed=1` — только отмеченные «Буду
     * смотреть» (для `movies` параметр сервер игнорирует, но передаём всегда — так проще сигнатура).
     *
     * Точного таймкода тут НЕТ — только id/title/posters (+ total/watched/new у сериалов).
     * За позицией конкретного тайтла — отдельным запросом, `CatalogApi.getItemDetails`.
     */
    suspend fun getWatchingList(type: WatchingListType, subscribed: Boolean): WatchingListResponseDto =
        client.get("api/v1/watching/${type.apiValue}") {
            parameter("subscribed", if (subscribed) 1 else 0)
        }.body()

    /**
     * История с прогрессом: `time` по каждому просмотренному видео + сам тайтл и `media`
     * (серия, её кадр и длительность). Отсортирована сервером по свежести.
     *
     * [perPage] — размер страницы СЫРЫХ записей (по сериям): тот же параметр `perpage`, которым
     * пользуется эталонный веб-клиент kino.watch (`/history?perpage=N&page=1`). Крупная страница
     * позволяет набрать нужное число разных тайтлов одним запросом вместо десятка последовательных.
     */
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

    /** Возвращает итоговое `watched` (0/1) — иначе кнопке «Я смотрю» нечем отразить новое состояние. */
    suspend fun toggleWatched(id: Int): ToggleWatchedResponseDto =
        client.get("api/v1/watching/toggle") { parameter("id", id) }.body()

    /**
     * Явная серверная отметка «видео досмотрено» (`watching.status = 1` в `items/{id}`). Не toggle:
     * с `status=1` повторный вызов ничего не снимает. Именно так эталонный клиент kino.watch
     * закрывает серию по завершении воспроизведения — сам по себе `marktime` статус не ставит.
     * [season] — только у сериала (у фильма 0: сервер ждёт запрос без `season`).
     */
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
