package com.filmax.feature.home.common.di

import com.filmax.core.domain.common.LastValueCache
import com.filmax.feature.home.common.HomeScreenModel
import com.filmax.feature.home.common.HomeSnapshot
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val homeModule = module {
    // Кэш последнего успешного снимка главной — single, чтобы переживать пересоздание
    // ScreenModel (офлайн-устойчивость #42).
    single { LastValueCache<HomeSnapshot>() }
    viewModelOf(::HomeScreenModel)
}
