package com.filmax.feature.library.common.di

import com.filmax.core.domain.common.LastValueCache
import com.filmax.feature.library.common.LibraryScreenModel
import com.filmax.feature.library.common.LibrarySnapshot
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

val LIBRARY_SNAPSHOT_CACHE: Qualifier = named("library_snapshot")

val libraryModule = module {
    single(qualifier = LIBRARY_SNAPSHOT_CACHE) { LastValueCache<LibrarySnapshot>() }
    viewModel {
        LibraryScreenModel(
            watching = get(),
            user = get(),
            favoritesRepo = get(),
            catalog = get(),
            snapshotCache = get(LIBRARY_SNAPSHOT_CACHE),
        )
    }
}
