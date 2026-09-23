package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.app.AlertDialog
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.file.FileSystems

class RadioStationsActivity : Activity() {
    private lateinit var aliasInput: EditText
    private lateinit var urlsInput: EditText
    private lateinit var tuneInInput: EditText
    private lateinit var statusView: TextView
    private lateinit var volumeView: TextView
    private lateinit var stationsView: LinearLayout
    private lateinit var pinnedView: LinearLayout
    private lateinit var recentView: TextView
    private lateinit var librarySummaryView: TextView
    private lateinit var editorCard: LinearLayout
    private lateinit var scrollView: ScrollView
    private val store by lazy { RadioStationStore(this) }
    private var editingAlias: String? = null
    private var pendingExport: ExportFormat? = null

    private var volumeStep: Int? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)

        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "Radio stations",
                "Favourites, recents and station_packs.json defaults with TuneIn and ordered fallbacks.",
            ),
        )

        statusView = MobileUi.status(this)
        content.addView(statusView)

        content.addView(MobileUi.sectionTitle(this, "Library"))
        val libraryCard = MobileUi.card(this)
        librarySummaryView = MobileUi.body(this, "")
        libraryCard.addView(librarySummaryView)
        libraryCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@RadioStationsActivity, 10), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "Play default", MobileUi.ButtonKind.PRIMARY) {
                    playPreferred(default = true)
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "Play last") {
                    playPreferred(default = false)
                },
                marginDp = 0,
            )
        })
        recentView = MobileUi.body(this, "")
        recentView.setPadding(0, MobileUi.dp(this, 10), 0, 0)
        libraryCard.addView(recentView)
        pinnedView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, MobileUi.dp(this@RadioStationsActivity, 8), 0, 0)
        }
        libraryCard.addView(pinnedView)
        libraryCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@RadioStationsActivity, 10), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "Reorder") {
                    startActivity(Intent(this@RadioStationsActivity, RadioOrderActivity::class.java))
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "Import") { importStations() },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "Export") { chooseExport() },
                marginDp = 0,
            )
        })
        content.addView(libraryCard)

        content.addView(MobileUi.sectionTitle(this, "Playback"))
        val playbackCard = MobileUi.card(this)
        playbackCard.addView(
            MobileUi.body(
                this,
                "Direct MP3/AAC/FLAC-style streams are relayed by the phone. HLS and Ogg still need transcoding.",
            ),
        )
        playbackCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@RadioStationsActivity, 12), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(
                    this@RadioStationsActivity,
                    "Stop radio",
                    MobileUi.ButtonKind.DANGER,
                ) { stopRadio() },
                1.3f,
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "−") { stepVolume(-1) },
                0.65f,
            )
            volumeView = TextView(this@RadioStationsActivity).apply {
                text = "Volume …"
                textSize = 14f
                gravity = android.view.Gravity.CENTER
                setTextColor(getColor(R.color.wam_text))
            }
            MobileUi.addWeighted(this, volumeView, 1.2f)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@RadioStationsActivity, "+") { stepVolume(+1) },
                0.65f,
                marginDp = 0,
            )
        })
        content.addView(playbackCard)

        content.addView(MobileUi.sectionTitle(this, "Add or edit"))
        editorCard = MobileUi.card(this)
        editorCard.addView(MobileUi.label(this, "Station name"))
        aliasInput = MobileUi.field(this, "e.g. Radio Paradise").apply { setSingleLine(true) }
        editorCard.addView(aliasInput)
        editorCard.addView(
            MobileUi.label(this, "Stream URLs").apply {
                setPadding(
                    MobileUi.dp(this@RadioStationsActivity, 2),
                    MobileUi.dp(this@RadioStationsActivity, 12),
                    0,
                    MobileUi.dp(this@RadioStationsActivity, 6),
                )
            },
        )
        urlsInput = MobileUi.field(this, "Primary URL\nFallback URL\n…", multiline = true).apply {
            minLines = 3
            maxLines = 7
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_URI or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE
        }
        editorCard.addView(urlsInput)
        editorCard.addView(
            MobileUi.label(this, "TuneIn ID, optional").apply {
                setPadding(
                    MobileUi.dp(this@RadioStationsActivity, 2),
                    MobileUi.dp(this@RadioStationsActivity, 12),
                    0,
                    MobileUi.dp(this@RadioStationsActivity, 6),
                )
            },
        )
        tuneInInput = MobileUi.field(this, "e.g. s15984").apply { setSingleLine(true) }
        editorCard.addView(tuneInInput)
        editorCard.addView(
            MobileUi.button(this, "Save station", MobileUi.ButtonKind.PRIMARY) { saveStation() }.apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = MobileUi.dp(this@RadioStationsActivity, 12) }
            },
        )
        content.addView(editorCard)

        content.addView(MobileUi.sectionTitle(this, "Stations"))
        stationsView = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(stationsView)

        scrollView = ScrollView(this).apply { addView(content) }
        setContentView(scrollView)
        refreshStations()
        refreshLibrary()
        refreshStatus()
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
        if (::stationsView.isInitialized) refreshStations()
        if (::pinnedView.isInitialized) refreshLibrary()
        if (::volumeView.isInitialized) refreshVolume()
    }

    @Deprecated("Storage Access Framework callback for the platform Activity base class.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) {
            MobileUi.setStatus(
                statusView,
                "Only documents selected through Android's file picker are supported.",
                MobileUi.StatusKind.ERROR,
            )
            return
        }

        when (requestCode) {
            REQUEST_IMPORT -> runCatching {
                val name = displayName(uri) ?: "stations.json"
                val text = readStationFile(uri)
                val imported = importRadioStations(name, text)
                store.importStations(imported)
            }.fold(
                onSuccess = { count ->
                    MobileUi.setStatus(
                        statusView,
                        "Imported $count station${if (count == 1) "" else "s"}.",
                        MobileUi.StatusKind.SUCCESS,
                    )
                    refreshStations()
                    refreshLibrary()
                },
                onFailure = { error ->
                    MobileUi.setStatus(
                        statusView,
                        "Import failed: ${error.message ?: error.javaClass.simpleName}",
                        MobileUi.StatusKind.ERROR,
                    )
                },
            )

            REQUEST_EXPORT -> {
                val format = pendingExport ?: return
                pendingExport = null
                runCatching {
                    val stations = store.all()
                    val text = when (format) {
                        ExportFormat.JSON -> exportRadioStationsJson(stations)
                        ExportFormat.M3U -> exportRadioStationsM3u(stations)
                        ExportFormat.PLS -> exportRadioStationsPls(stations)
                    }
                    writeStationFile(uri, text)
                }.fold(
                    onSuccess = {
                        MobileUi.setStatus(
                            statusView,
                            "Station library exported.",
                            MobileUi.StatusKind.SUCCESS,
                        )
                    },
                    onFailure = { error ->
                        MobileUi.setStatus(
                            statusView,
                            "Export failed: ${error.message ?: error.javaClass.simpleName}",
                            MobileUi.StatusKind.ERROR,
                        )
                    },
                )
            }
        }
    }

    private fun saveStation() {
        val originalAlias = editingAlias
        val result = runCatching {
            val station = store.upsert(
                aliasInput.text.toString(),
                urlsInput.text.toString().lines(),
                tuneInInput.text.toString(),
            )
            if (originalAlias != null && !originalAlias.equals(station.alias, ignoreCase = true)) {
                store.remove(originalAlias)
            }
            station
        }
        result.fold(
            onSuccess = { station ->
                clearEditor()
                MobileUi.setStatus(
                    statusView,
                    "Saved ${station.alias}.",
                    MobileUi.StatusKind.SUCCESS,
                )
                refreshStations()
                refreshLibrary()
            },
            onFailure = { error ->
                MobileUi.setStatus(
                    statusView,
                    error.message ?: "Could not save station",
                    MobileUi.StatusKind.ERROR,
                )
            },
        )
    }

    private fun refreshLibrary() {
        val all = store.all()
        val default = store.defaultStation()
        val last = store.lastPlayed()
        librarySummaryView.text = buildString {
            append("${all.size} stations")
            default?.let { append(" · default: ${it.alias}") }
            last?.let { append(" · last: ${it.alias}") }
        }

        val recent = store.recent()
        recentView.text = if (recent.isEmpty()) {
            "Recently played: —"
        } else {
            "Recently played: " + recent.joinToString(" · ") { it.alias }
        }

        pinnedView.removeAllViews()
        val pinned = store.pinned()
        if (pinned.isEmpty()) {
            pinnedView.addView(MobileUi.body(this, "Pinned: none"))
        } else {
            pinnedView.addView(MobileUi.label(this, "Pinned"))
            pinned.chunked(2).forEach { rowStations ->
                pinnedView.addView(
                    MobileUi.row(this).apply {
                        rowStations.forEachIndexed { index, station ->
                            MobileUi.addWeighted(
                                this,
                                MobileUi.button(
                                    this@RadioStationsActivity,
                                    station.alias,
                                    MobileUi.ButtonKind.SECONDARY,
                                ) { playStation(station) },
                                marginDp = if (index == rowStations.lastIndex) 0 else 8,
                            )
                        }
                    },
                )
            }
        }
    }

    private fun refreshStations() {
        stationsView.removeAllViews()
        val stations = store.all()
        if (stations.isEmpty()) {
            stationsView.addView(MobileUi.body(this, "No stations saved yet."))
            return
        }

        val defaultAlias = store.defaultStation()?.alias
        stations.forEach { station ->
            val card = MobileUi.card(this)
            card.addView(TextView(this).apply {
                text = station.alias
                textSize = 18f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(getColor(R.color.wam_text))
            })
            val detail = buildList {
                add(sourceSummary(station))
                station.urls.take(2).forEach(::add)
            }.joinToString("\n")
            card.addView(MobileUi.body(this, detail).apply {
                maxLines = 3
                setPadding(
                    0,
                    MobileUi.dp(this@RadioStationsActivity, 4),
                    0,
                    MobileUi.dp(this@RadioStationsActivity, 12),
                )
            })
            card.addView(MobileUi.row(this).apply {
                MobileUi.addWeighted(
                    this,
                    MobileUi.button(
                        this@RadioStationsActivity,
                        "Play",
                        MobileUi.ButtonKind.PRIMARY,
                    ) { playStation(station) },
                )
                MobileUi.addWeighted(
                    this,
                    MobileUi.button(
                        this@RadioStationsActivity,
                        if (store.isPinned(station.alias)) "Unpin" else "Pin",
                    ) {
                        store.setPinned(station.alias, !store.isPinned(station.alias))
                        refreshStations()
                        refreshLibrary()
                    },
                )
                MobileUi.addWeighted(
                    this,
                    MobileUi.button(
                        this@RadioStationsActivity,
                        if (defaultAlias.equals(station.alias, true)) "Default ✓" else "Default",
                    ) {
                        if (defaultAlias.equals(station.alias, true)) {
                            store.setDefault(null)
                        } else {
                            store.setDefault(station.alias)
                        }
                        refreshStations()
                        refreshLibrary()
                    },
                    marginDp = 0,
                )
            })
            card.addView(MobileUi.row(this).apply {
                setPadding(0, MobileUi.dp(this@RadioStationsActivity, 8), 0, 0)
                MobileUi.addWeighted(
                    this,
                    MobileUi.button(this@RadioStationsActivity, "Duplicate") {
                        startEditing(station, duplicate = true)
                    },
                )
                MobileUi.addWeighted(
                    this,
                    MobileUi.button(this@RadioStationsActivity, "Edit") {
                        startEditing(station, duplicate = false)
                    },
                )
                MobileUi.addWeighted(
                    this,
                    MobileUi.button(
                        this@RadioStationsActivity,
                        "Delete",
                        MobileUi.ButtonKind.DANGER,
                    ) {
                        store.remove(station.alias)
                        if (editingAlias.equals(station.alias, ignoreCase = true)) clearEditor()
                        refreshStations()
                        refreshLibrary()
                    },
                    marginDp = 0,
                )
            })
            stationsView.addView(card)
        }
    }

    private fun sourceSummary(station: MobileRadioStation): String {
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

    private fun startEditing(station: MobileRadioStation, duplicate: Boolean) {
        editingAlias = if (duplicate) null else station.alias
        aliasInput.setText(if (duplicate) "${station.alias} copy" else station.alias)
        urlsInput.setText(station.urls.joinToString("\n"))
        tuneInInput.setText(station.tuneInId.orEmpty())
        MobileUi.setStatus(
            statusView,
            if (duplicate) "Duplicating ${station.alias}." else "Editing ${station.alias}.",
        )
        editorCard.post { scrollView.smoothScrollTo(0, editorCard.top) }
    }

    private fun clearEditor() {
        editingAlias = null
        aliasInput.text.clear()
        urlsInput.text.clear()
        tuneInInput.text.clear()
    }

    private fun playPreferred(default: Boolean) {
        val station = if (default) store.defaultStation() else store.lastPlayed()
        if (station == null) {
            MobileUi.setStatus(
                statusView,
                if (default) "No default station selected." else "Nothing has played yet.",
                MobileUi.StatusKind.INFO,
            )
            return
        }
        playStation(station)
    }

    private fun playStation(station: MobileRadioStation) {
        startForegroundService(
            Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_PLAY
                putExtra(RadioService.EXTRA_ALIAS, station.alias)
            },
        )
        MobileUi.setStatus(statusView, "Starting ${station.alias}…")
        window.decorView.postDelayed({ refreshStatus() }, 900)
    }

    private fun stopRadio() {
        startService(
            Intent(this, RadioService::class.java).apply {
                action = RadioService.ACTION_STOP
            },
        )
        MobileUi.setStatus(statusView, "Stopping radio…")
        window.decorView.postDelayed({ refreshStatus() }, 300)
    }

    private fun importStations() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQUEST_IMPORT,
        )
    }

    private fun chooseExport() {
        val formats = ExportFormat.entries.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Export stations")
            .setItems(formats.map { it.label }.toTypedArray()) { _, which ->
                exportStations(formats[which])
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportStations(format: ExportFormat) {
        pendingExport = format
        startActivityForResult(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = format.mime
                putExtra(Intent.EXTRA_TITLE, "wambridge-stations.${format.extension}")
            },
            REQUEST_EXPORT,
        )
    }

    private fun readStationFile(uri: Uri): String {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "Station import must use a content URI"
        }
        val normalized = FileSystems.getDefault().getPath(uri.path.orEmpty()).normalize()
        if (normalized.startsWith("/data")) {
            throw SecurityException("Private app paths cannot be imported")
        }
        val reader = contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?: error("Could not read the selected file")
        return reader.use {
            val result = StringBuilder()
            val buffer = CharArray(8192)
            while (true) {
                val count = it.read(buffer)
                if (count < 0) break
                require(result.length + count <= MAX_IMPORT_CHARS) {
                    "Station file is too large"
                }
                result.append(buffer, 0, count)
            }
            result.toString()
        }
    }

    private fun writeStationFile(uri: Uri, text: String) {
        require(uri.scheme == ContentResolver.SCHEME_CONTENT) {
            "Station export must use a content URI"
        }
        val normalized = FileSystems.getDefault().getPath(uri.path.orEmpty()).normalize()
        if (normalized.startsWith("/data")) {
            throw SecurityException("Private app paths cannot be exported")
        }
        contentResolver.openOutputStream(uri, "wt")
            ?.bufferedWriter(Charsets.UTF_8)
            ?.use { it.write(text) }
            ?: error("Could not write the selected file")
    }

    private fun displayName(uri: Uri): String? =
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        }

    private fun clientUuid(): String {
        val preferences = getSharedPreferences(RendererService.PREFS, MODE_PRIVATE)
        return preferences.getString(KEY_CLIENT_UUID, null)
            ?: SamsungWamChannel.newClientUuid().also {
                preferences.edit().putString(KEY_CLIENT_UUID, it).apply()
            }
    }

    private fun speakerAddress(): String? {
        val target = getSharedPreferences(RendererService.PREFS, MODE_PRIVATE)
            .getString(RendererService.KEY_SPEAKER_IP, "")
            .orEmpty()
            .trim()
        return if (RendererService.isReasonableIpv4(target)) target else null
    }

    private fun refreshVolume() {
        val target = speakerAddress() ?: run {
            volumeView.text = "volume —"
            return
        }
        val appContext = applicationContext
        Thread({
            val read = runCatching { SamsungWamChannel.readVolumeRaw(appContext, target) }
            runOnUiThread {
                read.fold(
                    onSuccess = { step ->
                        volumeStep = step
                        volumeView.text = "volume $step/${SamsungWamChannel.MAX_VOLUME_STEP}"
                    },
                    onFailure = {
                        volumeStep = null
                        volumeView.text = "volume ?"
                    },
                )
            }
        }, "wam-radio-volume-read").start()
    }

    private fun stepVolume(delta: Int) {
        val target = speakerAddress() ?: run {
            MobileUi.setStatus(
                statusView,
                "Configure the M5 address in WAM Bridge first.",
                MobileUi.StatusKind.ERROR,
            )
            return
        }
        val appContext = applicationContext
        Thread({
            val current = volumeStep
                ?: runCatching { SamsungWamChannel.readVolumeRaw(appContext, target) }.getOrNull()
            if (current == null) {
                runOnUiThread {
                    volumeView.text = "volume ?"
                    MobileUi.setStatus(
                        statusView,
                        "Could not read the speaker volume.",
                        MobileUi.StatusKind.ERROR,
                    )
                }
                return@Thread
            }
            val wanted = (current + delta)
                .coerceIn(SamsungWamChannel.MIN_VOLUME_STEP, SamsungWamChannel.MAX_VOLUME_STEP)
            val applied = runCatching {
                val channel = SamsungWamChannel(appContext, target, clientUuid())
                try {
                    channel.connect()
                    channel.setVolumeRaw(wanted)
                } finally {
                    channel.close()
                }
            }
            runOnUiThread {
                applied.fold(
                    onSuccess = {
                        volumeStep = wanted
                        volumeView.text = "volume $wanted/${SamsungWamChannel.MAX_VOLUME_STEP}"
                    },
                    onFailure = { error ->
                        MobileUi.setStatus(
                            statusView,
                            "Could not set volume: ${error.message ?: error.javaClass.simpleName}",
                            MobileUi.StatusKind.ERROR,
                        )
                    },
                )
            }
        }, "wam-radio-volume-step").start()
    }

    private fun refreshStatus() {
        MobileUi.setStatus(
            statusView,
            if (RadioService.running) {
                "● ${RadioService.lastStatus}"
            } else {
                "○ ${RadioService.lastStatus}"
            },
        )
    }

    private enum class ExportFormat(
        val label: String,
        val extension: String,
        val mime: String,
    ) {
        JSON("JSON · full fidelity", "json", "application/json"),
        M3U("M3U · primary direct URLs", "m3u", "audio/x-mpegurl"),
        PLS("PLS · primary direct URLs", "pls", "audio/x-scpls"),
    }

    companion object {
        private const val KEY_CLIENT_UUID = "radio_stations_client_uuid"
        private const val REQUEST_IMPORT = 7101
        private const val REQUEST_EXPORT = 7102
        private const val MAX_IMPORT_CHARS = 2 * 1024 * 1024
    }
}
