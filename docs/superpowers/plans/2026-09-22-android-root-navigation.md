# Android Root Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the technical launcher screen with a persistent classic-Views shell for Home · Radio · Settings while preserving every existing Android control and deep Activity.

**Architecture:** Keep `MainActivity` as the single root Activity and build all three primary panes once, then switch them by visibility instead of introducing fragments or Compose. Move the existing technical control panel wholesale into the Settings pane, expose the existing radio entry points from Radio, and make Home a deliberately small landing pane that shows the shared speaker state and links into the other two destinations. A pure destination resolver owns launcher/deep-link behavior so launcher opens Home while Quick Settings preferences and explicit settings routes open Settings.

**Tech Stack:** Kotlin/JVM 17, Android Views, Android SDK 37, existing `MobileUi`, JUnit 4.13.2. No new runtime dependencies and no XML navigation framework.

**Spec:** `docs/superpowers/specs/2026-09-22-android-vnext-design.md`

## Global Constraints

- Keep classic Android Views; do not migrate to Compose or fragments.
- Home · Radio · Settings are the only persistent root destinations and Home is the launcher default.
- Build each root pane once and toggle visibility; do not recreate Activities for tab switches.
- Existing `TuneInActivity`, `CatalogueActivity`, and `RadioStationsActivity` remain deep Activities.
- Back from a deep Activity returns to the root destination that launched it because MainActivity remains underneath.
- Back from any primary destination exits the root Activity; do not synthesize tab history.
- The current technical speaker/renderer/system controls move to Settings without losing functionality.
- Manual IP remains available in Settings for this PR; moving it under an Advanced subsection is later Settings cleanup.
- Quick Settings preferences and explicit settings routes open the Settings destination.
- `SpeakerStateStore` is the source for Home status. Subscribe only while the Activity is started and close the subscription when stopped.
- Listener callbacks may arrive off the main thread; UI rendering must marshal to the main thread.
- Do not implement physical preset writes, full Now Playing, MediaSession, sleep timer, ICY metadata, or HLS/Ogg work in this PR.
- Final-head validation: `cd mobile && ./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`.

## Review Focus

1. **Quick Settings long-press/preferences launches MainActivity:** it must land on Settings, not Home.
2. **Widget/direct speaker control needs Settings:** it must use an explicit destination extra rather than relying on whichever tab happened to be visible last.
3. **SpeakerStateStore publishes from a worker thread:** Home rendering must not mutate Android Views off the main thread.
4. **Activity stops while state updates are arriving:** the subscription must be closed and must not retain the stopped Activity.
5. **Switching tabs repeatedly:** panes must be reused instead of duplicated so Settings fields, status and in-flight state are not reset.

---

### Task 1: Define root destinations and intent routing

**Files:**
- Create: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainNavigation.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationTest.kt`

**Interfaces:**
- Produces: `MainDestination { HOME, RADIO, SETTINGS }`.
- Produces: `mainDestination(action: String?, requested: String?): MainDestination`.
- Produces: `MainNavigation.EXTRA_DESTINATION`.
- Produces: `MainNavigation.intent(context, destination): Intent`.
- Later tasks consume the enum and helper from MainActivity and widget routes.

- [ ] **Step 1: Write the failing destination tests**

Create `MainNavigationTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import android.service.quicksettings.TileService
import org.junit.Assert.assertEquals
import org.junit.Test

class MainNavigationTest {
    @Test
    fun launcherDefaultsToHome() {
        assertEquals(MainDestination.HOME, mainDestination(null, null))
    }

    @Test
    fun explicitRadioDestinationWins() {
        assertEquals(
            MainDestination.RADIO,
            mainDestination(null, MainDestination.RADIO.name),
        )
    }

    @Test
    fun explicitSettingsDestinationWins() {
        assertEquals(
            MainDestination.SETTINGS,
            mainDestination(null, MainDestination.SETTINGS.name),
        )
    }

    @Test
    fun quickSettingsPreferencesOpenSettings() {
        assertEquals(
            MainDestination.SETTINGS,
            mainDestination(
                TileService.ACTION_QS_TILE_PREFERENCES,
                null,
            ),
        )
    }

