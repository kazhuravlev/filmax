package com.filmax.core.ui.cache

import android.content.Context
import coil3.disk.DiskCache
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Регрессия на «размер кэша в настройках был счётчиком, который только рос»: [ImageCacheStats]
 * теперь обязано быть честным зеркалом [DiskCache.size]/[DiskCache.maxSize] — реального состояния
 * диска, а не отдельно накопленной цифрой, которая могла разойтись с ним как угодно (именно это
 * и путало пользователя — «Очистить кеш» показывала 2 ГБ, хотя Coil сам держит диск в пределах
 * 250 МБ лимита).
 *
 * [context] нигде не вызывается: оба продовых лямбда-параметра ([ImageCacheRepositoryImpl]'s
 * diskCacheProvider/clearCaches) переопределены фейками, а сам Context нужен только чтобы
 * удовлетворить тип конструктора — mockk() тут не более чем заглушка.
 */
class ImageCacheRepositoryImplTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `stats reflect the live disk cache size right after construction`() {
        val diskCache = FakeDiskCache(size = 42_000_000L, maxSize = 250_000_000L)
        val repository = ImageCacheRepositoryImpl(context, diskCacheProvider = { diskCache })

        val stats = repository.stats.value

        assertEquals(42_000_000L, stats.sizeBytes)
        assertEquals(250_000_000L, stats.maxSizeBytes)
    }

    @Test
    fun `clear resets stats to whatever the disk cache reports afterward, not a separate counter`() {
        val diskCache = FakeDiskCache(size = 100_000_000L, maxSize = 250_000_000L)
        val repository = ImageCacheRepositoryImpl(
            context,
            diskCacheProvider = { diskCache },
            clearCaches = { diskCache.size = 0L },
        )

        runBlocking { repository.clear() }

        val stats = repository.stats.value
        assertEquals(0L, stats.sizeBytes)
        assertEquals(250_000_000L, stats.maxSizeBytes)
    }

    @Test
    fun `no disk cache yet (Coil not initialized) reports zeroed stats, not a crash`() {
        val repository = ImageCacheRepositoryImpl(context, diskCacheProvider = { null })

        val stats = repository.stats.value

        assertEquals(0L, stats.sizeBytes)
        assertEquals(0L, stats.maxSizeBytes)
    }

    /** Только size/maxSize нужны коду под тестом — остальное `DiskCache` тут не задействуется. */
    private class FakeDiskCache(override var size: Long, override var maxSize: Long) : DiskCache {
        override val directory: Path get() = error("not used by ImageCacheRepositoryImpl")
        override val fileSystem: FileSystem get() = error("not used by ImageCacheRepositoryImpl")
        override fun openSnapshot(key: String): DiskCache.Snapshot? = error("not used by ImageCacheRepositoryImpl")
        override fun openEditor(key: String): DiskCache.Editor? = error("not used by ImageCacheRepositoryImpl")
        override fun remove(key: String): Boolean = error("not used by ImageCacheRepositoryImpl")
        override fun clear() = error("not used by ImageCacheRepositoryImpl")
        override fun shutdown() = Unit
    }
}
