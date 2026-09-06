package com.filmax.core.network.di

import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.network.ApiHostRepository
import com.filmax.core.network.ApiHostRepositoryImpl
import com.filmax.core.network.BackgroundFetchSettingsImpl
import com.filmax.core.network.ItemDetailsCacheImpl
import com.filmax.core.network.TokenStorage
import com.filmax.core.network.buildHttpClient
import com.filmax.core.network.isDebugBuild
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Общий сетевой DI-модуль. Зависит от платформенных провайдеров [platformNetworkModule]
 * ([com.russhwolf.settings.Settings] и [io.ktor.client.engine.HttpClientEngine]).
 */
val networkModule = module {
    single { TokenStorage(get()) }
    single<ApiHostRepository> { ApiHostRepositoryImpl(settings = get(), engine = get()) }
    // createdAtStart — ItemDto.toDomain() (data:catalog) кладёт тайтлы в кэш напрямую через
    // ItemDetailsCacheAccess, без DI, и должен найти уже готовую реализацию с первого же тайтла.
    single<ItemDetailsCache>(createdAtStart = true) {
        ItemDetailsCacheImpl(settings = get(named(ITEM_CACHE_SETTINGS)))
    }
    // createdAtStart — оба фетчера (TitleBackgroundFetcherImpl в data:catalog,
    // ImagePrefetcherImpl в core:ui) — тоже createdAtStart и читают этот флаг с первого элемента
    // своей очереди.
    single<BackgroundFetchSettings>(createdAtStart = true) {
        BackgroundFetchSettingsImpl(settings = get(named(BG_FETCH_SETTINGS)))
    }
    single<HttpClient> {
        buildHttpClient(
            engine = get(),
            tokenStorage = get(),
            hostRepository = get(),
            // Только в debug: логи печатают URL, параметры и тела ответов.
            enableLogging = isDebugBuild,
        )
    }
}

/** Платформенные зависимости сети: хранилище настроек и HTTP-движок (+ инспектор на Android). */
expect val platformNetworkModule: Module
