package com.filmax.app.diagnostics

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.filmax.core.domain.cache.ImageCacheRepository
import com.filmax.core.domain.cache.ImagePrefetchThrottle
import com.filmax.core.domain.cache.ImagePrefetcher
import com.filmax.core.domain.cache.ItemDetailsCache
import com.filmax.core.domain.cache.NetworkStats
import com.filmax.core.domain.cache.TechOverlaySettings
import com.filmax.core.domain.cache.TitleBackgroundFetcher
import kotlinx.coroutines.delay
import org.koin.compose.koinInject
import java.util.Locale

@Composable
fun TechOverlay() {
    val settings: TechOverlaySettings = koinInject()
    val enabled by settings.enabled.collectAsState()
    if (!enabled) return

    val titleFetcher: TitleBackgroundFetcher = koinInject()
    val imagePrefetcher: ImagePrefetcher = koinInject()
    val itemCache: ItemDetailsCache = koinInject()
    val imageCache: ImageCacheRepository = koinInject()

    val titleProgress by titleFetcher.progress.collectAsState()
    val imageProgress by imagePrefetcher.progress.collectAsState()
    val itemCacheCount by itemCache.count.collectAsState()
    val imageCacheStats by imageCache.stats.collectAsState()

    var speedLabel by remember { mutableStateOf("0 КБ/с") }
    var throttled by remember { mutableStateOf(ImagePrefetchThrottle.shouldThrottle) }
    var cooldownRemainingMillis by remember {
        mutableStateOf(ImagePrefetchThrottle.cooldownRemainingMillis)
    }
    var playbackActive by remember { mutableStateOf(ImagePrefetchThrottle.isPlaybackActive) }
    LaunchedEffect(Unit) {
        var lastBytes = NetworkStats.totalBytes
        while (true) {
            delay(REFRESH_INTERVAL_MS)
            val bytes = NetworkStats.totalBytes
            speedLabel = formatSpeed(bytes - lastBytes)
            lastBytes = bytes
            throttled = ImagePrefetchThrottle.shouldThrottle
            cooldownRemainingMillis = ImagePrefetchThrottle.cooldownRemainingMillis
            playbackActive = ImagePrefetchThrottle.isPlaybackActive
        }
    }

    val usedMb = imageCacheStats.sizeBytes / BYTES_PER_MB
    val maxMb = imageCacheStats.maxSizeBytes / BYTES_PER_MB

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(OverlayPadding),
        ) {
            OverlayLine(
                "тайтлы: очередь ${titleProgress.remaining} · скачано ${titleProgress.downloaded} " +
                    "· в кэше $itemCacheCount/$ITEM_CACHE_MAX_ENTRIES",
            )
            OverlayLine(
                "картинки: очередь ${imageProgress.remaining} · скачано ${imageProgress.downloaded} " +
                    String.format(Locale.US, "· диск %.0f/%.0f МБ", usedMb, maxMb),
            )
            OverlayLine(
                "сеть: скорость $speedLabel · троттлинг " +
                    formatThrottle(throttled, playbackActive, cooldownRemainingMillis),
            )
        }
    }
}

private fun formatThrottle(throttled: Boolean, playbackActive: Boolean, cooldownMillis: Long): String = when {
    !throttled -> "нет"
    playbackActive -> "да · плеер"
    else -> "да · кулдаун ${(cooldownMillis + MILLIS_PER_SECOND - 1L) / MILLIS_PER_SECOND} с"
}

@Composable
private fun OverlayLine(text: String) {
    Text(
        text = text,
        color = Color.White.copy(alpha = OVERLAY_TEXT_ALPHA),
        fontSize = OVERLAY_FONT_SIZE_SP.sp,
        fontFamily = FontFamily.Monospace,
    )
}

private fun formatSpeed(bytesPerInterval: Long): String {
    val kbPerSecond = bytesPerInterval / BYTES_PER_KB
    return if (kbPerSecond >= KB_PER_MB) {
        String.format(Locale.US, "%.1f МБ/с", kbPerSecond / KB_PER_MB)
    } else {
        String.format(Locale.US, "%.0f КБ/с", kbPerSecond)
    }
}

private const val REFRESH_INTERVAL_MS = 1_000L
private const val MILLIS_PER_SECOND = 1_000L
private const val BYTES_PER_KB = 1024.0
private const val KB_PER_MB = 1024.0
private const val BYTES_PER_MB = 1024.0 * 1024.0

private val OverlayPadding = 12.dp
private const val OVERLAY_FONT_SIZE_SP = 10
private const val OVERLAY_TEXT_ALPHA = 0.7f

private const val ITEM_CACHE_MAX_ENTRIES = 2000
