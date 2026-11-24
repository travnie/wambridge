package io.github.trvny.wambridge.mobile

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import android.widget.ImageView
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

internal fun artworkSampleSize(width: Int, height: Int, maxEdge: Int): Int {
    require(maxEdge > 0)
    var sample = 1
    while (width / sample > maxEdge * 2 || height / sample > maxEdge * 2) {
        sample *= 2
    }
    return sample
}

internal fun artworkUrlCandidates(value: String): List<String> {
    val cleaned = value.trim()
    if (cleaned.isEmpty()) return emptyList()
    val candidates = linkedSetOf(cleaned)
    val match = TUNEIN_LOGO_EXTENSION.matchEntire(cleaned) ?: return candidates.toList()
    val extension = match.groupValues[2].lowercase()
    val replacement = if (extension == "png") "jpg" else "png"
    candidates += match.groupValues[1] + replacement + match.groupValues[3]
    return candidates.toList()
}

/** Shared, Wi-Fi-bound artwork cache for TuneIn lists and Home Now Playing. */
internal object ArtworkLoader {
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "wam-mobile-artwork").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun cached(url: String?): Bitmap? {
        val key = url?.trim()?.takeIf(::isHttpUrl) ?: return null
        return synchronized(cache) { cache.get(key) }
    }

    fun prefetch(
        context: Context,
        url: String?,
        onLoaded: (Bitmap?) -> Unit = {},
    ) {
        val key = url?.trim()?.takeIf(::isHttpUrl)
        if (key == null) {
            onLoaded(null)
            return
        }
        cached(key)?.let {
            onLoaded(it)
            return
        }
        val appContext = context.applicationContext
        executor.execute {
            val bitmap = runCatching { download(appContext, key) }.getOrNull()
            if (bitmap != null) synchronized(cache) { cache.put(key, bitmap) }
            onLoaded(bitmap)
        }
    }

    fun load(
        context: Context,
        view: ImageView,
        url: String?,
        placeholderRes: Int? = null,
    ) {
        val key = url?.trim()?.takeIf(::isHttpUrl)
        if (key != null) {
            synchronized(cache) { cache.get(key) }?.let { bitmap ->
                view.tag = key
                view.setImageBitmap(bitmap)
                return
            }
        }
        if (key != null && view.tag == key) return

        view.tag = key
        placeholderRes?.let(view::setImageResource)
        if (key == null) return

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = runCatching { download(appContext, key) }.getOrNull()
            if (bitmap == null) {
                view.post {
                    if (view.tag == key) view.tag = null
                }
                return@execute
            }
            synchronized(cache) { cache.put(key, bitmap) }
            view.post {
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)

    private fun download(context: Context, address: String): Bitmap {
        var lastError: IOException? = null
        for (candidate in artworkUrlCandidates(address)) {
            try {
                return downloadCandidate(context, candidate)
            } catch (error: IOException) {
                lastError = error
            }
        }
        throw lastError ?: IOException("No usable artwork URL")
    }

    private fun downloadCandidate(context: Context, address: String): Bitmap {
        var lastError: IOException? = null
        for (connection in WifiLan.openHttpConnections(context, URL(address))) {
            connection.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = true
                // Keep TRAVNY cover URLs on the trusted artwork origin.
                instanceFollowRedirects = !isTravnyRadioArtworkUrl(address)
                requestMethod = "GET"
            }
            try {
                return decodeArtwork(connection)
            } catch (error: IOException) {
                lastError = error
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("No active Wi-Fi network")
    }

    private fun decodeArtwork(connection: HttpURLConnection): Bitmap {
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            throw IOException("Artwork HTTP ${connection.responseCode}")
        }
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8 * 1024)
        connection.inputStream.use { input ->
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_BYTES) {
                    throw IOException("Artwork too large")
                }
                out.write(buffer, 0, count)
            }
        }
        val bytes = out.toByteArray()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw IOException("Unsupported artwork image")
        }
        val options = BitmapFactory.Options().apply {
            inSampleSize = artworkSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                maxEdge = MAX_BITMAP_EDGE,
            )
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw IOException("Unsupported artwork image")
        if (decoded.width <= MAX_BITMAP_EDGE && decoded.height <= MAX_BITMAP_EDGE) {
            return decoded
        }
        val scale = minOf(
            MAX_BITMAP_EDGE.toFloat() / decoded.width,
            MAX_BITMAP_EDGE.toFloat() / decoded.height,
        )
        val scaled = Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).toInt().coerceAtLeast(1),
            (decoded.height * scale).toInt().coerceAtLeast(1),
            true,
        )
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    private const val CACHE_KIB = 4 * 1024
    private const val MAX_BITMAP_EDGE = 512
    private const val TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 1024 * 1024
}

private val TUNEIN_LOGO_EXTENSION =
    Regex("(?i)^(https://cdn-profiles\\.tunein\\.com/.*/images/logo[dt]\\.)(png|jpg)(\\?.*)?$")
