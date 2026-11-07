package com.notlexi.nlfileeditor.ui.screens

import android.graphics.Typeface
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notlexi.nlfileeditor.tmx.InstallPhase
import com.notlexi.nlfileeditor.tmx.TmxClient
import com.notlexi.nlfileeditor.tmx.TmxSessions
import com.notlexi.nlfileeditor.ui.GradientButton
import com.notlexi.nlfileeditor.ui.GradientPill
import com.notlexi.nlfileeditor.ui.bounceClick
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.viewmodel.MainViewModel
import com.termux.terminal.TerminalSession
import com.termux.view.TerminalView
import kotlinx.coroutines.delay

// ====================================================================== chooser

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmxHomeScreen(vm: MainViewModel) {
    val scheme = MaterialTheme.colorScheme
    val full by vm.tmxFull.ui.collectAsStateWithLifecycle()

    // Cards slide/fade in one after the other, once.
    val a1 = remember { Animatable(0f) }
    val a2 = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        a1.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(Unit) {
        delay(110)
        a2.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
    }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("NL TMX", fontWeight = FontWeight.Bold, fontSize = 22.sp)
                        Text("NotLexi Termux", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    }
                },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TmxCard(
                title = "NL TMX Light",
                badge = "PRO",
                lines = listOf(
                    "Android's own shell (/system/bin/sh) on a real pseudo-terminal",
                    "Multi-line scripts, paste, scrollback, command history",
                    "CTRL, ALT, ESC, TAB, arrows, Home/End, PgUp/PgDn keys",
                    "Opens instantly, nothing to download",
                    "Runs with this app's permissions only (no root)"
                ),
                status = "Ready",
                onClick = vm::openTmxLight,
                modifier = Modifier.graphicsLayer { alpha = a1.value; translationY = (1f - a1.value) * 40f }
            )
            TmxCard(
                title = "NL TMX Full",
                badge = "ULTRA",
                lines = listOf(
                    "Termux's real terminal emulator and PTY",
                    "Complete Alpine Linux environment, installed once and kept",
                    "Install packages with apk (python3, git, nano, gcc ...)",
                    "Runs through proot: fake root, not real root",
                    "Not Termux's own pkg repository: Termux packages are built for Termux's own app folder"
                ),
                status = when {
                    full.unsupported != null -> "Not available on this phone"
                    full.installing -> "Installing… ${(full.fraction * 100).toInt()}%"
                    full.installed -> "Installed" + (full.release?.let { " · Alpine $it" } ?: "")
                    else -> "Needs a one-time download"
                },
                onClick = vm::openTmxFull,
                modifier = Modifier.graphicsLayer { alpha = a2.value; translationY = (1f - a2.value) * 40f }
            )
        }
    }
}

