package io.github.trvny.wambridge.mobile

import android.content.Context
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.TeeAudioProcessor
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import java.io.IOException
import java.io.OutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.min
import okhttp3.Dns
import okhttp3.OkHttpClient

internal fun radioNeedsPhoneTranscode(source: String, contentType: String? = null): Boolean {
    val path = runCatching { URI(source).path.orEmpty() }.getOrDefault(source)
        .lowercase(Locale.ROOT)
    val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    return path.endsWith(".m3u8") ||
        path.endsWith(".ogg") ||
        path.endsWith(".oga") ||
        path.endsWith(".opus") ||
        mime in TRANSCODE_CONTENT_TYPES
}

internal fun endlessWavHeader(
    sampleRateHz: Int = TRANSCODE_SAMPLE_RATE_HZ,
    channelCount: Int = TRANSCODE_CHANNEL_COUNT,
): ByteArray {
    require(sampleRateHz > 0)
    require(channelCount > 0)
    val bitsPerSample = 16
    val blockAlign = channelCount * (bitsPerSample / 8)
    val byteRate = sampleRateHz * blockAlign

    return ByteBuffer.allocate(WAV_HEADER_BYTES)
        .order(ByteOrder.LITTLE_ENDIAN)
        .apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(-1)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1)
            putShort(channelCount.toShort())
            putInt(sampleRateHz)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(-1)
        }
        .array()
}

internal fun radioTranscodeMediaMime(source: String, contentType: String? = null): String? {
    val path = runCatching { URI(source).path.orEmpty() }.getOrDefault(source)
        .lowercase(Locale.ROOT)
    val mime = contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
    return if (path.endsWith(".m3u8") || mime in HLS_CONTENT_TYPES) {
        MimeTypes.APPLICATION_M3U8
    } else {
        null
    }
}

internal fun media3RadioNowPlaying(
    title: CharSequence?,
    artist: CharSequence?,
    artworkUri: String?,
): RadioNowPlaying {
    val cleanTitle = title?.toString()?.trim()?.takeIf(String::isNotEmpty)
    val cleanArtist = artist?.toString()?.trim()?.takeIf(String::isNotEmpty)
    val displayTitle = when {
        cleanArtist != null && cleanTitle != null -> "$cleanArtist - $cleanTitle"
        cleanTitle != null -> cleanTitle
        else -> cleanArtist
    }
    return RadioNowPlaying(
        title = displayTitle,
        artworkUrl = webArtworkUrl(artworkUri),
    )
}

private fun transcodeMediaItem(source: String, contentType: String?): MediaItem =
    MediaItem.Builder()
        .setUri(source)
        .apply {
            radioTranscodeMediaMime(source, contentType)?.let(::setMimeType)
        }
        .build()

