package com.filmax.app.warmup

import com.filmax.core.domain.auth.AuthRepository
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.core.domain.common.LastValueCache
import com.filmax.core.domain.tuning.PerformanceTuning
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

class AppWarmup(
    private val auth: AuthRepository,
    private val watching: WatchingRepository,
    private val user: UserRepository,
    private val catalog: CatalogRepository,
    private val libraryCache: LastValueCache<LibrarySnapshot>,
    private val catalogCache: LastValueCache<CatalogSnapshot>,
) {
    private val started = AtomicBoolean(false)

    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            auth.isAuthenticated.first { it }
            delay(PerformanceTuning.Warmup.START_DELAY_MS)
            supervisorScope {
                launch { runCatching { libraryCache.putIfAbsent(fetchLibrarySnapshot(watching, user)) } }
                launch { runCatching { catalogCache.putIfAbsent(fetchCatalogSnapshot(catalog)) } }
            }
        }
    }
}
