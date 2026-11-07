package com.notlexi.nlfileeditor.hud

import android.Manifest
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.notlexi.nlfileeditor.MainActivity
import com.notlexi.nlfileeditor.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * Foreground service that owns the floating HUD overlay (a ComposeView added to WindowManager).
 * It only draws over other apps and reads/writes the one file the user picked through SAF.
 * Overlay permission gives no access to any app's private files or memory.
 *
 * Bubble position: the position of the COLLAPSED bubble is kept in [bubbleX]/[bubbleY] and saved
 * to SharedPreferences when a drag ends. The expanded panel is only ever placed (and clamped to
 * the screen) temporarily; clamping the panel never rewrites the bubble position, so collapsing
 * puts the bubble back exactly where the user left it.
 */
class FloatingHudService : Service() {

    private lateinit var wm: WindowManager
    private lateinit var files: FileAccessManager
    private lateinit var engine: SnapshotEngine
    private val owner = HudViewOwner()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val expanded = MutableStateFlow(false)

    private var root: HudDragLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var snapAnimator: ValueAnimator? = null
    private var foregroundStarted = false
    private var ownerCreated = false
    private var overlayLostHandled = false
    private var lastNotificationText: String? = null

    /** Last position of the collapsed bubble (window coordinates). Survives expand/collapse. */
    private var bubbleX = 0
    private var bubbleY = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        files = FileAccessManager(this)
        engine = SnapshotEngine(files, scope)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 8+ requires startForeground() soon after startForegroundService().
        if (!foregroundStarted) {
            if (!goForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            foregroundStarted = true
        }
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow \"Display over other apps\" to use the HUD.", Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (root == null && !attachOverlay()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // Not sticky: the overlay can only be started from a visible screen on Android 12+.
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // Rotation / fold / split-screen: only now can the saved bubble position be genuinely off-screen.
        root?.post {
            normalizeBubble()
            clampToScreen()
        }
    }

    override fun onDestroy() {
        snapAnimator?.cancel()
        snapAnimator = null
        scope.cancel()
        engine.shutdown()
        root?.let { view ->
            view.onDragStart = null
            view.onDragBy = null
            view.onDragEnd = null
            try {
                wm.removeViewImmediate(view)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Overlay was already removed")
            } catch (e: RuntimeException) {
                Log.w(TAG, "Overlay could not be removed cleanly", e)
            }
        }
        root = null
        params = null
        if (ownerCreated) {
            ownerCreated = false
            try {
                owner.destroy()
            } catch (e: RuntimeException) {
                Log.w(TAG, "Overlay lifecycle did not shut down cleanly", e)
            }
        }
        if (foregroundStarted) {
            foregroundStarted = false
            try {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            } catch (e: RuntimeException) {
                Log.w(TAG, "stopForeground failed", e)
            }
        }
        HudRuntime.running.value = false
        super.onDestroy()
    }

    // ------------------------------------------------------------------ foreground notification

    private fun goForeground(): Boolean {
        return try {
            val channel = NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Floating HUD")
                .setDescription("Shown while the NL File Editor floating HUD is running")
                .setShowBadge(false)
                .build()
            NotificationManagerCompat.from(this).createNotificationChannel(channel)

            val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
            ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(DEFAULT_NOTIFICATION_TEXT), type)
            lastNotificationText = DEFAULT_NOTIFICATION_TEXT
            true
        } catch (e: Exception) {
            // Includes ForegroundServiceStartNotAllowedException (Android 12+) and SecurityException.
            Log.e(TAG, "Could not start the foreground service", e)
            false
        }
    }

