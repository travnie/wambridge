package io.github.trvny.wambridge.mobile

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

private val TUNEIN_ID_PATTERN = Regex("^s[0-9]{1,12}$")

/** Whether a value has the TuneIn *station* shape, without throwing when it does not. */
internal fun isTuneInStationId(value: String): Boolean = TUNEIN_ID_PATTERN.matches(value)

/** Return a validated TuneIn station id such as `s15984`. */
internal fun validateTuneInId(value: String): String {
    require(TUNEIN_ID_PATTERN.matches(value)) {
        "TuneIn station id must look like s15984, got '$value'"
    }
    return value
}

/** Use TuneIn's station CDN only when we already have a validated station id. */
internal fun tuneInArtworkUrl(value: String?): String? {
    val id = value?.trim()?.takeIf(::isTuneInStationId) ?: return null
    return "https://cdn-profiles.tunein.com/$id/images/logod.png"
}

internal data class MobileRadioStation(
    val alias: String,
    val urls: List<String>,
    // TuneIn is resolved at play time; saved URLs remain the ordered fallback.
    val tuneInId: String? = null,
)

internal fun radioStationSourceSummary(station: MobileRadioStation): String {
    val fallbacks = (station.urls.size - 1).coerceAtLeast(0)
    return when {
        station.tuneInId != null && station.urls.isNotEmpty() ->
            "TuneIn ${station.tuneInId} · direct backup · $fallbacks fallback${if (fallbacks == 1) "" else "s"}"
        station.tuneInId != null ->
            "TuneIn ${station.tuneInId}"
        fallbacks > 0 ->
            "Direct · $fallbacks fallback${if (fallbacks == 1) "" else "s"}"
        else -> "Direct"
    }
}

internal fun mergeRadioStations(
    saved: List<MobileRadioStation>,
    bundled: List<MobileRadioStation>,
    hiddenBundledAliases: Set<String> = emptySet(),
): List<MobileRadioStation> {
    val savedByAlias = saved.associateBy { it.alias.lowercase() }
    val hidden = hiddenBundledAliases.mapTo(mutableSetOf()) { it.lowercase() }
    val merged = bundled.mapNotNull { station ->
        val key = station.alias.lowercase()
        savedByAlias[key] ?: station.takeUnless { key in hidden }
    }.toMutableList()
    val bundledAliases = bundled.mapTo(mutableSetOf()) { it.alias.lowercase() }
    merged += saved.filterNot { it.alias.lowercase() in bundledAliases }
    return merged
}

/** Apply a user order without losing stations added by a newer bundled pack. */
internal fun orderRadioStations(
    stations: List<MobileRadioStation>,
    aliases: List<String>,
): List<MobileRadioStation> {
    if (aliases.isEmpty()) return stations
    val byAlias = stations.associateBy { it.alias.lowercase() }
    val seen = mutableSetOf<String>()
    val ordered = buildList {
        aliases.forEach { alias ->
            val key = alias.lowercase()
            byAlias[key]?.takeIf { seen.add(key) }?.let(::add)
        }
        stations.forEach { station ->
            if (seen.add(station.alias.lowercase())) add(station)
        }
    }
    return ordered
}

internal fun stationsForAliases(
    stations: List<MobileRadioStation>,
    aliases: List<String>,
): List<MobileRadioStation> {
    val byAlias = stations.associateBy { it.alias.lowercase() }
    return aliases.mapNotNull { byAlias[it.lowercase()] }
}

/**
 * Pick the station a play request names.
 *
 * A station browsed out of the speaker's own TuneIn catalogue is never saved, so
 * it arrives as a title plus a TuneIn id and is played straight from that: the
 * resolver turns the id into stream URLs at play time, which is the same thing it
 * does for a saved station. Anything else is a saved alias.
 */
