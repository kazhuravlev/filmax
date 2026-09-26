package com.filmax.core.domain.watching

import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.watching.model.Notification
import com.filmax.core.domain.watching.model.WatchHistory
import com.filmax.core.domain.watching.model.WatchingItem

interface WatchingRepository {

    /**
     * История просмотров (по тайтлам, свежие первыми). Реализация кэширует последний успешный
     * ответ в памяти и сама сбрасывает его при локальных мутациях прогресса (marktime, отметка
     * «просмотрено», очистка) — обычному читателю [forceRefresh] не нужен. `true` — явное
     * обновление по действию пользователя (повторный выбор вкладки «Я смотрю»): кэш минуем.
     */
    suspend fun getHistory(type: String = "all", forceRefresh: Boolean = false): RequestResult<List<WatchHistory>>

    /**
     * Тайтлы «в процессе» одним запросом на тип (`watching/{type}`) — без обхода `/history` по
     * сериям и без резолва каждого тайтла отдельным `getItemDetails`. [type] — `"movies"` или
     * `"serials"`. Для точной позиции конкретного тайтла (когда его открывают) — отдельный запрос,
     * `CatalogRepository.getItemDetails`.
     */
    suspend fun getWatchingTitles(type: String, subscribed: Int = 1): RequestResult<List<WatchingItem>>

    suspend fun saveProgress(itemId: Int, videoId: Int, timeSeconds: Int): RequestResult<Unit>

    suspend fun saveProgressSerial(
        itemId: Int,
        season: Int,
        videoId: Int,
        timeSeconds: Int,
    ): RequestResult<Unit>

    /** true — тайтл теперь отмечен «Я смотрю», false — отметка снята (см. `watching/toggle`). */
    suspend fun toggleWatched(itemId: Int): RequestResult<Boolean>

    suspend fun toggleWatchlist(itemId: Int): RequestResult<Boolean>

    suspend fun clearHistory(itemId: Int): RequestResult<Unit>

    suspend fun getNotifications(): RequestResult<List<Notification>>

    suspend fun markNotificationRead(id: Int): RequestResult<Unit>

    suspend fun markAllNotificationsRead(): RequestResult<Unit>
}
