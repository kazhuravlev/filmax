package com.filmax.core.network.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.network.ItemDetailsCacheDb
import com.filmax.core.network.TokenStorage
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
            androidContext().getSharedPreferences(TokenStorage.PREFERENCES_NAME, Context.MODE_PRIVATE),
        )
    }
    single<ItemDetailsCache>(createdAtStart = true) {
        ItemDetailsCacheDb(context = androidContext())
    }
    single<Settings>(named(BG_FETCH_SETTINGS)) {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("filmax_bg_fetch", Context.MODE_PRIVATE),
        )
    }
    single<Settings>(named(TECH_OVERLAY_SETTINGS)) {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("filmax_tech_overlay", Context.MODE_PRIVATE),
        )
    }
    single { ChuckerInterceptor.Builder(androidContext()).build() }
    single<HttpClientEngine> {
        OkHttp.create { addInterceptor(get<ChuckerInterceptor>()) }
    }
}
