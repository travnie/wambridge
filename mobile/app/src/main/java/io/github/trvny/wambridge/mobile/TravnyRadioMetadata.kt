package io.github.trvny.wambridge.mobile

import android.content.Context
import java.io.IOException
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

private const val TRAVNY_METADATA_URL = "https://travny-radio.taild44c6c.ts.net/api/now.json"
private const val TRAVNY_ARTWORK_ORIGIN = "https://travny-radio.taild44c6c.ts.net"
private const val TRAVNY_REFRESH_MS = 15_000L
private const val TRAVNY_TIMEOUT_MS = 5_000
private val TRAVNY_COVER_PATH = Regex("^/artwork/[a-f0-9]{20}\\.(?:jpg|png)$")

internal fun hasTravnyRadioMetadata(station: MobileRadioStation): Boolean =
    station.urls.any { raw ->
        val uri = runCatching { URI(raw) }.getOrNull() ?: return@any false
        uri.host?.lowercase() in setOf("radio.bartek.lol", "travny-radio.taild44c6c.ts.net") &&
            uri.path == "/radio.mp3" &&
            uri.scheme.equals("https", ignoreCase = true)
    }

/** Only allow cover paths from the trusted radio's own artwork directory. */
internal fun parseTravnyRadioNowPlaying(text: String): RadioMetadataPoll {
    val root = JSONObject(text)
    val song = root.optJSONObject("song").takeIf { root.optBoolean("live", false) }
    val artist = song?.optString("artist")?.trim()?.takeIf(String::isNotEmpty)
    val title = song?.optString("title")?.trim()?.takeIf(String::isNotEmpty)
    val combined = listOfNotNull(artist, title).joinToString(" - ").takeIf(String::isNotBlank)
    val cover = song?.optString("cover")?.trim()
        ?.takeIf(TRAVNY_COVER_PATH::matches)
        ?.let { TRAVNY_ARTWORK_ORIGIN + it }
    return RadioMetadataPoll(
        nowPlaying = RadioNowPlaying(
            title = combined,
            artworkUrl = cover,
        ),
        refreshAfterMs = TRAVNY_REFRESH_MS,
    )
}

internal fun fetchTravnyRadioNowPlaying(context: Context): RadioMetadataPoll {
    var lastError: Exception? = null
    val source = URL(TRAVNY_METADATA_URL)
    for (connection in WifiLan.openHttpConnections(context.applicationContext, source)) {
        connection.apply {
            connectTimeout = TRAVNY_TIMEOUT_MS
            readTimeout = TRAVNY_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WAMBridge-Mobile/0.1")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("TRAVNY Radio metadata HTTP ${connection.responseCode}")
            }
            return parseTravnyRadioNowPlaying(readBoundedRadioMetadata(connection))
        } catch (error: Exception) {
            lastError = error
        } finally {
            connection.disconnect()
        }
    }
    throw lastError ?: IOException("No active Wi-Fi network for TRAVNY Radio metadata")
}
