package com.filmax.core.network

import io.ktor.client.plugins.logging.Logger

internal class SecretMaskingLogger(private val delegate: Logger) : Logger {
    override fun log(message: String) {
        delegate.log(mask(message))
    }

    private fun mask(message: String): String =
        MASKED_PARAMS.fold(message) { masked, param -> param.regex.replace(masked, param.replacement) }

    private companion object {
        val MASKED_PARAMS = listOf(
            MaskRule("""(?<=[?&](refresh_token|access_token|client_secret|code)=)[^&\s]+""", "***"),
            MaskRule("""(?<="(refresh_token|access_token)":")[^"]+""", "***"),
        )
    }
}

private class MaskRule(pattern: String, val replacement: String) {
    val regex = Regex(pattern)
}