    @Test
    fun unknownDestinationFallsBackToHome() {
        assertEquals(MainDestination.HOME, mainDestination(null, "NOPE"))
    }
}
```

- [ ] **Step 2: Run the focused test and verify RED**

Run:

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationTest'
```

Expected: FAIL because the destination model and resolver do not exist.

- [ ] **Step 3: Implement the destination model**

Create `MainNavigation.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import android.content.Context
import android.content.Intent
import android.service.quicksettings.TileService

internal enum class MainDestination { HOME, RADIO, SETTINGS }

internal fun mainDestination(
    action: String?,
    requested: String?,
): MainDestination {
    val explicit = requested
        ?.let { value -> MainDestination.entries.firstOrNull { it.name == value } }
    if (explicit != null) return explicit
    if (action == TileService.ACTION_QS_TILE_PREFERENCES) return MainDestination.SETTINGS
    return MainDestination.HOME
}

internal object MainNavigation {
    const val EXTRA_DESTINATION = "main_destination"

    fun intent(context: Context, destination: MainDestination): Intent =
        Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_DESTINATION, destination.name)
        }
}
```

- [ ] **Step 4: Run focused and full JVM tests**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainNavigation.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationTest.kt
git commit -m "feat(android): define root navigation"
```

### Task 2: Add reusable bottom-navigation UI primitives

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MobileUi.kt`
- Create: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationContractTest.kt`

**Interfaces:**
- Consumes: `MainDestination`.
- Produces: `MobileUi.bottomNavigation(context): LinearLayout`.
- Produces: `MobileUi.navigationButton(context, text, click): Button`.
- Produces: `MobileUi.setNavigationSelected(button, selected)`.
- Later MainActivity owns the three buttons and updates their selected styling.

- [ ] **Step 1: Write a source contract for the three-button shell**

Create `MainNavigationContractTest.kt`:

```kotlin
package io.github.trvny.wambridge.mobile

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class MainNavigationContractTest {
    @Test
    fun mobileUiExposesBottomNavigationPrimitives() {
        val source = File(
            "src/main/java/io/github/trvny/wambridge/mobile/MobileUi.kt",
        ).readText()

        assertTrue(source.contains("fun bottomNavigation("))
        assertTrue(source.contains("fun navigationButton("))
        assertTrue(source.contains("fun setNavigationSelected("))
    }
}
```

- [ ] **Step 2: Run and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationContractTest.mobileUiExposesBottomNavigationPrimitives'
```

Expected: FAIL because the helpers do not exist.

- [ ] **Step 3: Add the visual helpers without introducing a new theme system**

In `MobileUi.kt` add:

```kotlin
fun bottomNavigation(context: Context): LinearLayout = row(context).apply {
    setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 10))
    background = rounded(
        context,
        fill = context.getColor(R.color.wam_surface),
        stroke = context.getColor(R.color.wam_border),
        radiusDp = 18,
    )
}

fun navigationButton(
    context: Context,
    text: String,
    click: () -> Unit,
): Button = button(context, text, ButtonKind.QUIET, click).apply {
    minHeight = dp(context, 44)
}

fun setNavigationSelected(button: Button, selected: Boolean) {
    val context = button.context
    button.background = RippleDrawable(
        ColorStateList.valueOf(Color.argb(24, 0, 0, 0)),
        rounded(
            context,
            fill = context.getColor(
                if (selected) R.color.wam_accent_soft else R.color.wam_surface,
            ),
            stroke = context.getColor(
                if (selected) R.color.wam_accent_soft else R.color.wam_surface,
            ),
            radiusDp = 14,
        ),
        null,
    )
    button.setTextColor(
        context.getColor(if (selected) R.color.wam_accent else R.color.wam_muted),
    )
}
```

Do not add colors or resources unless the existing palette proves insufficient.

- [ ] **Step 4: Run the contract and full JVM suite**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationContractTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MobileUi.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationContractTest.kt
git commit -m "feat(android): add bottom navigation ui"
```

### Task 3: Build the three persistent MainActivity panes

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationContractTest.kt`

