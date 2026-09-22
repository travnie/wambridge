package io.github.trvny.wambridge.mobile

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors

class DiagnosticsActivity : Activity() {
    private lateinit var reportCard: LinearLayout
    private lateinit var statusView: TextView
    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "wam-mobile-diagnostics").apply { isDaemon = true }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MobileUi.applyWindow(this)

        val content = MobileUi.page(this)
        content.addView(
            MobileUi.header(
                this,
                "Diagnostics",
                "Useful state from the app, Android Wi-Fi binding and the current M5 session.",
            ),
        )

        statusView = MobileUi.status(this, "Ready")
        content.addView(statusView)

        reportCard = MobileUi.card(this)
        content.addView(reportCard)

        content.addView(MobileUi.sectionTitle(this, "Actions"))
        val actions = MobileUi.card(this)
        actions.addView(MobileUi.row(this).apply {
            MobileUi.addWeighted(
                this,
                MobileUi.button(
                    this@DiagnosticsActivity,
                    "Copy diagnostics",
                    MobileUi.ButtonKind.PRIMARY,
                ) { copyDiagnostics() },
            )
            MobileUi.addWeighted(
                this,
                MobileUi.button(this@DiagnosticsActivity, "Fix connection") {
                    fixConnection()
                },
                marginDp = 0,
            )
        })
        content.addView(actions)

        setContentView(ScrollView(this).apply { addView(content) })
        renderReport()
    }

    override fun onResume() {
        super.onResume()
        renderReport()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun currentReport(): DiagnosticsReport {
        val version = runCatching {
            packageManager.getPackageInfo(packageName, 0).versionName
        }.getOrNull().orEmpty().ifBlank { "Unknown" }
        return diagnosticsReport(
            snapshot = SpeakerStateStore.current(),
            wifiEndpoint = WifiLan.preferredTarget(applicationContext)?.endpoint,
            appVersion = version,
            rendererPhase = RendererService.phase.name,
            rendererStatus = RendererService.lastStatus,
            radioStatus = RadioService.lastStatus,
        )
    }

    private fun renderReport() {
        if (!::reportCard.isInitialized) return
        reportCard.removeAllViews()
        currentReport().rows.forEach { (label, value) ->
            reportCard.addView(MobileUi.label(this, label))
            reportCard.addView(
                MobileUi.body(this, value).apply {
                    setPadding(
                        MobileUi.dp(this@DiagnosticsActivity, 2),
                        0,
                        0,
                        MobileUi.dp(this@DiagnosticsActivity, 10),
                    )
                },
            )
        }
    }

    private fun copyDiagnostics() {
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(
            ClipData.newPlainText(
                "WAM Bridge diagnostics",
                currentReport().toPlainText(),
            ),
        )
        Toast.makeText(this, "Diagnostics copied.", Toast.LENGTH_SHORT).show()
    }

    private fun fixConnection() {
        if (worker.isShutdown) return
        MobileUi.setStatus(statusView, "Stopping active playback and reconnecting…")
        worker.execute {
            val result = runCatching {
                stopOwnersForRecovery()
                when (
                    val outcome = SpeakerTarget.resolveDetailed(
                        context = applicationContext,
                        persist = true,
                        forceDiscovery = true,
                        shouldContinue = { !Thread.currentThread().isInterrupted },
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
                ) {
                    is SpeakerTarget.ResolveOutcome.Found ->
                        "M5 ready at " + outcome.resolution.ip + "."

                    is SpeakerTarget.ResolveOutcome.Ambiguous ->
                        "Multiple WAM speakers found. Use Settings → Advanced to choose one."

                    is SpeakerTarget.ResolveOutcome.NotFound ->
                        when (outcome.scan) {
                            WamDiscovery.Scan.NotRun -> "Wi-Fi is not available."
                            else -> "M5 not found after a full reconnect attempt."
                        }

                    SpeakerTarget.ResolveOutcome.Cancelled -> "Reconnect cancelled."
                }
            }

            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                result.fold(
                    onSuccess = { message ->
                        renderReport()
                        val success = SpeakerStateStore.current().discovery == SpeakerDiscoveryStage.READY
                        MobileUi.setStatus(
                            statusView,
                            message,
                            if (success) MobileUi.StatusKind.SUCCESS else MobileUi.StatusKind.INFO,
                        )
                    },
                    onFailure = { error ->
                        val message = error.message ?: error.javaClass.simpleName
                        renderReport()
                        MobileUi.setStatus(statusView, message, MobileUi.StatusKind.ERROR)
                    },
                )
            }
        }
    }

    private fun stopOwnersForRecovery() {
        if (RendererService.busy) {
            startService(
                Intent(this, RendererService::class.java).apply {
                    action = RendererService.ACTION_STOP
                },
            )
            waitUntil(RELEASE_TIMEOUT_MS) { !RendererService.busy }
        }

        if (RadioService.active) {
            startService(
                Intent(this, RadioService::class.java).apply {
                    action = RadioService.ACTION_STOP
                },
            )
            waitUntil(RELEASE_TIMEOUT_MS) { !RadioService.active }
        }

        if (RendererService.busy || RadioService.active) {
            error("M5 is still busy. Try again in a moment.")
        }
    }

    private fun waitUntil(timeoutMs: Long, ready: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (!ready() && SystemClock.elapsedRealtime() < deadline) {
            if (Thread.currentThread().isInterrupted) error("Reconnect cancelled.")
            SystemClock.sleep(50)
        }
    }

    companion object {
        private const val RELEASE_TIMEOUT_MS = 3_000L
    }
}
