package io.github.trvny.wambridge.mobile

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.StatusBarManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The launcher alias, spelled as `AndroidManifest.xml` declares it.
 *
 * `LauncherAliasNameTest` pins the two together, because nothing else would notice them drifting:
 * `setComponentEnabledSetting` on a component that does not exist is a silent no-op.
 */
internal const val LAUNCHER_ALIAS_CLASS = "trvny.wambridge.mobile.LauncherAlias"
private const val REQUEST_NOTIFICATIONS = 4101
private const val KEY_NOTIFICATION_PERMISSION_REQUESTED = "notification_permission_requested"

class MainActivity : Activity() {
    private lateinit var launcherButton: Button
    private lateinit var discoverButton: Button
    private lateinit var startRendererButton: Button
    private lateinit var stopRendererButton: Button
    private lateinit var statusView: TextView
    private lateinit var homeStatusView: TextView
    private lateinit var homeNowPlayingTitle: TextView
    private lateinit var homeNowPlayingMeta: TextView
    private lateinit var homePlayPauseButton: Button
    private lateinit var homeMuteButton: Button
    private lateinit var homePresetStatusView: TextView
    private lateinit var sleepTimerStatusView: TextView
    private val homePresetButtons = mutableListOf<Button>()
    private lateinit var radioPresetStatusView: TextView
    private val radioPresetButtons = mutableListOf<Button>()
    private lateinit var homePane: View
    private lateinit var radioPane: View
    private lateinit var settingsPane: View
    private lateinit var homeNavButton: Button
    private lateinit var radioNavButton: Button
    private lateinit var settingsNavButton: Button
    private var currentDestination = MainDestination.HOME
    private var speakerStateSubscription: AutoCloseable? = null
    private var physicalPresetSubscription: AutoCloseable? = null
    private val speakerControlButtons = mutableListOf<Button>()
    private var speakerControlRunning = false

