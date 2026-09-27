
package com.filmax.feature.details.common

internal fun actorPhotoUrl(name: String): String = "https://m.staticpop.net/actors/${md5Hex(name)}.jpg"

private fun md5Hex(input: String): String {
    val digest = java.security.MessageDigest.getInstance("MD5").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
