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

/** Shared, Wi-Fi-bound artwork cache for TuneIn lists and Home Now Playing. */
internal object ArtworkLoader {
    private val executor = Executors.newFixedThreadPool(3) { runnable ->
        Thread(runnable, "wam-mobile-artwork").apply { isDaemon = true }
    }
    private val cache = object : LruCache<String, Bitmap>(CACHE_KIB) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
    }

    fun load(
        context: Context,
        view: ImageView,
        url: String?,
        placeholderRes: Int? = null,
    ) {
        val key = url?.trim()?.takeIf(::isHttpUrl)
        if (view.tag == key) return

        view.tag = key
        placeholderRes?.let(view::setImageResource)
        if (key == null) return

        synchronized(cache) { cache.get(key) }?.let { bitmap ->
            view.setImageBitmap(bitmap)
            return
        }

        val appContext = context.applicationContext
        executor.execute {
            val bitmap = runCatching { download(appContext, key) }.getOrNull()
                ?: return@execute
            synchronized(cache) { cache.put(key, bitmap) }
            view.post {
                if (view.tag == key) view.setImageBitmap(bitmap)
            }
        }
    }

    private fun isHttpUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

    private fun download(context: Context, address: String): Bitmap {
        var lastError: Exception? = null
        for (connection in WifiLan.openHttpConnections(context, URL(address))) {
            connection.apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                useCaches = true
                instanceFollowRedirects = true
                requestMethod = "GET"
            }
            try {
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
                return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    ?: throw IOException("Unsupported artwork image")
            } catch (error: Exception) {
                lastError = error
            } finally {
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("No active Wi-Fi network")
    }

    private const val CACHE_KIB = 4 * 1024
    private const val TIMEOUT_MS = 5_000
    private const val MAX_BYTES = 1024 * 1024
}