**Interfaces:**
- Consumes: `MainDestination` and MobileUi navigation helpers.
- Produces: `showDestination(destination: MainDestination)`.
- Produces: three panes created once: Home, Radio, Settings.
- Settings owns the current technical controls.
- Radio opens existing deep Activities.
- Home is intentionally minimal in this PR: shared speaker status plus links to Radio and Settings.

- [ ] **Step 1: Extend the contract test for the persistent shell**

Add:

```kotlin
@Test
fun mainActivityBuildsThreePersistentRootPanes() {
    val source = File(
        "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
    ).readText()

    assertTrue(source.contains("buildHomePane()"))
    assertTrue(source.contains("buildRadioPane()"))
    assertTrue(source.contains("buildSettingsPane()"))
    assertTrue(source.contains("showDestination("))
    assertTrue(source.contains(""Home""))
    assertTrue(source.contains(""Radio""))
    assertTrue(source.contains(""Settings""))
    assertTrue(!source.contains("startActivity(Intent(this@MainActivity, MainActivity::class.java"))
}
```

- [ ] **Step 2: Run and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationContractTest.mainActivityBuildsThreePersistentRootPanes'
```

Expected: FAIL because MainActivity is still one technical ScrollView.

- [ ] **Step 3: Introduce one root shell and destination state**

Add fields:

```kotlin
private lateinit var homePane: View
private lateinit var radioPane: View
private lateinit var settingsPane: View
private lateinit var homeNavButton: Button
private lateinit var radioNavButton: Button
private lateinit var settingsNavButton: Button
private lateinit var homeStatusView: TextView

private var currentDestination = MainDestination.HOME
private var speakerStateSubscription: AutoCloseable? = null
```

In `onCreate`, resolve the initial destination:

```kotlin
currentDestination = mainDestination(
    intent?.action,
    intent?.getStringExtra(MainNavigation.EXTRA_DESTINATION),
)
```

Replace the one root ScrollView with a vertical shell:

```kotlin
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

