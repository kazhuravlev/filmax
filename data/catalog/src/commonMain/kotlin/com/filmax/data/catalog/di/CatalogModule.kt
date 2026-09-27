package com.filmax.data.catalog.di

import com.filmax.core.domain.cache.TitleBackgroundFetcher
import com.filmax.core.domain.catalog.CatalogRepository
import com.filmax.data.catalog.CatalogRepositoryImpl
import com.filmax.data.catalog.TitleBackgroundFetcherImpl
import com.filmax.data.catalog.remote.CatalogApi
import org.koin.dsl.module

val catalogModule = module {
    single { CatalogApi(get()) }
    single<CatalogRepository> { CatalogRepositoryImpl(api = get(), itemCache = get()) }
    single<TitleBackgroundFetcher>(createdAtStart = true) {
        TitleBackgroundFetcherImpl(catalog = get(), itemCache = get(), backgroundFetch = get())
    }
}
