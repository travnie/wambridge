package io.github.trvny.wambridge.mobile

import android.content.Context
import org.json.JSONObject

internal data class RadioFallbackMemory(
    val lastWorking: String? = null,
    val failedAt: Map<String, Long> = emptyMap(),
)

internal fun prioritizeRadioCandidates(
    candidates: List<String>,
    memory: RadioFallbackMemory,
    nowMs: Long,
    cooldownMs: Long = RadioFallbackStore.FAILURE_COOLDOWN_MS,
): List<String> {
    val unique = candidates.distinct()
    if (unique.size < 2) return unique

    fun cooling(url: String): Boolean {
        val failed = memory.failedAt[url] ?: return false
        return nowMs - failed in 0 until cooldownMs
    }

    val preferred = memory.lastWorking
        ?.takeIf { it in unique && !cooling(it) }

    return buildList {
        preferred?.let(::add)
        unique.filterNot { it == preferred || cooling(it) }.forEach(::add)
        unique.filter { it != preferred && cooling(it) }.forEach(::add)
    }
}

internal fun radioFallbackPosition(sourceUrl: String, canonical: List<String>): Pair<Int, Int>? {
    val index = canonical.indexOf(sourceUrl)
    if (index < 0) return null
    return (index + 1) to canonical.size
}

/**
 * Persistent endpoint hints for the phone radio relay.
 *
 * This never probes a station. It only remembers outcomes observed by the one proxy connection
 * the M5 is already using, then changes the order on the next start/recovery.
 */
internal class RadioFallbackStore(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(
        RendererService.PREFS,
        Context.MODE_PRIVATE,
    )

    fun ordered(alias: String, candidates: List<String>, nowMs: Long = System.currentTimeMillis()): List<String> =
        prioritizeRadioCandidates(candidates, load(alias), nowMs)

    fun recordSuccess(alias: String, sourceUrl: String) {
        val current = load(alias)
        save(
            alias,
            current.copy(
                lastWorking = sourceUrl,
                failedAt = current.failedAt - sourceUrl,
            ),
        )
    }

    fun recordFailure(alias: String, sourceUrl: String, nowMs: Long = System.currentTimeMillis()) {
        val current = load(alias)
        val retained = current.failedAt.filterValues { failed ->
            nowMs - failed in 0 until FAILURE_RETENTION_MS
        }
        save(
            alias,
            current.copy(
                failedAt = retained + (sourceUrl to nowMs),
            ),
        )
    }

    private fun load(alias: String): RadioFallbackMemory {
        val raw = preferences.getString(key(alias), null) ?: return RadioFallbackMemory()
        return runCatching {
            val root = JSONObject(raw)
            val failures = buildMap {
                val objectValue = root.optJSONObject(KEY_FAILURES) ?: JSONObject()
                objectValue.keys().forEach { url ->
                    put(url, objectValue.optLong(url, 0L))
                }
            }.filterValues { it > 0L }
            RadioFallbackMemory(
                lastWorking = root.optString(KEY_LAST_WORKING)
                    .trim()
                    .takeIf(String::isNotEmpty),
                failedAt = failures,
            )
        }.getOrDefault(RadioFallbackMemory())
    }

    private fun save(alias: String, memory: RadioFallbackMemory) {
        val root = JSONObject()
        memory.lastWorking?.let { root.put(KEY_LAST_WORKING, it) }
        root.put(
            KEY_FAILURES,
            JSONObject().apply {
                memory.failedAt.forEach { (url, timestamp) -> put(url, timestamp) }
            },
        )
        preferences.edit().putString(key(alias), root.toString()).apply()
    }

    private fun key(alias: String): String =
        KEY_PREFIX + alias.trim().lowercase()

    companion object {
        internal const val FAILURE_COOLDOWN_MS = 15 * 60 * 1000L
        private const val FAILURE_RETENTION_MS = 24 * 60 * 60 * 1000L
        private const val KEY_PREFIX = "radio_fallback_"
        private const val KEY_LAST_WORKING = "last_working"
        private const val KEY_FAILURES = "failed_at"
    }
}
