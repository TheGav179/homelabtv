package dev.homelabtv

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Bundle
import android.os.SystemClock
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.homelabtv.data.forChannel
import dev.homelabtv.data.mergeGuide
import dev.homelabtv.theme.HomelabTVTheme
import dev.homelabtv.theme.JellyfinBlue
import dev.homelabtv.ui.MainViewModel
import dev.homelabtv.ui.guide.GUIDE_MINI_PLAYER_END
import dev.homelabtv.ui.guide.GUIDE_MINI_PLAYER_HEIGHT
import dev.homelabtv.ui.guide.GUIDE_MINI_PLAYER_TOP
import dev.homelabtv.ui.guide.GUIDE_MINI_PLAYER_WIDTH
import dev.homelabtv.ui.guide.GuideScreen
import dev.homelabtv.ui.menu.MenuOverlay
import dev.homelabtv.ui.player.DetailsBanner
import dev.homelabtv.ui.player.NumberEntryOverlay
import dev.homelabtv.ui.player.PlayerSurface
import dev.homelabtv.ui.player.ZapperOverlay
import dev.homelabtv.ui.player.rememberTvPlayerState
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Live TV is always "actively watched" — never let the screensaver kick in
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent { HomelabTVTheme { HomelabApp() } }
    }
}

// Not exposed as a Manifest.permission constant in current SDKs
private const val READ_TV_LISTINGS_PERMISSION = "android.permission.READ_TV_LISTINGS"

// A second INFO press this soon after the banner appeared upgrades to the
// detailed banner; later than this it just dismisses everything.
private const val INFO_DOUBLE_PRESS_WINDOW_MS = 2000L

private val HANDLED_KEYS =
    setOf(
        KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_CHANNEL_UP,
        KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER,
        KeyEvent.KEYCODE_ENTER,
        KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_INFO,
    )

private fun digitFor(keyCode: Int): Int? =
    when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> keyCode - KeyEvent.KEYCODE_0
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> keyCode - KeyEvent.KEYCODE_NUMPAD_0
        else -> null
    }

private enum class Overlay {
    NONE,
    GUIDE,
    MENU,
    SETTINGS,
}

