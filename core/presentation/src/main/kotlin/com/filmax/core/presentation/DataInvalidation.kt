package com.filmax.core.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

enum class DataDomain {
    WATCHING,

    BOOKMARKS,
}

object DataInvalidation {
    private val dirty = MutableStateFlow<Set<DataDomain>>(emptySet())

    fun markDirty(vararg domains: DataDomain) {
        dirty.update { it + domains }
    }

    fun consumeDirty(domain: DataDomain): Boolean {
        var wasDirty = false
        dirty.update { current ->
            if (domain in current) {
                wasDirty = true
                current - domain
            } else {
                current
            }
        }
        return wasDirty
    }
}
