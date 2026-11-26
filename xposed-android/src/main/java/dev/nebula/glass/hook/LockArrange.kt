package dev.nebula.glass.hook

import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.PathInterpolator
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.LockLayout
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import dev.nebula.glass.WallpaperFilter
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The iOS arrangement of Nubia's lock screen:
 *  - Resized clock, fitted date & custom text boxes, five-column widget grid, bottom shortcuts.
 *  - Non-destructive lock-screen wallpaper colour filter (Natural, Black & White, Duotone, Wash).
 *  - iOS-style 3D stacked notification deck at the bottom, which unfolds smoothly when slid upward
 *    while dynamically shrinking the clock height to make room.
 */
class LockArrange : IXposedHookLoadPackage {
    private val arrangers = WeakHashMap<View, Arranger>()

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        if (installed) return
        installed = true
        HookSettings.load()
        GlassSettings.addListener { for (root in arrangers.keys.toList()) root.invalidate() }

        val window = XposedHelpers.findClassIfExists("com.android.systemui.shade.NotificationShadeWindowView", app.classLoader)
        if (window == null) {
            Log.w(TAG, "lock layout: shade window class not found")
            return
        }
        try {
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    if (!window.isInstance(v)) return
                    try {
                        HookSettings.attach(v.context)
                        val a = arrangers.getOrPut(v) { Arranger(v) }
                        v.viewTreeObserver.removeOnPreDrawListener(a)
                        v.viewTreeObserver.addOnPreDrawListener(a)
                    } catch (t: Throwable) { Log.e(TAG, "lock layout attach", t) }
                }
            })
            val veil = Paint()
            XposedHelpers.findAndHookMethod(ViewGroup::class.java, "dispatchDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!window.isInstance(p.thisObject)) return
                    try {
                        val root = p.thisObject as View
                        val a = arrangers[root] ?: return
                        if (!a.isActive) return
                        val canvas = p.args[0] as Canvas
                        val cfg = GlassSettings.config
                        val filterMode = cfg.lock.filterMode
                        val filterColor = cfg.lock.filterColor
                        WallpaperBackdrop.setLockFilter(filterMode, filterColor)
                        // Paint the filtered lock wallpaper non-destructively over ImageWallpaper while locked.
                        if (filterMode != WallpaperFilter.NATURAL) {
                            val wp = WallpaperBackdrop.sharedLock(root.context)
                            if (!wp.isLive && wp.bitmap != null) {
                                wp.layoutTo(root.width, root.height)
                                val fade = a.lockFadeAlpha()
                                if (fade >= 0.99f) wp.draw(canvas)
                                else if (fade > 0.01f) {
                                    val save = canvas.saveLayerAlpha(0f, 0f, root.width.toFloat(), root.height.toFloat(), (fade * 255).toInt())
                                    wp.draw(canvas)
                                    canvas.restoreToCount(save)
                                }
                            }
                        }
                        val st = GlassSettings[Surface.LOCK_WIDGET, root.context]
                        if (!st.enabled) return
                        val dimAlpha = Looks.backdropDim(Surface.LOCK_WIDGET, st) * a.lockFadeAlpha()
                        if (dimAlpha <= 0f) return
                        veil.color = Color.argb((dimAlpha * 255).toInt(), 0, 0, 0)
                        canvas.drawPaint(veil)
                    } catch (t: Throwable) { Log.e(TAG, "lock dim/filter", t) }
                }
            })
            XposedBridge.hookAllMethods(window, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as? ViewGroup ?: return
                    val e = p.args[0] as? MotionEvent ?: return
                    try {
                        val a = arrangers[v] ?: return
                        val m = p.method
                        val cancelChildren = {
                            val c = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                            try { XposedBridge.invokeOriginalMethod(m, v, arrayOf(c)) } finally { c.recycle() }
                            Unit
                        }
                        if (a.onStackTouch(e, cancelChildren)) {
                            p.result = true
                            return
                        }
                        val lp = a.longPress ?: LockLongPress(v).also { a.longPress = it }
                        if (lp.onTouch(e, a.isActive, cancelChildren)) p.result = true
                    } catch (t: Throwable) { Log.e(TAG, "lock touch", t) }
                }
            })
            val controller = "com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayoutController"
            XposedHelpers.findClassIfExists(controller, app.classLoader)?.let { cls ->
                val unshift = object : XC_MethodHook() {
                    override fun afterHookedMethod(p: MethodHookParam) {
                        try {
                            val v = XposedHelpers.getObjectField(p.thisObject, "mView") as View
                            val dy = arrangers[v.rootView]?.shifts?.dy(v) ?: return
                            if (dy != 0) p.result = (p.result as Int) - dy
                        } catch (t: Throwable) { Log.e(TAG, "stack bounds", t) }
                    }
                }
                XposedBridge.hookAllMethods(cls, "getTop", unshift)
                XposedBridge.hookAllMethods(cls, "getBottom", unshift)
            }
            // A notification update makes NotificationStackScrollLayout run a one-shot pre-draw
            // (mChildrenUpdater) after ours, and ViewState.applyToView resets each row's alpha,
            // scale and Z. Without re-applying the deck here, the peeking card is drawn full size
            // and opaque over the card in front for one frame on every update.
            val reapply = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.args.getOrNull(0) as? View ?: return
                    try { arrangers[v.rootView]?.reapplyDeck(v) } catch (t: Throwable) { Log.e(TAG, "deck reapply", t) }
                }
            }
            for (name in listOf("ViewState", "ExpandableViewState")) {
                XposedHelpers.findClassIfExists("com.android.systemui.statusbar.notification.stack.$name", app.classLoader)
                    ?.let { XposedBridge.hookAllMethods(it, "applyToView", reapply) }
            }
            Log.i(TAG, "lock layout hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "lock layout hooks failed", t)
        }

        try {
            val editor = XposedHelpers.findClassIfExists(LockEditor.ACTIVITY, app.classLoader) ?: return
            XposedHelpers.findAndHookMethod(editor, "onCreate", Bundle::class.java, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    try { LockEditor.install(p.thisObject as Activity) } catch (t: Throwable) { Log.e(TAG, "lock editor", t) }
                }
            })
            XposedHelpers.findAndHookMethod(editor, "clickApply", Boolean::class.javaPrimitiveType, object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    if (!p.hasThrowable()) LockEditor.applied(p.thisObject as Activity)
                }
            })
            XposedHelpers.findAndHookMethod(editor, "onDestroy", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    try { LockEditor.closed(p.thisObject as Activity) } catch (t: Throwable) { Log.e(TAG, "lock editor close", t) }
                }
            })
            Log.i(TAG, "lock editor hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "lock editor hooks failed", t)
        }
    }

    /** Saved original visual transforms of notification rows we fold into the iOS stack. */
    private class StackedCardState(
        val scaleX: Float,
        val scaleY: Float,
        val alpha: Float,
        val z: Float,
    )

    private class Arranger(private val root: View) : ViewTreeObserver.OnPreDrawListener {
        val shifts = ViewShifts()
        private val found = HashMap<String, View>()
        private val missedAt = HashMap<String, Long>()

        private var lockedAt = 0L
        private var locked = false
        private var printsAt = -PRINTS_EVERY_MS
        private var hasPrints = false
        private var actualHeight: Method? = null
        private var ownScrollMethod: Method? = null
        private var indicationWidth: Int? = null
        private var active: Boolean? = null
        val isActive get() = active == true
        var longPress: LockLongPress? = null
        private val widgetRow = WidgetRow(shifts)

        private var shownHeight = 0f
        private var wantedHeight = 0f
        private var wantedAt = 0L
        private val settle = Runnable { root.invalidate() }

        // iOS notification stack slide & expansion state (0 = compact bottom deck, 1 = expanded list + shrunk clock).
        private var stackExpand = 0f
        private var stackAnim: ValueAnimator? = null
        private var draggingStack = false
        private var touchOnStack = false
        private var stackDownX = 0f
        private var stackDownY = 0f
        private var stackStartExpand = 0f
        private var velocity: VelocityTracker? = null
        private val touchSlop = ViewConfiguration.get(root.context).scaledTouchSlop
        private val hitRect = Rect()
        private val stackedCards = WeakHashMap<View, StackedCardState>()
        /** The transform [applyIosStack] last gave each row, re-applied after stock state passes. */
        private val deckTargets = WeakHashMap<View, StackedCardState>()
        private val stackShifts = ViewShifts()
        private var lastDepthFilterMode = -1
        private var lastDepthFilterColor = 0

        fun lockFadeAlpha(): Float = view("keyguard_status_view")?.alpha?.coerceIn(0f, 1f) ?: 1f

        private var traceAt = 0L
        private var traceOn = false

        /** `adb shell setprop debug.nebula.decktrace 1` logs each deck re-apply (re-read every 2 s). */
        private fun deckTrace(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - traceAt > 2000) {
                traceAt = now
                traceOn = try { XposedHelpers.callStaticMethod(Class.forName("android.os.SystemProperties"), "getBoolean", "debug.nebula.decktrace", false) == true } catch (_: Throwable) { false }
            }
            return traceOn
        }

        fun reapplyDeck(v: View) {
            if (!isActive) return
            val t = deckTargets[v] ?: return
            if (v.scaleX == t.scaleX && v.scaleY == t.scaleY && v.alpha == t.alpha && v.translationZ == t.z) return
            if (deckTrace()) {
                Log.i(TAG, "decktrace reapply ${v.javaClass.simpleName}@${System.identityHashCode(v)} " +
                    "stock(s=${v.scaleX} a=${v.alpha} z=${v.translationZ}) -> deck(s=${t.scaleX} a=${t.alpha} z=${t.z})")
            }
            v.scaleX = t.scaleX
            v.scaleY = t.scaleY
            v.alpha = t.alpha
            v.translationZ = t.z
        }

        override fun onPreDraw(): Boolean {
            try { arrange() } catch (t: Throwable) { Log.e(TAG, "lock layout", t) }
            return true
        }

        private fun view(name: String): View? {
            found[name]?.let { if (it.isAttachedToWindow && it.rootView === root) return it }
            found.remove(name)
            val now = SystemClock.uptimeMillis()
            missedAt[name]?.let { if (now - it < RETRY_MS) return null }
            val id = root.resources.getIdentifier(name, "id", SYSTEMUI)
            val v = if (id != 0) root.findViewById<View>(id) else null
            if (v == null) missedAt[name] = now else {
                found[name] = v
                missedAt.remove(name)
            }
            return v
        }

        private fun isLocked(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - lockedAt > LOCKED_EVERY_MS) {
                lockedAt = now
                locked = (root.context.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
            }
            return locked
        }

        private fun hasPrints(): Boolean {
            val now = SystemClock.uptimeMillis()
            if (now - printsAt > PRINTS_EVERY_MS) {
                printsAt = now
                hasPrints = try {
                    @Suppress("DEPRECATION")
                    root.context.getSystemService(FingerprintManager::class.java)?.hasEnrolledFingerprints() == true
                } catch (_: Throwable) { false }
            }
            return hasPrints
        }

        /** Handles vertical drag on the lock-screen notification stack to expand/collapse the stack & shrink/grow the clock. */
        fun onStackTouch(e: MotionEvent, cancelChildren: () -> Unit): Boolean {
            val layout = GlassSettings.config.lock
            if (!isActive || !layout.bottomNotifications) {
                resetStackGesture()
                return false
            }
            val stack = view("notification_stack_scroller") as? ViewGroup ?: return false
            val rows = visibleRows(stack)
            if (rows.isEmpty() || (rows.size < 2 && !layout.hasCustomClock)) {
                resetStackGesture()
                return false
            }
            val dp = root.resources.displayMetrics.density
            val travel = (210f * dp).coerceAtLeast(120f)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    touchOnStack = rows.any { r -> r.getGlobalVisibleRect(hitRect) && hitRect.contains(e.x.toInt(), e.y.toInt()) }
                    draggingStack = false
                    stackDownX = e.x
                    stackDownY = e.y
                    stackStartExpand = stackExpand
                    velocity?.recycle()
                    velocity = VelocityTracker.obtain().also { it.addMovement(e) }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!touchOnStack) return false
                    velocity?.addMovement(e)
                    val dx = e.x - stackDownX
                    val dy = e.y - stackDownY
                    if (!draggingStack) {
                        if (abs(dx) > touchSlop && abs(dx) >= abs(dy)) {
                            // Horizontal swipe (notification dismiss): let SystemUI handle it untouched.
                            touchOnStack = false
                            return false
                        }
                        if (abs(dy) > touchSlop && abs(dy) > abs(dx) * 1.2f) {
                            val scrollingUp = dy < 0 && stackExpand < 0.99f
                            val scrollingDown = dy > 0 && stackExpand > 0.01f && stackScrollY(stack) <= 0
                            if (scrollingUp || scrollingDown) {
                                draggingStack = true
                                stackAnim?.cancel()
                                try { cancelChildren() } catch (_: Throwable) {}
                            } else {
                                touchOnStack = false
                                return false
                            }
                        }
                    }
                    if (draggingStack) {
                        stackExpand = (stackStartExpand - dy / travel).coerceIn(0f, 1f)
                        root.invalidate()
                        return true
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val wasDragging = draggingStack
                    velocity?.addMovement(e)
                    velocity?.computeCurrentVelocity(1000)
                    val vy = velocity?.yVelocity ?: 0f
                    resetStackGesture()
                    if (wasDragging) {
                        val expand = when {
                            vy < -450f -> true
                            vy > 450f -> false
                            else -> stackExpand >= 0.38f
                        }
                        animateStackTo(if (expand) 1f else 0f)
                        return true
                    }
                }
            }
            return false
        }

        private fun resetStackGesture() {
            touchOnStack = false
            draggingStack = false
            velocity?.recycle()
            velocity = null
        }

        private fun animateStackTo(target: Float) {
            stackAnim?.cancel()
            if (abs(stackExpand - target) < 0.005f) {
                stackExpand = target
                root.invalidate()
                return
            }
            stackAnim = ValueAnimator.ofFloat(stackExpand, target).apply {
                duration = 260
                interpolator = SPRING_EASE
                addUpdateListener {
                    stackExpand = it.animatedValue as Float
                    root.invalidate()
                }
                start()
            }
        }

        private fun stackScrollY(stack: View): Int {
            var m = ownScrollMethod
            if (m == null || !m.declaringClass.isInstance(stack)) {
                m = try { stack.javaClass.getMethod("getOwnScrollY") } catch (_: Throwable) { null }
                ownScrollMethod = m
            }
            return try { (m?.invoke(stack) as? Int)?.coerceAtLeast(0) ?: 0 } catch (_: Throwable) { 0 }
        }

        private fun applyDepthForegroundFilter(mode: Int, color: Int) {
            if (mode == lastDepthFilterMode && color == lastDepthFilterColor) return
            val fg = view("depth_foreground_wallpaper") ?: return
            try {
                val pf = XposedHelpers.findFieldIfExists(fg.javaClass, "mPaint") ?: return
                val paint = (pf.get(fg) as? Paint) ?: Paint().also { pf.set(fg, it) }
                paint.colorFilter = WallpaperFilter.filter(mode, color)
                fg.invalidate()
                lastDepthFilterMode = mode
                lastDepthFilterColor = color
            } catch (_: Throwable) {}
        }

        private fun arrange() {
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) return
            val status = view("keyguard_status_view")
            val on = status != null && status.isShown && w < h && (active == true || isLocked())
            if (on != active) {
                active = on
                if (!on) {
                    stackAnim?.cancel()
                    stackExpand = 0f
                    resetStackGesture()
                    restoreStackedCards()
                }
                Log.i(TAG, "lock layout active=$on")
            }
            if (!on) {
                longPress?.close()
                restoreStackedCards()
                shifts.restoreAll()
                return
            }
            val layout = GlassSettings.config.lock
            applyDepthForegroundFilter(layout.filterMode, layout.filterColor)

            val clock = (view("clock_view") as? TextView)?.takeIf { resizable(it) }
            styleTexts(layout, w, h, { view(it) }, clock != null)
            val custom = clock != null && clock.isShown && layout.hasCustomClock
            if (!custom && clock != null) LockClock.set(clock, null, 0, 0f)

            val low = layout.bottomWidgets
            val gap = h * GAP
            val block = view("ower_and_widget_ll") as? ViewGroup
            val widgets = view("keyguard_widget_container_id")?.takeIf { it.isShown && it.height > 0 && block != null }

            val stack = view("notification_stack_scroller") as? ViewGroup
            val edges = cardEdges(stack, w)
            val circle = if (low && widgets != null) widgets.height * WIDGET_CIRCLE_HALF * 2 else 0f
            if (circle > 0f) KeyguardGlass.setShortcutSize(circle)
            val columnGap = if (circle > 0f) ((edges[1] - WIDGET_COLUMNS * circle) / (WIDGET_COLUMNS - 1)).coerceAtLeast(gap / 2) else gap
            val shortcutRow = placeShortcuts(w, low, columnGap)
            var limit: Float
            if (low) {
                val d = view("left_button")?.let { KeyguardGlass.shortcutDiameter(it) } ?: circle
                limit = if (shortcutRow != null) shortcutRow - d / 2 - columnGap
                    else h * SHORTCUT_Y - w * SHORTCUT_SIZE / 2 - gap
            } else {
                val indication = view("keyguard_indication_area")
                limit = if (indication != null) shifts.topIn(indication, root).toFloat() else h * 0.82f
            }
            val sensor = if (hasPrints()) view("device_entry_icon_view")?.takeIf { it.height > 0 } else null
            var widgetShift: Float? = null
            if (low && widgets != null) {
                val half = widgets.height * WIDGET_CIRCLE_HALF
                var centre = if (shortcutRow != null) limit - half else h * WIDGET_Y
                if (sensor != null) centre = maxOf(centre, shifts.topIn(sensor, root) + sensor.height + half + 8)
                widgetShift = centre - (shifts.topIn(widgets, root) + widgets.height * WIDGET_CIRCLE_CENTRE)
                limit = centre - half - gap
            }
            if (sensor != null) limit = minOf(limit, shifts.topIn(sensor, root) - gap / 2)

            (widgets as? ViewGroup)?.let { widgetRow.arrange(it, root, edges[0], edges[1]) }

            val signature = view("clock_status_view")?.takeIf { it.isShown }
            var under = 0f
            if (signature != null) under += signature.height
            if (!low && widgets != null) under += widgets.height

            val rows = if (stack != null) visibleRows(stack) else emptyList()
            val dp = root.resources.displayMetrics.density
            val scrollProgress = if (stack != null) (stackScrollY(stack) / (180f * dp)).coerceIn(0f, 1f) else 0f
            val expand = if (layout.bottomNotifications && rows.isNotEmpty()) maxOf(stackExpand, scrollProgress) else 0f

            // Arrange stacked notification deck vs unfolded list, and compute effective stack height.
            val stackedHeight = if (stack != null && layout.bottomNotifications && rows.isNotEmpty()) {
                val clockFullBottom = (clock?.let { shifts.topIn(it, root) + LockClock.TOP_INSET + layout.clockHeightPx(h) } ?: (h * 0.35f)) + under + gap
                val compactRoom = (limit - clockFullBottom).coerceAtLeast(140f * dp)
                applyIosStack(stack, rows, compactRoom, expand, dp)
            } else {
                restoreStackedCards()
                val cards = stack?.let { cardSpan(it) }
                if (cards != null) cards[1] - cards[0] else 0f
            }

            var blockDown = 0f
            var underTop = block?.let { shifts.topIn(it, root).toFloat() } ?: 0f
            if (custom && clock != null) {
                val top = shifts.topIn(clock, root) + LockClock.TOP_INSET
                val chosen = layout.clockHeightPx(h)
                val minClockPx = minOf(chosen, h * LockLayout.MIN_CLOCK)
                val roomHeight = if (layout.bottomNotifications) {
                    // In compact bottom-stack mode (expand = 0), keep the full tall clock unless even 1 card won't fit;
                    // as the user slides the notification stack up (expand -> 1), smoothly shrink the clock to minClockPx!
                    val compactMax = (limit - (if (stackedHeight > 0f) minOf(stackedHeight, limit - top - minClockPx) + gap else 0f) - under - gap / 2 - top)
                        .coerceIn(minClockPx, chosen)
                    compactMax + (minClockPx - compactMax) * expand
                } else {
                    val room = limit - (if (stackedHeight > 0f) stackedHeight + gap else 0f) - under - gap / 2 - top
                    room.coerceIn(minClockPx, chosen)
                }
                val wanted = if (draggingStack || stackAnim?.isRunning == true || scrollProgress > 0.01f) {
                    // Smooth 120 fps height morphing while sliding!
                    shownHeight = roomHeight
                    wantedHeight = roomHeight
                    roomHeight
                } else {
                    settled(roomHeight)
                }
                val boxWidth = layout.clockWidthPx(w)
                LockClock.set(clock, wanted, layout.clockBoldness, boxWidth)
                blockDown = maxOf(0f, top + wanted + gap / 4 - underTop)
                underTop += blockDown
            }

            if (block != null) {
                val blockShift = widgetShift ?: blockDown
                for (g in listOf(block, block.parent as? ViewGroup)) {
                    if (g == null || !g.clipChildren) continue
                    g.clipChildren = false
                    g.clipToPadding = false
                    g.forceHasOverlappingRendering(false)
                    g.invalidate()
                }
                shifts.move(block, 0, blockShift.roundToInt())
                view("clock_status_view")?.let { if (it.parent === block) shifts.move(it, 0, (blockDown - blockShift).roundToInt()) }
            }

            if (stack != null) {
                var dy = 0f
                val cards = cardSpan(stack)
                if (cards != null) {
                    val natural = shifts.topIn(stack, root) + cards[0]
                    val highest = underTop + under + gap / 2
                    val top = when {
                        layout.bottomNotifications -> {
                            val compactTop = maxOf(highest, limit - stackedHeight)
                            // Sliding the stack up brings its top smoothly up to right under the shrunk clock!
                            compactTop + (highest - compactTop) * expand
                        }
                        low || custom -> highest
                        else -> natural
                    }
                    dy = top - natural
                }
                shifts.move(stack, 0, dy.roundToInt())
            }
        }

        /**
         * Folds overflowing lock-screen notification cards into an iOS-style 3D peeking stack at
         * [expand] == 0, and smoothly unfolds them to their natural positions as [expand] -> 1.
         * @return the effective visual height of the stack in px
         */
        private fun applyIosStack(stack: ViewGroup, rows: List<View>, compactRoom: Float, expand: Float, dp: Float): Float {
            if (stack.clipChildren) {
                stack.clipChildren = false
                stack.clipToPadding = false
            }
            val firstTop = rows[0].let { stackShifts.top(it) + it.translationY }
            // How many leading cards fit at full size in compactRoom (at least 1, at most 2 when 3+ exist so the stack effect shows!).
            var shownCount = 1
            if (rows.size >= 2) {
                val twoBottom = stackShifts.top(rows[1]) + rows[1].translationY + heightOf(rows[1]) - firstTop
                val reservePeek = if (rows.size > 2) 24f * dp else 0f
                if (twoBottom + reservePeek <= compactRoom && rows.size != 2) {
                    shownCount = 2
                } else if (rows.size == 2 && twoBottom <= compactRoom * 0.85f) {
                    shownCount = 2
                }
            }

            val anchorRow = rows[(shownCount - 1).coerceAtMost(rows.lastIndex)]
            val anchorNaturalBottom = stackShifts.top(anchorRow) + anchorRow.translationY + heightOf(anchorRow)
            var maxBottom = anchorNaturalBottom

            val activeSet = HashSet<View>(rows.size)
            for ((idx, row) in rows.withIndex()) {
                activeSet.add(row)
                val saved = stackedCards.getOrPut(row) {
                    StackedCardState(row.scaleX, row.scaleY, row.alpha, row.translationZ)
                }
                if (idx < shownCount) {
                    stackShifts.move(row, 0, 0)
                    row.scaleX = saved.scaleX
                    row.scaleY = saved.scaleY
                    row.alpha = saved.alpha
                    row.translationZ = 6f * dp * (1f - expand)
                    deckTargets[row] = StackedCardState(row.scaleX, row.scaleY, row.alpha, row.translationZ)
                    val b = stackShifts.top(row) + row.translationY + heightOf(row)
                    maxBottom = maxOf(maxBottom, b)
                } else {
                    val deckDepth = idx - shownCount + 1   // 1 = first peeking card, 2 = second peeking card, 3+ = hidden behind
                    val h = heightOf(row).toFloat()
                    val naturalTop = stackShifts.top(row) + row.translationY
                    val naturalBottom = naturalTop + h
                    val peekPx = when (deckDepth) {
                        1 -> 13f * dp
                        2 -> 23f * dp
                        else -> 26f * dp
                    }
                    val targetCollapsedTop = anchorNaturalBottom + peekPx - h
                    val dy = ((targetCollapsedTop - naturalTop) * (1f - expand)).roundToInt()
                    stackShifts.move(row, 0, dy)

                    val targetScale = when (deckDepth) {
                        1 -> 0.945f
                        2 -> 0.89f
                        else -> 0.85f
                    }
                    val targetAlpha = when (deckDepth) {
                        1 -> 0.86f
                        2 -> 0.62f
                        else -> 0f
                    }
                    val s = targetScale + (1f - targetScale) * expand
                    val a = targetAlpha + (1f - targetAlpha) * expand
                    row.pivotX = row.width / 2f
                    row.pivotY = h
                    row.scaleX = s
                    row.scaleY = s
                    row.alpha = a.coerceIn(0f, 1f)
                    row.translationZ = (-deckDepth * 2.5f * dp) * (1f - expand)
                    deckTargets[row] = StackedCardState(row.scaleX, row.scaleY, row.alpha, row.translationZ)

                    val curBottom = naturalBottom + dy
                    if (a > 0.05f) maxBottom = maxOf(maxBottom, curBottom)
                }
            }
            // Restore any rows that left the visible list.
            for ((v, st) in stackedCards.entries.toList()) {
                if (v !in activeSet) {
                    stackedCards.remove(v)
                    deckTargets.remove(v)
                    stackShifts.move(v, 0, 0)
                    v.scaleX = st.scaleX
                    v.scaleY = st.scaleY
                    v.alpha = st.alpha
                    v.translationZ = st.z
                }
            }
            return (maxBottom - firstTop).coerceAtLeast(0f)
        }

        private fun restoreStackedCards() {
            stackShifts.restoreAll()
            for ((v, st) in stackedCards.entries.toList()) {
                v.scaleX = st.scaleX
                v.scaleY = st.scaleY
                v.alpha = st.alpha
                v.translationZ = st.z
            }
            stackedCards.clear()
            deckTargets.clear()
        }

        private fun visibleRows(stack: ViewGroup): List<View> {
            val out = ArrayList<View>()
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility != View.VISIBLE || c.height <= 0) continue
                // Skip invisible empty/footer views; include previously tucked deck cards (saved in stackedCards).
                if (c.alpha < 0.01f && c !in stackedCards) continue
                val h = heightOf(c)
                if (h <= 0) continue
                val name = c.javaClass.simpleName
                if (name.contains("Footer") || name.contains("EmptyShade") || name.contains("Shelf")) continue
                out.add(c)
            }
            out.sortBy { stackShifts.top(it) + it.translationY }
            return out
        }

        private fun placeShortcuts(w: Int, low: Boolean, columnGap: Float): Float? {
            val margin = w * WidgetRow.CARD_MARGIN
            var row: Float? = null
            var d = 0f
            for ((name, left) in listOf("left_button" to true, "camera_button" to false)) {
                val v = view(name) ?: continue
                if (v.width == 0) continue
                d = KeyguardGlass.shortcutDiameter(v) ?: minOf(v.width, v.height).toFloat()
                val centre = if (left) margin + d / 2 else w - margin - d / 2
                shifts.move(v, (centre - (shifts.leftIn(v, root) + v.width / 2f)).roundToInt(), 0)
                if (v.isShown) row = shifts.topIn(v, root) + v.height / 2f
            }
            val between = low && row != null
            view("keyguard_indication_area")?.let {
                val dy = if (between && it.height > 0) (row!! - (shifts.topIn(it, root) + it.height / 2f)).roundToInt() else 0
                shifts.move(it, 0, dy)
            }
            (view("keyguard_indication_text_bottom") as? TextView)?.let { tv ->
                val room = (w - 2 * margin - 2 * d - 2 * columnGap).toInt()
                if (between && room > 0 && tv.maxWidth != room) {
                    if (indicationWidth == null) indicationWidth = tv.maxWidth
                    tv.maxWidth = room
                } else if (!between) {
                    indicationWidth?.let { tv.maxWidth = if (it > 0) it else Int.MAX_VALUE }
                    indicationWidth = null
                }
            }
            return row
        }

        private fun cardEdges(stack: ViewGroup?, screen: Int): FloatArray {
            if (stack != null) {
                for (i in 0 until stack.childCount) {
                    val c = stack.getChildAt(i)
                    if (c.width in (screen / 2) until screen) {
                        return floatArrayOf((shifts.leftIn(stack, root) + shifts.left(c)).toFloat(), c.width.toFloat())
                    }
                }
            }
            val margin = screen * WidgetRow.CARD_MARGIN
            return floatArrayOf(margin, screen - 2 * margin)
        }

        private fun cardSpan(stack: ViewGroup): FloatArray? {
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility != View.VISIBLE || (c.alpha < 0.01f && c !in stackedCards) || c.height <= 0) continue
                val height = heightOf(c)
                if (height <= 0) continue
                val t = stackShifts.top(c) + c.translationY
                top = minOf(top, t)
                bottom = maxOf(bottom, t + height)
            }
            return if (bottom > top) floatArrayOf(top, bottom) else null
        }

        private fun heightOf(v: View): Int {
            var m = actualHeight
            if (m == null || !m.declaringClass.isInstance(v)) {
                m = try { v.javaClass.getMethod("getActualHeight") } catch (_: NoSuchMethodException) { return v.height }
                actualHeight = m
            }
            return m!!.invoke(v) as Int
        }

        private fun settled(wanted: Float): Float {
            val stepped = (wanted / HEIGHT_STEP).toInt() * HEIGHT_STEP
            val now = SystemClock.uptimeMillis()
            if (shownHeight == 0f) shownHeight = stepped
            if (stepped != wantedHeight) {
                wantedHeight = stepped
                wantedAt = now
                root.removeCallbacks(settle)
                root.postDelayed(settle, SETTLE_MS + 16)
            } else if (now - wantedAt >= SETTLE_MS) {
                shownHeight = stepped
            }
            return shownHeight
        }
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SYSTEMUI = "com.android.systemui"
        private var installed = false

        private const val RETRY_MS = 1000L
        private const val LOCKED_EVERY_MS = 400L
        private const val PRINTS_EVERY_MS = 10_000L
        private const val SETTLE_MS = 120L
        private const val HEIGHT_STEP = 8f

        private const val SHORTCUT_Y = 0.907f
        internal const val SHORTCUT_SIZE = 0.149f
        private const val WIDGET_Y = 0.817f
        private const val WIDGET_COLUMNS = 5
        private const val GAP = 0.0135f

        private const val WIDGET_CIRCLE_CENTRE = 0.54f
        private const val WIDGET_CIRCLE_HALF = 0.34f
        private val SPRING_EASE = PathInterpolator(0.2f, 0f, 0f, 1f)

        internal fun resizable(clock: View): Boolean {
            var p = clock.parent
            while (p is View) {
                if (p.javaClass.simpleName == "LockScreenClockDefault") return true
                p = p.parent
            }
            return false
        }

        private class TextOrigin(var size: Float, var applied: Float, val face: Typeface?, val maxWidth: Int, val maxLines: Int)

        private val textOrigins = WeakHashMap<TextView, TextOrigin>()

        internal val DATE_IDS = listOf("date_view", "lunar_date_widget_horiz", "day", "month", "lunar_date_widget")
        internal val NOTE_IDS = listOf("owner_info")
        internal val CLOCK_TEXT_IDS = listOf("hours", "minute", "clock_hour_view", "clock_minute_view", "clock_view")

        /** Exact Paint-measured pixel extent (width, height) of the fitted date and custom-text groups. */
        internal class FittedSizes(val date: Pair<Float, Float>?, val note: Pair<Float, Float>?)

        internal fun textViews(find: (String) -> View?, ids: List<String>): List<TextView> =
            ids.mapNotNull { find(it) as? TextView }.filter { it.isShown && (it.width > 0 || !it.text.isNullOrEmpty()) }

        internal fun boldnessToWeight(b: Int) = (100 + b * 8).coerceIn(100, 900)

        internal fun styleOf(views: List<View>): String? {
            var p: Any? = views.firstOrNull()?.parent
            while (p is View) {
                val n = p.javaClass.simpleName
                if (n.startsWith("LockScreenClock")) return n
                p = p.parent
            }
            return null
        }

        internal fun styleTexts(layout: LockLayout, w: Int, h: Int, find: (String) -> View?, resizableClock: Boolean): FittedSizes {
            val enlarged = ArrayList<TextView>()
            val dateViews = textViews(find, DATE_IDS)
            val (dateEnlarged, dateDrawn) = fit(dateViews, layout.dateW * w / 1000f, layout.dateH * h / 1000f, layout.dateWeight, uniform = true)
            enlarged += dateEnlarged

            val notes = textViews(find, NOTE_IDS)
            val (noteEnlarged, noteDrawn) = fit(notes, layout.noteW * w / 1000f, layout.noteH * h / 1000f, layout.noteWeight)
            enlarged += noteEnlarged

            val digits = if (resizableClock) emptyList() else textViews(find, CLOCK_TEXT_IDS)
            if (!resizableClock) {
                val box = styleOf(digits)?.let { layout.styleBoxes[it] }
                clockBoldness = layout.clockBoldness
                enlarged += fit(digits, (box?.first ?: 0) * w / 1000f, (box?.second ?: 0) * h / 1000f, 0, stretch = true).first
            }

            val root = (enlarged.firstOrNull() ?: dateViews.firstOrNull() ?: notes.firstOrNull() ?: digits.firstOrNull())?.rootView
            val keep = HashSet<View>()
            for (tv in enlarged) grow(tv, keep)
            for ((v, saved) in fixedFrames.entries.toList()) {
                if (v in keep || (root != null && v.rootView !== root)) continue
                fixedFrames.remove(v)
                v.layoutParams?.let { lp ->
                    lp.width = saved.width
                    lp.height = saved.height
                    if (lp is ViewGroup.MarginLayoutParams) {
                        lp.leftMargin = saved.leftMargin
                        lp.rightMargin = saved.rightMargin
                        lp.marginStart = saved.marginStart
                        lp.marginEnd = saved.marginEnd
                    }
                    v.layoutParams = lp
                }
            }
            return FittedSizes(dateDrawn, noteDrawn)
        }

        private val measure = Paint()
        private val at = IntArray(2)

        private fun fit(
            views: List<TextView>,
            boxW: Float,
            boxH: Float,
            weight: Int,
            stretch: Boolean = false,
            uniform: Boolean = false,
        ): Pair<List<TextView>, Pair<Float, Float>?> {
            if (views.isEmpty()) return emptyList<TextView>() to null
            for (tv in views) origin(tv)
            val boxed = boxW > 0f && boxH > 0f
            val same = uniform && boxed
            val lead = textOrigins[views[0]]!!
            fun baseSize(tv: TextView) = if (same) lead.size else textOrigins[tv]!!.size
            for (tv in views) weigh(tv, weight, if (same) lead.face else null)

            var scale = 1f
            var baseTotalW = 0f
            var baseTotalH = 0f
            if (boxed) {
                class Row(var top: Float, var bottom: Float, var width: Float, var line: Float)
                val rows = ArrayList<Row>()
                val gapPx = if (views.size > 1) 8f * views[0].resources.displayMetrics.density else 0f
                for (tv in views.sortedBy { tv -> tv.getLocationInWindow(at); at[1] }) {
                    tv.getLocationInWindow(at)
                    val mid = at[1] + tv.height / 2f
                    measure.set(tv.paint)
                    measure.textSize = baseSize(tv)
                    val str = tv.text?.toString().orEmpty()
                    val lines = str.split('\n')
                    val textW = lines.maxOfOrNull { measure.measureText(it) } ?: 0f
                    val width = textW + tv.compoundPaddingLeft + tv.compoundPaddingRight
                    val fm = measure.fontMetrics
                    val singleLineH = if (tv.includeFontPadding) fm.bottom - fm.top else fm.descent - fm.ascent
                    val line = singleLineH * lines.size.coerceAtLeast(1) + tv.compoundPaddingTop + tv.compoundPaddingBottom
                    val row = rows.firstOrNull { mid in it.top..it.bottom }
                    if (row == null) rows.add(Row(at[1].toFloat(), (at[1] + tv.height.coerceAtLeast(1)).toFloat(), width, line))
                    else { row.width += gapPx + width; row.line = maxOf(row.line, line) }
                }
                baseTotalW = rows.maxOf { it.width }
                baseTotalH = rows.sumOf { it.line.toDouble() }.toFloat()
                if (baseTotalW > 0f && baseTotalH > 0f) {
                    scale = minOf(boxW / baseTotalW, boxH / baseTotalH).coerceIn(0.2f, 10f)
                }
            }
            if (stretch) {
                val rows = rowsOf(views)
                for (row in rows) {
                    val natural = row.sumOf { naturalWidth(it).toDouble() }.toFloat().coerceAtLeast(1f)
                    for (tv in row) {
                        val o = textOrigins[tv]!!
                        if (abs(tv.textSize - o.size) > 0.5f) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o.size)
                        o.applied = o.size
                        if (!boxed) { LockClock.set(tv, null, 0, 0f); roomBelow(tv, 0f); continue }
                        val rowH = boxH / rows.size
                        LockClock.set(tv, rowH - 2 * LockClock.TOP_INSET, clockBoldness, boxW * naturalWidth(tv) / natural,
                            tv.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                        roomBelow(tv, rowH)
                    }
                }
                return (if (boxed) views else emptyList()) to (if (boxed) (boxW to boxH) else null)
            }
            for (tv in views) {
                val o = textOrigins[tv]!!
                // Lift Nubia's narrow maxWidth / wrapping while boxed so enlarged text scales freely across the box!
                if (boxed) {
                    if (tv.maxWidth != Int.MAX_VALUE) tv.maxWidth = Int.MAX_VALUE
                    val lineCount = tv.text?.toString()?.count { it == '\n' }?.plus(1) ?: 1
                    if (tv.maxLines != lineCount) tv.maxLines = lineCount
                } else {
                    if (tv.maxWidth != o.maxWidth && o.maxWidth > 0) tv.maxWidth = o.maxWidth
                    if (tv.maxLines != o.maxLines && o.maxLines > 0) tv.maxLines = o.maxLines
                }
                val size = baseSize(tv) * scale
                if (abs(tv.textSize - size) > 0.5f) {
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
                    tv.requestLayout()
                }
                o.applied = size
            }
            if (uniform) alignBaselines(views, same)
            val rows = if (boxed) rowsOf(views) else listOf(views)
            for (row in rows) for (tv in row) centreIn(tv, if (boxed) boxH / rows.size else 0f)
            val drawn = if (boxed && baseTotalW > 0f && baseTotalH > 0f) {
                (baseTotalW * scale) to (baseTotalH * scale)
            } else null
            return (if (scale > 1f || boxed) views else emptyList()) to drawn
        }

        private val textFrames = WeakHashMap<TextView, Pair<Int, Int>>()

        private fun centreIn(tv: TextView, height: Float) {
            if (height <= 0f) {
                textFrames.remove(tv)?.let { (min, gravity) ->
                    if (tv.minHeight != min) tv.minHeight = min
                    if (tv.gravity != gravity) tv.gravity = gravity
                }
                return
            }
            val (_, gravity) = textFrames.getOrPut(tv) { tv.minHeight to tv.gravity }
            val want = Math.round(height)
            if (tv.minHeight != want) tv.minHeight = want
            val centred = (gravity and android.view.Gravity.VERTICAL_GRAVITY_MASK.inv()) or android.view.Gravity.CENTER_VERTICAL
            if (tv.gravity != centred) tv.gravity = centred
        }

        private val baselined: MutableSet<TextView> = java.util.Collections.newSetFromMap(WeakHashMap())

        private fun alignBaselines(views: List<TextView>, on: Boolean) {
            if (!on) {
                for (tv in views) if (baselined.remove(tv)) tv.translationY = 0f
                return
            }
            for (row in rowsOf(views)) {
                val lead = row.minByOrNull { views.indexOf(it) } ?: continue
                if (lead.baseline < 0) continue
                lead.getLocationInWindow(at)
                val leadBase = at[1] - lead.translationY + lead.baseline
                for (tv in row) {
                    if (tv === lead || tv.baseline < 0) continue
                    tv.getLocationInWindow(at)
                    val dy = leadBase - (at[1] - tv.translationY + tv.baseline)
                    if (abs(tv.translationY - dy) > 0.5f) { tv.translationY = dy; baselined.add(tv) }
                }
            }
        }

        private val bottomMargins = WeakHashMap<View, Int>()
        private var clockBoldness = LockLayout.DEFAULT_BOLDNESS

        private fun roomBelow(tv: TextView, height: Float) {
            val lp = tv.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            if (height <= 0f) {
                bottomMargins.remove(tv)?.let { m -> lp.bottomMargin = m; tv.layoutParams = lp }
                return
            }
            if (tv.height == 0) return
            val base = bottomMargins.getOrPut(tv) { lp.bottomMargin }
            val want = base + maxOf(0, Math.round(height - tv.height))
            if (lp.bottomMargin != want) { lp.bottomMargin = want; tv.layoutParams = lp }
        }

        private fun naturalWidth(tv: TextView): Float {
            measure.set(tv.paint)
            measure.textSize = textOrigins[tv]?.size ?: tv.textSize
            return measure.measureText(tv.text?.toString().orEmpty())
        }

        private fun rowsOf(views: List<TextView>): List<List<TextView>> {
            val rows = ArrayList<Triple<Float, Float, ArrayList<TextView>>>()
            for (tv in views.sortedBy { tv -> tv.getLocationInWindow(at); at[1] }) {
                tv.getLocationInWindow(at)
                val mid = at[1] + tv.height / 2f
                val row = rows.firstOrNull { mid in it.first..it.second }
                if (row == null) rows.add(Triple(at[1].toFloat(), (at[1] + tv.height).toFloat(), arrayListOf(tv))) else row.third.add(tv)
            }
            return rows.map { it.third }
        }

        private class SavedFrame(
            val width: Int,
            val height: Int,
            val leftMargin: Int,
            val rightMargin: Int,
            val marginStart: Int,
            val marginEnd: Int,
        )

        private val fixedFrames = WeakHashMap<View, SavedFrame>()

        /**
         * Frees fixed heights, narrow widths and restrictive side margins on text containers up to
         * the screen-sized ConstraintLayout so resized date / custom text fills wide & tall boxes cleanly.
         */
        private fun grow(tv: TextView, keep: MutableSet<View>) {
            val screen = tv.rootView.height
            tv.ellipsize = null
            var child: View = tv
            repeat(10) {
                val parent = child.parent as? ViewGroup ?: return
                if (screen > 0 && parent.height >= screen * 0.6f) return
                if (parent.clipChildren) {
                    parent.clipChildren = false
                    parent.clipToPadding = false
                }
                val lp = child.layoutParams
                if (lp != null) {
                    val m = lp as? ViewGroup.MarginLayoutParams
                    val id = try { if (child.id != View.NO_ID) child.resources.getResourceEntryName(child.id) else null } catch (_: Throwable) { null }
                    val clearSideMargins = m != null && (id == "date_week_lunar_area" || id == "owner_info_border" || child === tv)
                    val needWidth = lp.width > 0
                    val needHeight = lp.height > 0
                    val needMargin = clearSideMargins && (m!!.leftMargin > 0 || m.rightMargin > 0 || m.marginStart > 0 || m.marginEnd > 0)
                    if (needWidth || needHeight || needMargin) {
                        fixedFrames.putIfAbsent(
                            child,
                            SavedFrame(
                                lp.width, lp.height,
                                m?.leftMargin ?: 0, m?.rightMargin ?: 0,
                                m?.marginStart ?: 0, m?.marginEnd ?: 0,
                            ),
                        )
                        if (needWidth) lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                        if (needHeight) lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        if (needMargin && m != null) {
                            m.leftMargin = 0; m.rightMargin = 0; m.marginStart = 0; m.marginEnd = 0
                        }
                        child.layoutParams = lp
                    }
                    if (child in fixedFrames) keep.add(child)
                }
                child = parent
            }
        }

        private fun origin(tv: TextView) {
            val o = textOrigins[tv]
            if (o == null || abs(tv.textSize - o.applied) > 0.5f) {
                textOrigins[tv] = TextOrigin(tv.textSize, tv.textSize, tv.typeface, tv.maxWidth, tv.maxLines)
            }
        }

        private fun weigh(tv: TextView, weight: Int, family: Typeface? = null) {
            val o = textOrigins[tv] ?: return
            val face = tv.typeface ?: Typeface.DEFAULT
            val want = if (weight > 0) weight else ((family ?: o.face) ?: Typeface.DEFAULT).weight
            if (family != null) {
                val target = Typeface.create(family, want, false)
                if (face != target) tv.typeface = target
            } else if (face.weight != want) tv.typeface = Typeface.create(face, want, face.isItalic)
        }
    }
}
