package com.filmax.core.domain.cache

import com.filmax.core.domain.catalog.model.ItemType

/**
 * Размер картинки в ключе кэша. [key] — суффикс ключа; сам суффикс наружу не выходит, только
 * через [ImageCacheKeys]: постер и бэкдроп одного тайтла обязаны жить как РАЗНЫЕ записи.
 */
enum class PosterSize(internal val key: String) {
    Small("poster_small"),
    Medium("poster_medium"),
    Big("poster_big"),

    /** Широкий 16:9 бэкдроп (`posters.wide`). */
    Wall("wall"),
}

/**
 * Ключи кэша картинок вида `entityType:entityId:subId` — независимы от URL (см.
 * `CacheableImage` в core:ui). Живёт в domain, а не в core:ui: тем же ключом теперь
 * пользуется и фоновый прогрев кэша ([ImagePrefetcher]), который стартует из data-мапперов
 * (`ItemDto.toDomain()`), не имеющих доступа к core:ui.
 *
 * Тип тайтла и размер — только типизированные ([ItemType], [PosterSize]): строка ключа собирается
 * здесь и больше нигде, так что «movie» и «poster_medium» невозможно перепутать местами.
 */
object ImageCacheKeys {
    fun poster(itemType: ItemType, itemId: Int, size: PosterSize): String = "${itemType.apiValue}:$itemId:${size.key}"

    fun actorPhoto(name: String): String = "actor:${name.trim().lowercase()}:photo"

    fun episodeThumbnail(trackId: Int): String = "track:$trackId:thumb"

    fun collectionPoster(collectionId: Int, size: PosterSize): String = "collection:$collectionId:${size.key}"
}
