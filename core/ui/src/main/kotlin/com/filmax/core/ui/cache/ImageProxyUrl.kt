package com.filmax.core.ui.cache

import java.net.URLEncoder

fun proxiedImageUrl(url: String, proxyEnabled: Boolean): String =
    if (proxyEnabled) "$IMAGE_PROXY_URL${URLEncoder.encode(url, "UTF-8")}" else url

private const val IMAGE_PROXY_URL = "https://kwip.dev-services.workers.dev/img?url="
