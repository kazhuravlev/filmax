package com.filmax.core.domain.cache

import com.filmax.core.domain.tuning.PerformanceTuning
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.concurrent.Volatile

/**
 * Очередь фоновой докачки тайтла по голому id — для мест, где тайтл известен только по ссылке
 * (id/название/постер из «лёгкого» ответа), а полных полей (жанры/рейтинги/трейлер и т.п.)
 * сервер для этого эндпоинта не отдаёт. Единая точка входа для ДВУХ шагов на один id, строго по
 * порядку: сначала статическая информация о тайтле ([ItemDetailsCache]), потом — постер
 * ([ImagePrefetcher]). Раньше это были два независимых Channel-конвейера, связанные только
 * побочным эффектом внутри маппера — из-за этого несложно было провести id туда, куда он не
 * должен попадать (см. [ItemDiscovery]), и легко забыть, что кэш-хит по деталям не досылает
 * заявку на постер вовсе. Обрабатывает очередь последовательно, как и [ImagePrefetcher] —
 * аккуратно, без параллельного залпа запросов.
 *
 * [progress] — тот же принцип, что и [PrefetchProgress] у [ImagePrefetcher]: живой счётчик для
 * оверлея «Показывать технические данные», не для логики очереди.
 */
interface TitleBackgroundFetcher {
    val progress: StateFlow<PrefetchProgress>

    fun enqueue(itemIds: List<Int>)
}

/**
 * Точка обнаружения вне DI-графа: любой маппер «лёгкого» ответа (`WatchingItemDto`,
 * `HistoryEntryDto` в data:watching) и, теперь, `ItemDto.toDomain()` (data:catalog — КАЖДЫЙ
 * тайтл КАЖДОГО спискового ответа: все ряды главной, каталог, поиск, похожее, подборки) сообщают
 * сюда id тайтла без DI-инъекции — аналогично
 * [ImageDiscovery]/[com.filmax.core.domain.common.ErrorReporting]. Тайтлы с уже полными данными
 * сюда слать не нужно — они и так закэшированы; сама реализация к тому же пропускает id, уже
 * стоящие в очереди/обрабатывающиеся прямо сейчас (`queuedIds` в `TitleBackgroundFetcherImpl`) —
 * из-за этого самозацикливания нет: пока id обрабатывается, повторный `discovered(id)` из
 * результата этого же запроса (тот же тайтл, вновь прошедший через `toDomain()`) отклоняется.
 *
 * Раньше `ItemDto.toDomain()` НАМЕРЕННО не был источником сюда: кэш деталей был файлом
 * `Settings`/SharedPreferences без потолка размера, и слать сюда каждую карточку каждого списка
 * означало бы один сетевой запрос и одну запись в [ItemDetailsCacheAccess] (persist на диск,
 * без потолка) на каждый тайтл, когда-либо промелькнувший в любом списке — с обычным скроллом
 * это тысячи записей за сессию, распухающий файл кэша переживал даже перезапуск приложения.
 * Это ограничение снято: [ItemDetailsCacheAccess.cache] теперь — SQLite (`ItemDetailsCacheDb`,
 * core:network) с жёстким потолком строк (2000) и TTL-вытеснением старых записей, а сама очередь
 * здесь — строго последовательная, с собственным потолком (drop-newest, см.
 * [PerformanceTuning.BackgroundQueues.MAX_QUEUED_TITLE_IDS]) и паузой на время активности
 * пользователя ([ImagePrefetchThrottle]). Кэш-хит по уже свежим деталям фетчер обрабатывает
 * вовсе без похода в сеть — повторный маппинг одного и того же id из десятка разных списков
 * почти бесплатен.
 *
 * Гонка «пользователь открыл тайтл, пока фоновая очередь качает его же id»: обе стороны в итоге
 * зовут `CatalogRepository.getItemDetails(id)`, и именно там (в реализации, data:catalog) один
 * и тот же id схлопывается в один сетевой запрос — ждут оба, ходит в сеть один. Здесь, на уровне
 * очереди, никакой блокировки нарочно нет: она не нужна, пока запись в кэш идёт через ту же
 * единственную точку.
 */
object ItemDiscovery {
    @Volatile
    var prefetcher: TitleBackgroundFetcher = NoopTitleBackgroundFetcher

    fun discovered(itemId: Int) {
        prefetcher.enqueue(listOf(itemId))
    }

    fun discovered(itemIds: List<Int>) {
        if (itemIds.isNotEmpty()) prefetcher.enqueue(itemIds)
    }
}

private object NoopTitleBackgroundFetcher : TitleBackgroundFetcher {
    override val progress: StateFlow<PrefetchProgress> = MutableStateFlow(PrefetchProgress())
    override fun enqueue(itemIds: List<Int>) = Unit
}
