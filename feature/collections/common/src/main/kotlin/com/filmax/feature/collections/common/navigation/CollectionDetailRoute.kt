package com.filmax.feature.collections.common.navigation

import kotlinx.serialization.Serializable

/**
 * Экран одной подборки. Живёт в слое логики — его читает CollectionDetailScreenModel.
 *
 * Маршрута самого раздела «Подборки» здесь нет: вкладкой они быть перестали и живут содержимым
 * Каталога и рядом на Главной — оттуда и открывается конкретная подборка.
 */
@Serializable
data class CollectionDetailRoute(val collectionId: Int, val title: String)
