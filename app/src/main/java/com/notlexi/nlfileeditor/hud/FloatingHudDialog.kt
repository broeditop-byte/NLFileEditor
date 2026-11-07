package com.notlexi.nlfileeditor.hud

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Entry point on the Home screen: choose the target file, then start / stop the floating HUD.
 * Handles the notification permission (Android 13+) and the "display over other apps" permission.
 */
@Composable
fun FloatingHudDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val running by HudRuntime.running.collectAsState()
    val target by HudRuntime.target.collectAsState()
    var targetName by remember { mutableStateOf<String?>(null) }
    var needOverlay by remember { mutableStateOf(false) }
    var waitingForOverlay by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) { FileAccessManager(context).restoreTarget() }
    }
    LaunchedEffect(target) {
        val uri = target
        targetName = if (uri == null) null else withContext(Dispatchers.IO) {
            runCatching { FileAccessManager(context).describe(uri)?.name }.getOrNull()
        }
    }

    fun startNow() {
        try {
            FloatingHudService.start(context)
            onDismiss()
        } catch (e: Exception) {
            error = "The HUD could not be started: ${e.javaClass.simpleName}"
        }
    }

    fun afterNotificationStep() {
        if (Settings.canDrawOverlays(context)) startNow() else needOverlay = true
    }

    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        // Granted or not, the HUD still works; only its status notification may stay hidden.
        afterNotificationStep()
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (waitingForOverlay) {
            waitingForOverlay = false
            if (Settings.canDrawOverlays(context)) startNow()
            else error = "Overlay permission was not granted, so the HUD cannot be shown."
        }
    }

    fun onStartStopClick() {
        error = null
        if (running) {
            FloatingHudService.stop(context)
            onDismiss()
            return
        }
        val needsNotificationPermission = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsNotificationPermission) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            afterNotificationStep()
        }
    }

    if (needOverlay) {
        AlertDialog(
            onDismissRequest = { needOverlay = false },
            title = { Text("Allow display over other apps") },
            text = {
                Text(
                    "The floating HUD is drawn on top of your game, which Android only allows after you turn on " +
                        "\"Display over other apps\" for NL File Editor. This does not give the app access to " +
                        "any other app's data."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    needOverlay = false
                    waitingForOverlay = true
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                        )
                    } catch (e: Exception) {
                        waitingForOverlay = false
                        error = "Could not open the overlay settings. Enable it manually in Settings > Apps."
                    }
                }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { needOverlay = false }) { Text("Not now") } }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Floating HUD") },
        text = {
            Column {
                Text(
                    text = "Target file: " + (targetName ?: "none selected"),
                    fontSize = 14.sp
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    "Pick a save file you can access, start the HUD, open your game, then use 5s / 10s Record " +
                        "and perform an action. Works without root, only on the selected file.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    "It cannot read game memory or other apps' private storage, and a game may overwrite edits.",
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onStartStopClick() }) { Text(if (running) "Stop HUD" else "Start HUD") }
        },
        dismissButton = {
            Column {
                TextButton(onClick = {
                    context.startActivity(Intent(context, HudFilePickerActivity::class.java))
                }) { Text("Choose target file") }
                TextButton(onClick = onDismiss) { Text("Close") }
            }
        }
    )
}
