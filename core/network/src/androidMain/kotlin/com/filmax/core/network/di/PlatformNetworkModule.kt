package com.filmax.core.network.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.russhwolf.settings.Settings
import com.russhwolf.settings.SharedPreferencesSettings
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.android.ext.koin.androidContext
import org.koin.core.module.Module
import org.koin.core.qualifier.named
import org.koin.dsl.module

actual val platformNetworkModule: Module = module {
    single<Settings> {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("filmax_tokens", Context.MODE_PRIVATE),
        )
    }
    // Отдельный файл, а не общий с токенами: кэш тайтлов может разрастись до сотен записей,
    // а `Settings.clear()` у ItemDetailsCacheImpl должен чистить только его, не разлогинивая заодно.
    single<Settings>(named(ITEM_CACHE_SETTINGS)) {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("filmax_item_cache", Context.MODE_PRIVATE),
        )
    }
    // Свой файл под общий выключатель фоновой докачки — сброс кэша тайтлов/изображений не должен
    // заодно включать фоновую загрузку обратно (см. BackgroundFetchSettingsImpl).
    single<Settings>(named(BG_FETCH_SETTINGS)) {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("filmax_bg_fetch", Context.MODE_PRIVATE),
        )
    }
    single { ChuckerInterceptor.Builder(androidContext()).build() }
    single<HttpClientEngine> {
        OkHttp.create { addInterceptor(get<ChuckerInterceptor>()) }
    }
}
