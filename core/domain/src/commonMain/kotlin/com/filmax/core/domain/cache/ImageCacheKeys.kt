package com.filmax.core.domain.cache

/**
 * Ключи кэша картинок вида `entityType:entityId:subId` — независимы от URL (см.
 * `CacheableImage` в core:ui). Живёт в domain, а не в core:ui: тем же ключом теперь
 * пользуется и фоновый прогрев кэша ([ImagePrefetcher]), который стартует из data-мапперов
 * (`ItemDto.toDomain()`), не имеющих доступа к core:ui.
 */
object ImageCacheKeys {
    const val SIZE_SMALL = "poster_small"
    const val SIZE_MEDIUM = "poster_medium"
    const val SIZE_BIG = "poster_big"
    const val WALL = "wall"

    fun poster(itemType: String, itemId: Int, size: String): String = "$itemType:$itemId:$size"

    fun actorPhoto(name: String): String = "actor:${name.trim().lowercase()}:photo"

    fun episodeThumbnail(trackId: Int): String = "track:$trackId:thumb"

    fun collectionPoster(collectionId: Int, size: String): String = "collection:$collectionId:$size"
}