@Composable
private fun TmxCard(title: String, badge: String, lines: List<String>, status: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(26.dp)
    Column(
        modifier
            .fillMaxWidth()
            .bounceClick(onClick = onClick)
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.85f))
            .border(1.5.dp, NLColors.gradient, shape)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Terminal, contentDescription = null, tint = NLColors.Blue, modifier = Modifier.size(28.dp))
            Spacer(Modifier.width(10.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(
                badge, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(NLColors.gradient).padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }
        for (l in lines) {
            Row {
                Text("•  ", color = NLColors.Blue, fontSize = 13.sp)
                Text(l, fontSize = 13.sp, lineHeight = 18.sp, color = scheme.onSurface.copy(alpha = 0.9f))
            }
        }
        Text(status, color = NLColors.Success, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ====================================================================== Light

@Composable
fun TmxLightScreen(vm: MainViewModel) {
    val context = LocalContext.current
    TerminalPane(
        title = "NL TMX Light",
        badge = "PRO",
        subtitle = "Android shell · app permissions",
        onBack = vm::back,
        sessionFactory = { client, forceNew -> TmxSessions.lightSession(context, client, forceNew) }
    )
}

// ====================================================================== Full

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmxFullScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val ui by vm.tmxFull.ui.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    var open by rememberSaveable { mutableStateOf(false) }
    var confirmUninstall by remember { mutableStateOf(false) }

    if (open && ui.installed) {
        TerminalPane(
            title = "NL TMX Full",
            badge = "ULTRA",
            subtitle = "Alpine Linux · proot",
            onBack = { open = false },
            sessionFactory = { client, forceNew -> TmxSessions.fullSession(context, client, ui.compatibilityMode, forceNew) }
        )
        return
    }

    BackHandler { vm.back() }
    val progress by animateFloatAsState(ui.fraction, tween(250), label = "tmxProgress")

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = { Text("NL TMX Full", fontWeight = FontWeight.Bold, fontSize = 20.sp) },
                navigationIcon = {
                    IconButton(onClick = vm::back) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text("Linux environment", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text(
                "A full Alpine Linux root filesystem, entered with proot and shown in Termux's real terminal emulator (true PTY). " +
                    "It is installed once into this app's private storage and kept between launches.\n\n" +
                    "What it is not: real root, and not Termux's pkg repository. Termux's packages are compiled for " +
                    "Termux's own app folder and can't run from another app. Packages come from Alpine (apk add ...). " +
                    "Programs that need kernel features Android blocks (some containers, mounts, raw sockets) won't work.",
                fontSize = 13.sp, lineHeight = 19.sp, color = scheme.onSurfaceVariant
            )

            when {
                ui.unsupported != null -> Text(ui.unsupported!!, color = scheme.error, fontSize = 14.sp)
                ui.installing -> {
                    Text(ui.detail, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)),
                        color = NLColors.Blue
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            when (ui.phase) {
                                InstallPhase.DOWNLOADING -> "Downloading"
                                InstallPhase.VERIFYING -> "Verifying"
                                InstallPhase.EXTRACTING -> "Extracting"
                                InstallPhase.CONFIGURING -> "Configuring"
                                else -> "Preparing"
                            } + " · ${(ui.fraction * 100).toInt()}%",
                            fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = vm.tmxFull::cancelDownload) { Text("Cancel") }
                    }
                    Text("You can leave this screen; the installation continues while the app is open.", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                }
                ui.installed -> {
                    // Installation finished: the Download button has turned into Open Terminal.
                    Text("Installed" + (ui.release?.let { " · Alpine Linux $it" } ?: ""), color = NLColors.Success, fontWeight = FontWeight.SemiBold)
                    GradientButton(
                        text = "Open Terminal", icon = Icons.Rounded.Terminal, onClick = { open = true },
                        glow = true, modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = ui.compatibilityMode, onCheckedChange = vm.tmxFull::setCompatibilityMode)
                        Text(
                            "Compatibility mode (turn on if the terminal closes immediately; slower)",
                            fontSize = 12.sp, color = scheme.onSurfaceVariant
                        )
                    }
                    TextButton(onClick = { confirmUninstall = true }) { Text("Uninstall environment", color = scheme.error) }
                }
                else -> {
                    GradientButton(
                        text = "Download", icon = Icons.Rounded.Download, onClick = vm.tmxFull::download,
                        glow = true, modifier = Modifier.fillMaxWidth()
                    )
                    Text("About 4 MB to download and about 10 MB on disk. Needs an internet connection once.", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                }
            }
            AnimatedVisibility(visible = ui.error != null, enter = fadeIn(), exit = fadeOut()) {
                Text(ui.error ?: "", color = scheme.error, fontSize = 13.sp, lineHeight = 18.sp)
            }
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text("Uninstall NL TMX Full?") },
            text = { Text("The Linux environment and everything you stored inside it will be deleted.") },
            confirmButton = { TextButton(onClick = { confirmUninstall = false; vm.tmxFull.uninstall() }) { Text("Uninstall") } },
            dismissButton = { TextButton(onClick = { confirmUninstall = false }) { Text("Keep") } }
        )
    }
}

