package com.filmax.app.di

import com.filmax.app.navigation.RootScreenModel
import com.filmax.app.update.AppUpdateScreenModel
import com.filmax.app.update.GitHubUpdateRepository
import com.filmax.app.warmup.AppWarmup
import com.filmax.core.domain.watching.model.ContinuationResolver
import com.filmax.feature.library.common.di.LIBRARY_SNAPSHOT_CACHE
import com.filmax.feature.search.common.di.CATALOG_SNAPSHOT_CACHE
import kotlinx.coroutines.Dispatchers
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

// Диспетчер IO раздаётся отсюда, а не берётся по месту использования: с инжектом его можно
// подменить в тестах. DI-модуль — единственная легитимная точка прямого Dispatchers.IO,
// но правило исключений не делает, поэтому Suppress именно здесь.
@Suppress("InjectDispatcher")
val appModule = module {
    factory { ContinuationResolver(catalog = get()) }
    viewModelOf(::RootScreenModel)
    single { GitHubUpdateRepository(androidContext(), Dispatchers.IO) }
    viewModel { AppUpdateScreenModel(get(), Dispatchers.IO) }
    // single, а не factory: одноразовость прогрева за процесс держится и на внутреннем флаге
    // AppWarmup, и на том, что это один и тот же инстанс на все вызовы get() из onCreate.
    single {
        AppWarmup(
            auth = get(),
            watching = get(),
            user = get(),
            catalog = get(),
            libraryCache = get(LIBRARY_SNAPSHOT_CACHE),
            catalogCache = get(CATALOG_SNAPSHOT_CACHE),
        )
    }
}
