package io.github.trvny.wambridge.mobile

import android.app.Activity
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
                "Manual speaker address for networks where automatic discovery cannot reach the M5.",
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

        setContentView(ScrollView(this).apply { addView(content) })
        MobileUi.setStatus(
            statusView,
            "Automatic discovery is preferred. Use this only as a manual override.",
            MobileUi.StatusKind.INFO,
        )
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
                if (preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim() != value) {
                    return@runOnUiThread
                }
                if (reachable) SpeakerStateStore.publishSpeaker(value, null)
                MobileUi.setStatus(
                    statusView,
                    if (reachable) "M5 answered at " + value + "."
                    else "No WAM response from " + value + ".",
                    if (reachable) MobileUi.StatusKind.SUCCESS else MobileUi.StatusKind.ERROR,
                )
            }
        }
    }


}