// ====================================================================== terminal

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalPane(
    title: String,
    badge: String,
    subtitle: String,
    onBack: () -> Unit,
    sessionFactory: (TmxClient, Boolean) -> TerminalSession
) {
    val context = LocalContext.current
    val client = remember { TmxClient(context) }
    var session by remember { mutableStateOf<TerminalSession?>(null) }
    var showScript by remember { mutableStateOf(false) }
    var scriptText by rememberSaveable { mutableStateOf("") }
    var exit by remember { mutableStateOf<Int?>(null) }
    val scheme = MaterialTheme.colorScheme

    LaunchedEffect(Unit) {
        client.onFinished = { code -> exit = code }
        session = sessionFactory(client, false)
        exit = null
        client.finished = false
        delay(250)
        client.showKeyboard()
    }
    BackHandler(onBack = onBack)

    Scaffold(
        containerColor = Color.Black,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(title, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                badge, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(NLColors.gradient).padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(subtitle, fontSize = 11.sp, color = scheme.onSurfaceVariant)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") } },
                actions = {
                    TextButton(onClick = client::pasteFromClipboard) { Text("Paste") }
                    TextButton(onClick = { showScript = true }) { Text("Script") }
                    IconButton(onClick = {
                        session = sessionFactory(client, true)
                        exit = null
                        client.finished = false
                    }) { Icon(Icons.Rounded.Refresh, contentDescription = "New session", tint = NLColors.Blue) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().navigationBarsPadding()) {
            AndroidView(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                factory = { ctx ->
                    TerminalView(ctx, null).apply {
                        setTerminalViewClient(client)
                        client.view = this
                        setTextSize(client.textSizePx)
                        setTypeface(Typeface.MONOSPACE)
                        isFocusable = true
                        isFocusableInTouchMode = true
                    }
                },
                update = { v ->
                    session?.let { v.attachSession(it) }
                },
                onRelease = { client.view = null }
            )
            if (exit != null) {
                Row(
                    Modifier.fillMaxWidth().background(scheme.surface).padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Session ended (exit ${exit}).", fontSize = 13.sp, modifier = Modifier.weight(1f))
                    GradientPill(text = "New session", icon = Icons.Rounded.Refresh, onClick = {
                        session = sessionFactory(client, true)
                        exit = null
                        client.finished = false
                    })
                }
            }
            ExtraKeys(client)
        }
    }

    if (showScript) {
        AlertDialog(
            onDismissRequest = { showScript = false },
            title = { Text("Run a script") },
            text = {
                Column {
                    Text("Every line is typed into the shell and run, like pasting it.", fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = scriptText,
                        onValueChange = { scriptText = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 6,
                        maxLines = 12,
                        textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showScript = false
                    if (scriptText.isNotBlank()) client.runScript(scriptText)
                    client.showKeyboard()
                }) { Text("Run") }
            },
            dismissButton = { TextButton(onClick = { showScript = false }) { Text("Cancel") } }
        )
    }
}

/** Keys a phone keyboard lacks. CTRL / ALT stay active for the next key (tap again to lock, third tap to release). */
@Composable
private fun ExtraKeys(client: TmxClient) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .background(scheme.surface)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Key("ESC") { client.sendKey(KeyEvent.KEYCODE_ESCAPE) }
        Key("TAB") { client.sendKey(KeyEvent.KEYCODE_TAB) }
        Key(if (client.ctrlLocked) "CTRL•" else "CTRL", active = client.ctrl) { client.toggleCtrl() }
        Key(if (client.altLocked) "ALT•" else "ALT", active = client.alt) { client.toggleAlt() }
        Key("↑") { client.sendKey(KeyEvent.KEYCODE_DPAD_UP) }
        Key("↓") { client.sendKey(KeyEvent.KEYCODE_DPAD_DOWN) }
        Key("←") { client.sendKey(KeyEvent.KEYCODE_DPAD_LEFT) }
        Key("→") { client.sendKey(KeyEvent.KEYCODE_DPAD_RIGHT) }
        Key("HOME") { client.sendKey(KeyEvent.KEYCODE_MOVE_HOME) }
        Key("END") { client.sendKey(KeyEvent.KEYCODE_MOVE_END) }
        Key("PGUP") { client.sendKey(KeyEvent.KEYCODE_PAGE_UP) }
        Key("PGDN") { client.sendKey(KeyEvent.KEYCODE_PAGE_DOWN) }
        Key("^C") { client.sendByte(3) }
        Key("^D") { client.sendByte(4) }
        Key("^Z") { client.sendByte(26) }
        for (c in listOf('-', '/', '|', '~', '\\', '_', ':', ';', '"', '\'', '$', '&', '*', '<', '>')) Key(c.toString()) { client.sendChar(c) }
    }
}

@Composable
private fun Key(label: String, active: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier
            .height(38.dp)
            .widthIn38()
            .bounceClick(onClick = onClick)
            .clip(shape)
            .background(if (active) NLColors.Blue else Color(0x22FFFFFF))
            .border(1.dp, if (active) NLColors.Blue else Color(0x553D7BFF), shape)
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace)
    }
}

private fun Modifier.widthIn38(): Modifier = this.then(Modifier.defaultMinSize(minWidth = 38.dp))
