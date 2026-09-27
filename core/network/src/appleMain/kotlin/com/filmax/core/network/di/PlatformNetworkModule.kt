package com.filmax.core.network.di

import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.network.ItemDetailsCacheImpl
import com.filmax.core.network.TokenStorage
import com.russhwolf.settings.ExperimentalSettingsImplementation
import com.russhwolf.settings.KeychainSettings
import com.russhwolf.settings.Settings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

@OptIn(ExperimentalSettingsImplementation::class)
actual val platformNetworkModule: Module = module {
    single<Settings> { KeychainSettings(service = TokenStorage.PREFERENCES_NAME) }
    single<Settings>(named(ITEM_CACHE_SETTINGS)) { KeychainSettings(service = "filmax_item_cache") }
    single<Settings>(named(BG_FETCH_SETTINGS)) { KeychainSettings(service = "filmax_bg_fetch") }
    single<Settings>(named(TECH_OVERLAY_SETTINGS)) { KeychainSettings(service = "filmax_tech_overlay") }
    single<ItemDetailsCache>(createdAtStart = true) {
        ItemDetailsCacheImpl(settings = get(named(ITEM_CACHE_SETTINGS)))
    }
    single<HttpClientEngine> { Darwin.create() }
}