@Composable
private fun HomelabApp(viewModel: MainViewModel = viewModel()) {
    val context = LocalContext.current
    val guideState by viewModel.guideState.collectAsStateWithLifecycle()
    var overlay by remember { mutableStateOf(Overlay.NONE) }
    val playerState = rememberTvPlayerState()

    // Without READ_TV_LISTINGS the TvProvider hides every channel we didn't create,
    // so the tuner looks missing even when it's fine. Ask once on launch.
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) viewModel.reloadChannels()
        }
    LaunchedEffect(Unit) {
        val status = ContextCompat.checkSelfPermission(context, READ_TV_LISTINGS_PERMISSION)
        if (status != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(READ_TV_LISTINGS_PERMISSION)
        } else {
            viewModel.reloadChannels()
        }
    }

    // Re-read the channel list whenever the app returns to the foreground, so a
    // channel rescan done in the TV's setup screens is picked up automatically
    LifecycleResumeEffect(Unit) {
        viewModel.reloadChannels()
        onPauseOrDispose {}
    }

    // INFO banners: press 1 -> bottom zapper bar, quick press 2 -> detailed top
    // banner with track options, press 3 (or a slow press 2) -> everything away.
    var zapCounter by remember { mutableIntStateOf(0) }
    var zapperVisible by remember { mutableStateOf(false) }
    var zapperShownAt by remember { mutableLongStateOf(0L) }
    // The system clock/date only rides along when the banner came from INFO
    var zapperShowsClock by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(zapCounter) {
        if (zapCounter > 0) {
            zapperShownAt = SystemClock.uptimeMillis()
            zapperVisible = true
            delay(4000)
            zapperVisible = false
        }
    }
    // Announce the current channel with the banner once the app has started up
    LaunchedEffect(guideState.hasLoadedCache) {
        if (guideState.hasLoadedCache && zapCounter == 0) {
            zapperShowsClock = false
            zapCounter++
        }
    }

    val onInfoPressed: () -> Unit = {
        val pressedAt = SystemClock.uptimeMillis()
        when {
            showDetails -> showDetails = false
            zapperVisible -> {
                zapperVisible = false
                if (pressedAt - zapperShownAt <= INFO_DOUBLE_PRESS_WINDOW_MS) showDetails = true
            }
            else -> {
                zapperShowsClock = true
                zapCounter++
            }
        }
    }
    // OK only toggles the bottom channel info bar — it can never escalate
    // to the detailed banner (that's INFO's job)
    val onOkPressed: () -> Unit = {
        if (zapperVisible) {
            zapperVisible = false
        } else {
            zapperShowsClock = false
            zapCounter++
        }
    }

    // Direct channel entry from the remote's number keys. Majors complete after
    // two digits — in the default leading-zero mode 6.1 is typed "06" (so 61.1
    // stays reachable); quick mode lets a first digit above 5 complete the major
    // by itself. The decimal part maxes out at two digits (hundredths), and a
    // partial entry commits after a 2s pause either way.
    var numberEntry by remember { mutableStateOf("") }
    val commitEntry: () -> Unit = {
        val target = numberEntry.trimEnd('.')
        numberEntry = ""
        if (target.isNotEmpty() && viewModel.tuneToNumber(target)) {
            zapperShowsClock = false
            zapCounter++
        }
    }
    val appendDigit: (Int) -> Unit = { digit ->
        var entry = numberEntry
        if ('.' in entry) {
            if (entry.substringAfter('.').length < 2) entry += digit
        } else {
            entry += digit
            val majorDone =
                if (viewModel.numberEntryQuickMode) {
                    (entry.length == 1 && digit > 5) || entry.length == 2
                } else {
                    entry.length == 2
                }
            if (majorDone) entry += "."
        }
        numberEntry = entry
    }
    LaunchedEffect(numberEntry) {
        if (numberEntry.isEmpty()) return@LaunchedEffect
        if (numberEntry.substringAfter('.', "").length >= 2) {
            commitEntry()
        } else {
            delay(2000)
            commitEntry()
        }
    }

    val rootFocus = remember { FocusRequester() }
    LaunchedEffect(overlay, showDetails) {
        if (overlay == Overlay.NONE && !showDetails) {
            try {
                rootFocus.requestFocus()
            } catch (_: Exception) {}
        }
    }

    val currentChannel = viewModel.currentChannel
    val currentGuide = guideState.channels.forChannel(currentChannel)

    Box(
        Modifier.fillMaxSize()
            .background(Color.Black)
            .focusRequester(rootFocus)
            .focusable()
            // Handle BACK before the focus system sees it — otherwise the first
            // press just clears the focus highlight and a second press is needed.
            .onPreviewKeyEvent { event ->
                if (event.nativeKeyEvent.keyCode != KeyEvent.KEYCODE_BACK) return@onPreviewKeyEvent false
                if (event.type == KeyEventType.KeyUp) {
                    when {
                        showDetails -> showDetails = false
                        overlay == Overlay.SETTINGS -> overlay = Overlay.MENU
                        overlay != Overlay.NONE -> overlay = Overlay.NONE
                        else -> overlay = Overlay.MENU
                    }
                }
                true
            }
            .onKeyEvent { event ->
                val keyCode = event.nativeKeyEvent.keyCode
                if (showDetails) {
                    // D-pad and OK navigate the banner's own controls; INFO/GUIDE are ours
                    return@onKeyEvent when (keyCode) {
                        KeyEvent.KEYCODE_INFO -> {
                            if (event.type == KeyEventType.KeyUp) onInfoPressed()
                            true
                        }
                        KeyEvent.KEYCODE_GUIDE -> {
                            if (event.type == KeyEventType.KeyUp) {
                                showDetails = false
                                overlay = Overlay.GUIDE
                            }
                            true
                        }
                        else -> false
                    }
                }
                if (overlay != Overlay.NONE) {
                    // GUIDE stays ours even inside overlays — if it leaks to the
                    // system, Sony's own TV app launches over us
                    if (keyCode == KeyEvent.KEYCODE_GUIDE) {
                        if (event.type == KeyEventType.KeyUp) {
                            overlay = if (overlay == Overlay.GUIDE) Overlay.NONE else Overlay.GUIDE
                        }
                        return@onKeyEvent true
                    }
                    return@onKeyEvent false
                }
                val digit = digitFor(keyCode)
                if (digit == null && keyCode !in HANDLED_KEYS) return@onKeyEvent false
                // Swallow KeyDown as well as KeyUp, so the system's own guide or
                // channel UIs never react to keys this app owns (e.g. Sony's guide
                // popping up over ours on KEYCODE_GUIDE)
                if (event.type != KeyEventType.KeyUp) return@onKeyEvent true
                when {
                    digit != null -> appendDigit(digit)
                    keyCode == KeyEvent.KEYCODE_DPAD_UP || keyCode == KeyEvent.KEYCODE_CHANNEL_UP -> {
                        numberEntry = ""
                        viewModel.zap(1)
                        zapperShowsClock = false
                        zapCounter++
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_DOWN || keyCode == KeyEvent.KEYCODE_CHANNEL_DOWN -> {
                        numberEntry = ""
                        viewModel.zap(-1)
                        zapperShowsClock = false
                        zapCounter++
                    }
                    keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER -> {
                        // Long-press OK opens the detailed banner — the remote's ⓘ key
                        // always summons Sony's own system InfoBar on top of everything
                        // (firmware-level, unsuppressible), so OK covers all its jobs.
                        val isLongPress =
                            event.nativeKeyEvent.eventTime - event.nativeKeyEvent.downTime >= 600
                        when {
                            numberEntry.isNotEmpty() -> commitEntry()
                            isLongPress -> {
                                zapperVisible = false
                                showDetails = true
                            }
                            else -> onOkPressed()
                        }
                    }
                    keyCode == KeyEvent.KEYCODE_GUIDE -> {
                        numberEntry = ""
                        overlay = Overlay.GUIDE
                    }
                    keyCode == KeyEvent.KEYCODE_INFO -> onInfoPressed()
                }
                true
            }
    ) {
        // While the guide is open the TvView shrinks into the hero's top-right
        // corner (the guide leaves that region transparent as a mini player)
        val playerModifier =
            if (overlay == Overlay.GUIDE) {
                Modifier.align(Alignment.TopEnd)
                    .padding(top = GUIDE_MINI_PLAYER_TOP, end = GUIDE_MINI_PLAYER_END)
                    .size(GUIDE_MINI_PLAYER_WIDTH, GUIDE_MINI_PLAYER_HEIGHT)
            } else {
                Modifier.fillMaxSize()
            }
        PlayerSurface(
            channel = currentChannel,
            fallbackInputId = viewModel.fallbackInputId,
            playerState = playerState,
            modifier = playerModifier,
        )

        ZapperOverlay(
            visible = zapperVisible && overlay == Overlay.NONE && !showDetails,
            channel = currentChannel,
            guide = currentGuide,
            showClock = zapperShowsClock,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

        NumberEntryOverlay(entry = numberEntry, modifier = Modifier.align(Alignment.TopEnd))

        if (showDetails && overlay == Overlay.NONE) {
            DetailsBanner(
                channel = currentChannel,
                guide = currentGuide,
                playerState = playerState,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }

        when (overlay) {
            Overlay.GUIDE ->
                GuideScreen(
                    channels = mergeGuide(viewModel.physicalChannels, guideState.channels),
                    isOnline = guideState.isOnline,
                    initialChannelNumber = currentChannel?.displayNumber,
                    onTune = { guide ->
                        if (viewModel.tuneTo(guide)) {
                            overlay = Overlay.NONE
                            zapperShowsClock = false
                            zapCounter++
                        }
                    },
                )
            Overlay.MENU,
            Overlay.SETTINGS ->
                MenuOverlay(
                    showSettings = overlay == Overlay.SETTINGS,
                    isOnline = guideState.isOnline,
                    lastUpdatedMillis = guideState.lastUpdatedMillis,
                    serverUrl = viewModel.serverUrl,
                    tunerInfo =
                        currentChannel?.let {
                            "${it.displayName} (${it.inputId ?: viewModel.fallbackInputId ?: "no input id"})"
                        } ?: "No tuner detected",
                    onServerUrlChange = viewModel::updateServerUrl,
                    onResume = { overlay = Overlay.NONE },
                    onOpenGuide = { overlay = Overlay.GUIDE },
                    onRefresh = { viewModel.refreshNow() },
                    onOpenSettings = { overlay = Overlay.SETTINGS },
                    onCloseSettings = {
                        viewModel.refreshNow()
                        overlay = Overlay.MENU
                    },
                    onReloadChannels = {
                        viewModel.reloadChannels()
                        overlay = Overlay.NONE
                        zapperShowsClock = false
                        zapCounter++
                    },
                    onScanChannels = {
                        val manager = context.getSystemService(Context.TV_INPUT_SERVICE) as? TvInputManager
                        val tuner =
                            manager?.tvInputList?.firstOrNull {
                                it.type == TvInputInfo.TYPE_TUNER && !it.isPassthroughInput
                            }
                        val setupIntent = tuner?.createSetupIntent()
                        if (setupIntent != null) {
                            setupIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            try {
                                context.startActivity(setupIntent)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    },
                    numberEntryQuickMode = viewModel.numberEntryQuickMode,
                    onToggleNumberEntry = viewModel::toggleNumberEntryMode,
                    onRestartApp = {
                        val pm = context.packageManager
                        val relaunch =
                            pm.getLeanbackLaunchIntentForPackage(context.packageName)
                                ?: pm.getLaunchIntentForPackage(context.packageName)
                        relaunch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                        if (relaunch != null) context.startActivity(relaunch)
                        Runtime.getRuntime().exit(0)
                    },
                    onExit = { (context as? ComponentActivity)?.finish() },
                )
            Overlay.NONE -> {}
        }

        // Dark boot screen with a spinner instead of any bright flash
        if (!guideState.hasLoadedCache) {
            Box(
                Modifier.fillMaxSize().background(Color(0xFF181818)),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator(color = JellyfinBlue)
            }
        }
    }
}
