package com.filmax.core.domain.cache

import com.filmax.core.domain.catalog.model.ItemType

enum class PosterSize(internal val key: String) {
    Small("poster_small"),
    Medium("poster_medium"),
    Big("poster_big"),

    Wall("wall"),
}

object ImageCacheKeys {
    fun poster(itemType: ItemType, itemId: Int, size: PosterSize): String = "${itemType.apiValue}:$itemId:${size.key}"

    fun actorPhoto(name: String): String = "actor:${name.trim().lowercase()}:photo"

    fun episodeThumbnail(trackId: Int): String = "track:$trackId:thumb"

    fun collectionPoster(collectionId: Int, size: PosterSize): String = "collection:$collectionId:${size.key}"
}
