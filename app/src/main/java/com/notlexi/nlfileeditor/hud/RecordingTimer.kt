package com.notlexi.nlfileeditor.hud

import android.os.SystemClock
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Non-blocking countdown used by the 5s / 10s recording modes. */
object RecordingTimer {
    /**
     * Emits [seconds], [seconds]-1, ... 1, one value per second (drift-corrected with the
     * monotonic clock), and completes once the last second has elapsed. Uses `delay`, so it
     * never blocks a thread and is cancelled together with its collector.
     */
    fun countdown(seconds: Int): Flow<Int> = flow {
        val start = SystemClock.elapsedRealtime()
        for (left in seconds downTo 1) {
            emit(left)
            val dueAt = start + (seconds - left + 1) * 1000L
            delay((dueAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
        }
    }
}