internal fun radioStationToPlay(
    alias: String,
    tuneInId: String?,
    saved: List<MobileRadioStation>,
): MobileRadioStation? {
    val name = alias.trim()
    val id = tuneInId?.trim()?.takeUnless { it.isEmpty() }
    if (id != null) {
        return MobileRadioStation(
            alias = name.ifEmpty { id },
            urls = emptyList(),
            tuneInId = id,
        )
    }
    if (name.isEmpty()) return null
    return saved.firstOrNull { it.alias.equals(name, ignoreCase = true) }
}

internal fun exportRadioStationsJson(stations: List<MobileRadioStation>): String =
    JSONObject().apply {
        put("version", 1)
        put(
            "stations",
            JSONArray().apply {
                stations.forEach { station ->
                    put(
                        JSONObject().apply {
                            put("alias", station.alias)
                            put("urls", JSONArray(station.urls))
                            station.tuneInId?.let { put("tunein_id", it) }
                        },
                    )
                }
            },
        )
    }.toString(2)

internal fun exportRadioStationsM3u(stations: List<MobileRadioStation>): String = buildString {
    appendLine("#EXTM3U")
    stations.forEach { station ->
        station.urls.firstOrNull()?.let { url ->
            appendLine("#EXTINF:-1,${station.alias}")
            appendLine(url)
        }
    }
}

internal fun exportRadioStationsPls(stations: List<MobileRadioStation>): String = buildString {
    val playable = stations.mapNotNull { station ->
        station.urls.firstOrNull()?.let { station.alias to it }
    }
    appendLine("[playlist]")
    playable.forEachIndexed { index, (alias, url) ->
        val slot = index + 1
        appendLine("File$slot=$url")
        appendLine("Title$slot=$alias")
        appendLine("Length$slot=-1")
    }
    appendLine("NumberOfEntries=${playable.size}")
    appendLine("Version=2")
}

internal fun importRadioStations(fileName: String, text: String): List<MobileRadioStation> {
    val lower = fileName.lowercase()
    return when {
        lower.endsWith(".m3u") || lower.endsWith(".m3u8") || text.trimStart().startsWith("#EXTM3U") ->
            importRadioStationsM3u(text)
        lower.endsWith(".pls") || text.trimStart().startsWith("[playlist]", ignoreCase = true) ->
            importRadioStationsPls(text)
        else -> importRadioStationsJson(text)
    }
}

private fun importRadioStationsJson(text: String): List<MobileRadioStation> {
    val trimmed = text.trim()
    val array = if (trimmed.startsWith("[")) {
        JSONArray(trimmed)
    } else {
        JSONObject(trimmed).getJSONArray("stations")
    }
    return buildList {
        for (index in 0 until array.length()) {
            val item = array.getJSONObject(index)
            val alias = item.getString("alias").trim()
            val tuneInId = item.optString("tunein_id").trim().takeIf(String::isNotEmpty)
                ?.let(::validateTuneInId)
            val urls = item.optJSONArray("urls")?.let(::jsonStrings).orEmpty()
            if (alias.isNotEmpty()) {
                add(
                    MobileRadioStation(
                        alias,
                        validateRadioUrls(urls, allowEmpty = tuneInId != null),
                        tuneInId,
                    ),
                )
            }
        }
    }
}

private fun importRadioStationsM3u(text: String): List<MobileRadioStation> {
    val result = mutableListOf<MobileRadioStation>()
    var pendingTitle: String? = null
    text.lineSequence().map(String::trim).forEach { line ->
        when {
            line.startsWith("#EXTINF:", ignoreCase = true) ->
                pendingTitle = line.substringAfter(',', "").trim().takeIf(String::isNotEmpty)
            line.isEmpty() || line.startsWith("#") -> Unit
            line.startsWith("http://", true) || line.startsWith("https://", true) -> {
                val alias = pendingTitle ?: defaultAliasForUrl(line, result.size + 1)
                result += MobileRadioStation(alias, validateRadioUrls(listOf(line)))
                pendingTitle = null
            }
        }
    }
    return result
}

