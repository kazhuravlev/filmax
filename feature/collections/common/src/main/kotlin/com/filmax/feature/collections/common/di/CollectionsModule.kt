package com.filmax.feature.collections.common.di

import com.filmax.feature.collections.common.CollectionDetailScreenModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.dsl.module

val collectionsModule = module {
    viewModelOf(::CollectionDetailScreenModel)
}