val nav = MobileUi.bottomNavigation(this).apply {
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
root.addView(nav)
setContentView(root)
showDestination(currentDestination)
```

Import `android.view.View` and `android.widget.FrameLayout`.

- [ ] **Step 4: Move the existing technical UI into buildSettingsPane without rewriting behavior**

Extract the current `onCreate` content construction into:

```kotlin
private fun buildSettingsPane(): View {
    val content = MobileUi.page(this)
    content.addView(
        MobileUi.header(
            this,
            "Settings",
            "Speaker setup, renderer controls and system integration.",
        ),
    )

    // Move existing Speaker, Renderer, Speaker controls and System cards here.
    // Keep the same fields, click handlers and helper methods.

    return ScrollView(this).apply { addView(content) }
}
```

Do not duplicate these cards in Home. Preserve:
- speaker IP field and TextWatcher;
- Discover and Save + test;
- renderer start/stop;
- direct speaker controls;
- Quick Settings tile setup;
- launcher hide/show;
- existing status refresh behavior.

The old MainActivity Radio card is removed from Settings because Radio gets its own pane.

- [ ] **Step 5: Build the Radio pane from existing entry points**

Implement:

```kotlin
private fun buildRadioPane(): View {
    val content = MobileUi.page(this)
    content.addView(
        MobileUi.header(
            this,
            "Radio",
            "Physical presets, saved stations and the M5 TuneIn catalogue.",
        ),
    )

    val card = MobileUi.card(this)
    card.addView(MobileUi.body(this, "Use the existing radio tools while the vNext library grows around them."))
    card.addView(MobileUi.row(this).apply {
        setPadding(0, MobileUi.dp(this@MainActivity, 12), 0, 0)
        MobileUi.addWeighted(
            this,
            MobileUi.button(this@MainActivity, "Presets") {
                startActivity(Intent(this@MainActivity, TuneInActivity::class.java))
            },
        )
        MobileUi.addWeighted(
            this,
            MobileUi.button(this@MainActivity, "Browse") {
                startActivity(Intent(this@MainActivity, CatalogueActivity::class.java))
            },
        )
        MobileUi.addWeighted(
            this,
            MobileUi.button(this@MainActivity, "Stations") {
                startActivity(Intent(this@MainActivity, RadioStationsActivity::class.java))
            },
            marginDp = 0,
        )
    })
    content.addView(card)

    return ScrollView(this).apply { addView(content) }
}
```

Do not modify the deep Activities in this task.

- [ ] **Step 6: Build the minimal Home pane**

Implement:

```kotlin
private fun buildHomePane(): View {
    val content = MobileUi.page(this)
    content.addView(
        MobileUi.header(
            this,
            getString(R.string.app_name),
            "Your M5 at a glance.",
        ),
    )

    homeStatusView = MobileUi.status(this, "Connecting…")
    content.addView(homeStatusView)

    val actions = MobileUi.card(this)
    actions.addView(MobileUi.row(this).apply {
        MobileUi.addWeighted(
            this,
            MobileUi.button(this@MainActivity, "Radio") {
                showDestination(MainDestination.RADIO)
            },
        )
        MobileUi.addWeighted(
            this,
            MobileUi.button(this@MainActivity, "Settings") {
                showDestination(MainDestination.SETTINGS)
            },
            marginDp = 0,
        )
    })
    content.addView(actions)

    return ScrollView(this).apply { addView(content) }
}
```

The full Now Playing controls and physical presets belong to the next Home plan.

- [ ] **Step 7: Implement tab switching without tab history**

```kotlin
private fun showDestination(destination: MainDestination) {
    currentDestination = destination
    homePane.visibility = if (destination == MainDestination.HOME) View.VISIBLE else View.GONE
    radioPane.visibility = if (destination == MainDestination.RADIO) View.VISIBLE else View.GONE
    settingsPane.visibility = if (destination == MainDestination.SETTINGS) View.VISIBLE else View.GONE
    MobileUi.setNavigationSelected(homeNavButton, destination == MainDestination.HOME)
    MobileUi.setNavigationSelected(radioNavButton, destination == MainDestination.RADIO)
    MobileUi.setNavigationSelected(settingsNavButton, destination == MainDestination.SETTINGS)
}
```

Do not override Back to traverse tabs.

- [ ] **Step 8: Run contract and full JVM tests**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationContractTest'
./gradlew :app:testDebugUnitTest
```

Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationContractTest.kt
git commit -m "feat(android): add home radio settings shell"
```

### Task 4: Render shared speaker state on Home safely

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationContractTest.kt`

**Interfaces:**
- Consumes: `SpeakerStateStore.subscribe`, `SpeakerSnapshot`.
- Produces: `renderHomeState(snapshot: SpeakerSnapshot)`.
- Subscription lifetime is `onStart` to `onStop`.

- [ ] **Step 1: Add lifecycle/threading contract assertions**

Append:

```kotlin
@Test
fun homeSpeakerStateSubscriptionIsLifecycleBoundAndMainThreadRendered() {
    val source = File(
        "src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt",
    ).readText()

    assertTrue(source.contains("override fun onStart()"))
    assertTrue(source.contains("SpeakerStateStore.subscribe"))
    assertTrue(source.contains("runOnUiThread"))
    assertTrue(source.contains("override fun onStop()"))
    assertTrue(source.contains("speakerStateSubscription?.close()"))
}
```

- [ ] **Step 2: Run and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationContractTest.homeSpeakerStateSubscriptionIsLifecycleBoundAndMainThreadRendered'
```

Expected: FAIL because the root Activity is not subscribed yet.

- [ ] **Step 3: Subscribe only while started**

Add:

```kotlin
override fun onStart() {
    super.onStart()
    speakerStateSubscription?.close()
    speakerStateSubscription = SpeakerStateStore.subscribe { snapshot ->
        runOnUiThread {
            if (!isFinishing && !isDestroyed) renderHomeState(snapshot)
        }
    }
}

override fun onStop() {
    speakerStateSubscription?.close()
    speakerStateSubscription = null
    super.onStop()
}
```

Keep `onDestroy` executor shutdown behavior unchanged.

- [ ] **Step 4: Render a compact truthful Home summary**

Add:

```kotlin
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
    val station = snapshot.stationAlias?.let { " · $it" }.orEmpty()
    val address = snapshot.speakerIp?.let { " · $it" }.orEmpty()

    val kind = when (snapshot.discovery) {
        SpeakerDiscoveryStage.FAILED -> MobileUi.StatusKind.ERROR
        SpeakerDiscoveryStage.READY -> MobileUi.StatusKind.SUCCESS
        else -> MobileUi.StatusKind.INFO
    }

    MobileUi.setStatus(
        homeStatusView,
        "$owner · $playback$station$address",
        kind,
    )
}
```

Do not infer service state from service globals here.

- [ ] **Step 5: Run focused, full JVM and Android verification**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.MainNavigationContractTest'
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add mobile/app/src/main/java/io/github/trvny/wambridge/mobile/MainActivity.kt \
        mobile/app/src/test/java/io/github/trvny/wambridge/mobile/MainNavigationContractTest.kt
git commit -m "feat(android): show shared state on home"
```