private fun importRadioStationsPls(text: String): List<MobileRadioStation> {
    val files = mutableMapOf<Int, String>()
    val titles = mutableMapOf<Int, String>()
    text.lineSequence().map(String::trim).forEach { line ->
        val key = line.substringBefore('=', "").trim()
        val value = line.substringAfter('=', "").trim()
        when {
            key.startsWith("File", ignoreCase = true) ->
                key.drop(4).toIntOrNull()?.let { files[it] = value }
            key.startsWith("Title", ignoreCase = true) ->
                key.drop(5).toIntOrNull()?.let { titles[it] = value }
        }
    }
    return files.toSortedMap().map { (index, url) ->
        MobileRadioStation(
            titles[index]?.takeIf(String::isNotEmpty) ?: defaultAliasForUrl(url, index),
            validateRadioUrls(listOf(url)),
        )
    }
}

private fun defaultAliasForUrl(url: String, index: Int): String =
    runCatching { URI(url).host }.getOrNull()?.takeIf(String::isNotBlank) ?: "station-$index"

private fun jsonStrings(array: JSONArray): List<String> = buildList {
    for (index in 0 until array.length()) add(array.getString(index))
}

private fun validateRadioUrls(values: List<String>, allowEmpty: Boolean = false): List<String> {
    val result = LinkedHashSet<String>()
    values.map(String::trim).filter(String::isNotEmpty).forEach { value ->
        val uri = URI(value)
        require(
            uri.scheme.equals("http", ignoreCase = true) ||
                uri.scheme.equals("https", ignoreCase = true),
        ) { "Radio URL must use HTTP or HTTPS" }
        require(!uri.host.isNullOrBlank()) { "Radio URL needs a host" }
        result += value
    }
    require(allowEmpty || result.isNotEmpty()) { "Station needs at least one stream URL" }
    return result.toList()
}

