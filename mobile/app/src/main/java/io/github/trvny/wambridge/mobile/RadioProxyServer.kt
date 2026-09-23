package io.github.trvny.wambridge.mobile

import android.content.Context
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal class RadioProxyServer(
    context: Context,
    private val speakerIp: String,
    // Already ordered: a freshly resolved TuneIn stream first when the station
    // carries an id, the saved URLs behind it as the static fallbacks.
    private val sources: List<String>,
    private val listener: Listener,
    private val wifiTarget: WifiLan.Target,
) : AutoCloseable {
    interface Listener {
        fun onStreamOpened(source: Any, sourceUrl: String)
        fun onSourceFailed(source: Any, sourceUrl: String, message: String)
        fun onMetadata(source: Any, metadata: RadioNowPlaying)
        fun onStreamClosed(source: Any)
        fun onProxyError(source: Any, message: String)
    }

    private data class OpenSource(
        val connection: HttpURLConnection,
        val contentType: String,
        val metadataInterval: Int?,
    )

    private val appContext = context.applicationContext
    private val running = AtomicBoolean(false)
    private val activeStreamClient = AtomicReference<Socket?>()
    private val executor = Executors.newCachedThreadPool()
    private val clients = mutableSetOf<Socket>()
    private val clientLock = Any()
    private val path = "/radio/${UUID.randomUUID().toString().replace("-", "")}"
    private var server: ServerSocket? = null
    @Volatile private var activeTranscoder: Media3RadioTranscoder? = null

    lateinit var localAddress: Inet4Address
        private set
    var networkHandle: Long = 0
        private set
    var port: Int = 0
        private set

    val url: String
        get() = "http://${localAddress.hostAddress}:$port$path"

    fun start() {
        if (!running.compareAndSet(false, true)) return
        try {
            localAddress = wifiTarget.address
            networkHandle = wifiTarget.network.networkHandle
            val socket = ServerSocket(0, 8, localAddress)
            server = socket
            port = socket.localPort
            Thread({ acceptLoop(socket) }, "wam-radio-proxy").apply {
                isDaemon = true
                start()
            }
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (running.get()) {
            try {
                val client = socket.accept()
                synchronized(clientLock) { clients += client }
                executor.execute {
                    try {
                        handleClient(client)
                    } catch (error: IOException) {
                        reportClientError(client, error)
                    } finally {
                        synchronized(clientLock) { clients -= client }
                        runCatching { client.close() }
                    }
                }
            } catch (_: IOException) {
                if (running.get()) listener.onProxyError(this, "Radio proxy listener stopped")
            }
        }
    }

    private fun reportClientError(client: Socket, error: Exception) {
        if (!running.get() || client.inetAddress.hostAddress != speakerIp) return
        listener.onProxyError(this, error.message ?: error.javaClass.simpleName)
    }

    private fun handleClient(client: Socket) {
        client.soTimeout = CLIENT_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val output = BufferedOutputStream(client.getOutputStream())
        val request = readRequestLine(input)
        val speakerPeer = client.inetAddress.hostAddress == speakerIp
        if (!speakerPeer || request.first != "GET" || request.second != path) {
            writeError(output, 403, "Forbidden")
            return
        }
        if (!claimStream(client)) {
            if (running.get()) writeError(output, 409, "Radio stream already active")
            return
        }

        try {
            var lastError: Exception? = null
            for (source in sources) {
                if (!running.get()) return
                if (radioNeedsPhoneTranscode(source)) {
                    val result = relayTranscodedSource(source, output)
                    if (!running.get() || result.started) return
                    lastError = result.error
                    continue
                }

                val opened = try {
                    openSource(source)
                } catch (error: Exception) {
                    lastError = error
                    listener.onSourceFailed(
                        this,
                        source,
                        error.message ?: error.javaClass.simpleName,
                    )
                    continue
                }

                if (radioNeedsPhoneTranscode(source, opened.contentType)) {
                    opened.connection.disconnect()
                    val result = relayTranscodedSource(source, output, opened.contentType)
                    if (!running.get() || result.started) return
                    lastError = result.error
                    continue
                }

                writeSuccess(output, opened.contentType)
                listener.onStreamOpened(this, source)
                try {
                    opened.connection.inputStream.use { raw ->
                        val buffered = BufferedInputStream(raw, COPY_BUFFER)
                        val metadataInterval = opened.metadataInterval
                        if (metadataInterval == null) {
                            buffered.copyTo(output, COPY_BUFFER)
                        } else {
                            relayIcyAudio(
                                input = buffered,
                                output = output,
                                metadataInterval = metadataInterval,
                            ) { metadata ->
                                listener.onMetadata(this, metadata)
                            }
                        }
                        output.flush()
                    }
                } catch (error: Exception) {
                    listener.onSourceFailed(
                        this,
                        source,
                        error.message ?: error.javaClass.simpleName,
                    )
                    listener.onProxyError(this, error.message ?: error.javaClass.simpleName)
                } finally {
                    opened.connection.disconnect()
                    listener.onStreamClosed(this)
                }
                return
            }

            if (!running.get()) return
            val message = lastError?.message ?: "No usable station URL"
            listener.onProxyError(this, message)
            runCatching { writeError(output, 502, "Bad Gateway") }
        } finally {
            activeStreamClient.compareAndSet(client, null)
        }
    }

    private fun claimStream(client: Socket): Boolean {
        val deadlineNanos = System.nanoTime() + CLAIM_TAKEOVER_TIMEOUT_MS * 1_000_000L
        while (running.get()) {
            if (activeStreamClient.compareAndSet(null, client)) return true

            runCatching { activeStreamClient.get()?.close() }
            runCatching { activeTranscoder?.close() }

            if (System.nanoTime() >= deadlineNanos) return false
            try {
                Thread.sleep(CLAIM_RETRY_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun openSource(source: String): OpenSource {
        var lastError: Exception? = null
        val sourceUrl = URL(source)
        for (connection in WifiLan.openHttpConnections(appContext, sourceUrl)) {
            connection.apply {
                connectTimeout = SOURCE_CONNECT_TIMEOUT_MS
                readTimeout = SOURCE_READ_TIMEOUT_MS
                useCaches = false
                requestMethod = "GET"
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "WAMBridge-Mobile/0.1")
                setRequestProperty("Icy-MetaData", "1")
            }
            try {
                connection.connect()
                require(connection.responseCode in 200..299) {
                    "Radio source HTTP ${connection.responseCode}"
                }
                val contentType = connection.contentType.orEmpty()
                    .substringBefore(';')
                    .trim()
                    .lowercase(Locale.ROOT)
                val metadataInterval = connection.getHeaderField("icy-metaint")
                    ?.trim()
                    ?.toIntOrNull()
                    ?.takeIf { it > 0 }
                return OpenSource(
                    connection = connection,
                    contentType = contentType.ifBlank { "application/octet-stream" },
                    metadataInterval = metadataInterval,
                )
            } catch (error: Exception) {
                lastError = error
                connection.disconnect()
            }
        }
        throw lastError ?: IOException("No active Wi-Fi network")
    }

    private data class TranscodeAttempt(
        val started: Boolean,
        val error: Exception?,
    )

    private fun relayTranscodedSource(
        source: String,
        output: BufferedOutputStream,
        contentType: String? = null,
    ): TranscodeAttempt {
        val started = AtomicBoolean(false)
        val transcoder = Media3RadioTranscoder(appContext, wifiTarget.network)
        activeTranscoder = transcoder
        if (!running.get()) {
            if (activeTranscoder === transcoder) activeTranscoder = null
            transcoder.close()
            return TranscodeAttempt(false, null)
        }
        return try {
            transcoder.relay(
                source = source,
                contentType = contentType,
                output = output,
                beforeFirstBytes = {
                    writeSuccess(output, "audio/wav")
                    listener.onStreamOpened(this, source)
                    started.set(true)
                },
                onMetadata = { metadata ->
                    listener.onMetadata(this, metadata)
                },
            )
            if (started.get()) listener.onStreamClosed(this)
            TranscodeAttempt(started.get(), null)
        } catch (error: Exception) {
            listener.onSourceFailed(
                this,
                source,
                error.message ?: error.javaClass.simpleName,
            )
            if (started.get()) {
                listener.onProxyError(this, error.message ?: error.javaClass.simpleName)
                listener.onStreamClosed(this)
            }
            TranscodeAttempt(started.get(), error)
        } finally {
            if (activeTranscoder === transcoder) activeTranscoder = null
            transcoder.close()
        }
    }

    private fun readRequestLine(input: BufferedInputStream): Pair<String, String> {
        val bytes = ArrayList<Byte>()
        var matched = 0
        val end = byteArrayOf(13, 10, 13, 10)
        while (bytes.size < MAX_HEADER_BYTES) {
            val value = input.read()
            if (value < 0) throw IOException("Radio proxy request ended before headers")
            val byte = value.toByte()
            bytes += byte
            matched = if (byte == end[matched]) matched + 1 else if (byte == end[0]) 1 else 0
            if (matched == end.size) break
        }
        require(matched == end.size) { "Radio proxy request headers too large" }
        val header = bytes.toByteArray().toString(StandardCharsets.ISO_8859_1)
        val first = header.substringBefore("\r\n").split(' ', limit = 3)
        require(first.size >= 2) { "Invalid radio proxy request" }
        return first[0].uppercase(Locale.ROOT) to first[1].substringBefore('?')
    }

    private fun writeSuccess(output: BufferedOutputStream, contentType: String) {
        output.write(
            buildString {
                append("HTTP/1.0 200 OK\r\n")
                append("Content-Type: $contentType\r\n")
                append("Cache-Control: no-store\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1),
        )
        output.flush()
    }

    private fun writeError(output: BufferedOutputStream, status: Int, reason: String) {
        val body = reason.toByteArray(StandardCharsets.UTF_8)
        output.write(
            buildString {
                append("HTTP/1.0 $status $reason\r\n")
                append("Content-Type: text/plain\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(StandardCharsets.ISO_8859_1),
        )
        output.write(body)
        output.flush()
    }

    override fun close() {
        if (!running.getAndSet(false)) return
        runCatching { server?.close() }
        server = null
        runCatching { activeStreamClient.getAndSet(null)?.close() }
        runCatching { activeTranscoder?.close() }
        activeTranscoder = null
        synchronized(clientLock) {
            clients.forEach { runCatching { it.close() } }
            clients.clear()
        }
        executor.shutdownNow()
    }

    companion object {
        private const val CLIENT_TIMEOUT_MS = 15_000
        private const val SOURCE_CONNECT_TIMEOUT_MS = 7_000
        private const val SOURCE_READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 64 * 1024
        private const val COPY_BUFFER = 64 * 1024
        private const val CLAIM_TAKEOVER_TIMEOUT_MS = 2_000L
        private const val CLAIM_RETRY_MS = 25L
    }
}
