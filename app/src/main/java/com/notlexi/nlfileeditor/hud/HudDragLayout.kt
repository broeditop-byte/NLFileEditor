package com.notlexi.nlfileeditor.hud

import android.content.Context
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Root view of the overlay. Moving a window under the finger makes in-window pointer deltas
 * unreliable, so dragging is tracked with raw screen coordinates here, outside Compose.
 * A touch only becomes a drag after it passes the touch slop, so taps still reach the buttons.
 * Dragging can start anywhere while collapsed, and only in the header while expanded
 * ([dragZoneBottomPx]).
 */
class HudDragLayout(context: Context) : FrameLayout(context) {
    var dragZoneBottomPx: Int = Int.MAX_VALUE
    var onDragStart: (() -> Unit)? = null
    var onDragBy: ((dx: Int, dy: Int) -> Unit)? = null
    var onDragEnd: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var canDrag = false
    private var dragging = false

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        return dragging || (ev.actionMasked == MotionEvent.ACTION_DOWN && canDrag)
    }

    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX
                downY = ev.rawY
                lastX = downX
                lastY = downY
                canDrag = ev.y <= dragZoneBottomPx
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!canDrag) return
                if (!dragging && hypot(ev.rawX - downX, ev.rawY - downY) > slop) {
                    dragging = true
                    onDragStart?.invoke()
                }
                if (dragging) {
                    val dx = (ev.rawX - lastX).roundToInt()
                    val dy = (ev.rawY - lastY).roundToInt()
                    if (dx != 0 || dy != 0) {
                        lastX += dx
                        lastY += dy
                        onDragBy?.invoke(dx, dy)
                    }
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    dragging = false
                    onDragEnd?.invoke()
                }
                canDrag = false
            }
        }
    }
}