### Task 5: Route Settings entry points explicitly and document the shell

**Files:**
- Modify: `mobile/app/src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt`
- Modify: `mobile/app/src/test/java/io/github/trvny/wambridge/mobile/AndroidUxContractTest.kt`
- Modify: `mobile/README.md`
- Modify: `docs/DEVELOPMENT_STATUS.md`

**Interfaces:**
- Consumes: `MainNavigation.intent(context, MainDestination.SETTINGS)`.
- Produces: widget Settings routing that always lands on Settings.
- Quick Settings preferences rely on MainActivity's action resolver from Task 1.

- [ ] **Step 1: Add the explicit Settings-route contract**

Append to `AndroidUxContractTest.kt`:

```kotlin
@Test
fun widgetSettingsRouteUsesTheSettingsDestination() {
    val widget = File(
        "src/main/java/io/github/trvny/wambridge/mobile/WamBridgeWidget.kt",
    ).readText()

    assertTrue(
        widget.contains(
            "MainNavigation.intent(context, MainDestination.SETTINGS)",
        ),
    )
}
```

- [ ] **Step 2: Run and verify RED**

```bash
cd mobile
./gradlew :app:testDebugUnitTest --tests 'io.github.trvny.wambridge.mobile.AndroidUxContractTest.widgetSettingsRouteUsesTheSettingsDestination'
```

Expected: FAIL because the widget currently constructs a plain MainActivity intent.

- [ ] **Step 3: Route widget Settings explicitly**

Replace the widget's Settings opener with:

```kotlin
private fun openSettings(context: Context) {
    context.startActivity(
        MainNavigation.intent(context, MainDestination.SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
```

Do not change widget control routing otherwise.

- [ ] **Step 4: Update docs briefly**

In `mobile/README.md`, update the Android UI description to say the root app now has Home, Radio and Settings; Home shows the shared runtime summary, Radio retains existing deep tools, and technical controls live in Settings.

In `docs/DEVELOPMENT_STATUS.md`, add a dated 2026-09-22 note that the persistent root shell landed and that full Now Playing/physical preset Home work remains next.

- [ ] **Step 5: Run final validation**

```bash
cd mobile
./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug
```

Expected: PASS.

- [ ] **Step 6: Check scope**

```bash
git diff --stat main...HEAD
git diff main...HEAD -- mobile/app/src/main mobile/app/src/test mobile/README.md docs/DEVELOPMENT_STATUS.md
```

Expected: root navigation, minimal Home shared-state summary, explicit Settings routing and docs only. No physical preset implementation, MediaSession, sleep timer, ICY metadata, or transport changes.

- [ ] **Step 7: Push and open one PR**

Branch:

```text
feat/android-root-navigation
```

PR title:

```text
feat(android): add home radio settings navigation
```

PR body:

```markdown
Add the vNext Android root shell without changing the transport stack.

- Home is the default landing screen with shared M5 state
- Radio keeps the existing Presets/Browse/Stations tools
- Settings owns the existing technical controls
- widget and Quick Settings settings routes land on Settings explicitly

Validation:
- `./gradlew :app:lintDebug :app:testDebugUnitTest :app:assembleDebug`
```

Open as `trvny`. Check final-head Mobile CI and review threads. Apply valid findings directly, react 👍 to useful review comments, and squash merge only when final-head relevant checks are green and actionable threads are resolved.
