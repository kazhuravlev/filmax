package com.filmax.core.network.di

/** Koin-квалификатор [Settings][com.russhwolf.settings.Settings] под общий выключатель фоновой
 * закачки ([com.filmax.core.domain.cache.BackgroundFetchSettings]) — свой файл, отдельный и от
 * токенов, и от кэша тайтлов/изображений: сброс любого из тех кэшей не должен заодно включать
 * фоновую загрузку обратно. */
const val BG_FETCH_SETTINGS = "bgFetchSettings"
