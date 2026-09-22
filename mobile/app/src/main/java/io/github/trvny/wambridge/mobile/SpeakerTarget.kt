package io.github.trvny.wambridge.mobile

import android.content.Context

internal object SpeakerTarget {
    private const val KEY_SPEAKER_DEVICE_ID = "speaker_device_id"
    private const val IDENTITY_TIMEOUT_MS = 1_500
    private const val BOUND_RESOLVE_ATTEMPTS = 3
    private val resolutionLock = Any()

    data class Resolution(val ip: String, val deviceId: String?)

    data class BoundResolution(val resolution: Resolution, val wifi: WifiLan.Target) {
        val ip: String get() = resolution.ip
    }

    sealed interface ResolveOutcome {
        data class Found(val resolution: Resolution) : ResolveOutcome

        data class Ambiguous(
            val speakers: List<WamDiscovery.Speaker>,
            val scan: WamDiscovery.Scan,
        ) : ResolveOutcome

        data class NotFound(val scan: WamDiscovery.Scan) : ResolveOutcome

        data object Cancelled : ResolveOutcome
    }

    fun resolve(
        context: Context,
        verifySaved: Boolean = true,
        shouldContinue: () -> Boolean = { true },
    ): String? = when (
        val outcome = resolveDetailed(
            context = context,
            verifySaved = verifySaved,
            persist = true,
            shouldContinue = shouldContinue,
        )
    ) {
        is ResolveOutcome.Found -> outcome.resolution.ip
        else -> null
    }

    fun resolveBound(
        context: Context,
        verifySaved: Boolean = true,
        shouldContinue: () -> Boolean = { true },
        onStage: (SpeakerDiscoveryStage) -> Unit = {},
    ): BoundResolution? = withDiscoveryLock {
        repeat(BOUND_RESOLVE_ATTEMPTS) {
            if (!shouldContinue()) return@withDiscoveryLock null
            val wifi = WifiLan.preferredTarget(context) ?: run {
                onStage(SpeakerDiscoveryStage.WAITING_FOR_WIFI)
                return@withDiscoveryLock null
            }
            val outcome = resolveDetailed(
                context = context,
                verifySaved = verifySaved,
                persist = false,
                shouldContinue = shouldContinue,
                onStage = onStage,
            )
            if (!shouldContinue()) return@withDiscoveryLock null
            if (!WifiLan.isCurrentPreferred(context, wifi)) return@repeat
            val result = (outcome as? ResolveOutcome.Found)?.resolution
                ?: return@withDiscoveryLock null

            val currentId = runCatching {
                SamsungWamChannel.readDeviceId(
                    context,
                    result.ip,
                    IDENTITY_TIMEOUT_MS,
                    wifi,
                )
            }.getOrNull()
            if (!shouldContinue()) return@withDiscoveryLock null
            if (!WifiLan.isCurrentPreferred(context, wifi)) return@repeat

            val verified = when {
                currentId != null && result.deviceId != null -> currentId == result.deviceId
                currentId != null -> true
                result.deviceId != null ->
                    result.ip == savedSpeakerIp(context) &&
                        SamsungWamChannel.probe(context, result.ip, IDENTITY_TIMEOUT_MS, wifi)
                else -> SamsungWamChannel.probe(context, result.ip, IDENTITY_TIMEOUT_MS, wifi)
            }
            if (!verified) return@withDiscoveryLock null
            if (!shouldContinue()) return@withDiscoveryLock null

            val stable = Resolution(result.ip, currentId ?: result.deviceId)
            rememberResolved(context, stable)
            SpeakerStateStore.publishSpeaker(stable.ip, stable.deviceId)
            return@withDiscoveryLock BoundResolution(stable, wifi)
        }
        null
    }

    fun resolveUnpersisted(
        context: Context,
        verifySaved: Boolean = true,
        shouldContinue: () -> Boolean = { true },
    ): Resolution? = when (
        val outcome = resolveDetailed(
            context = context,
            verifySaved = verifySaved,
            persist = false,
            shouldContinue = shouldContinue,
        )
    ) {
        is ResolveOutcome.Found -> outcome.resolution
        else -> null
    }

