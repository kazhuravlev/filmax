package com.filmax.feature.profile.common

import com.filmax.core.domain.cache.ImageCacheStats
import com.filmax.core.domain.cache.ItemCacheTtl
import com.filmax.core.domain.playback.PlaybackSettings
import com.filmax.core.domain.user.model.UserProfile

data class ProfileState(
    val profile: UserProfile? = null,
    /** Кол-во просмотренного контента — из истории просмотров (`watching/history`). */
    val watchedCount: Int = 0,
    /** Кол-во элементов в избранном — из локального кэша favorites. */
    val favoritesCount: Int = 0,
    /** Максимальное качество устройства — из `device/info` (4K/HDR/HEVC/HD). */
    val quality: String? = null,
    /** Пользовательские настройки воспроизведения (качество/аудио/субтитры). */
    val playback: PlaybackSettings = PlaybackSettings(),
    /** Текущий хост API (см. [com.filmax.core.domain.network.ApiHostRepository]). */
    val apiHost: String = "",
    /** Хосты-кандидаты для пункта настроек «Сервер API». */
    val availableApiHosts: List<String> = emptyList(),
    /** Включён ли прокси изображений (см. [com.filmax.core.domain.cache.ImageProxyRepository]). */
    val imageProxyEnabled: Boolean = true,
    /** Включена ли фоновая тихая подгрузка изображений (см. [com.filmax.core.domain.cache.ImagePrefetcher]). */
    val imagePrefetchEnabled: Boolean = true,
    /** Сколько картинок фоновая закачка уже скачала с момента старта приложения. */
    val imagePrefetchDownloaded: Int = 0,
    /** Сколько картинок ещё стоит в очереди фоновой закачки. */
    val imagePrefetchRemaining: Int = 0,
    /** Сколько сейчас реально занято на диске кэшем изображений — для подписи на кнопке сброса. */
    val imageCacheStats: ImageCacheStats = ImageCacheStats(),
    /** Срок жизни кэша статической информации о тайтлах (см. [com.filmax.core.domain.cache.ItemDetailsCache]). */
    val itemCacheTtl: ItemCacheTtl = ItemCacheTtl.MONTH,
    /** Сколько тайтлов сейчас в этом кэше — для подписи на кнопке сброса; растёт не сканированием,
     * а счётчиком в самой реализации (см. `ItemDetailsCacheImpl`). */
    val itemCacheCount: Int = 0,
    val loading: Boolean = true,
    val error: String? = null,
)

sealed interface ProfileEvent {
    data object Logout : ProfileEvent
    data class SetQuality(val quality: String) : ProfileEvent
    data class SetAudioLanguage(val language: String) : ProfileEvent
    data class SetSubtitleLanguage(val language: String) : ProfileEvent
    data object ResetSubtitlePreferences : ProfileEvent
    data class SetApiHost(val host: String) : ProfileEvent
    data object ClearImageCache : ProfileEvent
    data class SetImageProxyEnabled(val enabled: Boolean) : ProfileEvent
    data class SetImagePrefetchEnabled(val enabled: Boolean) : ProfileEvent
    data object ClearItemCache : ProfileEvent
    data class SetItemCacheTtl(val ttl: ItemCacheTtl) : ProfileEvent
}

sealed interface ProfileSideEffect {
    /** Сессия завершена — экран должен увести пользователя на онбординг. */
    data object LoggedOut : ProfileSideEffect
}
