package dev.nebula.glass.hook

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import de.robv.android.xposed.XposedHelpers
import dev.nebula.glass.CompositeBackdrop
import dev.nebula.glass.GlassMaterial
import dev.nebula.glass.GlassRegistry
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.LensGlass
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import dev.nebula.glass.applyStyle

/**
 * Long-press on an empty part of the lock screen: a live refractive Liquid Glass popup that blurs
 * and refracts both the wallpaper AND in-window lock-screen elements (clock, date, widgets, and
 * notification cards) underneath it in real time.
 */
internal class LockLongPress(private val root: ViewGroup) {
    private val slop = ViewConfiguration.get(root.context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var armed = false
    private var menu: Menu? = null
    private val fire = Runnable { if (armed) open() }

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

    private var cancel: (() -> Unit)? = null

    private fun disarm() {
        armed = false
        root.removeCallbacks(fire)
    }

    private fun open() {
        armed = false
        try { cancel?.invoke() } catch (t: Throwable) { Log.e(TAG, "long-press cancel", t) }
        root.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        val m = Menu(root, downX, downY) { choice -> close(); if (choice >= 0) start(choice) }
        menu = m
        root.addView(m, ViewGroup.LayoutParams(-1, -1))
        m.popIn()
        Log.i(TAG, "lock long-press menu")
    }

    fun close() {
        menu?.let {
            it.release()
            root.removeView(it)
        }
        menu = null
    }

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
        dismissKeyguard()
    }

    private fun dismissKeyguard() {
        try {
            val wm = XposedHelpers.callStaticMethod(Class.forName("android.view.WindowManagerGlobal"), "getWindowManagerService")
            val m = wm.javaClass.methods.firstOrNull { it.name == "dismissKeyguard" } ?: return
            m.invoke(wm, *arrayOfNulls<Any>(m.parameterTypes.size))
        } catch (t: Throwable) { Log.e(TAG, "dismiss keyguard", t) }
    }

    /** Full-screen touch catcher hosting a compact [Card] that captures & blurs `root` + wallpaper. */
    @SuppressLint("ViewConstructor")
    private class Menu(
        private val shadeRoot: ViewGroup,
        private val atX: Float,
        private val atY: Float,
        private val pick: (Int) -> Unit,
    ) : FrameLayout(shadeRoot.context) {
        private val dp = resources.displayMetrics.density
        private val composite = CompositeBackdrop(context.applicationContext, WallpaperBackdrop.sharedLock(context))
        private val card = Card(context)
        private val box = RectF()
        private var pressed = -1

        private val preDraw = android.view.ViewTreeObserver.OnPreDrawListener {
            if (isAttachedToWindow) card.invalidate()
            true
        }

        init {
            contentDescription = "Lock screen options"
            clipChildren = false
            clipToPadding = false
            GlassRegistry.register(this)
            GlassRegistry.register(card)
            addView(card, LayoutParams((CARD_W_DP * dp).toInt(), (ROW_DP * 2 * dp).toInt()))
            // Refresh the popup continuously if underlying clock/notifications animate while open.
            viewTreeObserver.addOnPreDrawListener(preDraw)
        }

        fun release() {
            if (viewTreeObserver.isAlive) viewTreeObserver.removeOnPreDrawListener(preDraw)
            card.release()
            GlassRegistry.unregister(card)
            GlassRegistry.unregister(this)
        }

        override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
            val w = CARD_W_DP * dp
            val h = ROW_DP * 2 * dp
            val pw = (right - left).toFloat()
            val ph = (bottom - top).toFloat()
            val l = (atX - w / 2f).coerceIn(16 * dp, (pw - w - 16 * dp).coerceAtLeast(16 * dp))
            val t = (atY - h - 24 * dp).let { if (it < 48 * dp) atY + 24 * dp else it }
                .coerceAtMost((ph - h - 16 * dp).coerceAtLeast(48 * dp))
            box.set(l, t, l + w, t + h)
            card.measure(
                MeasureSpec.makeMeasureSpec(w.toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h.toInt(), MeasureSpec.EXACTLY),
            )
            card.layout(l.toInt(), t.toInt(), (l + w).toInt(), (t + h).toInt())
        }

        fun popIn() {
            card.alpha = 0f
            card.scaleX = 0.88f
            card.scaleY = 0.88f
            card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(190).setInterpolator(OvershootInterpolator(0.8f)).start()
        }

        private fun rowAt(x: Float, y: Float): Int =
            if (!box.contains(x, y)) -1 else ((y - box.top) / (ROW_DP * dp)).toInt().coerceIn(0, 1)