    fun resolveDetailed(
        context: Context,
        verifySaved: Boolean = true,
        persist: Boolean = true,
        shouldContinue: () -> Boolean = { true },
        onStage: (SpeakerDiscoveryStage) -> Unit = {},
    ): ResolveOutcome = withDiscoveryLock {
        val appContext = context.applicationContext
        val preferences = appContext.getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
        val savedIp = preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim()
        val savedId = preferences.getString(KEY_SPEAKER_DEVICE_ID, "").orEmpty().trim()
        val savedIsValid = RendererService.isReasonableIpv4(savedIp)

        if (!shouldContinue()) return@withDiscoveryLock ResolveOutcome.Cancelled

        // Keep the deliberate fast path used by native TuneIn controls while an
        // owner already holds the speaker. Those callers explicitly opt out of
        // verification so discovery never opens a competing control connection.
        if (savedIsValid && (RadioService.running || !verifySaved)) {
            return@withDiscoveryLock finishFound(
                appContext,
                Resolution(savedIp, savedId.ifBlank { null }),
                persist,
                shouldContinue,
            )
        }

        if (WifiLan.targets(appContext).isEmpty()) {
            onStage(SpeakerDiscoveryStage.WAITING_FOR_WIFI)
            return@withDiscoveryLock ResolveOutcome.NotFound(WamDiscovery.Scan.NotRun)
        }

        if (savedIsValid) {
            onStage(SpeakerDiscoveryStage.CHECKING_SAVED)
            resolveSaved(appContext, savedIp, savedId, shouldContinue)?.let { resolution ->
                return@withDiscoveryLock finishFound(
                    appContext,
                    resolution,
                    persist,
                    shouldContinue,
                )
            }
        }

        if (!shouldContinue()) return@withDiscoveryLock ResolveOutcome.Cancelled

        val discovery = WamDiscovery.discover(
            appContext,
            allowScan = true,
            shouldContinue = shouldContinue,
            onStage = onStage,
        )
        if (!shouldContinue()) return@withDiscoveryLock ResolveOutcome.Cancelled

        val selected = selectCandidate(savedIp, savedId, discovery.speakers) { ip ->
            if (!shouldContinue()) null else identify(appContext, ip)
        }
        if (!shouldContinue()) return@withDiscoveryLock ResolveOutcome.Cancelled

        if (selected == null) {
            return@withDiscoveryLock if (discovery.speakers.isEmpty()) {
                ResolveOutcome.NotFound(discovery.scan)
            } else {
                ResolveOutcome.Ambiguous(discovery.speakers, discovery.scan)
            }
        }

        val selectedId = identify(appContext, selected.ip)
        if (!shouldContinue()) return@withDiscoveryLock ResolveOutcome.Cancelled

        finishFound(
            appContext,
            Resolution(selected.ip, selectedId),
            persist,
            shouldContinue,
        )
    }

    private fun finishFound(
        context: Context,
        resolution: Resolution,
        persist: Boolean,
        shouldContinue: () -> Boolean,
    ): ResolveOutcome {
        if (!shouldContinue()) return ResolveOutcome.Cancelled
        if (persist) {
            rememberResolved(context, resolution)
            SpeakerStateStore.publishSpeaker(resolution.ip, resolution.deviceId)
        }
        return ResolveOutcome.Found(resolution)
    }

    private fun resolveSaved(
        context: Context,
        savedIp: String,
        savedId: String,
        shouldContinue: () -> Boolean,
    ): Resolution? {
        val currentId = identify(context, savedIp)
        if (!shouldContinue()) return null
        if (currentId != null && (savedId.isBlank() || currentId == savedId)) {
            return Resolution(savedIp, currentId)
        }
        if (
            savedId.isBlank() &&
            currentId == null &&
            SamsungWamChannel.probe(context, savedIp, IDENTITY_TIMEOUT_MS)
        ) {
            if (!shouldContinue()) return null
            return Resolution(savedIp, null)
        }
        return null
    }

    /** Keep discovery/probes off the legacy speaker control port one at a time. */
    internal fun <T> withDiscoveryLock(block: () -> T): T =
        synchronized(resolutionLock) { block() }

    /** Persist a validated automatic result together with its stable device id. */
    fun rememberResolved(context: Context, result: Resolution) {
        val editor = context.applicationContext
            .getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(RendererService.KEY_SPEAKER_IP, result.ip)
        if (!result.deviceId.isNullOrBlank()) editor.putString(KEY_SPEAKER_DEVICE_ID, result.deviceId)
        editor.apply()
    }

    fun acceptDiscovered(
        context: Context,
        speaker: WamDiscovery.Speaker,
    ): Resolution {
        val appContext = context.applicationContext
        val resolution = Resolution(speaker.ip, identify(appContext, speaker.ip))
        rememberResolved(appContext, resolution)
        SpeakerStateStore.publishSpeaker(resolution.ip, resolution.deviceId)
        return resolution
    }

    fun rememberManualIp(context: Context, ip: String) {
        val preferences = context.applicationContext
            .getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
        val previous = preferences.getString(RendererService.KEY_SPEAKER_IP, "").orEmpty().trim()
        val editor = preferences.edit().putString(RendererService.KEY_SPEAKER_IP, ip)
        if (previous != ip) editor.remove(KEY_SPEAKER_DEVICE_ID)
        editor.apply()
    }

    private fun savedSpeakerIp(context: Context): String =
        context.applicationContext
            .getSharedPreferences(RendererService.PREFS, Context.MODE_PRIVATE)
            .getString(RendererService.KEY_SPEAKER_IP, "")
            .orEmpty()
            .trim()

    private fun identify(context: Context, ip: String): String? = runCatching {
        SamsungWamChannel.readDeviceId(context, ip, IDENTITY_TIMEOUT_MS)
    }.getOrNull()

    internal fun selectCandidate(
        savedIp: String,
        savedId: String,
        speakers: List<WamDiscovery.Speaker>,
        identify: (String) -> String?,
    ): WamDiscovery.Speaker? {
        if (savedId.isNotBlank()) {
            var savedIpWithoutIdentity: WamDiscovery.Speaker? = null
            for (speaker in speakers) {
                val currentId = identify(speaker.ip)
                if (currentId == savedId) return speaker
                if (speaker.ip == savedIp && currentId == null) {
                    savedIpWithoutIdentity = speaker
                }
            }
            if (speakers.size == 1) return savedIpWithoutIdentity
            return null
        }
        if (speakers.size == 1) return speakers.single()
        return speakers.firstOrNull { it.ip == savedIp }
    }
}
