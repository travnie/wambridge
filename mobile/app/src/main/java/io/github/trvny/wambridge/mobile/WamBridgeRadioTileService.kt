package io.github.trvny.wambridge.mobile

import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/**
 * One-tap radio surface.
 *
 * Idle tap starts the last played station (then default/top3 fallback). While app radio is
 * already running, another tap advances through the shared station_packs.json top3 list.
 */
class WamBridgeRadioTileService : TileService() {
    private var speakerSubscription: AutoCloseable? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        speakerSubscription?.close()
        speakerSubscription = SpeakerStateStore.subscribe {
            mainHandler.post(::refreshTile)
        }
        refreshTile()
    }

    override fun onStopListening() {
        speakerSubscription?.close()
        speakerSubscription = null
        super.onStopListening()
    }

    override fun onDestroy() {
        speakerSubscription?.close()
        speakerSubscription = null
        super.onDestroy()
    }

    override fun onClick() {
        super.onClick()
        if (RadioService.active && !RadioService.running) {
            showTile(Tile.STATE_UNAVAILABLE, "Radio busy…")
            return
        }

        val store = RadioStationStore(this)
        val snapshot = SpeakerStateStore.current()
        val station = nextRadioTileStation(
            quickStations = store.quickStations(),
            lastPlayed = store.lastPlayed(),
            defaultStation = store.defaultStation(),
            activeAlias = snapshot.stationAlias.takeIf { RadioService.running },
        )
        if (station == null) {
            showTile(Tile.STATE_INACTIVE, "No station")
            return
        }

        showTile(Tile.STATE_UNAVAILABLE, "Starting ${station.alias}…")
        try {
            startForegroundService(
                Intent(this, RadioService::class.java).apply {
                    action = RadioService.ACTION_PLAY
                    putExtra(RadioService.EXTRA_ALIAS, station.alias)
                },
            )
        } catch (_: IllegalStateException) {
            showTile(Tile.STATE_INACTIVE, "Start blocked")
        } catch (_: SecurityException) {
            showTile(Tile.STATE_INACTIVE, "Start blocked")
        }
    }

    private fun refreshTile() {
        val tile = qsTile ?: return
        val snapshot = SpeakerStateStore.current()
        tile.label = getString(R.string.tile_radio_name)
        when {
            RadioService.active && !RadioService.running -> {
                tile.state = Tile.STATE_UNAVAILABLE
                setSubtitle(tile, "Starting…")
            }

            RadioService.running -> {
                tile.state = Tile.STATE_ACTIVE
                setSubtitle(
                    tile,
                    snapshot.metadata?.takeIf(String::isNotBlank)
                        ?: snapshot.stationAlias?.takeIf(String::isNotBlank)
                        ?: "Radio on",
                )
            }

            else -> {
                tile.state = Tile.STATE_INACTIVE
                val store = RadioStationStore(this)
                val next = store.lastPlayed() ?: store.defaultStation() ?: store.quickStations().firstOrNull()
                setSubtitle(tile, next?.let { "Tap · ${it.alias}" } ?: "Choose station")
            }
        }
        tile.updateTile()
    }

    private fun showTile(state: Int, subtitle: String) {
        val tile = qsTile ?: return
        tile.label = getString(R.string.tile_radio_name)
        tile.state = state
        setSubtitle(tile, subtitle)
        tile.updateTile()
    }

    private fun setSubtitle(tile: Tile, value: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) tile.subtitle = value
    }
}
