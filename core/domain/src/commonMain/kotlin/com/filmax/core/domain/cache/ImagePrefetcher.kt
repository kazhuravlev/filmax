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
 * [enabled] — персистентная настройка «Фоновая загрузка изображений», по умолчанию включена;
 * выключение не трогает уже закэшированное — оно лишь останавливает тихий прогрев картинок,
 * которые пользователь ещё не открывал (экраны продолжат грузить их как обычно по мере просмотра).
 */
interface ImagePrefetcher {
    val enabled: StateFlow<Boolean>
    val progress: StateFlow<PrefetchProgress>

    suspend fun setEnabled(enabled: Boolean)
    fun enqueue(images: List<PrefetchImage>)
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
}

private object NoopImagePrefetcher : ImagePrefetcher {
    override val enabled: StateFlow<Boolean> = MutableStateFlow(true)
    override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
    override suspend fun setEnabled(enabled: Boolean) = Unit
    override fun enqueue(images: List<PrefetchImage>) = Unit
}
