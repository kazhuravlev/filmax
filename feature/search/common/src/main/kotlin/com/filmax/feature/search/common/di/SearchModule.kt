package com.filmax.feature.search.common.di

import com.filmax.core.domain.common.LastValueCache
import com.filmax.feature.search.common.CatalogSnapshot
import com.filmax.feature.search.common.FilmographyScreenModel
import com.filmax.feature.search.common.SearchScreenModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

val CATALOG_SNAPSHOT_CACHE: Qualifier = named("catalog_snapshot")

val searchFeatureModule = module {
    single(qualifier = CATALOG_SNAPSHOT_CACHE) { LastValueCache<CatalogSnapshot>() }
    viewModel {
        SearchScreenModel(
            search = get(),
            catalog = get(),
            catalogSnapshotCache = get(CATALOG_SNAPSHOT_CACHE),
        )
    }
    viewModelOf(::FilmographyScreenModel)
}
