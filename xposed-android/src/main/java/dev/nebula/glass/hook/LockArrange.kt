package dev.nebula.glass.hook

import android.app.Activity
import android.app.KeyguardManager
import android.content.Context
import android.graphics.Typeface
import android.hardware.fingerprint.FingerprintManager
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
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
import java.lang.reflect.Method
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * The iOS arrangement of Nubia's lock screen: the resized clock with bolder date lines, widgets
 * on a five-column grid between the cards' edges, widgets and shortcuts at the bottom, and notifications stacked up
 * from the bottom.
 *
 * Nothing is re-laid out (see [ViewShifts]). As on iOS, the clock gives up height when the cards
 * need the room, and takes it back when they go.
 */
class LockArrange : IXposedHookLoadPackage {
    private val arrangers = WeakHashMap<View, Arranger>()

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != SYSTEMUI || app.processName != SYSTEMUI) return
        if (installed) return   // SystemUI calls this twice
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
            // The lock screen's dim (user request 2026-11-24): over the wallpaper, under everything the
            // window draws. Only while the lock layout is active, so not over the pulled-down shade.
            val veil = android.graphics.Paint()
            XposedHelpers.findAndHookMethod(ViewGroup::class.java, "dispatchDraw", android.graphics.Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    if (!window.isInstance(p.thisObject)) return
                    try {
                        if (arrangers[p.thisObject as View]?.isActive != true || !GlassSettings[Surface.LOCK_WIDGET].enabled) return
                        val a = Looks.backdropDim(Surface.LOCK_WIDGET, GlassSettings[Surface.LOCK_WIDGET])
                        if (a <= 0f) return
                        veil.color = android.graphics.Color.argb((a * 255).toInt(), 0, 0, 0)
                        (p.args[0] as android.graphics.Canvas).drawPaint(veil)
                    } catch (t: Throwable) { Log.e(TAG, "lock dim", t) }
                }
            })
            // Long-press on an empty part of the lock screen: Nubia's editor or this module's settings.
            XposedBridge.hookAllMethods(window, "dispatchTouchEvent", object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as? ViewGroup ?: return
                    val e = p.args[0] as? android.view.MotionEvent ?: return
                    try {
                        val a = arrangers[v] ?: return
                        val lp = a.longPress ?: LockLongPress(v).also { a.longPress = it }
                        val m = p.method
                        if (lp.onTouch(e, a.isActive) {
                                val c = android.view.MotionEvent.obtain(e).apply { action = android.view.MotionEvent.ACTION_CANCEL }
                                try { XposedBridge.invokeOriginalMethod(m, v, arrayOf(c)) } finally { c.recycle() }
                            }) p.result = true
                    } catch (t: Throwable) { Log.e(TAG, "lock long-press", t) }
                }
            })
            // Nubia works out how many cards fit from these; they must not see the stack's shift.
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

    private class Arranger(private val root: View) : ViewTreeObserver.OnPreDrawListener {
        val shifts = ViewShifts()
        private val found = HashMap<String, View>()
        private val missedAt = HashMap<String, Long>()

        private var lockedAt = 0L
        private var locked = false
        private var printsAt = -PRINTS_EVERY_MS
        private var hasPrints = false
        private var actualHeight: Method? = null
        private var indicationWidth: Int? = null
        private var active: Boolean? = null
        val isActive get() = active == true
        var longPress: LockLongPress? = null
        private val widgetRow = WidgetRow(shifts)

        // The clock's adaptive height settles before it is used: each new height rebuilds its glass.
        private var shownHeight = 0f
        private var wantedHeight = 0f
        private var wantedAt = 0L
        private val settle = Runnable { root.invalidate() }

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

        private fun arrange() {
            val w = root.width
            val h = root.height
            if (w <= 0 || h <= 0) return
            val status = view("keyguard_status_view")
            // Portrait lock screen only; the pulled shade and heads-ups share this window.
            // Once arranged, stay so until the status view goes: the lock ends before the unlock fade does.
            val on = status != null && status.isShown && w < h && (active == true || isLocked())
            if (on != active) {
                active = on
                Log.i(TAG, "lock layout active=$on")
            }
            if (!on) {
                longPress?.close()
                shifts.restoreAll()
                return
            }
            val layout = GlassSettings.config.lock

            val clock = (view("clock_view") as? TextView)?.takeIf { resizable(it) }
            styleTexts(layout, w, h, { view(it) }, clock != null)
            val custom = clock != null && clock.isShown && layout.hasCustomClock
            if (!custom && clock != null) LockClock.set(clock, null, 0, 0f)

            val low = layout.bottomWidgets
            val gap = h * GAP
            val block = view("ower_and_widget_ll") as? ViewGroup
            val widgets = view("keyguard_widget_container_id")?.takeIf { it.isShown && it.height > 0 && block != null }

            // From the bottom up: shortcuts, widgets, then the cards.
            // The corner shortcuts stay at Nubia's height, the size of the round widgets, under the
            // first and last widget column; the charging line sits between them (user requests 2026-11-24).
            val stack = view("notification_stack_scroller") as? ViewGroup
            val edges = cardEdges(stack, w)
            val circle = if (low && widgets != null) widgets.height * WIDGET_CIRCLE_HALF * 2 else 0f
            if (circle > 0f) KeyguardGlass.setShortcutSize(circle)
            val columnGap = if (circle > 0f) ((edges[1] - WIDGET_COLUMNS * circle) / (WIDGET_COLUMNS - 1)).coerceAtLeast(gap / 2) else gap
            val shortcutRow = placeShortcuts(w, low, columnGap)
            var limit: Float
            if (low) {
                // Widgets and cards stack up from just above the shortcuts.
                val d = view("left_button")?.let { KeyguardGlass.shortcutDiameter(it) } ?: circle
                limit = if (shortcutRow != null) shortcutRow - d / 2 - columnGap
                    else h * SHORTCUT_Y - w * SHORTCUT_SIZE / 2 - gap
            } else {
                val indication = view("keyguard_indication_area")
                limit = if (indication != null) shifts.topIn(indication, root).toFloat() else h * 0.82f
            }
            // With prints enrolled, stay clear of the in-display sensor.
            val sensor = if (hasPrints()) view("device_entry_icon_view")?.takeIf { it.height > 0 } else null
            var widgetShift: Float? = null
            if (low && widgets != null) {
                val half = widgets.height * WIDGET_CIRCLE_HALF
                // One widget-column gap above the shortcuts, like another row of the grid.
                var centre = if (shortcutRow != null) limit - half else h * WIDGET_Y
                if (sensor != null) centre = maxOf(centre, shifts.topIn(sensor, root) + sensor.height + half + 8)
                widgetShift = centre - (shifts.topIn(widgets, root) + widgets.height * WIDGET_CIRCLE_CENTRE)
                limit = centre - half - gap
            }
            if (sensor != null) limit = minOf(limit, shifts.topIn(sensor, root) - gap / 2)

            (widgets as? ViewGroup)?.let { widgetRow.arrange(it, root, edges[0], edges[1]) }
            val cards = stack?.let { cardSpan(it) }
            val cardsHeight = if (cards != null) cards[1] - cards[0] else 0f

            // What sits under the clock: the signature, and the widgets while they stay up there.
            val signature = view("clock_status_view")?.takeIf { it.isShown }
            var under = 0f
            if (signature != null) under += signature.height
            if (!low && widgets != null) under += widgets.height

            var blockDown = 0f
            var underTop = block?.let { shifts.topIn(it, root).toFloat() } ?: 0f
            if (custom && clock != null) {
                val top = shifts.topIn(clock, root) + LockClock.TOP_INSET
                val chosen = layout.clockHeightPx(h)
                val room = limit - (if (cardsHeight > 0) cardsHeight + gap else 0f) - under - gap / 2 - top
                val wanted = settled(room.coerceIn(minOf(chosen, h * LockLayout.MIN_CLOCK), chosen))
                val boxWidth = layout.clockWidthPx(w)
                LockClock.set(clock, wanted, layout.clockBoldness, boxWidth)
                // The area is the box; the digits are centred in it ([LockClock.draw]).
                val height = wanted
                // Nubia's box is taller than the smallest digits: follow the digits down, never up.
                blockDown = maxOf(0f, top + height + gap / 4 - underTop)
                underTop += blockDown
            }

            if (block != null) {
                val blockShift = widgetShift ?: blockDown
                // The signature stays under the clock while the block goes down: out of the block's
                // box, which is its parent's clipping to give up.
                for (g in listOf(block, block.parent as? ViewGroup)) {
                    if (g == null || !g.clipChildren) continue
                    g.clipChildren = false
                    g.clipToPadding = false
                    g.forceHasOverlappingRendering(false)   // or the unlock fade clips what moved out
                    g.invalidate()
                }
                shifts.move(block, 0, blockShift.roundToInt())
                view("clock_status_view")?.let { if (it.parent === block) shifts.move(it, 0, (blockDown - blockShift).roundToInt()) }
            }

            if (stack != null) {
                var dy = 0f
                if (cards != null) {
                    val natural = shifts.topIn(stack, root) + cards[0]
                    val highest = underTop + under + gap / 2
                    val top = when {
                        layout.bottomNotifications -> maxOf(highest, limit - cardsHeight)
                        low || custom -> highest   // close the space the widgets left, or follow the clock
                        else -> natural
                    }
                    dy = top - natural
                }
                shifts.move(stack, 0, dy.roundToInt())
            }
        }

        /**
         * The corner shortcuts at Nubia's height, their circles' outer edges on the cards' edges, and
         * the charging / unlock line centred between them ([low], widgets at the bottom). Returns the
         * shortcuts' centre line, or null when they aren't laid out.
         */
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

        /** The cards' left edge and width, which the widget row lines up with. */
        private fun cardEdges(stack: ViewGroup?, screen: Int): FloatArray {
            if (stack != null) {
                for (i in 0 until stack.childCount) {
                    val c = stack.getChildAt(i)
                    // Any card's box will do, shown or not: they all have the cards' width.
                    if (c.width in (screen / 2) until screen) {
                        return floatArrayOf((shifts.leftIn(stack, root) + shifts.left(c)).toFloat(), c.width.toFloat())
                    }
                }
            }
            val margin = screen * WidgetRow.CARD_MARGIN
            return floatArrayOf(margin, screen - 2 * margin)
        }

        /** Top and bottom of what the stack shows (player, rows, shelf), in its own coordinates. */
        private fun cardSpan(stack: ViewGroup): FloatArray? {
            var top = Float.MAX_VALUE
            var bottom = -Float.MAX_VALUE
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility != View.VISIBLE || c.alpha < 0.01f || c.height <= 0) continue
                val height = heightOf(c)
                if (height <= 0) continue
                val t = c.top + c.translationY
                top = minOf(top, t)
                bottom = maxOf(bottom, t + height)
            }
            return if (bottom > top) floatArrayOf(top, bottom) else null
        }

        /** ExpandableView.getActualHeight(): a row's view is as tall as its expanded form. */
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

        // Measured on the iOS 27 captures, as shares of the screen's width or height.
        private const val SHORTCUT_X = 0.19f
        private const val SHORTCUT_Y = 0.907f
        /** Shortcut circle diameter, share of the width. */
        internal const val SHORTCUT_SIZE = 0.149f
        private const val WIDGET_Y = 0.817f
        private const val WIDGET_COLUMNS = 5
        private const val GAP = 0.0135f

        // Where the circles sit inside Nubia's 241 px widget container.
        private const val WIDGET_CIRCLE_CENTRE = 0.54f
        private const val WIDGET_CIRCLE_HALF = 0.34f

        /** Only the one-line digital style can be resized; the stacked and dial styles keep Nubia's size. */
        internal fun resizable(clock: View): Boolean {
            var p = clock.parent
            while (p is View) {
                if (p.javaClass.simpleName == "LockScreenClockDefault") return true
                p = p.parent
            }
            return false
        }

        private class TextOrigin(var size: Float, var applied: Float, val face: Typeface?)

        private val textOrigins = WeakHashMap<TextView, TextOrigin>()

        /** Every clock style's date lines: date and lunar date, or day and month, and a lunar line under them. */
        internal val DATE_IDS = listOf("date_view", "lunar_date_widget_horiz", "day", "month", "lunar_date_widget")
        internal val NOTE_IDS = listOf("owner_info")
        /** The digits of the styles [resizable] doesn't cover: they are fitted as text. */
        internal val CLOCK_TEXT_IDS = listOf("hours", "minute", "clock_hour_view", "clock_minute_view", "clock_view")

        internal fun textViews(find: (String) -> View?, ids: List<String>): List<TextView> =
            ids.mapNotNull { find(it) as? TextView }.filter { it.isShown && it.width > 0 }

        internal fun boldnessToWeight(b: Int) = (100 + b * 8).coerceIn(100, 900)

        /** The clock style [views] belong to: its class's simple name (LockScreenClockVertical, ...). */
        internal fun styleOf(views: List<View>): String? {
            var p: Any? = views.firstOrNull()?.parent
            while (p is View) {
                val n = p.javaClass.simpleName
                if (n.startsWith("LockScreenClock")) return n
                p = p.parent
            }
            return null
        }

        /**
         * The date lines, the custom text under the clock and, in styles other than the one-line
         * digital one, the clock's digits: each group fitted to its box from [layout] (user request
         * 2026-11-24), at the text size that fills the box's width or height, whichever is reached
         * first, never stretched. No box = Nubia's size. Reapplied on every pass, as Nubia resets
         * fonts and sizes. [w] x [h] is the screen; [resizableClock] = the digits are [LockClock]'s.
         */
        internal fun styleTexts(layout: LockLayout, w: Int, h: Int, find: (String) -> View?, resizableClock: Boolean) {
            val enlarged = ArrayList<TextView>()
            enlarged += fit(textViews(find, DATE_IDS), layout.dateW * w / 1000f, layout.dateH * h / 1000f, layout.dateWeight, uniform = true)
            val notes = textViews(find, NOTE_IDS)
            enlarged += fit(notes, layout.noteW * w / 1000f, layout.noteH * h / 1000f, layout.noteWeight)
            val digits = if (resizableClock) emptyList() else textViews(find, CLOCK_TEXT_IDS)
            if (!resizableClock) {
                val box = styleOf(digits)?.let { layout.styleBoxes[it] }
                clockBoldness = layout.clockBoldness
                enlarged += fit(digits, (box?.first ?: 0) * w / 1000f, (box?.second ?: 0) * h / 1000f, 0, stretch = true)
            }
            // Grow the containers of every enlarged text; put back the rest in this window.
            val root = (enlarged.firstOrNull() ?: notes.firstOrNull() ?: digits.firstOrNull())?.rootView
            val keep = HashSet<View>()
            for (tv in enlarged) grow(tv, keep)
            for ((v, height) in fixedHeights.entries.toList()) {
                if (v in keep || (root != null && v.rootView !== root)) continue
                fixedHeights.remove(v)
                v.layoutParams?.let { lp -> lp.height = height; v.layoutParams = lp }
            }
        }

        private val measure = android.graphics.Paint()
        private val at = IntArray(2)

        /**
         * [views] as they sit (side by side in a row, rows stacked) fitted to a [boxW] x [boxH] px box.
         * @param weight font weight, 0 = Nubia's
         */
        /** @return the views it made larger than Nubia's size */
        /**
         * @param stretch the clock's digit rows (user requests 2026-11-24): drawn as [LockClock]
         *   faces filling the box, iOS-style (taller at the same stroke), with the room below each
         *   row kept so the area grows with the box.
         */
        private fun fit(views: List<TextView>, boxW: Float, boxH: Float, weight: Int, stretch: Boolean = false,
                        uniform: Boolean = false): List<TextView> {
            if (views.isEmpty()) return emptyList()
            for (tv in views) origin(tv)
            // The date line (user report 2026-11-24): Nubia sizes the lunar date apart and gives it the
            // clock's font, so in a box they drifted apart. Boxed, every view takes the date's size and font.
            val same = uniform && boxW > 0f && boxH > 0f
            val lead = textOrigins[views[0]]!!
            fun baseSize(tv: TextView) = if (same) lead.size else textOrigins[tv]!!.size
            for (tv in views) weigh(tv, weight, if (same) lead.face else null)
            var scale = 1f
            if (boxW > 0f && boxH > 0f) {
                // Rows: views whose middles fall within each other's height share one.
                class Row(var top: Float, var bottom: Float, var width: Float, var line: Float)
                val rows = ArrayList<Row>()
                for (tv in views.sortedBy { tv -> tv.getLocationInWindow(at); at[1] }) {
                    tv.getLocationInWindow(at)
                    val mid = at[1] + tv.height / 2f
                    measure.set(tv.paint)
                    measure.textSize = baseSize(tv)
                    val width = measure.measureText(tv.text?.toString().orEmpty()) + tv.totalPaddingLeft + tv.totalPaddingRight
                    val fm = measure.fontMetrics
                    val line = (if (tv.includeFontPadding) fm.bottom - fm.top else fm.descent - fm.ascent) +
                        tv.totalPaddingTop + tv.totalPaddingBottom
                    val row = rows.firstOrNull { mid in it.top..it.bottom }
                    if (row == null) rows.add(Row(at[1].toFloat(), (at[1] + tv.height).toFloat(), width, line))
                    else { row.width += width; row.line = maxOf(row.line, line) }
                }
                val width = rows.maxOf { it.width }
                val height = rows.sumOf { it.line.toDouble() }.toFloat()
                if (width > 0f && height > 0f) scale = minOf(boxW / width, boxH / height).coerceIn(0.2f, 8f)
            }
            if (stretch) {
                // The digit rows are drawn as clock faces in the box (taller at the same stroke):
                // each row gets an equal share of its height, and the room below it in the layout.
                val rows = rowsOf(views)
                val boxed = boxW > 0f && boxH > 0f
                for (row in rows) {
                    val natural = row.sumOf { naturalWidth(it).toDouble() }.toFloat().coerceAtLeast(1f)
                    for (tv in row) {
                        val o = textOrigins[tv]!!
                        if (kotlin.math.abs(tv.textSize - o.size) > 0.5f) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, o.size)
                        o.applied = o.size
                        if (!boxed) { LockClock.set(tv, null, 0, 0f); roomBelow(tv, 0f); continue }
                        val rowH = boxH / rows.size
                        LockClock.set(tv, rowH - 2 * LockClock.TOP_INSET, clockBoldness, boxW * naturalWidth(tv) / natural,
                            tv.gravity and android.view.Gravity.HORIZONTAL_GRAVITY_MASK)
                        roomBelow(tv, rowH)
                    }
                }
                return if (boxed) views else emptyList()
            }
            for (tv in views) {
                val o = textOrigins[tv]!!
                val size = baseSize(tv) * scale
                if (kotlin.math.abs(tv.textSize - size) > 0.5f) tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, size)
                o.applied = size
            }
            if (uniform) alignBaselines(views, same)
            // The text sits in the middle of its box's height (user request 2026-11-24): each row is
            // the box's share of it tall, its text centred.
            val boxed = boxW > 0f && boxH > 0f
            val rows = if (boxed) rowsOf(views) else listOf(views)
            for (row in rows) for (tv in row) centreIn(tv, if (boxed) boxH / rows.size else 0f)
            return if (scale > 1f || boxed) views else emptyList()
        }

        /** Nubia's minimum height and gravity of the texts we centre, to put back. */
        private val textFrames = WeakHashMap<TextView, Pair<Int, Int>>()

        /** [tv] at least [height] px tall with its text centred in it; 0 puts Nubia's back. */
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

        /** Date views we moved onto the date's baseline, to put back. */
        private val baselined: MutableSet<TextView> = java.util.Collections.newSetFromMap(WeakHashMap())

        /** Views sharing a row sit on the first one's baseline ([on]); else back where Nubia put them. */
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
                    if (kotlin.math.abs(tv.translationY - dy) > 0.5f) { tv.translationY = dy; baselined.add(tv) }
                }
            }
        }

        /** Nubia's bottom margins of the drawn digit rows, to put back. */
        private val bottomMargins = WeakHashMap<View, Int>()

        /** The boldness the digit rows are drawn at, set by [styleTexts]. */
        private var clockBoldness = LockLayout.DEFAULT_BOLDNESS

        /** Keeps [height] px of layout for [tv] (a larger bottom margin); 0 puts Nubia's margin back. */
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

        /** [views] grouped into rows: those whose middles fall within each other's height. */
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

        /** Nubia's fixed heights of the containers we let grow, to put back. */
        private val fixedHeights = WeakHashMap<View, Int>()

        /**
         * Lets every container round an enlarged text grow with it, up to the screen-sized one, so the
         * whole area resizes and what is below moves down instead of the text being cut at Nubia's
         * boxes: fixed heights (the date row's 24 dp, the clock row's 97 dp, the ones Nubia's code
         * sets per style) become wrap-content, and clipping is off. Each container grown is added to
         * [keep]; [styleTexts] puts back the others.
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
                    if (lp.height > 0) {
                        fixedHeights.putIfAbsent(child, lp.height)
                        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT
                        child.layoutParams = lp
                    }
                    if (child in fixedHeights) keep.add(child)
                }
                child = parent
            }
        }

        /** Nubia's size and font for [tv], re-read whenever Nubia changed them since our last pass. */
        private fun origin(tv: TextView) {
            val o = textOrigins[tv]
            if (o == null || kotlin.math.abs(tv.textSize - o.applied) > 0.5f) {
                textOrigins[tv] = TextOrigin(tv.textSize, tv.textSize, tv.typeface)
            }
        }

        /** [tv] at [weight] (0 = Nubia's), in [family] when given (else its own font). */
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
