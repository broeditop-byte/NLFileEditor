package com.notlexi.nlfileeditor.hud

import android.net.Uri
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Small in-process bridge between the overlay service, the file-picker / edit activities and the
 * main app UI. Nothing here leaves the app (no broadcasts, no exported components).
 */
object HudRuntime {
    /** True while the floating HUD service is running. */
    val running = MutableStateFlow(false)

    /** The target file chosen through the Storage Access Framework (null = none). */
    val target = MutableStateFlow<Uri?>(null)

    /**
     * Bumped every time the user picks OR clears a target. A StateFlow ignores a value equal to
     * the old one, so without this, picking the same file again (or recovering from a failed
     * state) would never reset the engine. The service listens to target + epoch together.
     */
    val targetEpoch = MutableStateFlow(0L)

    /** True while the system file picker (HudFilePickerActivity) is on screen. */
    val pickerOpen = MutableStateFlow(false)

    /** A new value typed in the edit dialog, waiting for "Save / Patch". */
    val edits = MutableSharedFlow<String>(extraBufferCapacity = 4)
}
