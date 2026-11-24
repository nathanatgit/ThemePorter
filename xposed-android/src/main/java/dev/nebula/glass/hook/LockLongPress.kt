package dev.nebula.glass.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import de.robv.android.xposed.XposedHelpers
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin

/**
 * Long-press on an empty part of the lock screen (user request 2026-11-24): a glass menu that opens
 * Nubia's lock-screen editor or this module's lock-screen settings. Nubia's lock screen has no
 * long-press of its own there.
 *
 * Fed from the shade window's dispatchTouchEvent by [LockArrange] while the lock layout is active.
 * While the menu shows it takes every touch; a touch outside closes it.
 */
internal class LockLongPress(private val root: ViewGroup) {
    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var menu: Menu? = null
    private val fire = Runnable { if (armed) open() }

    /**
     * @return true when the event is consumed here. [cancel] sends Nubia's views a cancel, so the
     *   press doesn't also act as a tap.
     */
    fun onTouch(e: MotionEvent, active: Boolean, cancel: () -> Unit): Boolean {
        menu?.let { m ->
            m.handle(e)
            return true
        }
        this.cancel = cancel
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                armed = active && isEmpty(e.x, e.y)
                downX = e.x; downY = e.y
                root.removeCallbacks(fire)
                if (armed) root.postDelayed(fire, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (armed && (Math.abs(e.x - downX) > slop || Math.abs(e.y - downY) > slop)) disarm()
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> disarm()
        }
        return false
    }

    /** Sends Nubia's views a cancel for the gesture in progress. */
    private var cancel: (() -> Unit)? = null

    private fun disarm() {
        armed = false
        root.removeCallbacks(fire)
    }

    private fun open() {
        armed = false
        // The rest of this gesture is the menu's: the press must not also act on Nubia's views.
        try { cancel?.invoke() } catch (t: Throwable) { Log.e(TAG, "long-press cancel", t) }
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val m = Menu(root.context, downX, downY) { choice -> close(); if (choice >= 0) start(choice) }
        menu = m
        root.addView(m, ViewGroup.LayoutParams(-1, -1))
        Log.i(TAG, "lock long-press menu")
    }

    fun close() {
        menu?.let { root.removeView(it) }
        menu = null
    }

    /** No card, widget, shortcut or status bar under the finger. */
    private fun isEmpty(x: Float, y: Float): Boolean {
        val h = root.height
        if (y < h * 0.06f || y > h * 0.97f) return false
        val r = Rect()
        fun hit(v: View?): Boolean = v != null && v.isShown && v.getGlobalVisibleRect(r) && r.contains(x.toInt(), y.toInt())
        fun named(name: String): View? {
            val id = root.resources.getIdentifier(name, "id", SYSTEMUI)
            return if (id != 0) root.findViewById(id) else null
        }
        for (name in listOf("keyguard_widget_container_id", "left_button", "camera_button", "keyguard_indication_area",
                "device_entry_icon_view", "lock_icon_view", "keyguard_bottom_area_affordance")) {
            if (hit(named(name))) return false
        }
        (named("notification_stack_scroller") as? ViewGroup)?.let { stack ->
            for (i in 0 until stack.childCount) {
                val c = stack.getChildAt(i)
                if (c.visibility == View.VISIBLE && c.alpha > 0.01f && hit(c)) return false
            }
        }
        return true
    }

    private fun start(choice: Int) {
        val ctx = root.context
        val intent = when (choice) {
            NUBIA -> Intent().setClassName(SYSTEMUI, NUBIA_EDITOR)
            else -> Intent().setClassName(GlassSettings.PKG, SETTINGS).putExtra(EXTRA_SECTION, SECTION_LOCK)
        }.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        try {
            ctx.startActivity(intent)
        } catch (t: Throwable) {
            Log.e(TAG, "lock long-press start", t)
            return
        }
        // Both open behind the lock screen; the phone has no credential to ask for.
        dismissKeyguard()
    }

