package com.filmax.feature.home.common.di

import com.filmax.core.domain.common.LastValueCache
import com.filmax.feature.home.common.HomeScreenModel
import com.filmax.feature.home.common.HomeSnapshot
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val homeModule = module {
    single { LastValueCache<HomeSnapshot>() }
    viewModelOf(::HomeScreenModel)
}
