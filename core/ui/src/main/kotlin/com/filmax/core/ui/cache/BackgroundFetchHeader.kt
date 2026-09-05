package com.filmax.core.ui.cache

/**
 * HTTP-заголовок-маркер: этим запросом картинку тянет фоновая очередь ([ImagePrefetcherImpl]),
 * а не экран, который сейчас смотрит пользователь. `FilmaxImageLoaderFactory` (app) читает его
 * в сетевом интерцепторе, чтобы придушить скорость именно фоновой закачки — обычные запросы
 * идут без ограничений. До сервера не доезжает: интерцептор снимает заголовок перед отправкой.
 */
const val BACKGROUND_FETCH_HEADER = "X-Filmax-Background-Fetch"
