package com.filmax.data.watching

import com.filmax.core.domain.catalog.model.Item
import com.filmax.core.domain.common.RequestResult
import com.filmax.core.domain.common.getOrNull
import com.filmax.core.domain.user.UserRepository
import com.filmax.core.domain.user.getDedupedBookmarkItems
import com.filmax.core.domain.user.isItemInBookmark
import com.filmax.core.domain.watching.WatchingNowRepository
import com.russhwolf.settings.Settings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * «Watching Now» — та же механика, что и «Буду смотреть» в [FavoritesRepositoryImpl] (папка-
 * закладка, найти/создать по имени, id закеширован), но БЕЗ дискового кэша: это не основной
 * список, а страховка для фильмов, и на холодный офлайн-старт можно просто не показать пункт —
 * следующий онлайн-заход довоспроизведёт всё с сервера.
 */
internal class WatchingNowRepositoryImpl(
    private val userRepository: UserRepository,
    private val settings: Settings,
) : WatchingNowRepository {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val folderMutex = Mutex()
    private val state = MutableStateFlow<List<Item>>(emptyList())

    init {
        scope.launch { refresh() }
    }

    override fun isMember(id: Int): Flow<Boolean> = state.map { list -> list.any { it.id == id } }

    override suspend fun toggle(item: Item): Boolean =
        if (state.value.any { it.id == item.id }) {
            remove(item.id)
            false
        } else {
            add(item)
            true
        }

    private suspend fun add(item: Item) {
        // Оптимистично: значок на экране деталей реагирует мгновенно, сервер догоняет.
        state.value = state.value.filterNot { it.id == item.id } + item
        val folderId = ensureFolderId() ?: return
        // Проверка по серверу, а не только по локальному [state]: он мог отстать от реальности
        // (переустановка, другое устройство, ручной вызов API) — иначе повторный addToBookmark на
        // уже существующую связь и есть источник дублей в папке.
        if (!userRepository.isItemInBookmark(item.id, folderId)) {
            userRepository.addToBookmark(item.id, folderId)
        }
    }

    private suspend fun remove(id: Int) {
        state.value = state.value.filterNot { it.id == id }
        val folderId = ensureFolderId() ?: return
        userRepository.removeFromBookmark(id, folderId)
    }

    /**
     * [UserRepository.getDedupedBookmarkItems] чистит дубликаты СЕРВЕРНОЙ связи `(folderId, id)`
     * до того, как список попадёт в кэш/на экран «Я смотрю» — иначе накопленные дубли пережили бы
     * любое количество перезапусков.
     */
    override suspend fun getAll(): RequestResult<List<Item>> {
        val folderId = ensureFolderId() ?: return RequestResult.Success(emptyList())
        val result = userRepository.getDedupedBookmarkItems(folderId, MAX_PAGES)
        // Кэшированный id мог протухнуть (папку удалили на сервере вручную) — сбрасываем кэш,
        // чтобы следующий вызов заново нашёл/создал папку, а не бился в мёртвый id вечно.
        if (result is RequestResult.Error) settings.remove(FOLDER_ID_KEY)
        return result
    }

    /** Перечитывает папку с сервера в кэш. Тихо выходит, если папки/сети нет. */
    private suspend fun refresh() {
        (getAll() as? RequestResult.Success)?.let { state.value = it.data }
    }

    /**
     * Id папки «Watching Now»: из кэша, иначе найти по имени, иначе создать. Под мьютексом —
     * иначе два параллельных вызова создали бы две одноимённые папки (тот же приём, что и в
     * [FavoritesRepositoryImpl.ensureFolderId]).
     */
    private suspend fun ensureFolderId(): Int? = folderMutex.withLock {
        settings.getIntOrNull(FOLDER_ID_KEY)?.let { return it }
        val folders = userRepository.getBookmarkFolders().getOrNull() ?: return null
        val existing = folders.firstOrNull { it.title == FOLDER_TITLE }
        val folderId = existing?.id
            ?: userRepository.createBookmarkFolder(FOLDER_TITLE).getOrNull()?.id
            ?: return null
        settings.putInt(FOLDER_ID_KEY, folderId)
        folderId
    }

    private companion object {
        const val FOLDER_TITLE = "Watching Now"
        const val FOLDER_ID_KEY = "watching_now_folder_id"
        const val MAX_PAGES = 10
    }
}
