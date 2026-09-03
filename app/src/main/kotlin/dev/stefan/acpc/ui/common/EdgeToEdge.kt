package dev.stefan.acpc.ui.common

import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Android 15+ lays every window out edge to edge; screens that are not the
 * emulator want their content between the system bars instead.
 */
object EdgeToEdge {
    /** Adds the system bar and cutout insets to the view's own padding. */
    fun padSystemBars(view: View) {
        val left = view.paddingLeft; val top = view.paddingTop; val right = view.paddingRight; val bottom = view.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            insets
        }
    }
}
