package com.filmax.core.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey

/**
 * Помечает короткий API-запрос как часть фоновой очереди. Такой запрос по-прежнему учитывается в
 * сетевой статистике, но не должен сам продлевать cooldown этой же очереди.
 */
private val BackgroundNetworkRequestKey = AttributeKey<Unit>("FilmaxBackgroundNetworkRequest")

fun HttpRequestBuilder.markAsBackgroundNetworkRequest() {
    attributes.put(BackgroundNetworkRequestKey, Unit)
}

internal val HttpRequestBuilder.isBackgroundNetworkRequest: Boolean
    get() = attributes.contains(BackgroundNetworkRequestKey)
