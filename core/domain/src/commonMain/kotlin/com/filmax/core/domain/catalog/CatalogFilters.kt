package com.filmax.core.domain.catalog

data class CatalogFilters(
    val yearFrom: Int? = null,
    val yearTo: Int? = null,
    val kpRatingFrom: Int? = null,
    val imdbRatingFrom: Int? = null,
    val countryId: Int? = null,
    val only4k: Boolean = false,
    val onlyFinished: Boolean? = null,
) {
    val activeCount: Int
        get() = listOf(
            yearFrom != null || yearTo != null,
            kpRatingFrom != null,
            imdbRatingFrom != null,
            countryId != null,
            only4k,
            onlyFinished != null,
        ).count { active -> active }
}
