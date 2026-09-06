package com.filmax.core.network.di

import android.content.Context
import com.chuckerteam.chucker.api.ChuckerInterceptor
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.network.ItemDetailsCacheDb
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
    // createdAtStart — ItemDto.toDomain() (data:catalog) кладёт тайтлы в кэш напрямую через
    // ItemDetailsCacheAccess, без DI, и должен найти уже готовую реализацию с первого же тайтла.
    // SQLite вместо Settings/SharedPreferences — см. doc-комментарий ItemDetailsCacheDb: старый
    // файл "filmax_item_cache" рос без ограничений, целиком жил в памяти и синхронно парсился на
    // главном потоке при старте. Конструктор ItemDetailsCacheDb дёшев (без обращений к диску),
    // поэтому createdAtStart не возвращает проблему со стартом обратно.
    single<ItemDetailsCache>(createdAtStart = true) {
        ItemDetailsCacheDb(context = androidContext())
    }
    // Свой файл под общий выключатель фоновой докачки — сброс кэша тайтлов/изображений не должен
    // заодно включать фоновую загрузку обратно (см. BackgroundFetchSettingsImpl).
    single<Settings>(named(BG_FETCH_SETTINGS)) {
        SharedPreferencesSettings(
            androidContext().getSharedPreferences("filmax_bg_fetch", Context.MODE_PRIVATE),
        )
    }
    // Свой файл под видимость технического оверлея — сброс любого из кэшей не должен менять,
    // виден ли оверлей (см. TechOverlaySettingsImpl).
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
