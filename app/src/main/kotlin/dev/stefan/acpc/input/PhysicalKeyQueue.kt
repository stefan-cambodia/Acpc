package dev.stefan.acpc.input

import android.os.Handler
import dev.stefan.acpc.core.keyboard.CpcKey

/**
 * Feeds physical key events to the emulator in order, spacing them so that
 * the CPC firmware (which scans the keyboard every 20 ms) sees every state:
 * consecutive events are at least [gapMs] apart and every key stays pressed
 * for at least [minHoldMs]. Human typing is slower than that, so the queue
 * is transparent in normal use; it only matters for very fast typists,
 * injected input and key repeat.
 */
class PhysicalKeyQueue(
    private val handler: Handler,
    private val press: (CpcKey) -> Unit,
    private val release: (CpcKey) -> Unit,
    private val minHoldMs: Long = 50,
    private val gapMs: Long = 25,
) {
    private class Event(val key: CpcKey, val down: Boolean)

    private val queue = ArrayDeque<Event>()
    private var lastAppliedAt = 0L
    private val downAt = HashMap<CpcKey, Long>()
    private var pumpScheduled = false
    private val pump = Runnable { pumpScheduled = false; drain() }

    fun keyDown(key: CpcKey) {
        queue.addLast(Event(key, true))
        drain()
    }

    fun keyUp(key: CpcKey) {
        queue.addLast(Event(key, false))
        drain()
    }

    private fun drain() {
        while (queue.isNotEmpty()) {
            val e = queue.first()
            val now = System.currentTimeMillis()
            var due = maxOf(now, lastAppliedAt + gapMs)
            if (!e.down) downAt[e.key]?.let { due = maxOf(due, it + minHoldMs) }
            if (due > now) {
                if (!pumpScheduled) {
                    pumpScheduled = true
                    handler.postDelayed(pump, due - now)
                }
                return
            }
            queue.removeFirst()
            lastAppliedAt = now
            if (e.down) {
                downAt[e.key] = now
                press(e.key)
            } else {
                downAt.remove(e.key)
                release(e.key)
            }
        }
    }

    fun releaseAll() {
        handler.removeCallbacks(pump)
        pumpScheduled = false
        queue.clear()
        for (k in downAt.keys) release(k)
        downAt.clear()
    }
}
