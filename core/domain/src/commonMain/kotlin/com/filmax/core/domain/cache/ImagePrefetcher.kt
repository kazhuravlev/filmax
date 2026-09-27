package com.filmax.core.domain.cache

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/** Одна картинка для тихой фоновой закачки: стабильный ключ (см. [ImageCacheKeys]) + текущий адрес. */
data class PrefetchImage(val key: String, val url: String)

/**
 * Живой прогресс очереди фоновой закачки — для настроек, не для логики. [downloaded] — сколько
 * картинок фоновая закачка обработала с момента старта процесса, [remaining] — сколько ещё стоит
 * в очереди. Оба поля живут только в памяти и обнуляются перезапуском приложения — это не
 * персистентная статистика диска (см. [ImageCacheStats] для неё).
 */
data class PrefetchProgress(val downloaded: Int = 0, val remaining: Int = 0)

/**
 * Очередь фоновой закачки картинок в кэш. Порядок не гарантирован явно, но реализация обрабатывает
 * его последовательно (одна закачка за раз) — см. `ImagePrefetcherImpl` в core:ui.
 *
 * Включена/выключена — общим [BackgroundFetchSettings], а не своей настройкой: выключение не
 * трогает уже закэшированное, оно лишь останавливает тихий прогрев картинок, которые пользователь
 * ещё не открывал (экраны продолжат грузить их как обычно по мере просмотра).
 */
interface ImagePrefetcher {
    val progress: StateFlow<PrefetchProgress>

    fun enqueue(images: List<PrefetchImage>)

    /**
     * Прогреть картинки СЕЙЧАС, а не «когда-нибудь в фоне»: пользователь только что открыл
     * экран, где вот-вот пролистает их все (браузер серий — кадры каждой серии). В отличие от
     * [enqueue] — без cooldown-троттлинга ([ImagePrefetchThrottle]: он стоит 10 с после любого
     * обычного запроса, а каждое показанное превью и есть обычный запрос — фоновая очередь
     * при листании не сдвинулась бы вовсе), без придушивания скорости и не одна за раз, а
     * несколькими параллельно. Это не фоновая докачка, а явное действие пользователя, поэтому
     * [BackgroundFetchSettings] не учитывается. Уже закэшированное не перекачивается.
     * По умолчанию — обычная очередь: фейкам и реализациям без «сейчас» этого достаточно.
     */
    fun warm(images: List<PrefetchImage>) = enqueue(images)
}

/**
 * Точка обнаружения картинок вне DI-графа: DTO→domain мапперы (модули data, например
 * `ItemDto.toDomain()`) и экраны без доступа к core:ui шлют сюда всё, что «увидели» — постеры
 * тайтла, фото актёров из сырой строки `cast` — не дожидаясь, пока пользователь реально откроет
 * экран с этой картинкой. Реализацию (реальную очередь на Coil) подставляет `core:ui` при старте,
 * аналогично [com.filmax.core.domain.common.ErrorReporting]/[com.filmax.core.domain.common.ConnectionFailures].
 * До подстановки — no-op, чтобы вызовы из data-слоя были безопасны в тестах и до старта DI.
 */
object ImageDiscovery {
    @Volatile
    var prefetcher: ImagePrefetcher = NoopImagePrefetcher

    fun discovered(images: List<PrefetchImage>) {
        if (images.isNotEmpty()) prefetcher.enqueue(images)
    }

    /** См. [ImagePrefetcher.warm] — картинки экрана, который пользователь только что открыл. */
    fun warm(images: List<PrefetchImage>) {
        if (images.isNotEmpty()) prefetcher.warm(images)
    }
}

private object NoopImagePrefetcher : ImagePrefetcher {
    override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
    override fun enqueue(images: List<PrefetchImage>) = Unit
}
