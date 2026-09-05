package com.filmax.core.ui.cache

import java.net.URLEncoder

/**
 * Оборачивает [url] через image-прокси, если [proxyEnabled] (см. [com.filmax.core.domain.cache.ImageProxyRepository]).
 * Кодируем исходный URL: постеры kino.watch иногда несут `?`/`&` в query-строке, а без кодирования
 * они разрезали бы параметр `url=` прокси на части.
 */
fun proxiedImageUrl(url: String, proxyEnabled: Boolean): String =
    if (proxyEnabled) "$IMAGE_PROXY_URL${URLEncoder.encode(url, "UTF-8")}" else url

private const val IMAGE_PROXY_URL = "https://kwip.dev-services.workers.dev/img?url="