        fun handle(e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                    val r = rowAt(e.x, e.y)
                    if (r != pressed) { pressed = r; card.invalidate() }
                    if (e.actionMasked == MotionEvent.ACTION_DOWN && r < 0) pick(-1)
                }
                MotionEvent.ACTION_UP -> {
                    val r = rowAt(e.x, e.y)
                    pressed = -1
                    card.invalidate()
                    if (r >= 0) pick(r)
                }
                MotionEvent.ACTION_CANCEL -> { pressed = -1; card.invalidate() }
            }
        }

        @SuppressLint("ViewConstructor")
        private inner class Card(ctx: Context) : View(ctx) {
            private val lens = LensGlass().apply {
                material = GlassMaterial.REGULAR
                refreshAccessibility(ctx)
            }
            private val skin = GlassSkin()
            private val localBox = RectF()
            private val clipPath = Path()
            private val liveInWindowNode = if (Build.VERSION.SDK_INT >= 31) RenderNode("LockMenuInWindow") else null
            private val rows = arrayOf("Nubia lock screen editor", "nebulaColorize lock screen")
            private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = 15.5f * dp
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            private val line = Paint(Paint.ANTI_ALIAS_FLAG)
            private val press = Paint(Paint.ANTI_ALIAS_FLAG)
            private var blur: Drawable? = null

            fun release() {
                lens.release()
                liveInWindowNode?.discardDisplayList()
            }

            override fun onDraw(c: Canvas) {
                if (GlassRegistry.isCapturing(this) || GlassRegistry.isCapturing(this@Menu)) return
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return
                localBox.set(0f, 0f, w, h)
                val r = 26 * dp
                val night = GlassSettings.isNight(context)
                val st = GlassSettings[Surface.LOCK_WIDGET, night]
                val builtIn = if (Looks.dark(night)) 0x5614161C else 0x5CFFFFFF
                val body = if (!st.tinted) st.frosted(builtIn) else Looks.lockCardBody(st, night)

                val wp = WallpaperBackdrop.sharedLock(context)
                if (shadeRoot.width > 0 && shadeRoot.height > 0) {
                    composite.layoutTo(shadeRoot.width, shadeRoot.height)
                }
                composite.content = shadeRoot
                // Hide the entire Menu overlay while capturing shadeRoot so clock, widgets & cards behind us are captured!
                composite.glass = this@Menu

                var drewLens = false
                if (!wp.isLive && wp.bitmap != null && lens.isAvailable) {
                    lens.applyStyle(
                        st, dp, r, body, 1.15f, 36f * dp,
                        originalBevel = 14f * dp,
                        originalRefraction = 28f * dp,
                        originalDispersion = 0.06f,
                        originalSaturation = 185f,
                    )
                    drewLens = lens.draw(c, this, composite, localBox)
                }
                if (!drewLens) {
                    val blurPx = st.blurPx(75 * dp).coerceAtLeast(1f)
                    try {
                        val b = blur ?: (XposedHelpers.callMethod(XposedHelpers.callMethod(this, "getViewRootImpl"), "createBackgroundBlurDrawable") as Drawable).also { blur = it }
                        XposedHelpers.callMethod(b, "setBlurRadius", blurPx.toInt())
                        XposedHelpers.callMethod(b, "setCornerRadius", r)
                        try { XposedHelpers.callMethod(b, "setSaturation", Looks.panelSaturation(st)) } catch (_: Throwable) {}
                        b.setBounds(0, 0, width, height)
                        b.draw(c)
                    } catch (_: Throwable) {}
                    // Also blur in-window elements (clock, widgets, cards) on live wallpapers!
                    val node = liveInWindowNode
                    if (node != null && Build.VERSION.SDK_INT >= 31 && c.isHardwareAccelerated) {
                        val margin = (blurPx * 2f).toInt().coerceIn(24, 128)
                        node.setPosition(-margin, -margin, width + margin, height + margin)
                        node.setRenderEffect(RenderEffect.createBlurEffect(blurPx * 0.5f, blurPx * 0.5f, Shader.TileMode.CLAMP))
                        val rc = node.beginRecording(width + 2 * margin, height + 2 * margin)
                        try {
                            val loc = IntArray(2); val rLoc = IntArray(2)
                            getLocationOnScreen(loc); shadeRoot.getLocationOnScreen(rLoc)
                            rc.translate((margin - (loc[0] - rLoc[0])).toFloat(), (margin - (loc[1] - rLoc[1])).toFloat())
                            this@Menu.setTransitionVisibility(View.INVISIBLE)
                            try { shadeRoot.draw(rc) } finally { this@Menu.setTransitionVisibility(View.VISIBLE) }
                        } finally {
                            node.endRecording()
                        }
                        clipPath.reset()
                        clipPath.addRoundRect(localBox, r, r, Path.Direction.CW)
                        c.save()
                        c.clipPath(clipPath)
                        c.drawRenderNode(node)
                        c.restore()
                    }
                    skin.drawPanel(c, localBox, GlassSkin.uniform(r), body, 1.15f * st.glow, dp, st)
                }

                val darkInk = !Looks.dark(night) && Color.luminance(body or 0xFF000000.toInt()) > 0.45f
                text.color = if (darkInk) 0xFF14161C.toInt() else Color.WHITE
                if (darkInk) text.clearShadowLayer() else text.setShadowLayer(3f * dp, 0f, 0.8f * dp, 0x38000000)
                line.color = if (darkInk) 0x22000000 else 0x2EFFFFFF
                press.color = if (darkInk) 0x1A000000 else 0x26FFFFFF

                val rowH = ROW_DP * dp
                for ((i, label) in rows.withIndex()) {
                    val top = i * rowH
                    if (i == pressed) {
                        c.save(); c.clipRect(0f, top, w, top + rowH)
                        c.drawRoundRect(localBox, r, r, press); c.restore()
                    }
                    if (i > 0) c.drawRect(20 * dp, top, w - 20 * dp, top + dp * 0.65f, line)
                    c.drawText(label, 22 * dp, top + rowH / 2 - (text.ascent() + text.descent()) / 2, text)
                }
            }
        }
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val SYSTEMUI = "com.android.systemui"
        const val NUBIA = 0
        const val CARD_W_DP = 264
        const val ROW_DP = 54
        const val NUBIA_EDITOR = "com.zte.mifavor.keyguard.personalclock.MyLockScreenSettingsActivity"
        const val SETTINGS = "dev.nebula.glass.ui.SettingsActivity"
        const val EXTRA_SECTION = "section"
        const val SECTION_LOCK = "lock"
    }
}
