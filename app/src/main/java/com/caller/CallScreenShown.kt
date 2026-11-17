package com.caller

import android.os.Handler
import android.os.Looper
import android.view.View
import androidx.core.view.OneShotPreDrawListener
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Tells the keypad screen that the call screen has really been drawn over it.
 *
 * The call screen's theme is translucent, so Android keeps the keypad screen visible (it is never stopped) while the call
 * screen is on top, and the keypad shows through until the call screen has painted its first frame. Anything the keypad
 * screen wants to change out of sight has to wait for this signal, not for onStop.
 */
internal object CallScreenShown {
    // A short margin after the frame was drawn, so the call screen is already on the display when the keypad changes.
    private const val SETTLE_MS = 120L

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun add(listener: () -> Unit) {
        listeners.addIfAbsent(listener)
    }

    fun remove(listener: () -> Unit) {
        listeners.remove(listener)
    }

    /** Called by the call screen from onResume: [view] is its window's decor view, drawn for the first time after this. */
    fun announceAfterNextDraw(view: View) {
        OneShotPreDrawListener.add(view) {
            Handler(Looper.getMainLooper()).postDelayed({ notifyShown() }, SETTLE_MS)
        }
    }

    internal fun notifyShown() {
        listeners.forEach { it() }
    }
}
