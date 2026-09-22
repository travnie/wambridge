package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

class AdvancedSettingsActivity : Activity() {
    private lateinit var speakerIp: EditText
    private lateinit var statusView: TextView
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wam-mobile-advanced-settings").apply { isDaemon = true }
    }

    private val preferences by lazy {
        getSharedPreferences(RendererService.PREFS, MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)

        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "Advanced",
                "Manual speaker address and low-level renderer controls.",
            ),
        )

        statusView = MobileUi.status(this)
        content.addView(statusView)

        content.addView(MobileUi.sectionTitle(this, "Manual speaker"))
        val speakerCard = MobileUi.card(this)
        speakerCard.addView(MobileUi.label(this, "IPv4 address"))
        speakerIp = MobileUi.field(this, "IPv4 address").apply {
            setText(preferences.getString(RendererService.KEY_SPEAKER_IP, ""))
            setSingleLine(true)
        }
        speakerCard.addView(speakerIp)
        speakerCard.addView(
            MobileUi.body(
                this,
                "Normally leave discovery alone. Use a manual address only when the network blocks discovery.",
            ).apply {
                setPadding(
                    0,
                    MobileUi.dp(this@AdvancedSettingsActivity, 10),
                    0,
                    MobileUi.dp(this@AdvancedSettingsActivity, 10),
                )
            },
        )
        speakerCard.addView(
            MobileUi.button(this, "Save + test", MobileUi.ButtonKind.PRIMARY) {
                saveAndTest()
            },
        )
        content.addView(speakerCard)

        content.addView(MobileUi.sectionTitle(this, "Renderer"))
        val rendererCard = MobileUi.card(this)
        rendererCard.addView(
            MobileUi.body(
                this,
                "Explicit service controls for troubleshooting. Normal DLNA starts can use Home, widgets or Quick Settings.",
            ),
        )
        rendererCard.addView(MobileUi.row(this).apply {
            setPadding(0, MobileUi.dp(this@AdvancedSettingsActivity, 10), 0, 0)
            MobileUi.addWeighted(
                this,
                MobileUi.button(
                    this@AdvancedSettingsActivity,
                    "Start renderer",
                    MobileUi.ButtonKind.PRIMARY,
                ) {
                    startForegroundService(
                        Intent(this@AdvancedSettingsActivity, RendererService::class.java).apply {
                            action = RendererService.ACTION_START
                        },
                    )
                    renderStatus("Starting renderer…")
                    refreshUntilSettled()
                },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(
                    this@AdvancedSettingsActivity,
                    "Stop",
                    MobileUi.ButtonKind.DANGER,
                ) {
                    startService(
                        Intent(this@AdvancedSettingsActivity, RendererService::class.java).apply {
                            action = RendererService.ACTION_STOP
                        },
                    )
                    renderStatus("Stopping renderer…")
                    refreshUntilSettled()
                },
                marginDp = 0,
            )
        })
        content.addView(rendererCard)

        setContentView(ScrollView(this).apply { addView(content) })
        renderStatus()
    }

    override fun onResume() {
        super.onResume()
        renderStatus()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun saveAndTest() {
        val value = speakerIp.text.toString().trim()
        if (!RendererService.isReasonableIpv4(value)) {
            speakerIp.error = "Enter an IPv4 address"
            return
        }
        if (RendererService.busy || RadioService.active) {
            MobileUi.setStatus(
                statusView,
                "Stop renderer/radio playback before probing the M5.",
                MobileUi.StatusKind.ERROR,
            )
            return
        }

        SpeakerTarget.rememberManualIp(applicationContext, value)
        MobileUi.setStatus(statusView, "Testing " + value + "…")
        worker.execute {
            val reachable = runCatching {
                SpeakerTarget.withDiscoveryLock {
                    SamsungWamChannel.probe(applicationContext, value)
                }
            }.getOrDefault(false)

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                MobileUi.setStatus(
                    statusView,
                    if (reachable) "M5 answered at " + value + "."
                    else "No WAM response from " + value + ".",
                    if (reachable) MobileUi.StatusKind.SUCCESS else MobileUi.StatusKind.ERROR,
                )
            }
        }
    }

    private fun refreshUntilSettled(minimumPolls: Int = 2) {
        if (isFinishing || isDestroyed) return
        renderStatus()
        if (minimumPolls > 0 || RendererService.transitioning) {
            window.decorView.postDelayed(
                {
                    refreshUntilSettled((minimumPolls - 1).coerceAtLeast(0))
                },
                250,
            )
        }
    }

    private fun renderStatus(prefix: String? = null) {
        if (!::statusView.isInitialized) return
        val marker = when (RendererService.phase) {
            RendererService.Phase.RUNNING -> "●"
            RendererService.Phase.STARTING, RendererService.Phase.STOPPING -> "◐"
            RendererService.Phase.STOPPED -> "○"
        }
        val text = prefix ?: marker + " " + RendererService.lastStatus
        MobileUi.setStatus(
            statusView,
            text,
            if (RendererService.phase == RendererService.Phase.RUNNING) {
                MobileUi.StatusKind.SUCCESS
            } else {
                MobileUi.StatusKind.INFO
            },
        )
    }
}
