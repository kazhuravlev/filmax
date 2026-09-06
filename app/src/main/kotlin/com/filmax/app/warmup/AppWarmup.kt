package com.filmax.app.warmup

import com.filmax.core.domain.auth.AuthRepository
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.common.LastValueCache
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.watching.WatchingRepository
import com.filmax.feature.library.common.LibrarySnapshot
import com.filmax.feature.library.common.fetchLibrarySnapshot
import com.filmax.feature.search.common.CatalogSnapshot
import com.filmax.feature.search.common.fetchCatalogSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Одноразовый фоновый прогрев вкладок, которые зритель ещё не открывал.
 *
 * ЗАЧЕМ: сразу после старта приложения (и после обновления/переустановки) `ScreenModel` каждой
 * вкладки пуст и уходит в сеть только в момент реального захода на неё — «Моё» и «Каталог» тогда
 * показывают скелетон, хотя данные вполне можно было бы начать тянуть заранее, пока зритель ещё
 * смотрит главную. [AppWarmup] запускает эти фетчи параллельно в фоне и складывает результат в
 * те же in-memory кэши (`LastValueCache`), из которых сами экраны сеют своё стартовое состояние
 * (см. `LibraryScreenModel.onFetchData` и `SearchScreenModel` на `SearchEvent.LoadCatalog`) —
 * когда зритель реально открывает вкладку, она красится мгновенно уже готовыми данными и тут же
 * тихо ревалидируется своим обычным фетчем, как будто прогрева и не было.
 *
 * ПОЧЕМУ ГЛАВНОЙ ЗДЕСЬ НЕТ: главная — стартовый экран, она прогревает себя сама первым же
 * `onFetchData()` в момент открытия приложения; отдельный прогрев для неё бессмыслен и только
 * дублировал бы её же собственный запрос.
 *
 * ПРАВИЛО putIfAbsent (никакого рассинхрона): прогрев пишет в кэш ТОЛЬКО через
 * [LastValueCache.putIfAbsent] — экран остаётся единственным источником истины для своего кэша, и
 * если он успел сам туда что-то положить (или уже идёт с полными данными) раньше, чем прогрев
 * дошёл до записи, прогрев это значение не тронет. Читают кэш сами экраны — и тоже только один
 * раз, при (пере)создании модели, пока их состояние ещё пусто.
 *
 * ТИШИНА ПРИ ОШИБКАХ: прогрев — best-effort фоновая оптимизация, а не источник правды. Сбой
 * любого источника (сеть недоступна, 401 из-за гонки с обновлением токена и т.п.) просто оставляет
 * соответствующий кэш пустым — без модалок, баннеров офлайна, серверных уведомлений и без
 * `DataInvalidation.markDirty`: ничего из этого не должно всплыть на экране, который зритель даже
 * не открывал.
 *
 * ОДНОРАЗОВОСТЬ: [start] строго один раз за жизнь процесса — повторные вызовы (например, если
 * [com.filmax.app.FilmaxApplication.onCreate] вдруг отработает дважды) не запускают второй залп
 * фетчей поверх первого.
 */
class AppWarmup(
    private val auth: AuthRepository,
    private val watching: WatchingRepository,
    private val user: UserRepository,
    private val catalog: CatalogRepository,
    private val libraryCache: LastValueCache<LibrarySnapshot>,
    private val catalogCache: LastValueCache<CatalogSnapshot>,
) {

    /** Одноразовость на процесс — не Mutex/корутинный флаг: [start] может позвать обычный код
     * (не suspend) синхронно, а `compareAndSet` дешевле явной синхронизации ради того же эффекта. */
    private val started = AtomicBoolean(false)

    /**
     * Запускает прогрев на [scope] (ожидается фоновый, IO-скоуп — см. вызов из
     * [com.filmax.app.FilmaxApplication.onCreate]). Не suspend и не блокирует вызывающего: вся
     * работа, включая ожидание авторизации, уходит в запущенную корутину.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            // Неавторизованный запрос улетит 401-м — ждём первого `true` того же флага, что
            // читает RootScreenModel (см. app/navigation/RootScreenModel.kt), прежде чем стрелять
            // сетью вообще.
            auth.isAuthenticated.first { it }
            // Даём стартовому залпу главной забрать сеть первым: прогрев второстепенных вкладок
            // не должен состязаться с тем, что зритель видит на экране прямо сейчас.
            delay(WARMUP_START_DELAY_MS)
            supervisorScope {
                launch { runCatching { libraryCache.putIfAbsent(fetchLibrarySnapshot(watching, user)) } }
                launch { runCatching { catalogCache.putIfAbsent(fetchCatalogSnapshot(catalog)) } }
            }
        }
    }

    private companion object {
        /** Задержка перед стартом прогрева — см. doc [start]. */
        const val WARMUP_START_DELAY_MS = 3_000L
    }
}
