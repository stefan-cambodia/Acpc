package dev.stefan.acpc.core.keyboard

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Orders and paces key events in emulated frames so that software scanning
 * the keyboard at 50 Hz sees every press and release, however fast or
 * irregularly the events arrive from the front-end.
 *
 * Events are queued from any thread and applied at the start of each frame
 * on the emulation thread, in order. A press is applied only once the key
 * has been released for at least [MIN_UP_FRAMES], a release only once the
 * key has been held for at least [MIN_DOWN_FRAMES]; an event that has to
 * wait blocks the ones behind it, which keeps modifier ordering intact
 * (SHIFT down before the key, key up before SHIFT up). A press is never
 * applied in the same frame as a release: the firmware reads a new key's
 * SHIFT/CTRL state from the previous scan, so "&" then "4" typed quickly
 * would otherwise come out as "&$".
 *
 * Human typing is far slower than these limits, so the queue is transparent
 * in normal use. It matters for fast typists, key repeat, injected text and
 * short taps on a touch keyboard, and it decouples input timing from the
 * bursts in which the emulation thread runs frames (audio-paced pacing).
 */
class KeyQueue(private val matrix: KeyboardMatrix) {
    private class Event(val key: CpcKey, val pressed: Boolean)

    private val incoming = ConcurrentLinkedQueue<Event>()
    private val pending = ArrayDeque<Event>()
    private val downSince = HashMap<CpcKey, Long>()
    private val upSince = HashMap<CpcKey, Long>()
    private var frame = 0L

    /** Queues a press or release (thread-safe). */
    fun push(key: CpcKey, pressed: Boolean) {
        incoming.add(Event(key, pressed))
    }

    /** Drops every queued event and releases the keys the queue pressed. Emulation thread only. */
    fun clear() {
        incoming.clear()
        pending.clear()
        for (k in downSince.keys) matrix.release(k)
        downSince.clear()
        upSince.clear()
    }

    /** Applies the events that are due; call once per frame on the emulation thread. */
    fun onFrame() {
        frame++
        while (true) {
            val e = incoming.poll() ?: break
            pending.addLast(e)
        }
        var releasedThisFrame = false
        while (pending.isNotEmpty()) {
            val e = pending.first()
            if (e.pressed) {
                val isDown = downSince.containsKey(e.key)
                if (isDown) { pending.removeFirst(); continue }            // already held: nothing to do
                if (releasedThisFrame) return                              // releases first, presses next frame
                val up = upSince[e.key]
                if (up != null && frame - up < MIN_UP_FRAMES) return       // let the release be scanned first
                pending.removeFirst()
                downSince[e.key] = frame
                matrix.press(e.key)
            } else {
                val down = downSince[e.key]
                if (down == null) { pending.removeFirst(); continue }       // not held by us: nothing to do
                if (frame - down < MIN_DOWN_FRAMES) return                  // keep it held long enough
                pending.removeFirst()
                downSince.remove(e.key)
                upSince[e.key] = frame
                matrix.release(e.key)
                releasedThisFrame = true
            }
        }
    }

    companion object {
        /** Frames a key stays pressed at least (three keyboard scans). */
        const val MIN_DOWN_FRAMES = 3L

        /** Frames a key stays released at least before it can be pressed again (two scans). */
        const val MIN_UP_FRAMES = 2L
    }
}