@OptIn(UnstableApi::class)
internal class Media3RadioTranscoder(
    context: Context,
    network: Network,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val closed = AtomicBoolean(false)
    private val finished = CountDownLatch(1)
    private val started = CountDownLatch(1)
    private val failure = AtomicReference<Throwable?>()
    private val player = AtomicReference<ExoPlayer?>()
    private val thread = AtomicReference<HandlerThread?>()
    private val handler = AtomicReference<Handler?>()
    private val lifecycleLock = Any()
    private val httpClosed = AtomicBoolean(false)
    private val httpClient = OkHttpClient.Builder()
        .socketFactory(network.socketFactory)
        .dns(
            object : Dns {
                override fun lookup(hostname: String) =
                    network.getAllByName(hostname).toList()
            },
        )
        .build()

    fun relay(
        source: String,
        contentType: String?,
        output: OutputStream,
        beforeFirstBytes: () -> Unit,
        onMetadata: (RadioNowPlaying) -> Unit = {},
    ) {
        val playbackThread: HandlerThread
        val playbackHandler: Handler
        synchronized(lifecycleLock) {
            if (closed.get()) return
            check(thread.get() == null) { "Transcoder instances are single-use" }

            playbackThread = HandlerThread(
                "wam-radio-transcode",
                Process.THREAD_PRIORITY_AUDIO,
            ).apply { start() }
            playbackHandler = Handler(playbackThread.looper)
            thread.set(playbackThread)
            handler.set(playbackHandler)
        }

        val waveSink = StreamingWaveSink(
            output = output,
            beforeFirstBytes = beforeFirstBytes,
            onReady = started::countDown,
            onFailure = ::fail,
        )

        val posted = playbackHandler.post {
            if (closed.get()) return@post
            try {
                val renderersFactory = object : DefaultRenderersFactory(appContext) {
                    override fun buildAudioSink(
                        context: Context,
                        enableFloatOutput: Boolean,
                        enableAudioOutputPlaybackParams: Boolean,
                    ): AudioSink {
                        val channelMixer = ChannelMixingAudioProcessor().apply {
                            putChannelMixingMatrix(
                                ChannelMixingMatrix.createForConstantGain(1, TRANSCODE_CHANNEL_COUNT),
                            )
                            putChannelMixingMatrix(
                                ChannelMixingMatrix.createForConstantGain(2, TRANSCODE_CHANNEL_COUNT),
                            )
                        }
                        val resampler = SonicAudioProcessor().apply {
                            setOutputSampleRateHz(TRANSCODE_SAMPLE_RATE_HZ)
                        }
                        return DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(false)
                            .setEnableAudioOutputPlaybackParameters(false)
                            .setAudioProcessors(
                                arrayOf(
                                    ToInt16PcmAudioProcessor(),
                                    channelMixer,
                                    resampler,
                                    TeeAudioProcessor(waveSink),
                                ),
                            )
                            .build()
                    }
                }

                val dataSourceFactory = OkHttpDataSource.Factory(httpClient)
                    .setUserAgent("WAMBridge-Mobile/0.1")
                val mediaSourceFactory = DefaultMediaSourceFactory(appContext)
                    .setDataSourceFactory(dataSourceFactory)
                val activePlayer = ExoPlayer.Builder(appContext, renderersFactory)
                    .setMediaSourceFactory(mediaSourceFactory)
                    .setLooper(playbackThread.looper)
                    .build()
                player.set(activePlayer)
                if (closed.get()) return@post
                activePlayer.volume = 0f
                val lastMetadata = AtomicReference<RadioNowPlaying?>()
                activePlayer.addListener(
                    object : Player.Listener {
                        override fun onPlayerError(error: PlaybackException) {
                            fail(error)
                        }

                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                finished.countDown()
                            }
                        }

                        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
                            val update = media3RadioNowPlaying(
                                title = mediaMetadata.title,
                                artist = mediaMetadata.artist,
                                artworkUri = mediaMetadata.artworkUri?.toString(),
                            )
                            if (
                                (update.title != null || update.artworkUrl != null) &&
                                lastMetadata.getAndSet(update) != update
                            ) {
                                onMetadata(update)
                            }
                        }
                    },
                )
                activePlayer.setMediaItem(transcodeMediaItem(source, contentType))
                activePlayer.prepare()
                activePlayer.play()
            } catch (error: Throwable) {
                fail(error)
            }
        }
        if (!posted) return

        if (!started.await(TRANSCODE_START_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            val cause = failure.get()
            throw IOException(
                cause?.message ?: "Timed out waiting for phone-side radio transcoding",
                cause,
            )
        }

        finished.await()
        failure.get()?.let { error ->
            if (!closed.get()) {
                throw IOException(error.message ?: "Phone-side radio transcoding failed", error)
            }
        }
    }

    private fun fail(error: Throwable) {
        failure.compareAndSet(null, error)
        finished.countDown()
        started.countDown()
    }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        started.countDown()

        val activeHandler: Handler?
        val activeThread: HandlerThread?
        synchronized(lifecycleLock) {
            activeHandler = handler.getAndSet(null)
            activeThread = thread.getAndSet(null)
        }

        val posted = activeHandler?.post {
            player.getAndSet(null)?.let { activePlayer ->
                runCatching { activePlayer.release() }
            }
            closeHttpClient()
            finished.countDown()
        } ?: false

        if (!posted) {
            player.getAndSet(null)?.let { activePlayer ->
                runCatching { activePlayer.release() }
            }
            closeHttpClient()
            finished.countDown()
        }
        activeThread?.quitSafely()
    }

    private fun closeHttpClient() {
        if (!httpClosed.compareAndSet(false, true)) return
        httpClient.dispatcher.cancelAll()
        httpClient.connectionPool.evictAll()
        httpClient.dispatcher.executorService.shutdown()
    }
}

@OptIn(UnstableApi::class)
private class StreamingWaveSink(
    private val output: OutputStream,
    private val beforeFirstBytes: () -> Unit,
    private val onReady: () -> Unit,
    private val onFailure: (Throwable) -> Unit,
) : TeeAudioProcessor.AudioBufferSink {
    private var formatReady = false
    private var streamReady = false

    override fun flush(sampleRateHz: Int, channelCount: Int, encoding: Int) {
        check(sampleRateHz == TRANSCODE_SAMPLE_RATE_HZ) {
            "Unexpected transcoder sample rate: $sampleRateHz"
        }
        check(channelCount == TRANSCODE_CHANNEL_COUNT) {
            "Unexpected transcoder channel count: $channelCount"
        }
        check(encoding == C.ENCODING_PCM_16BIT) {
            "Unexpected transcoder PCM encoding: $encoding"
        }
        formatReady = true
    }

    override fun handleBuffer(buffer: ByteBuffer) {
        if (!formatReady || !buffer.hasRemaining()) return
        try {
            if (!streamReady) {
                beforeFirstBytes()
                output.write(endlessWavHeader())
                output.flush()
                streamReady = true
                onReady()
            }

            val copy = buffer.asReadOnlyBuffer()
            val chunk = ByteArray(min(copy.remaining(), TRANSCODE_COPY_BUFFER))
            while (copy.hasRemaining()) {
                val count = min(copy.remaining(), chunk.size)
                copy.get(chunk, 0, count)
                output.write(chunk, 0, count)
            }
        } catch (error: Throwable) {
            onFailure(error)
            throw IllegalStateException("Radio PCM relay failed", error)
        }
    }
}

private val HLS_CONTENT_TYPES = setOf(
    "application/vnd.apple.mpegurl",
    "application/x-mpegurl",
    "audio/mpegurl",
)

private val TRANSCODE_CONTENT_TYPES = HLS_CONTENT_TYPES + setOf(
    "audio/ogg",
    "application/ogg",
)

internal const val TRANSCODE_SAMPLE_RATE_HZ = 44_100
internal const val TRANSCODE_CHANNEL_COUNT = 2
private const val WAV_HEADER_BYTES = 44
private const val TRANSCODE_COPY_BUFFER = 32 * 1024
private const val TRANSCODE_START_TIMEOUT_SECONDS = 20L
