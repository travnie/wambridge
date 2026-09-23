package io.github.trvny.wambridge.mobile

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.nio.charset.StandardCharsets
import org.json.JSONObject

internal data class RadioMetadataPoll(
    val nowPlaying: RadioNowPlaying,
    val refreshAfterMs: Long,
)

internal fun hasRadioParadiseMetadata(station: MobileRadioStation): Boolean =
    station.urls.any { raw ->
        runCatching { URI(raw).host.orEmpty().lowercase() }
            .getOrDefault("")
            .let { host -> host == "radioparadise.com" || host.endsWith(".radioparadise.com") }
    }

internal fun parseRadioParadiseNowPlaying(text: String): RadioMetadataPoll {
    val root = JSONObject(text)
    val artist = root.optString("artist").trim().takeIf(String::isNotEmpty)
    val track = root.optString("title").trim().takeIf(String::isNotEmpty)
    val title = listOfNotNull(artist, track).joinToString(" - ").takeIf(String::isNotEmpty)
    val artwork = sequenceOf("cover_med", "cover", "cover_small")
        .map { root.optString(it).trim() }
        .mapNotNull(::likelyArtworkUrl)
        .firstOrNull()
    val remainingSeconds = root.optLong("time", DEFAULT_REFRESH_SECONDS)
        .coerceIn(MIN_REFRESH_SECONDS, MAX_REFRESH_SECONDS)
    return RadioMetadataPoll(
        nowPlaying = RadioNowPlaying(title = title, artworkUrl = artwork),
        refreshAfterMs = remainingSeconds * 1_000L + REFRESH_SLOP_MS,
    )
}

internal fun fetchRadioParadiseNowPlaying(context: Context): RadioMetadataPoll {
    var lastError: Exception? = null
    val source = URL(RADIO_PARADISE_NOW_PLAYING)
    for (connection in WifiLan.openHttpConnections(context.applicationContext, source)) {
        connection.apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = true
            requestMethod = "GET"
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "WAMBridge-Mobile/0.1")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IOException("Radio Paradise metadata HTTP ${connection.responseCode}")
            }
            return parseRadioParadiseNowPlaying(readBounded(connection))
        } catch (error: Exception) {
            lastError = error
        } finally {
            connection.disconnect()
        }
    }
    throw lastError ?: IOException("No active Wi-Fi network for Radio Paradise metadata")
}

private fun readBounded(connection: HttpURLConnection): String {
    val declared = connection.contentLengthLong
    if (declared > MAX_RESPONSE_BYTES) throw IOException("Radio Paradise metadata too large")
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(4 * 1024)
    connection.inputStream.use { input ->
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            if (output.size() + count > MAX_RESPONSE_BYTES) {
                throw IOException("Radio Paradise metadata too large")
            }
            output.write(buffer, 0, count)
        }
    }
    return output.toString(StandardCharsets.UTF_8.name())
}

private const val RADIO_PARADISE_NOW_PLAYING =
    "https://api.radioparadise.com/api/now_playing?chan=0"
private const val TIMEOUT_MS = 5_000
private const val MAX_RESPONSE_BYTES = 64 * 1024
private const val MIN_REFRESH_SECONDS = 10L
private const val MAX_REFRESH_SECONDS = 5 * 60L
private const val DEFAULT_REFRESH_SECONDS = 30L
private const val REFRESH_SLOP_MS = 1_500L
