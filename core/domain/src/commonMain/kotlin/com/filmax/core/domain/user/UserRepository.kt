package com.filmax.core.domain.user

import com.filmax.core.domain.catalog.model.ItemPage
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.user.model.BookmarkFolder
import com.filmax.core.domain.user.model.DeviceSettings
import com.filmax.core.domain.user.model.UserProfile

// Контракт профиля/устройства/закладок целиком — дробить интерфейс ради лимита незачем.
@Suppress("TooManyFunctions")
interface UserRepository {

    suspend fun getProfile(): RequestResult<UserProfile>

    suspend fun getDeviceSettings(): RequestResult<DeviceSettings>

    suspend fun updateDeviceSettings(settings: DeviceSettings): RequestResult<Unit>

    suspend fun registerDevice(title: String, hardware: String, software: String): RequestResult<Unit>

    suspend fun getBookmarkFolders(): RequestResult<List<BookmarkFolder>>

    suspend fun getBookmarkItems(folderId: Int, page: Int = 1): RequestResult<ItemPage>

    /**
     * Id папок-закладок, в которых лежит [itemId] — одним запросом (`bookmarks/get-item-folders`,
     * тот же эндпоинт, которым эталонный веб-клиент kino.watch красит галочки в диалоге подборок).
     * Заменяет постраничный обход каждой папки ради одного тайтла (см. [isItemInBookmark]).
     */
    suspend fun getItemBookmarkFolderIds(itemId: Int): RequestResult<Set<Int>>

    suspend fun createBookmarkFolder(title: String): RequestResult<BookmarkFolder>

    suspend fun deleteBookmarkFolder(folderId: Int): RequestResult<Unit>

    suspend fun addToBookmark(itemId: Int, folderId: Int): RequestResult<Unit>

    suspend fun removeFromBookmark(itemId: Int, folderId: Int): RequestResult<Unit>
}