internal class RadioStationStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = appContext.getSharedPreferences(
        RendererService.PREFS,
        Context.MODE_PRIVATE,
    )
    private val bundledStations: List<MobileRadioStation> by lazy(::loadBundledFavorites)

    fun all(): List<MobileRadioStation> = orderRadioStations(
        mergeRadioStations(
            loadSaved(),
            bundledStations,
            hiddenBundledAliases(),
        ),
        orderedAliases(),
    )

    fun pinned(): List<MobileRadioStation> {
        val pinned = pinnedAliases()
        return all().filter { it.alias.lowercase() in pinned }
    }

    fun recent(limit: Int = RECENT_LIMIT): List<MobileRadioStation> {
        val byAlias = all().associateBy { it.alias.lowercase() }
        return recentAliases().mapNotNull { byAlias[it.lowercase()] }.take(limit)
    }

    fun lastPlayed(): MobileRadioStation? = stationByPreference(KEY_LAST_PLAYED)
    fun defaultStation(): MobileRadioStation? = stationByPreference(KEY_DEFAULT_STATION)

    fun quickStations(): List<MobileRadioStation> {
        val available = all()
        return stationsForAliases(available, bundledPackAliases(QUICK_PACK))
    }

    fun upsert(alias: String, urls: List<String>, tuneInId: String? = null): MobileRadioStation {
        val cleanedAlias = alias.trim()
        require(cleanedAlias.isNotEmpty()) { "Station name cannot be empty" }
        val cleanedTuneInId = cleanTuneInId(tuneInId)
        val station = MobileRadioStation(
            cleanedAlias,
            validateRadioUrls(urls, allowEmpty = cleanedTuneInId != null),
            cleanedTuneInId,
        )
        val stations = loadSaved().filterNot {
            it.alias.equals(cleanedAlias, ignoreCase = true)
        } + station
        saveSaved(stations)
        unhideBundled(cleanedAlias)
        return station
    }

    fun importStations(stations: List<MobileRadioStation>): Int {
        stations.forEach { station ->
            upsert(station.alias, station.urls, station.tuneInId)
        }
        return stations.size
    }

    fun remove(alias: String) {
        val cleanedAlias = alias.trim()
        saveSaved(loadSaved().filterNot { it.alias.equals(cleanedAlias, ignoreCase = true) })
        if (bundledStations.any { it.alias.equals(cleanedAlias, ignoreCase = true) }) {
            val hidden = hiddenBundledAliases().toMutableSet()
            hidden += cleanedAlias.lowercase()
            preferences.edit().putStringSet(KEY_HIDDEN_BUNDLED, hidden).apply()
        }
        val key = cleanedAlias.lowercase()
        preferences.edit()
            .putStringSet(KEY_PINNED, pinnedAliases().filterNot { it == key }.toSet())
            .putString(KEY_ORDER, JSONArray(orderedAliases().filterNot { it.equals(cleanedAlias, true) }).toString())
            .putString(KEY_RECENT, JSONArray(recentAliases().filterNot { it.equals(cleanedAlias, true) }).toString())
            .apply()
        if (preferences.getString(KEY_LAST_PLAYED, null).equals(cleanedAlias, true)) {
            preferences.edit().remove(KEY_LAST_PLAYED).apply()
        }
        if (preferences.getString(KEY_DEFAULT_STATION, null).equals(cleanedAlias, true)) {
            preferences.edit().remove(KEY_DEFAULT_STATION).apply()
        }
    }

    fun isPinned(alias: String): Boolean = alias.lowercase() in pinnedAliases()

    fun setPinned(alias: String, pinned: Boolean) {
        require(all().any { it.alias.equals(alias, true) }) { "Unknown station '$alias'" }
        val key = alias.lowercase()
        val values = pinnedAliases().toMutableSet()
        if (pinned) values += key else values -= key
        preferences.edit().putStringSet(KEY_PINNED, values).apply()
    }

    fun setDefault(alias: String?) {
        val cleaned = alias?.trim().orEmpty()
        if (cleaned.isEmpty()) {
            preferences.edit().remove(KEY_DEFAULT_STATION).apply()
            return
        }
        val station = all().firstOrNull { it.alias.equals(cleaned, true) }
            ?: error("Unknown station '$cleaned'")
        preferences.edit().putString(KEY_DEFAULT_STATION, station.alias).apply()
    }

    fun recordPlayed(station: MobileRadioStation) {
        val saved = all().firstOrNull { it.alias.equals(station.alias, true) } ?: return
        val recent = buildList {
            add(saved.alias)
            addAll(recentAliases().filterNot { it.equals(saved.alias, true) })
        }.take(RECENT_LIMIT)
        preferences.edit()
            .putString(KEY_LAST_PLAYED, saved.alias)
            .putString(KEY_RECENT, JSONArray(recent).toString())
            .apply()
    }

    fun saveOrder(aliases: List<String>) {
        val available = all().map { it.alias }
        val requested = aliases.map(String::trim).filter(String::isNotEmpty)
        val seen = mutableSetOf<String>()
        val normalized = buildList {
            requested.forEach { alias ->
                available.firstOrNull { it.equals(alias, true) }?.let { candidate ->
                    if (seen.add(candidate.lowercase())) add(candidate)
                }
            }
            available.forEach { alias ->
                if (seen.add(alias.lowercase())) add(alias)
            }
        }
        preferences.edit().putString(KEY_ORDER, JSONArray(normalized).toString()).apply()
    }

    private fun stationByPreference(key: String): MobileRadioStation? {
        val alias = preferences.getString(key, null) ?: return null
        return all().firstOrNull { it.alias.equals(alias, true) }
    }

    private fun unhideBundled(alias: String) {
        val hidden = hiddenBundledAliases().toMutableSet()
        if (hidden.remove(alias.lowercase())) {
            preferences.edit().putStringSet(KEY_HIDDEN_BUNDLED, hidden).apply()
        }
    }

    private fun hiddenBundledAliases(): Set<String> =
        preferences.getStringSet(KEY_HIDDEN_BUNDLED, emptySet()).orEmpty().toSet()

    private fun pinnedAliases(): Set<String> =
        preferences.getStringSet(KEY_PINNED, emptySet()).orEmpty().mapTo(mutableSetOf()) { it.lowercase() }

    private fun orderedAliases(): List<String> = loadStringArray(KEY_ORDER)

    private fun recentAliases(): List<String> = loadStringArray(KEY_RECENT)

    private fun loadStringArray(key: String): List<String> {
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching { jsonStrings(JSONArray(raw)) }.getOrDefault(emptyList())
    }

    private fun loadSaved(): List<MobileRadioStation> {
        val raw = preferences.getString(KEY_STATIONS, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val alias = item.getString("alias").trim()
                    val urls = jsonStrings(item.getJSONArray("urls"))
                    val tuneInId = cleanTuneInId(item.optString(KEY_TUNEIN_ID))
                    if (alias.isNotEmpty()) {
                        add(
                            MobileRadioStation(
                                alias,
                                validateRadioUrls(urls, allowEmpty = tuneInId != null),
                                tuneInId,
                            ),
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun loadBundledFavorites(): List<MobileRadioStation> = runCatching {
        val root = bundledRoot()
        val byAlias = buildMap {
            val stations = root.getJSONArray("stations")
            for (index in 0 until stations.length()) {
                bundledStation(stations.getJSONObject(index))?.let { station ->
                    put(station.alias, station)
                }
            }
        }
        bundledPackAliases(DEFAULT_PACK, root).mapNotNull(byAlias::get)
    }.getOrDefault(emptyList())

    private fun bundledPackAliases(
        pack: String,
        root: JSONObject = bundledRoot(),
    ): List<String> = runCatching {
        jsonStrings(root.getJSONObject("packs").getJSONArray(pack))
    }.getOrDefault(emptyList())

    private fun bundledRoot(): JSONObject {
        val text = appContext.assets.open(BUNDLED_ASSET)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        return JSONObject(text)
    }

    private fun bundledStation(item: JSONObject): MobileRadioStation? {
        if (!item.optBoolean("mobile_supported", true)) return null
        val alias = item.getString("alias").trim()
        val urls = buildList {
            add(item.getString("url"))
            item.optJSONArray("fallback_urls")?.let { addAll(jsonStrings(it)) }
        }
        return MobileRadioStation(
            alias = alias,
            urls = validateRadioUrls(urls),
            tuneInId = cleanTuneInId(item.optString(KEY_TUNEIN_ID)),
        )
    }

    private fun saveSaved(stations: List<MobileRadioStation>) {
        val array = JSONArray()
        stations.forEach { station ->
            array.put(JSONObject().apply {
                put("alias", station.alias)
                put("urls", JSONArray(station.urls))
                station.tuneInId?.let { put(KEY_TUNEIN_ID, it) }
            })
        }
        preferences.edit().putString(KEY_STATIONS, array.toString()).apply()
    }

    private fun cleanTuneInId(value: String?): String? =
        value?.trim()?.takeIf(String::isNotEmpty)?.let(::validateTuneInId)

    companion object {
        private const val KEY_STATIONS = "radio_stations"
        private const val KEY_TUNEIN_ID = "tunein_id"
        private const val KEY_HIDDEN_BUNDLED = "radio_hidden_bundled"
        private const val KEY_PINNED = "radio_pinned"
        private const val KEY_ORDER = "radio_order"
        private const val KEY_RECENT = "radio_recent"
        private const val KEY_LAST_PLAYED = "radio_last_played"
        private const val KEY_DEFAULT_STATION = "radio_default_station"
        private const val BUNDLED_ASSET = "station_packs.json"
        private const val DEFAULT_PACK = "favorites"
        private const val QUICK_PACK = "top3"
        private const val RECENT_LIMIT = 8
    }
}
