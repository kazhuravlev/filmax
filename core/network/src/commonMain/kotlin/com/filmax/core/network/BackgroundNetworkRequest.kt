package com.filmax.core.network

import io.ktor.client.request.HttpRequestBuilder
import io.ktor.util.AttributeKey

private val BackgroundNetworkRequestKey = AttributeKey<Unit>("FilmaxBackgroundNetworkRequest")

fun HttpRequestBuilder.markAsBackgroundNetworkRequest() {
    attributes.put(BackgroundNetworkRequestKey, Unit)
}

internal val HttpRequestBuilder.isBackgroundNetworkRequest: Boolean
    get() = attributes.contains(BackgroundNetworkRequestKey)
