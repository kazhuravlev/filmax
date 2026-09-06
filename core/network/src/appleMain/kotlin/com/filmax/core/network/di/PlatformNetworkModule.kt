package com.filmax.core.network.di

import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.network.ItemDetailsCacheImpl
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
    single<Settings> { KeychainSettings(service = "filmax_tokens") }
    single<Settings>(named(ITEM_CACHE_SETTINGS)) { KeychainSettings(service = "filmax_item_cache") }
    single<Settings>(named(BG_FETCH_SETTINGS)) { KeychainSettings(service = "filmax_bg_fetch") }
    single<Settings>(named(TECH_OVERLAY_SETTINGS)) { KeychainSettings(service = "filmax_tech_overlay") }
    // На Android этот кэш заменён на ItemDetailsCacheDb (SQLite, см. androidMain) — apple-таргеты
    // сейчас не используются реальным приложением (iOS-клиента нет), но должны собираться и
    // резолвиться, поэтому тут остаётся старая реализация поверх Settings/Keychain.
    // createdAtStart — см. комментарий в networkModule/androidMain PlatformNetworkModule:
    // ItemDto.toDomain() кладёт тайтлы в кэш напрямую через ItemDetailsCacheAccess, без DI.
    single<ItemDetailsCache>(createdAtStart = true) {
        ItemDetailsCacheImpl(settings = get(named(ITEM_CACHE_SETTINGS)))
    }
    single<HttpClientEngine> { Darwin.create() }
}
