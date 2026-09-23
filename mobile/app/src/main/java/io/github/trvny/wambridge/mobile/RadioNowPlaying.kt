package io.github.trvny.wambridge.mobile

import java.net.URI
import java.util.Locale

internal data class RadioNowPlaying(
    val title: String? = null,
    val artworkUrl: String? = null,
)

internal fun likelyArtworkUrl(value: String?): String? {
    val cleaned = value?.trim()?.takeIf(String::isNotEmpty) ?: return null
    val uri = runCatching { URI(cleaned) }.getOrNull() ?: return null
    if (
        !uri.scheme.equals("http", ignoreCase = true) &&
        !uri.scheme.equals("https", ignoreCase = true)
    ) {
        return null
    }
    if (uri.host.isNullOrBlank()) return null
    val path = uri.path.orEmpty().lowercase(Locale.ROOT)
    return cleaned.takeIf { extension ->
        ARTWORK_EXTENSIONS.any(path::endsWith)
    }
}

private val ARTWORK_EXTENSIONS = setOf(
    ".jpg",
    ".jpeg",
    ".png",
    ".webp",
    ".gif",
    ".avif",
)
