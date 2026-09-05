package com.filmax.core.ui.di

import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImageProxyRepository
import com.filmax.core.ui.cache.ImageCacheRepositoryImpl
import com.filmax.core.ui.cache.ImageProxyRepositoryImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreUiModule = module {
    single<ImageCacheRepository> { ImageCacheRepositoryImpl(androidContext()) }
    single<ImageProxyRepository> { ImageProxyRepositoryImpl(androidContext()) }
}