    private fun buildNotification(text: String) = run {
        val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            immutable
        )
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, FloatingHudService::class.java).setAction(ACTION_STOP),
            immutable
        )
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hud_notification)
            .setContentTitle("NL File Editor HUD is running")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, "Close HUD", stop)
            .build()
    }

    /** Re-posts the foreground notification with new text (same id, so it updates in place). */
    @SuppressLint("MissingPermission")
    private fun updateNotification(text: String) {
        if (!foregroundStarted || text == lastNotificationText) return
        // Without the Android 13+ notification permission the system hides it anyway; skip quietly.
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        lastNotificationText = text
        try {
            NotificationManagerCompat.from(this).notify(NOTIFICATION_ID, buildNotification(text))
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not update the notification", e)
        }
    }

    private fun notificationTextFor(ui: HudUiState): String = when (ui.phase) {
        HudPhase.RECORDING -> "Recording\u2026 ${ui.countdown}s left. Play the game now."
        HudPhase.CAPTURING -> "Reading the file\u2026"
        HudPhase.DIFFING -> "Comparing snapshots\u2026"
        HudPhase.PATCHING -> "Writing the patch\u2026"
        HudPhase.ERROR -> "Problem: " + (ui.message ?: "see the HUD").take(90)
        HudPhase.READY ->
            if (ui.changes.isNotEmpty()) "${ui.changes.size} changed value(s) found. Tap the bubble to view."
            else if (ui.noChanges) "No changes detected."
            else DEFAULT_NOTIFICATION_TEXT
        else ->
            if (ui.armed) "Snapshot A saved. Ready to record."
            else DEFAULT_NOTIFICATION_TEXT
    }

    // ------------------------------------------------------------------ overlay

    private fun attachOverlay(): Boolean {
        owner.create()
        ownerCreated = true
        val density = resources.displayMetrics.density

        val frame = HudDragLayout(this)
        val compose = ComposeView(this).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnDetachedFromWindow)
            setContent {
                val ui by engine.state.collectAsState()
                val isExpanded by expanded.collectAsState()
                FloatingHudView(ui = ui, expanded = isExpanded, actions = actions)
            }
        }
        frame.addView(compose, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        for (v in listOf(frame, compose)) {
            v.setViewTreeLifecycleOwner(owner)
            v.setViewTreeViewModelStoreOwner(owner)
            v.setViewTreeSavedStateRegistryOwner(owner)
        }

        frame.dragZoneBottomPx = Int.MAX_VALUE
        frame.onDragStart = { snapAnimator?.cancel() }
        frame.onDragBy = { dx, dy ->
            params?.let { lp ->
                lp.x += dx
                lp.y += dy
                if (!expanded.value) {
                    bubbleX = lp.x
                    bubbleY = lp.y
                }
                clampToScreen(update = false)
                if (!expanded.value) {
                    bubbleX = lp.x
                    bubbleY = lp.y
                }
                updateLayoutSafely()
            }
        }
        frame.onDragEnd = {
            if (expanded.value) {
                // Only the panel moved; the bubble's remembered position is left alone.
                clampToScreen()
            } else if (SNAP_TO_EDGE) {
                snapToEdge()
            } else {
                clampToScreen()
                savePosition()
            }
        }
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (r - l != or2 - ol || b - t != ob - ot) frame.post { clampToScreen() }
        }

        @Suppress("DEPRECATION")
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        val bounds = usableBounds()
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        bubbleX = prefs.getInt(PREF_X, bounds.right - (80 * density).toInt())
        bubbleY = prefs.getInt(PREF_Y, bounds.top + (bounds.height() * 0.25f).toInt())
        normalizeBubble() // only changes it when it is genuinely off-screen

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX
            y = bubbleY
        }

        root = frame
        params = lp
        owner.resume()
        try {
            wm.addView(frame, lp)
        } catch (e: Exception) {
            // BadTokenException / SecurityException: overlay permission missing or revoked.
            Log.e(TAG, "Could not add the overlay window", e)
            root = null
            params = null
            Toast.makeText(this, "Could not show the HUD overlay. Check \"Display over other apps\".", Toast.LENGTH_LONG).show()
            return false
        }

        HudRuntime.running.value = true
        frame.post { clampToScreen() }

        scope.launch {
            val hadSaved = withContext(Dispatchers.IO) {
                val had = files.savedTarget() != null
                files.restoreTarget()
                had
            }
            if (hadSaved && HudRuntime.target.value == null) {
                engine.postMessage(
                    "The previously selected file is no longer accessible (permission revoked or file moved). Select it again.",
                    true
                )
            }
        }
        // target + epoch: picking the same file again, or clearing, must still reset the engine.
        scope.launch {
            combine(HudRuntime.target, HudRuntime.targetEpoch) { uri, _ -> uri }
                .collect { engine.onTargetChanged(it) }
        }
        scope.launch { HudRuntime.pickerOpen.collect { engine.setSelectingFile(it) } }
        scope.launch { HudRuntime.edits.collect { engine.setPending(it) } }
        scope.launch {
            engine.state.map { notificationTextFor(it) }.distinctUntilChanged().collect { updateNotification(it) }
        }
        scope.launch {
            expanded.collect { isExpanded ->
                frame.dragZoneBottomPx = if (isExpanded) (HUD_HEADER_DRAG_DP * density).toInt() else Int.MAX_VALUE
                if (!Settings.canDrawOverlays(this@FloatingHudService)) {
                    overlayLost()
                    return@collect
                }
                // Both states start from the bubble's remembered position. Expanded is then clamped
                // (window only); collapsed returns to exactly this spot.
                snapAnimator?.cancel()
                params?.let {
                    it.x = bubbleX
                    it.y = bubbleY
                }
                updateLayoutSafely()
                frame.post { clampToScreen() }
            }
        }
        return true
    }

    private val actions = HudActions(
        onToggleExpanded = { expanded.value = !expanded.value },
        onClose = { stopSelf() },
        onSelectFile = { launchActivity(Intent(this, HudFilePickerActivity::class.java)) },
        onClearTarget = {
            scope.launch {
                try {
                    withContext(Dispatchers.IO) { files.clearTarget() }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not clear the target", e)
                    engine.postMessage("Could not clear the selection. Try again.", true)
                }
            }
        },
        onStartGame = { engine.startGame() },
        onSnapshot1 = { engine.snapshot1() },
        onSnapshot2 = { engine.snapshot2() },
        onRecord5 = { engine.record(5) },
        onRecord10 = { engine.record(10) },
        onToggleDiffMode = { engine.toggleDiffMode() },
        onSelectChange = { engine.select(it) },
        onEdit = {
            val selected = engine.state.value.selected
            if (selected == null) engine.postMessage("Select a detected value first.", true)
            else launchActivity(HudEditActivity.intentFor(this, selected))
        },
        onPatch = { engine.patch() },
        onCancel = { engine.cancel() }
    )

    private fun launchActivity(intent: Intent) {
        try {
            startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) {
            Log.e(TAG, "Could not open the activity", e)
            engine.postMessage("Could not open the dialog. Open NL File Editor and try again.", true)
        }
    }

    /** The overlay permission disappeared while the HUD was running: say so once, then stop cleanly. */
    private fun overlayLost() {
        if (overlayLostHandled) return
        overlayLostHandled = true
        Log.w(TAG, "Overlay permission lost; stopping the HUD")
        Toast.makeText(
            this,
            "The HUD was stopped because \"Display over other apps\" is no longer allowed. Turn it on and start the HUD again.",
            Toast.LENGTH_LONG
        ).show()
        stopSelf()
    }

    // ------------------------------------------------------------------ position / bounds

    /** Area of the screen the HUD may occupy (below the status bar, above the navigation bar). */
    private fun usableBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
            )
            val b = metrics.bounds
            return Rect(b.left + insets.left, b.top + insets.top, b.right - insets.right, b.bottom - insets.bottom)
        }
        val dm = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(dm)
        val id = resources.getIdentifier("status_bar_height", "dimen", "android")
        val status = if (id > 0) resources.getDimensionPixelSize(id) else 0
        return Rect(0, status, dm.widthPixels, dm.heightPixels)
    }

    /**
     * Keeps the bubble's remembered position on screen. It is only changed when it is genuinely
     * outside the usable area (e.g. after rotation), never because the expanded panel is larger.
     */
    private fun normalizeBubble() {
        val density = resources.displayMetrics.density
        val b = usableBounds()
        val w = (BUBBLE_WIDTH_DP * density).toInt()
        val h = (BUBBLE_HEIGHT_DP * density).toInt()
        val nx = bubbleX.coerceIn(b.left, max(b.left, b.right - w))
        val ny = bubbleY.coerceIn(b.top, max(b.top, b.bottom - h))
        if (nx != bubbleX || ny != bubbleY) {
            bubbleX = nx
            bubbleY = ny
            savePosition()
        }
    }

    /**
     * Keeps the whole HUD window on screen. While collapsed the window is placed from the saved
     * bubble position (so a temporarily larger window, e.g. during the collapse animation, can
     * never overwrite it). While expanded only the window is clamped; [bubbleX]/[bubbleY] stay.
     */
    private fun clampToScreen(update: Boolean = true) {
        val lp = params ?: return
        val view = root ?: return
        val w = view.width
        val h = view.height
        if (w == 0 || h == 0) return
        // Collapsing: the card is still on screen for its short close animation. Placing a card-sized
        // window "inside the screen" now would shift the bubble's window and snap it back a moment
        // later (a visible jump), so wait until the window has shrunk to the bubble.
        if (!expanded.value && w > (BUBBLE_WIDTH_DP * resources.displayMetrics.density * 1.5f)) return
        val b = usableBounds()
        val wantX = if (expanded.value) lp.x else bubbleX
        val wantY = if (expanded.value) lp.y else bubbleY
        val nx = wantX.coerceIn(b.left, max(b.left, b.right - w))
        val ny = wantY.coerceIn(b.top, max(b.top, b.bottom - h))
        if (nx != lp.x || ny != lp.y) {
            lp.x = nx
            lp.y = ny
            if (update) updateLayoutSafely()
        }
    }

    private fun updateLayoutSafely() {
        val view = root ?: return
        val lp = params ?: return
        try {
            wm.updateViewLayout(view, lp)
        } catch (e: IllegalArgumentException) {
            // View is not attached (service is shutting down).
        } catch (e: RuntimeException) {
            // BadTokenException / SecurityException: the window can no longer be shown.
            Log.e(TAG, "Overlay update failed", e)
            overlayLost()
        }
    }

    private fun snapToEdge() {
        val lp = params ?: return
        val view = root ?: return
        val b = usableBounds()
        val centerX = lp.x + view.width / 2
        val targetX = if (centerX < (b.left + b.right) / 2) b.left else max(b.left, b.right - view.width)
        snapAnimator?.cancel()
        snapAnimator = ValueAnimator.ofInt(lp.x, targetX).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Int
                lp.x = v
                if (!expanded.value) bubbleX = v
                updateLayoutSafely()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    if (!expanded.value) {
                        bubbleX = lp.x
                        bubbleY = lp.y
                    }
                    clampToScreen()
                    if (!expanded.value) savePosition()
                }
            })
            start()
        }
    }

    /** Saves the collapsed bubble's position (the only position that is remembered). */
    private fun savePosition() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putInt(PREF_X, bubbleX)
            .putInt(PREF_Y, bubbleY)
            .apply()
    }

    companion object {
        private const val TAG = "NLHud"
        private const val ACTION_START = "com.notlexi.nlfileeditor.hud.START"
        private const val ACTION_STOP = "com.notlexi.nlfileeditor.hud.STOP"
        private const val CHANNEL_ID = "nl_hud_channel"
        private const val NOTIFICATION_ID = 5001
        private const val PREFS = "nl_hud"
        private const val PREF_X = "hud_x"
        private const val PREF_Y = "hud_y"
        private const val DEFAULT_NOTIFICATION_TEXT = "Tap to open the app. Use Close HUD to stop."

        /** Collapsed bubble window size (56dp circle + 10dp padding, plus room for the countdown pill). */
        private const val BUBBLE_WIDTH_DP = 76
        private const val BUBBLE_HEIGHT_DP = 100

        /** After a drag the collapsed bubble glides to the nearest screen edge. Set false to leave it exactly where dropped. */
        private const val SNAP_TO_EDGE = true

        /** Call from a visible screen (Android 12+ restricts starting foreground services in the background). */
        fun start(context: Context) {
            val intent = Intent(context, FloatingHudService::class.java).setAction(ACTION_START)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingHudService::class.java))
        }
    }
}
