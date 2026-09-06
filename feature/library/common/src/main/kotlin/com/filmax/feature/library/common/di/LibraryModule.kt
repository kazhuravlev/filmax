package com.filmax.feature.library.common.di

import com.filmax.core.domain.common.LastValueCache
import com.filmax.feature.library.common.LibraryScreenModel
import com.filmax.feature.library.common.LibrarySnapshot
import org.koin.core.module.dsl.viewModel
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Квалификатор кэша [LibrarySnapshot]. ОБЯЗАТЕЛЕН: Koin ключует `single` по стёртому (erasure)
 * классу — без квалификатора `LastValueCache<LibrarySnapshot>` был бы неотличим от других
 * `single { LastValueCache<...>() }` в приложении (см. `homeModule`, `searchFeatureModule`) и
 * они бы столкнулись в одном DI-контейнере. Публичный — нужен и `AppWarmup` в модуле `:app`
 * (см. `app/di/AppModule.kt`), чтобы просить ровно этот кэш, а не чей-то чужой.
 */
val LIBRARY_SNAPSHOT_CACHE: Qualifier = named("library_snapshot")

val libraryModule = module {
    // Кэш последнего успешного лёгкого снимка «Моё» — single, чтобы переживать пересоздание
    // ScreenModel и служить и офлайн-устойчивости, и затравкой для фонового прогрева AppWarmup.
    single(qualifier = LIBRARY_SNAPSHOT_CACHE) { LastValueCache<LibrarySnapshot>() }
    // Не viewModelOf(::LibraryScreenModel): последнему параметру конструктора нужен КВАЛИФИЦИРОВАННЫЙ
    // кэш (см. doc LIBRARY_SNAPSHOT_CACHE выше), а автоматическая constructor-DSL зовёт для каждого
    // параметра только безусловный get().
    viewModel {
        LibraryScreenModel(
            watching = get(),
            user = get(),
            favoritesRepo = get(),
            catalog = get(),
            snapshotCache = get(LIBRARY_SNAPSHOT_CACHE),
        )
    }
}