    private fun dismissKeyguard() {
        try {
            val wm = XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"), "getWindowManagerService")
            val m = wm.javaClass.methods.firstOrNull { it.name == "dismissKeyguard" } ?: return
            m.invoke(wm, *arrayOfNulls<Any>(m.parameterTypes.size))
        } catch (t: Throwable) { Log.e(TAG, "dismiss keyguard", t) }
    }

    /** Two glass rows near the press, over a full-screen catcher. */
    @SuppressLint("ViewConstructor")
    private class Menu(context: Context, private val atX: Float, private val atY: Float, private val pick: (Int) -> Unit) : View(context) {
        private val dp = resources.displayMetrics.density
        private val skin = GlassSkin()
        private val box = RectF()
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 16 * dp }
        private val line = Paint().apply { color = 0x33FFFFFF }
        private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26FFFFFF }
        private val rows = arrayOf("Nubia lock screen editor", "nebulaColorize lock screen")
        private var pressed = -1
        private var blur: Drawable? = null

        init { contentDescription = "Lock screen options" }

        private fun layoutBox() {
            val w = 272 * dp
            val h = ROW_DP * dp * rows.size
            val l = (atX - w / 2).coerceIn(16 * dp, width - w - 16 * dp)
            val t = (atY - h - 24 * dp).let { if (it < 48 * dp) atY + 24 * dp else it }.coerceAtMost(height - h - 16 * dp)
            box.set(l, t, l + w, t + h)
        }

        override fun onDraw(c: Canvas) {
            layoutBox()
            val r = 26 * dp
            try {
                val b = blur ?: (XposedHelpers.callMethod(XposedHelpers.callMethod(this, "getViewRootImpl"), "createBackgroundBlurDrawable") as Drawable).also { blur = it }
                XposedHelpers.callMethod(b, "setBlurRadius", (60 * dp).toInt())
                XposedHelpers.callMethod(b, "setCornerRadius", r)
                b.setBounds(box.left.toInt(), box.top.toInt(), box.right.toInt(), box.bottom.toInt())
                b.draw(c)
            } catch (_: Throwable) {}
            skin.drawPanel(c, box, GlassSkin.uniform(r), BODY, 1.2f, dp)
            val rowH = ROW_DP * dp
            for ((i, label) in rows.withIndex()) {
                val top = box.top + i * rowH
                if (i == pressed) {
                    c.save(); c.clipRect(box.left, top, box.right, top + rowH)
                    c.drawRoundRect(box, r, r, press); c.restore()
                }
                if (i > 0) c.drawRect(box.left + 20 * dp, top, box.right - 20 * dp, top + dp * 0.7f, line)
                c.drawText(label, box.left + 22 * dp, top + rowH / 2 - (text.ascent() + text.descent()) / 2, text)
            }
        }

        private fun rowAt(x: Float, y: Float): Int =
            if (!box.contains(x, y)) -1 else ((y - box.top) / (ROW_DP * dp)).toInt().coerceIn(0, rows.size - 1)

        fun handle(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val r = rowAt(e.x, e.y)
                    if (r != pressed) { pressed = r; invalidate() }
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && r < 0) pick(-1)
                }
                MotionEvent.ACTION_UP -> {
                    val r = rowAt(e.x, e.y)
                    pressed = -1
                    invalidate()
                    if (r >= 0) pick(r)
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; invalidate() }
            }
        }

        companion object {
            const val ROW_DP = 56
            const val BODY = 0x8C1E2228.toInt()
        }
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SYSTEMUI = "com.android.systemui"
        const val NUBIA = 0
        const val NUBIA_EDITOR = "com.zte.mifavor.keyguard.personalclock.MyLockScreenSettingsActivity"
        const val SETTINGS = "dev.nebula.glass.ui.SettingsActivity"
        const val EXTRA_SECTION = "section"
        const val SECTION_LOCK = "lock"
    }
}
