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

@Suppress("InjectDispatcher")
val appModule = module {
    factory { ContinuationResolver(catalog = get()) }
    viewModelOf(::RootScreenModel)
    single { GitHubUpdateRepository(androidContext(), Dispatchers.IO) }
    viewModel { AppUpdateScreenModel(get(), Dispatchers.IO) }
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
