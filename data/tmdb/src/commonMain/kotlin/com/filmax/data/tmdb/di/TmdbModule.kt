package com.filmax.data.tmdb.di

import com.filmax.core.domain.person.CastRepository
import com.filmax.data.tmdb.TmdbCastRepositoryImpl
import com.filmax.data.tmdb.remote.TmdbApi
import org.koin.dsl.module

val tmdbModule = module {
    single { TmdbApi(engine = get(), apiKey = getProperty(TMDB_API_KEY_PROPERTY, "")) }
    single<CastRepository> { TmdbCastRepositoryImpl(api = get()) }
}

const val TMDB_API_KEY_PROPERTY = "tmdbApiKey"
