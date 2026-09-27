package com.filmax.core.network.di

import com.filmax.core.domain.cache.BackgroundFetchSettings
import com.filmax.core.domain.cache.TechOverlaySettings
import com.filmax.core.domain.network.ApiHostRepository
import com.filmax.core.network.ApiHostRepositoryImpl
import com.filmax.core.network.BackgroundFetchSettingsImpl
import com.filmax.core.network.TechOverlaySettingsImpl
import com.filmax.core.network.TokenStorage
import com.filmax.core.network.buildHttpClient
import com.filmax.core.network.isDebugBuild
import io.ktor.client.HttpClient
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

val networkModule = module {
    single { TokenStorage(get()) }
    single<ApiHostRepository> { ApiHostRepositoryImpl(settings = get(), engine = get()) }
    single<BackgroundFetchSettings>(createdAtStart = true) {
        BackgroundFetchSettingsImpl(settings = get(named(BG_FETCH_SETTINGS)))
    }
    single<TechOverlaySettings> {
        TechOverlaySettingsImpl(settings = get(named(TECH_OVERLAY_SETTINGS)))
    }
    single<HttpClient> {
        buildHttpClient(
            engine = get(),
            tokenStorage = get(),
            hostRepository = get(),
            enableLogging = isDebugBuild,
        )
    }
}

expect val platformNetworkModule: Module
