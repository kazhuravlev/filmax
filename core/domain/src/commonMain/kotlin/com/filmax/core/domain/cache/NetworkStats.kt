package com.filmax.core.domain.cache

import kotlin.concurrent.Volatile

/**
 * Суммарный сетевой трафик приложения — сугубо для строки «сеть» в оверлее «Показывать
 * технические данные» (см. `TechOverlay` в app), не источник истины ни для какой логики.
 * Копится из двух мест:
 *  - картинки: `FilmaxImageLoaderFactory` (app) считает реально прочитанные байты тела ответа —
 *    и обычной загрузки, и фоновой докачки;
 *  - основной API: `HttpClientFactory` (core:network, `ActivityTrackingPlugin`) добавляет
 *    заявленный `Content-Length` ответа — это приближение (тело может быть chunked без
 *    заголовка, а фактически прочитанное может отличаться), но для диагностической цифры этого
 *    достаточно.
 *
 * `core:domain` — общий commonMain-модуль (компилируется и под iOS/tvOS-таргеты, хоть их никто
 * не потребляет), а `kotlinx.atomicfu` в проекте не подключён и `kotlin.synchronized` в common
 * коде недоступен (это JVM-only функция stdlib) — честный атомарный счётчик здесь пришлось бы
 * тянуть через expect/actual ради одной статистической цифры. Вместо этого — обычный
 * `@Volatile var` с невзведённым `+=`: пишут сюда часто (на каждый сетевой ответ) из разных
 * потоков без какой-либо синхронизации, поэтому теоретически возможна гонка «читать-изменить-
 * записать», при которой один из двух почти одновременных `addBytes` потеряется. Это не страшно —
 * счётчик исключительно для диагностической скорости в строке «сеть» оверлея, а не для чего-то,
 * где точность имеет значение (в отличие, например, от [ItemDetailsCache.count]).
 */
object NetworkStats {
    @Volatile
    private var totalBytesState: Long = 0L

    val totalBytes: Long
        get() = totalBytesState

    fun addBytes(bytes: Long) {
        if (bytes <= 0) return
        totalBytesState += bytes
    }
}
