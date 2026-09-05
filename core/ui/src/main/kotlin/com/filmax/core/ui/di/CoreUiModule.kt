package com.filmax.core.ui.di

import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.ImageProxyRepository
import com.filmax.core.ui.cache.ImageCacheRepositoryImpl
import com.filmax.core.ui.cache.ImagePrefetcherImpl
import com.filmax.core.ui.cache.ImageProxyRepositoryImpl
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val coreUiModule = module {
    single<ImageCacheRepository>(createdAtStart = true) { ImageCacheRepositoryImpl(androidContext()) }
    single<ImageProxyRepository> { ImageProxyRepositoryImpl(androidContext()) }
    // createdAtStart — очередь обнаружения (ImageDiscovery) должна быть готова ДО первого списка
    // тайтлов: CatalogMapper.toDomain() зовёт её напрямую, без DI, и без этого флага синглтон
    // создался бы лениво, только когда кто-то явно его инжектит (см. ImagePrefetcherImpl).
    single<ImagePrefetcher>(createdAtStart = true) { ImagePrefetcherImpl(androidContext()) }
}
