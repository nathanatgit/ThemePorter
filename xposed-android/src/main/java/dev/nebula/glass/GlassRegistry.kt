package dev.nebula.glass

import android.view.View
import java.util.Collections
import java.util.WeakHashMap

/**
 * Replaces the upstream `child is LiquidGlassView` / `isCapturingBackdrop` checks.
 *
 * Inside hooked processes the glass is often a host view (for example the lock-screen
 * TextClock), not one of our classes, so glass membership is tracked by identity.
 * Main thread only.
 */
object GlassRegistry {
    private val glass: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())
    private val capturing: MutableSet<View> = Collections.newSetFromMap(WeakHashMap())

    /** True while any glass is drawing its backdrop or sampling luminance. */
    @JvmStatic var isSampling = false
        internal set

    @JvmStatic fun register(view: View) { glass.add(view) }
    @JvmStatic fun unregister(view: View) { glass.remove(view); capturing.remove(view) }
    @JvmStatic fun isGlass(view: View): Boolean = view in glass

    /** True while [view] is recording its own backdrop. Its draw hook must not re-enter. */
    @JvmStatic fun isCapturing(view: View): Boolean = view in capturing

    internal fun <T> capture(view: View, block: () -> T): T {
        capturing.add(view)
        val outer = isSampling
        isSampling = true
        try {
            return block()
        } finally {
            isSampling = outer
            capturing.remove(view)
        }
    }
}
