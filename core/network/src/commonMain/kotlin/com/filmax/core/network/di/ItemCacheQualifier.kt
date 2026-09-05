package com.filmax.core.network.di

/** Koin-квалификатор [Settings][com.russhwolf.settings.Settings] под кэш тайтлов — отдельный
 * файл от токенов, чтобы `Settings.clear()` в `ItemDetailsCacheImpl` не задевал авторизацию. */
const val ITEM_CACHE_SETTINGS = "itemCacheSettings"
