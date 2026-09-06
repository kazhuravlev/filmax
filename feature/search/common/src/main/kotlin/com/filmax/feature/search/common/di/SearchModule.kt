package com.filmax.feature.search.common.di

import com.filmax.core.domain.common.LastValueCache
import com.filmax.feature.search.common.CatalogSnapshot
import com.filmax.feature.search.common.FilmographyScreenModel
import com.filmax.feature.search.common.SearchScreenModel
import org.koin.core.module.dsl.viewModel
import org.koin.core.module.dsl.viewModelOf
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Квалификатор кэша [CatalogSnapshot]. ОБЯЗАТЕЛЕН: Koin ключует `single` по стёртому (erasure)
 * классу — без квалификатора `LastValueCache<CatalogSnapshot>` был бы неотличим от других
 * `single { LastValueCache<...>() }` в приложении (см. `homeModule`, `libraryModule`) и они бы
 * столкнулись в одном DI-контейнере. Публичный — нужен и `AppWarmup` в модуле `:app`
 * (см. `app/di/AppModule.kt`), чтобы просить ровно этот кэш, а не чей-то чужой.
 */
val CATALOG_SNAPSHOT_CACHE: Qualifier = named("catalog_snapshot")

val searchFeatureModule = module {
    // Кэш последнего успешного снимка дефолтной витрины каталога — single, чтобы переживать
    // пересоздание ScreenModel и служить затравкой для фонового прогрева AppWarmup.
    single(qualifier = CATALOG_SNAPSHOT_CACHE) { LastValueCache<CatalogSnapshot>() }
    // Не viewModelOf(::SearchScreenModel): последнему параметру конструктора нужен КВАЛИФИЦИРОВАННЫЙ
    // кэш (см. doc CATALOG_SNAPSHOT_CACHE выше), а автоматическая constructor-DSL зовёт для каждого
    // параметра только безусловный get().
    viewModel {
        SearchScreenModel(
            search = get(),
            catalog = get(),
            catalogSnapshotCache = get(CATALOG_SNAPSHOT_CACHE),
        )
    }
    viewModelOf(::FilmographyScreenModel)
}
