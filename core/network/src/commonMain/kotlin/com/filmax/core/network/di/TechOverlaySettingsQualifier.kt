package com.filmax.core.network.di

/** Koin-квалификатор [Settings][com.russhwolf.settings.Settings] под настройку видимости
 * технического оверлея ([com.filmax.core.domain.cache.TechOverlaySettings]) — свой файл, отдельный
 * и от токенов, и от фоновой докачки, и от кэша тайтлов/изображений: сброс любого из них не
 * должен менять видимость оверлея. */
const val TECH_OVERLAY_SETTINGS = "techOverlaySettings"
