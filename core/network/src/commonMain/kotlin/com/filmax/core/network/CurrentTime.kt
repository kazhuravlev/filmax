package com.filmax.core.network

/** Настенное время в миллисекундах — для TTL персистентного кэша (`ItemDetailsCacheImpl`). */
internal expect fun currentTimeMillis(): Long