    private val autoDiscoveryGeneration = AtomicInteger()
    private val speakerInputRevision = AtomicInteger()
    private val discoveryExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wam-mobile-auto-discovery").apply { isDaemon = true }
    }
    private val controlExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wam-mobile-speaker-control").apply { isDaemon = true }
    }
    private val presetExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wam-mobile-physical-presets").apply { isDaemon = true }
    }
    private val timerExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wam-mobile-sleep-timer").apply { isDaemon = true }
    }
    private val presetWorkRunning = AtomicBoolean(false)
    private val autoDiscoveryRetry = Runnable {
        if (!isFinishing && !isDestroyed) autoDiscoverSpeaker()
    }

    private val preferences by lazy {
        getSharedPreferences(RendererService.PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)
        requestNotificationPermission()

        currentDestination = mainDestination(
            intent?.action,
            intent?.getStringExtra(MainNavigation.EXTRA_DESTINATION),
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.wam_background))
        }
        val contentHost = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f,
            )
        }

        homePane = buildHomePane()
        radioPane = buildRadioPane()
        settingsPane = buildSettingsPane()
        contentHost.addView(homePane)
        contentHost.addView(radioPane)
        contentHost.addView(settingsPane)

        val navigation = MobileUi.bottomNavigation(this).apply {
            homeNavButton = MobileUi.navigationButton(this@MainActivity, "Home") {
                showDestination(MainDestination.HOME)
            }
            radioNavButton = MobileUi.navigationButton(this@MainActivity, "Radio") {
                showDestination(MainDestination.RADIO)
            }
            settingsNavButton = MobileUi.navigationButton(this@MainActivity, "Settings") {
                showDestination(MainDestination.SETTINGS)
            }
            MobileUi.addWeighted(this, homeNavButton)
            MobileUi.addWeighted(this, radioNavButton)
            MobileUi.addWeighted(this, settingsNavButton, marginDp = 0)
        }

        root.addView(contentHost)
        root.addView(navigation)
        setContentView(root)
        showDestination(currentDestination)

        refreshLauncherButton()
        refreshStatus()
        window.decorView.post(autoDiscoveryRetry)
    }

    private fun buildHomePane(): View {
        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                getString(R.string.app_name),
                "Your M5, without the archaeology.",
            ),
        )

        val nowPlaying = MobileUi.card(this)
        nowPlaying.addView(MobileUi.label(this, "Now Playing"))
        nowPlaying.addView(MobileUi.row(this).apply {
            val badge = MobileUi.heroBadge(this@MainActivity, "♪")
            addView(
                badge,
                LinearLayout.LayoutParams(
                    MobileUi.dp(this@MainActivity, 76),
                    MobileUi.dp(this@MainActivity, 76),
                ).apply { marginEnd = MobileUi.dp(this@MainActivity, 14) },
            )
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    homeNowPlayingTitle = MobileUi.heroTitle(this@MainActivity, "Samsung M5")
                    homeNowPlayingMeta = MobileUi.heroMeta(this@MainActivity, "Connecting…")
                    addView(homeNowPlayingTitle)
                    addView(homeNowPlayingMeta)
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        })
        fun homeControl(
            label: String,
            action: SpeakerControls.Action,
            kind: MobileUi.ButtonKind = MobileUi.ButtonKind.SECONDARY,
        ): Button = MobileUi.button(this, label, kind) {
            runSpeakerControl(action, homeStatusView)
        }.also { speakerControlButtons += it }

        nowPlaying.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 14), 0, 0)
            val stopButton = MobileUi.button(
                this@MainActivity,
                "Stop",
                MobileUi.ButtonKind.DANGER,
            ) { stopHomePlayback() }
            homePlayPauseButton = homeControl(
                "Play / pause",
                SpeakerControls.Action.PLAY_PAUSE,
                MobileUi.ButtonKind.PRIMARY,
            )
            homeMuteButton = homeControl("Mute", SpeakerControls.Action.MUTE)
            MobileUi.addWeighted(this, stopButton, 0.85f)
            MobileUi.addWeighted(this, homePlayPauseButton, 1.35f)
            MobileUi.addWeighted(this, homeMuteButton, 1f, marginDp = 0)
        })
        nowPlaying.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 8), 0, 0)
            MobileUi.addWeighted(
                this,
                homeControl("Volume −", SpeakerControls.Action.VOLUME_DOWN),
            )
            MobileUi.addWeighted(
                this,
                homeControl("Volume +", SpeakerControls.Action.VOLUME_UP),
                marginDp = 0,
            )
        })
        content.addView(nowPlaying)

        homeStatusView = MobileUi.status(this, "Connecting…")
        content.addView(homeStatusView)

        content.addView(MobileUi.sectionTitle(this, "Physical presets"))
        val presets = MobileUi.card(this)
        presets.addView(
            MobileUi.body(
                this,
                "The same three slots cycled by the Radio button on the M5.",
            ),
        )
        homePresetStatusView = MobileUi.status(this, "Waiting for M5…")
        homePresetStatusView.setPadding(
            MobileUi.dp(this, 10),
            MobileUi.dp(this, 8),
            MobileUi.dp(this, 10),
            MobileUi.dp(this, 8),
        )
        presets.addView(homePresetStatusView)
        presets.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 10), 0, 0)
            repeat(PHYSICAL_PRESET_SLOTS) { index ->
                val button = MobileUi.button(
                    this@MainActivity,
                    "Preset ${index + 1}",
                    if (index == 0) MobileUi.ButtonKind.PRIMARY else MobileUi.ButtonKind.SECONDARY,
                ) {
                    playPhysicalPreset(index)
                }.apply {
                    maxLines = 2
                }
                homePresetButtons += button
                MobileUi.addWeighted(
                    this,
                    button,
                    marginDp = if (index == PHYSICAL_PRESET_SLOTS - 1) 0 else 6,
                )
            }
        })
        content.addView(presets)

        content.addView(MobileUi.sectionTitle(this, "Quick actions"))
        val quickActions = MobileUi.card(this)
        quickActions.addView(MobileUi.row(this).apply {
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Stations") {
                    showDestination(MainDestination.RADIO)
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Sleep") {
                    showSleepTimerChooser()
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Reconnect") {
                    runDiscovery(manual = false)
                },
                marginDp = 0,
            )
        })
        content.addView(quickActions)

        return ScrollView(this).apply { addView(content) }
    }

    private fun buildRadioPane(): View {
        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "Radio",
                "The M5's physical presets, your saved stations and TuneIn.",
            ),
        )

        content.addView(MobileUi.sectionTitle(this, "Physical presets"))
        val physical = MobileUi.card(this)
        physical.addView(
            MobileUi.body(
                this,
                "Slots 1–3 are read directly from the speaker and match its physical Radio button.",
            ),
        )
        radioPresetStatusView = MobileUi.status(this, "Waiting for M5…")
        radioPresetStatusView.setPadding(
            MobileUi.dp(this, 10),
            MobileUi.dp(this, 8),
            MobileUi.dp(this, 10),
            MobileUi.dp(this, 8),
        )
        physical.addView(radioPresetStatusView)
        physical.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 10), 0, 0)
            repeat(PHYSICAL_PRESET_SLOTS) { index ->
                val button = MobileUi.button(
                    this@MainActivity,
                    "Preset ${index + 1}",
                    if (index == 0) MobileUi.ButtonKind.PRIMARY else MobileUi.ButtonKind.SECONDARY,
                ) {
                    playPhysicalPreset(index)
                }.apply { maxLines = 2 }
                radioPresetButtons += button
                MobileUi.addWeighted(
                    this,
                    button,
                    marginDp = if (index == PHYSICAL_PRESET_SLOTS - 1) 0 else 6,
                )
            }
        })
        content.addView(physical)

        content.addView(MobileUi.sectionTitle(this, "Saved stations"))
        val saved = MobileUi.card(this)
        saved.addView(
            MobileUi.body(
                this,
                "Your app-side station list, independent from the three physical M5 slots.",
            ),
        )
        saved.addView(
            MobileUi.button(this, "Saved stations", MobileUi.ButtonKind.PRIMARY) {
                startActivity(Intent(this, RadioStationsActivity::class.java))
            },
        )
        content.addView(saved)

        content.addView(MobileUi.sectionTitle(this, "Explore"))
        val explore = MobileUi.card(this)
        explore.addView(
            MobileUi.body(
                this,
                "Browse the M5 TuneIn catalogue or open the full preset list.",
            ),
        )
        explore.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 10), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Browse TuneIn", MobileUi.ButtonKind.PRIMARY) {
                    startActivity(Intent(this@MainActivity, CatalogueActivity::class.java))
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "All presets") {
                    startActivity(Intent(this@MainActivity, TuneInActivity::class.java))
                },
                marginDp = 0,
            )
        })
        content.addView(explore)

        return ScrollView(this).apply { addView(content) }
    }

    private fun buildSettingsPane(): View {
        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "Settings",
                "Speaker, playback, radio and troubleshooting.",
            ),
        )

        statusView = MobileUi.status(this)
        content.addView(statusView)

        content.addView(MobileUi.sectionTitle(this, "Speaker"))
        val speakerCard = MobileUi.card(this)
        speakerCard.addView(MobileUi.label(this, "Samsung M5"))
        speakerCard.addView(
            MobileUi.body(
                this,
                "Discovery follows the saved M5 across DHCP changes. Manual IP lives under Advanced.",
            ),
        )
        speakerCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 12), 0, 0)
            discoverButton = MobileUi.button(
                this@MainActivity,
                "Discover",
                MobileUi.ButtonKind.PRIMARY,
            ) { runDiscovery(manual = true) }
            MobileUi.addWeighted(this, discoverButton)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Test connection") { testConnection() },
                marginDp = 0,
            )
        })
        content.addView(speakerCard)

        content.addView(MobileUi.sectionTitle(this, "Playback & system"))
        val playbackCard = MobileUi.card(this)
        playbackCard.addView(
            MobileUi.body(
                this,
                "DLNA renderer status plus Android shortcuts. Playback controls live on Home.",
            ),
        )
        sleepTimerStatusView = MobileUi.status(this, "Sleep timer · unknown")
        playbackCard.addView(sleepTimerStatusView)
        playbackCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 12), 0, 0)
            startRendererButton = MobileUi.button(
                this@MainActivity,
                "Start renderer",
                MobileUi.ButtonKind.PRIMARY,
            ) { startRenderer() }
            stopRendererButton = MobileUi.button(
                this@MainActivity,
                "Stop renderer",
                MobileUi.ButtonKind.DANGER,
            ) {
                startService(
                    Intent(this@MainActivity, RendererService::class.java).apply {
                        action = RendererService.ACTION_STOP
                    },
                )
                refreshUntilSettled()
            }
            MobileUi.addWeighted(this, startRendererButton)
            MobileUi.addWeighted(this, stopRendererButton, marginDp = 0)
        })
        playbackCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 8), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Quick Settings") {
                    requestQuickSettingsTile()
                },
            )
            launcherButton = MobileUi.button(this@MainActivity, "Launcher") {
                if (isLauncherHidden()) setLauncherVisible(true) else confirmHideLauncher()
            }
            MobileUi.addWeighted(this, launcherButton, marginDp = 0)
        })
        content.addView(playbackCard)

        content.addView(MobileUi.sectionTitle(this, "Radio"))
        val radioCard = MobileUi.card(this)
        radioCard.addView(
            MobileUi.body(
                this,
                "The physical M5 presets and your saved app-side stations stay separate.",
            ),
        )
        radioCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 10), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Physical presets") {
                    showDestination(MainDestination.RADIO)
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Saved stations") {
                    startActivity(Intent(this@MainActivity, RadioStationsActivity::class.java))
                },
                marginDp = 0,
            )
        })
        content.addView(radioCard)

        content.addView(MobileUi.sectionTitle(this, "Diagnostics"))
        val diagnosticsCard = MobileUi.card(this)
        diagnosticsCard.addView(
            MobileUi.body(
                this,
                "Readable runtime/network state first; manual IP and low-level setup stay tucked away.",
            ),
        )
        diagnosticsCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@MainActivity, 10), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(
                    this@MainActivity,
                    "Diagnostics",
                    MobileUi.ButtonKind.PRIMARY,
                ) {
                    startActivity(Intent(this@MainActivity, DiagnosticsActivity::class.java))
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@MainActivity, "Advanced") {
                    startActivity(Intent(this@MainActivity, AdvancedSettingsActivity::class.java))
                },
                marginDp = 0,
            )
        })
        content.addView(diagnosticsCard)

        return ScrollView(this).apply { addView(content) }
    }

    private fun showDestination(destination: MainDestination) {
        currentDestination = destination
        val visibility = mainPaneVisibility(destination)
        homePane.visibility = if (visibility.home) View.VISIBLE else View.GONE
        radioPane.visibility = if (visibility.radio) View.VISIBLE else View.GONE
        settingsPane.visibility = if (visibility.settings) View.VISIBLE else View.GONE
        MobileUi.setNavigationSelected(homeNavButton, visibility.home)
        MobileUi.setNavigationSelected(radioNavButton, visibility.radio)
        MobileUi.setNavigationSelected(settingsNavButton, visibility.settings)
        if (visibility.home || visibility.radio) {
            val presets = PhysicalPresetStore.current()
            if (!presets.loading && presets.slots.all { it == null }) {
                refreshPhysicalPresets()
            }
        }
        if (visibility.settings) refreshSleepTimerState()
    }

    private fun renderHomeState(snapshot: SpeakerSnapshot) {
        if (!::homeStatusView.isInitialized) return

        val owner = when (snapshot.owner) {
            SpeakerOwner.IDLE -> "M5"
            SpeakerOwner.RADIO -> "Radio"
            SpeakerOwner.RENDERER -> "DLNA"
        }
        val playback = when (snapshot.playback) {
            SpeakerPlaybackState.STOPPED -> "idle"
            SpeakerPlaybackState.STARTING -> "starting"
            SpeakerPlaybackState.PLAYING -> "playing"
            SpeakerPlaybackState.PAUSED -> "paused"
            SpeakerPlaybackState.STOPPING -> "stopping"
            SpeakerPlaybackState.UNKNOWN -> "status unknown"
        }

        homeNowPlayingTitle.text = snapshot.stationAlias
            ?: when (snapshot.owner) {
                SpeakerOwner.RENDERER -> "DLNA player"
                SpeakerOwner.RADIO -> "Radio"
                SpeakerOwner.IDLE -> "Samsung M5"
            }

        val detail = buildList {
            snapshot.source?.takeIf(String::isNotBlank)?.let(::add)
            snapshot.metadata?.takeIf(String::isNotBlank)?.let(::add)
            add(playback)
            snapshot.volume?.let { add("volume $it/30") }
            if (snapshot.muted == true) add("muted")
        }.joinToString(" · ")
        homeNowPlayingMeta.text = detail

        if (::homePlayPauseButton.isInitialized) {
            homePlayPauseButton.text = when (snapshot.playback) {
                SpeakerPlaybackState.PLAYING -> "Pause"
                SpeakerPlaybackState.PAUSED, SpeakerPlaybackState.STOPPED -> "Play"
                else -> "Play / pause"
            }
        }
        if (::homeMuteButton.isInitialized) {
            homeMuteButton.text = if (snapshot.muted == true) "Unmute" else "Mute"
        }

        val connectionText = when (snapshot.discovery) {
            SpeakerDiscoveryStage.READY ->
                "● $owner connected" + snapshot.speakerIp?.let { " · $it" }.orEmpty()
            SpeakerDiscoveryStage.FAILED ->
                snapshot.lastError?.let { "M5 not found · $it" } ?: "M5 not found"
            else -> discoveryStatus(snapshot.discovery)
        }
        val kind = when (snapshot.discovery) {
            SpeakerDiscoveryStage.FAILED -> MobileUi.StatusKind.ERROR
            SpeakerDiscoveryStage.READY -> MobileUi.StatusKind.SUCCESS
            else -> MobileUi.StatusKind.INFO
        }
        MobileUi.setStatus(homeStatusView, connectionText, kind)
        renderSleepTimerState(snapshot.sleepTimer)
    }

    private fun renderSleepTimerState(state: SleepTimerState) {
        if (!::sleepTimerStatusView.isInitialized) return
        val text = when (state.phase) {
            SleepTimerPhase.UNKNOWN -> "Sleep timer · unknown"
            SleepTimerPhase.REQUESTED -> "Sleep timer · request sent"
            SleepTimerPhase.OFF -> "Sleep timer · off"
            SleepTimerPhase.ARMED -> "Sleep timer · M5 reports ${state.seconds ?: "?"}s"
        }
        MobileUi.setStatus(
            sleepTimerStatusView,
            text,
            if (state.phase == SleepTimerPhase.ARMED) {
                MobileUi.StatusKind.SUCCESS
            } else {
                MobileUi.StatusKind.INFO
            },
        )
    }

    private fun showSleepTimerChooser() {
        val labels = arrayOf("15 min", "30 min", "45 min", "60 min", "Off", "Standby now")
        AlertDialog.Builder(this)
            .setTitle("Sleep timer")
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> runSleepTimerAction { SleepTimerControls.set(applicationContext, sleepTimerSeconds(15)) }
                    1 -> runSleepTimerAction { SleepTimerControls.set(applicationContext, sleepTimerSeconds(30)) }
                    2 -> runSleepTimerAction { SleepTimerControls.set(applicationContext, sleepTimerSeconds(45)) }
                    3 -> runSleepTimerAction { SleepTimerControls.set(applicationContext, sleepTimerSeconds(60)) }
                    4 -> runSleepTimerAction { SleepTimerControls.set(applicationContext, 0) }
                    5 -> runSleepTimerAction { SleepTimerControls.standbyNow(applicationContext) }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun runSleepTimerAction(action: () -> SleepTimerControls.Outcome) {
        if (timerExecutor.isShutdown) return
        if (::homeStatusView.isInitialized) {
            MobileUi.setStatus(homeStatusView, "Updating M5 sleep timer…")
        }
        timerExecutor.execute {
            val result = runCatching(action)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderSleepTimerState(SpeakerStateStore.current().sleepTimer)
                result.fold(
                    onSuccess = { outcome ->
                        val kind = when (outcome.state.phase) {
                            SleepTimerPhase.REQUESTED, SleepTimerPhase.UNKNOWN ->
                                MobileUi.StatusKind.INFO
                            SleepTimerPhase.ARMED, SleepTimerPhase.OFF ->
                                MobileUi.StatusKind.SUCCESS
                        }
                        MobileUi.setStatus(
                            homeStatusView,
                            outcome.message,
                            kind,
                        )
                    },
                    onFailure = { error ->
                        MobileUi.setStatus(
                            homeStatusView,
                            error.message ?: error.javaClass.simpleName,
                            MobileUi.StatusKind.ERROR,
                        )
                    },
                )
            }
        }
    }

    private fun refreshSleepTimerState() {
        if (timerExecutor.isShutdown) return
        timerExecutor.execute {
            runCatching { SleepTimerControls.refresh(applicationContext) }
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    renderSleepTimerState(SpeakerStateStore.current().sleepTimer)
                }
            }
        }
    }

    private fun renderPhysicalPresets(snapshot: PhysicalPresetSnapshot) {
        if (!::homePresetStatusView.isInitialized) return
        val busy = presetWorkRunning.get()
        val status = when {
            busy -> "Working with M5 presets…"
            snapshot.loading -> "Reading physical presets…"
            snapshot.error != null -> snapshot.error
            snapshot.slots.any { it != null } -> "Synced with the M5 Radio button."
            else -> "No physical presets loaded yet."
        }
        val statusKind =
            if (snapshot.error != null) MobileUi.StatusKind.ERROR else MobileUi.StatusKind.INFO
        MobileUi.setStatus(homePresetStatusView, status, statusKind)
        if (::radioPresetStatusView.isInitialized) {
            MobileUi.setStatus(radioPresetStatusView, status, statusKind)
        }

        fun renderButtons(buttons: List<Button>) {
            buttons.forEachIndexed { index, button ->
                val preset = snapshot.slots.getOrNull(index)
                button.text = if (preset == null) {
                    "${index + 1} · Empty"
                } else {
                    "${index + 1} · ${preset.title}"
                }
                MobileUi.setEnabled(button, preset != null && !snapshot.loading && !busy)
            }
        }
        renderButtons(homePresetButtons)
        renderButtons(radioPresetButtons)
    }

    private fun refreshPhysicalPresets() {
        if (presetExecutor.isShutdown || !presetWorkRunning.compareAndSet(false, true)) return
        renderPhysicalPresets(PhysicalPresetStore.current())
        presetExecutor.execute {
            runCatching { PhysicalPresetController.refresh(applicationContext) }
            presetWorkRunning.set(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderPhysicalPresets(PhysicalPresetStore.current())
            }
        }
    }

    private fun playPhysicalPreset(index: Int) {
        val preset = PhysicalPresetStore.current().slots.getOrNull(index) ?: return
        if (presetExecutor.isShutdown || !presetWorkRunning.compareAndSet(false, true)) return
        renderPhysicalPresets(PhysicalPresetStore.current())
        MobileUi.setStatus(homeStatusView, "Starting ${preset.title}…")
        if (::radioPresetStatusView.isInitialized) {
            MobileUi.setStatus(radioPresetStatusView, "Starting ${preset.title}…")
        }
        presetExecutor.execute {
            val result = runCatching {
                PhysicalPresetController.play(applicationContext, preset)
            }
            presetWorkRunning.set(false)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                renderPhysicalPresets(PhysicalPresetStore.current())
                result.fold(
                    onSuccess = {
                        MobileUi.setStatus(
                            homeStatusView,
                            "Playing · ${preset.title}",
                            MobileUi.StatusKind.SUCCESS,
                        )
                        if (::radioPresetStatusView.isInitialized) {
                            MobileUi.setStatus(
                                radioPresetStatusView,
                                "Playing · ${preset.title}",
                                MobileUi.StatusKind.SUCCESS,
                            )
                        }
                    },
                    onFailure = { error ->
                        val message = error.message ?: error.javaClass.simpleName
                        MobileUi.setStatus(
                            homeStatusView,
                            message,
                            MobileUi.StatusKind.ERROR,
                        )
                        if (::radioPresetStatusView.isInitialized) {
                            MobileUi.setStatus(
                                radioPresetStatusView,
                                message,
                                MobileUi.StatusKind.ERROR,
                            )
                        }
                    },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        speakerStateSubscription?.close()
        physicalPresetSubscription?.close()
        speakerStateSubscription = SpeakerStateStore.subscribe { snapshot ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderHomeState(snapshot)
            }
        }
        physicalPresetSubscription = PhysicalPresetStore.subscribe { snapshot ->
            runOnUiThread {
                if (!isFinishing && !isDestroyed) renderPhysicalPresets(snapshot)
            }
        }
    }

    override fun onStop() {
        speakerStateSubscription?.close()
        speakerStateSubscription = null
        physicalPresetSubscription?.close()
        physicalPresetSubscription = null
        super.onStop()
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (preferences.getBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, false)) return

        preferences.edit().putBoolean(KEY_NOTIFICATION_PERMISSION_REQUESTED, true).apply()
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) refreshStatus()
        if (currentDestination == MainDestination.SETTINGS) refreshSleepTimerState()
    }

    override fun onDestroy() {
        autoDiscoveryGeneration.incrementAndGet()
        window.decorView.removeCallbacks(autoDiscoveryRetry)
        discoveryExecutor.shutdownNow()
        controlExecutor.shutdownNow()
        presetExecutor.shutdownNow()
        timerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun cancelAutoDiscovery() {
        autoDiscoveryGeneration.incrementAndGet()
        window.decorView.removeCallbacks(autoDiscoveryRetry)
        if (::discoverButton.isInitialized) MobileUi.setEnabled(discoverButton, true)
    }

    private fun autoDiscoverSpeaker() = runDiscovery(manual = false)

    private fun runDiscovery(manual: Boolean) {
        if (isFinishing || isDestroyed || discoveryExecutor.isShutdown) return
        window.decorView.removeCallbacks(autoDiscoveryRetry)

        if (RendererService.busy || RadioService.active) {
            if (manual) {
                Toast.makeText(
                    this,
                    "Stop renderer/radio playback before discovery.",
                    Toast.LENGTH_SHORT,
                ).show()
            } else {
                window.decorView.postDelayed(autoDiscoveryRetry, 1_000L)
            }
            return
        }

        val savedBefore = preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim()
        val previous = savedBefore
        val inputRevision = speakerInputRevision.get()
        val generation = autoDiscoveryGeneration.incrementAndGet()

        MobileUi.setEnabled(discoverButton, false)
        MobileUi.setStatus(
            statusView,
            if (manual) "Discovering WAM speakers on Wi-Fi…" else "Finding M5 on Wi-Fi…",
        )

        discoveryExecutor.execute {
            val result = runCatching {
                SpeakerTarget.resolveDetailed(
                    context = applicationContext,
                    persist = false,
                    forceDiscovery = manual,
                    shouldContinue = {
                        autoDiscoveryStillCurrent(generation, inputRevision, savedBefore)
                    },
                    onStage = { stage ->
                        SpeakerStateStore.update {
                            it.copy(
                                discovery = stage,
                                status = discoveryStatus(stage),
                                lastError = null,
                            )
                        }
                    },
                )
            }

            runOnUiThread {
                if (isFinishing || isDestroyed || autoDiscoveryGeneration.get() != generation) {
                    return@runOnUiThread
                }
                MobileUi.setEnabled(discoverButton, true)

                result.fold(
                    onSuccess = { outcome ->
                        applyDiscoveryOutcome(
                            manual = manual,
                            generation = generation,
                            inputRevision = inputRevision,
                            savedBefore = savedBefore,
                            previous = previous,
                            outcome = outcome,
                        )
                    },
                    onFailure = { error ->
                        val message = error.message ?: error.javaClass.simpleName
                        SpeakerStateStore.update {
                            it.copy(
                                discovery = SpeakerDiscoveryStage.FAILED,
                                status = "M5 not found",
                                lastError = message,
                            )
                        }
                        MobileUi.setStatus(
                            statusView,
                            "Discovery failed: $message",
                            MobileUi.StatusKind.ERROR,
                        )
                    },
                )
            }
        }
    }

    private fun autoDiscoveryStillCurrent(
        generation: Int,
        inputRevision: Int,
        savedBefore: String,
    ): Boolean = autoDiscoveryGeneration.get() == generation &&
        speakerInputRevision.get() == inputRevision &&
        preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim() == savedBefore &&
        !Thread.currentThread().isInterrupted

    private fun applyDiscoveryOutcome(
        manual: Boolean,
        generation: Int,
        inputRevision: Int,
        savedBefore: String,
        previous: String,
        outcome: SpeakerTarget.ResolveOutcome,
    ) {
        if (isFinishing || isDestroyed || autoDiscoveryGeneration.get() != generation) return
        if (speakerInputRevision.get() != inputRevision) return

        when (outcome) {
            is SpeakerTarget.ResolveOutcome.Found -> {
                val savedNow = preferences.getString(
                    RendererService.KEY_SPEAKER_IP,
                    "",
                ).orEmpty().trim()
                if (savedNow != savedBefore && savedNow != outcome.resolution.ip) return

                SpeakerTarget.rememberResolved(applicationContext, outcome.resolution)
                SpeakerStateStore.publishSpeaker(
                    outcome.resolution.ip,
                    outcome.resolution.deviceId,
                )
                MobileUi.setStatus(
                    statusView,
                    if (outcome.resolution.ip == previous) {
                        "M5 ready at " + outcome.resolution.ip + "."
                    } else {
                        "Found M5 at " + outcome.resolution.ip + " and updated the saved address."
                    },
                    MobileUi.StatusKind.SUCCESS,
                )
                refreshPhysicalPresets()
            }

            is SpeakerTarget.ResolveOutcome.Ambiguous -> {
                if (manual) {
                    chooseDiscoveredSpeaker(outcome.speakers)
                } else {
                    MobileUi.setStatus(
                        statusView,
                        "Multiple WAM speakers found. Tap Discover to choose one.",
                        MobileUi.StatusKind.INFO,
                    )
                }
            }

            is SpeakerTarget.ResolveOutcome.NotFound -> {
                val message = emptyScanMessage(outcome.scan)
                val stage = finalDiscoveryStage(outcome.scan)
                SpeakerStateStore.update {
                    it.copy(
                        discovery = stage,
                        status = discoveryStatus(stage),
                        lastError = message.takeIf { stage == SpeakerDiscoveryStage.FAILED },
                    )
                }
                MobileUi.setStatus(
                    statusView,
                    message,
                    if (stage == SpeakerDiscoveryStage.FAILED) {
                        MobileUi.StatusKind.ERROR
                    } else {
                        MobileUi.StatusKind.INFO
                    },
                )
            }

            SpeakerTarget.ResolveOutcome.Cancelled -> Unit
        }
    }

    /**
     * What to say when the sweep came back empty. Only a full sweep licenses
     * "not found"; a narrowed one has to admit it did not look, or the reader
     * goes hunting for a network problem that is not there.
     */
    private fun emptyScanMessage(scan: WamDiscovery.Scan): String = when (scan) {
        is WamDiscovery.Scan.Narrowed ->
            "Scanned ${scan.hosts} addresses around this phone. This Wi-Fi is a /${scan.prefixLength} " +
                "(${scan.subnetHosts} addresses), too wide to sweep, so a speaker outside that range " +
                "was not checked. Enter the IP manually."

        is WamDiscovery.Scan.Overlapping ->
            "Scanned ${scan.hosts} addresses, but ${scan.shared} of them exist on more than one " +
                "Wi-Fi network here and were checked on only one. Enter the IP manually."

        WamDiscovery.Scan.NoAddresses ->
            "This Wi-Fi has no other addresses to scan. Enter the speaker IP manually."

        WamDiscovery.Scan.NotRun ->
            "No Wi-Fi network available for discovery. Enter the speaker IP manually."

        is WamDiscovery.Scan.Full ->
            "No WAM speaker found on any of the ${scan.hosts} addresses on this Wi-Fi. " +
                "Enter the IP manually if discovery is blocked by the network."
    }

    private fun chooseDiscoveredSpeaker(speakers: List<WamDiscovery.Speaker>) {
        if (speakers.size == 1) {
            useDiscoveredSpeaker(speakers.single())
            return
        }
        val labels = speakers.map { "${it.ip} · ${it.source}" }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Choose WAM speaker")
            .setItems(labels) { _, which -> useDiscoveredSpeaker(speakers[which]) }
            .setNegativeButton("Cancel", null)
            .show()
        MobileUi.setStatus(
            statusView,
            "Found ${speakers.size} WAM speakers.",
            MobileUi.StatusKind.SUCCESS,
        )
    }

    private fun useDiscoveredSpeaker(speaker: WamDiscovery.Speaker) {
        if (discoveryExecutor.isShutdown) return
        MobileUi.setEnabled(discoverButton, false)
        MobileUi.setStatus(statusView, "Reading M5 identity at ${speaker.ip}…")

        discoveryExecutor.execute {
            val result = runCatching {
                SpeakerTarget.acceptDiscovered(applicationContext, speaker)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                MobileUi.setEnabled(discoverButton, true)
                result.fold(
                    onSuccess = { resolution ->
                        MobileUi.setStatus(
                            statusView,
                            "Found WAM speaker at ${resolution.ip} via ${speaker.source}.",
                            MobileUi.StatusKind.SUCCESS,
                        )
                    },
                    onFailure = { error ->
                        MobileUi.setStatus(
                            statusView,
                            error.message ?: error.javaClass.simpleName,
                            MobileUi.StatusKind.ERROR,
                        )
                    },
                )
            }
        }
    }

    private fun testConnection() {
        if (RendererService.busy || RadioService.active) {
            Toast.makeText(
                this,
                "Stop renderer/radio playback before testing the M5 connection.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }
        if (discoveryExecutor.isShutdown) return

        cancelAutoDiscovery()
        MobileUi.setStatus(statusView, "Testing saved M5…")
        discoveryExecutor.execute {
            val saved = preferences.getString(
                RendererService.KEY_SPEAKER_IP,
                "",
            ).orEmpty().trim()
            val reachable = if (RendererService.isReasonableIpv4(saved)) {
                runCatching {
                    SpeakerTarget.withDiscoveryLock {
                        SamsungWamChannel.probe(applicationContext, saved)
                    }
                }.getOrDefault(false)
            } else {
                null
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                when (reachable) {
                    null -> MobileUi.setStatus(
                        statusView,
                        "No saved M5 yet. Run Discover first.",
                        MobileUi.StatusKind.INFO,
                    )
                    true -> MobileUi.setStatus(
                        statusView,
                        "M5 answered at " + saved + ".",
                        MobileUi.StatusKind.SUCCESS,
                    )
                    false -> MobileUi.setStatus(
                        statusView,
                        "No WAM response from " + saved + ". Try Discover.",
                        MobileUi.StatusKind.ERROR,
                    )
                }
            }
        }
    }

    private fun startRenderer() {
        cancelAutoDiscovery()
        val intent = Intent(this, RendererService::class.java).apply {
            action = RendererService.ACTION_START
        }
        startForegroundService(intent)
        MobileUi.setStatus(statusView, "Finding M5 and starting renderer…")
        refreshUntilSettled()
    }

    private fun refreshUntilSettled(minimumPolls: Int = 2) {
        refreshStatus()
        if (minimumPolls > 0 || RendererService.transitioning) {
            window.decorView.postDelayed(
                { refreshUntilSettled((minimumPolls - 1).coerceAtLeast(0)) },
                250,
            )
        }
    }

    private fun refreshStatus() {
        val marker = when (RendererService.phase) {
            RendererService.Phase.RUNNING -> "●"
            RendererService.Phase.STARTING, RendererService.Phase.STOPPING -> "◐"
            RendererService.Phase.STOPPED -> "○"
        }
        MobileUi.setStatus(
            statusView,
            "$marker ${RendererService.lastStatus}",
            if (RendererService.phase == RendererService.Phase.RUNNING) {
                MobileUi.StatusKind.SUCCESS
            } else {
                MobileUi.StatusKind.INFO
            },
        )
        if (::startRendererButton.isInitialized) {
            MobileUi.setEnabled(startRendererButton, !RendererService.active)
            MobileUi.setEnabled(stopRendererButton, RendererService.busy)
        }
        refreshSpeakerControlButtons()
    }

    private fun stopHomePlayback() {
        MobileUi.setStatus(homeStatusView, "Stopping playback…")
        when {
            RadioService.active -> {
                startService(
                    Intent(this, RadioService::class.java).apply {
                        action = RadioService.ACTION_STOP
                    },
                )
            }

            RendererService.busy -> {
                startService(
                    Intent(this, RendererService::class.java).apply {
                        action = RendererService.ACTION_STOP
                    },
                )
            }

            else -> {
                startActivity(
                    Intent(this, TuneInActivity::class.java).apply {
                        action = TuneInActivity.ACTION_STOP_PLAYBACK
                    },
                )
            }
        }
    }

    private fun runSpeakerControl(
        action: SpeakerControls.Action,
        feedbackView: TextView = statusView,
    ) {
        if (controlExecutor.isShutdown || speakerControlRunning) return
        speakerControlRunning = true
        refreshSpeakerControlButtons()
        MobileUi.setStatus(feedbackView, "Sending speaker command…")

        controlExecutor.execute {
            val result = runCatching { SpeakerControls.perform(applicationContext, action) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                speakerControlRunning = false
                refreshSpeakerControlButtons()
                result.fold(
                    onSuccess = { outcome ->
                        when (outcome.destination) {
                            SpeakerControls.Destination.TUNEIN ->
                                startActivity(Intent(this, TuneInActivity::class.java))
                            SpeakerControls.Destination.SETTINGS ->
                                startActivity(Intent(this, AdvancedSettingsActivity::class.java))
                            null -> Unit
                        }
                        val message = outcome.message
                            ?: if (outcome.destination == null) "Radio control sent." else null
                        if (message != null) {
                            MobileUi.setStatus(
                                feedbackView,
                                message,
                                if (outcome.destination == SpeakerControls.Destination.SETTINGS) {
                                    MobileUi.StatusKind.ERROR
                                } else {
                                    MobileUi.StatusKind.SUCCESS
                                },
                            )
                        }
                    },
                    onFailure = { error ->
                        MobileUi.setStatus(
                            feedbackView,
                            error.message ?: error.javaClass.simpleName,
                            MobileUi.StatusKind.ERROR,
                        )
                    },
                )
            }
        }
    }

    private fun refreshSpeakerControlButtons() {
        setSpeakerControlsEnabled(!RendererService.busy && !speakerControlRunning)
    }

    private fun setSpeakerControlsEnabled(enabled: Boolean) {
        speakerControlButtons.forEach { MobileUi.setEnabled(it, enabled) }
    }

    private fun requestQuickSettingsTile() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(
                this,
                "Add the WAM Bridge tile manually from Android Quick Settings before hiding the launcher icon.",
                Toast.LENGTH_LONG,
            ).show()
            return
        }

        val statusBar = getSystemService(StatusBarManager::class.java)
        val component = ComponentName(this, WamBridgeTileService::class.java)
        val icon = Icon.createWithResource(this, R.drawable.ic_qs_tile)

        statusBar.requestAddTileService(
            component,
            getString(R.string.app_name),
            icon,
            mainExecutor,
        ) { result ->
            val ready = result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED ||
                result == StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED
            preferences.edit().putBoolean("recovery_tile_ready", ready).apply()
            Toast.makeText(
                this,
                if (ready) "Quick Settings toggle ready." else "Quick Settings tile was not added.",
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    private fun confirmHideLauncher() {
        val recoveryReady = preferences.getBoolean("recovery_tile_ready", false)
        val message = if (recoveryReady) {
            "The launcher icon will disappear. Tap the WAM Bridge Quick Settings tile to start/stop the renderer; long-press it to reopen this screen."
        } else {
            "The launcher icon will disappear. Add the WAM Bridge Quick Settings tile first. Its long-press opens this screen even after the launcher icon is hidden."
        }

        AlertDialog.Builder(this)
            .setTitle("Hide launcher icon?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Hide") { _, _ -> setLauncherVisible(false) }
            .show()
    }

    /**
     * The launcher alias, named exactly as the manifest declares it.
     *
     * **Do not rebuild this from `packageName`.** The alias is declared with an absolute name, so
     * it stays `trvny.wambridge.mobile.LauncherAlias` in every variant, while `packageName` is the
     * applicationId and a debug build suffixes that to `trvny.wambridge.mobile.debug`. Deriving one
     * from the other pointed the hide/show control at a component that does not exist, and
     * `setComponentEnabledSetting` on a missing component is a silent no-op - the button would have
     * looked fine and done nothing. Caught in review on the commit that added the suffix.
     */
    private fun launcherAliasComponent(): ComponentName =
        ComponentName(this, LAUNCHER_ALIAS_CLASS)

    private fun setLauncherVisible(visible: Boolean) {
        val launcher = launcherAliasComponent()
        packageManager.setComponentEnabledSetting(
            launcher,
            if (visible) {
                PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            },
            PackageManager.DONT_KILL_APP,
        )

        Toast.makeText(
            this,
            if (visible) "Launcher icon restored." else "Launcher icon hidden.",
            Toast.LENGTH_SHORT,
        ).show()
        refreshLauncherButton()
    }

    private fun isLauncherHidden(): Boolean {
        val launcher = launcherAliasComponent()
        return packageManager.getComponentEnabledSetting(launcher) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
    }

    private fun refreshLauncherButton() {
        launcherButton.text = if (isLauncherHidden()) {
            "Restore icon"
        } else {
            "Hide icon"
        }
    }
}
