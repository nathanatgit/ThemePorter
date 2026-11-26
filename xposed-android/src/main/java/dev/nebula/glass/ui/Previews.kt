package dev.nebula.glass.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.GlassText
import dev.nebula.glass.Looks
import dev.nebula.glass.Shapes
import dev.nebula.glass.Surface

private val APP_COLOURS = intArrayOf(
    0xFFFF3B5C.toInt(), 0xFF34C759.toInt(), 0xFFFFD60A.toInt(), 0xFF0A84FF.toInt(),
    0xFFF4F5F7.toInt(), 0xFFFF9F0A.toInt(), 0xFF1C1C1E.toInt(), 0xFFBF5AF2.toInt(),
)

private fun roundOff(v: View, radiusDp: Float) {
    v.outlineProvider = object : ViewOutlineProvider() {
        override fun getOutline(view: View, o: Outline) =
            o.setRoundRect(0, 0, view.width, view.height, radiusDp * view.resources.displayMetrics.density)
    }
    v.clipToOutline = true
}

private fun drawHomeScene(c: Canvas, w: Float, h: Float, wallpaper: Bitmap?, paint: Paint, columns: Int = 4, sparse: Boolean = false, icons: Boolean = true, iconRowsLimit: Int = -1) {
    paint.color = Color.WHITE
    if (wallpaper != null && !wallpaper.isRecycled) {
        val s = maxOf(w / wallpaper.width, h / wallpaper.height)
        val dx = (w - wallpaper.width * s) / 2
        val dy = (h - wallpaper.height * s) / 2
        c.drawBitmap(wallpaper, null, RectF(dx, dy, dx + wallpaper.width * s, dy + wallpaper.height * s), paint)
    } else {
        paint.shader = LinearGradient(0f, 0f, w, h,
            intArrayOf(0xFF8FB8D8.toInt(), 0xFFF0905A.toInt(), 0xFF28405E.toInt()), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
    }
    if (!icons) return
    val cell = w / columns
    val rows = if (iconRowsLimit > 0) iconRowsLimit else ((h / cell).toInt() + 1)
    for (i in 0 until rows * columns) {
        if (sparse && (i % columns + i / columns) % 2 == 1) continue
        val x = (i % columns + 0.22f) * cell
        val y = (i / columns + 0.16f) * cell
        paint.color = APP_COLOURS[(i * 3 + i / columns) % APP_COLOURS.size]
        c.drawRoundRect(x, y, x + cell * 0.56f, y + cell * 0.56f, cell * 0.14f, cell * 0.14f, paint)
    }
}

@SuppressLint("ViewConstructor")
internal class PreviewStage(
    context: Context,
    private val style: (Surface) -> GlassStyle,
    private val shapes: () -> Shapes,
    private val wallpaper: () -> Bitmap?,
    private val nightMode: () -> Boolean = { false },
    private val pick: (Surface) -> Unit = {},
) : LinearLayout(context) {
    private val parts = ArrayList<View>()

    init { orientation = VERTICAL }

    fun show(scope: Surface?) {
        removeAllViews()
        parts.clear()
        fun panel(s: Surface, showBadge: Boolean = false): View {
            val view: View = when (s) {
                Surface.LOCK_WIDGET -> LockPreview(context, style, wallpaper, nightMode)
                Surface.FOLDER -> HomePreview(context, { style(Surface.FOLDER) }, wallpaper, nightMode)
                Surface.HOME_WIDGET -> WidgetPreview(context, { style(Surface.HOME_WIDGET) }, wallpaper, nightMode)
                Surface.DOCK -> DockPreview(context, { style(Surface.DOCK) }, shapes, wallpaper, nightMode)
                else -> PanelPreview(context, s, { style(s) }, wallpaper, nightMode)
            }
            parts.add(view)
            if (!showBadge) return view
            val wrap = FrameLayout(context)
            roundOff(wrap, 20f)
            wrap.addView(view, FrameLayout.LayoutParams(-1, -1))
            val dp = resources.displayMetrics.density
            val badge = TextView(context).apply {
                text = badgeLabel(s)
                textSize = 10.5f
                setTextColor(Color.WHITE)
                setShadowLayer(3f * dp, 0f, 1f * dp, 0x99000000.toInt())
                setPadding((8 * dp).toInt(), (3 * dp).toInt(), (8 * dp).toInt(), (3 * dp).toInt())
                background = android.graphics.drawable.GradientDrawable().apply {
                    setColor(0x4D000000)
                    cornerRadius = 10 * dp
                }
            }
            wrap.addView(badge, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                leftMargin = (6 * dp).toInt()
                bottomMargin = (5 * dp).toInt()
            })
            return wrap
        }
        if (scope != null) {
            addView(panel(scope, showBadge = false), LayoutParams(-1, -1))
            return
        }
        val gap = (7 * resources.displayMetrics.density).toInt()
        val grid = listOf(
            listOf(Surface.CONTROL_CENTER, Surface.NOTIFICATION),
            listOf(Surface.LOCK_WIDGET, Surface.FOLDER),
            listOf(Surface.HOME_WIDGET, Surface.DOCK),
        )
        for ((r, surfaces) in grid.withIndex()) {
            val row = LinearLayout(context)
            for ((i, s) in surfaces.withIndex()) {
                val v = panel(s, showBadge = true)
                v.contentDescription = "${s.title}: edit"
                v.isClickable = true
                v.setOnClickListener { pick(s) }
                row.addView(v, LayoutParams(0, -1, 1f).apply { if (i == 0) marginEnd = gap })
            }
            addView(row, LayoutParams(-1, 0, 1f).apply { if (r < grid.lastIndex) bottomMargin = gap })
        }
    }

    private fun badgeLabel(s: Surface) = when (s) {
        Surface.CONTROL_CENTER -> L("Control centre")
        Surface.NOTIFICATION -> L("Notifications")
        Surface.LOCK_WIDGET -> L("Lock screen")
        Surface.FOLDER -> L("Folders")
        Surface.HOME_WIDGET -> L("Home widgets")
        Surface.DOCK -> L("Dock panel")
        else -> s.title
    }

    fun refresh() {
        for (p in parts) when (p) {
            is HomePreview -> p.refresh()
            is WidgetPreview -> p.refresh()
            is DockPreview -> p.refresh()
            is LockPreview -> p.refresh()
            else -> p.invalidate()
        }
    }
}

@SuppressLint("ViewConstructor")
internal class LockPreview(
    context: Context,
    private val style: (Surface) -> GlassStyle,
    private val wallpaper: () -> Bitmap?,
    private val nightMode: () -> Boolean,
) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scene = object : View(context) {
        override fun onDraw(c: Canvas) {
            drawHomeScene(c, width.toFloat(), height.toFloat(), wallpaper(), paint, icons = false)
            val st = style(Surface.LOCK_WIDGET)
            if (st.enabled) c.drawColor(Color.argb((Looks.backdropDim(Surface.LOCK_WIDGET, st) * 255).toInt(), 0, 0, 0))
        }
    }
    private val clock = Clock()
    private val widget = Dot(Surface.LOCK_WIDGET, "7°")
    private val shortcut = Dot(Surface.LOCK_SHORTCUT, "")
    private val card = Card()

    init {
        roundOff(this, 20f)
        for (v in listOf(scene, clock, card, widget, shortcut)) addView(v, LayoutParams(-1, -1))
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        val w = right - left
        val h = bottom - top
        fun place(v: View, l: Float, t: Float, r: Float, b: Float) {
            v.measure(MeasureSpec.makeMeasureSpec((r - l).toInt(), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec((b - t).toInt(), MeasureSpec.EXACTLY))
            v.layout(l.toInt(), t.toInt(), r.toInt(), b.toInt())
        }
        place(scene, 0f, 0f, w.toFloat(), h.toFloat())
        val d = h * 0.26f
        place(clock, 0f, 0f, w.toFloat(), h * 0.56f)
        place(card, w * 0.05f, h * 0.60f, w * 0.95f, h * 0.92f)
        place(widget, w * 0.06f, h * 0.17f, w * 0.06f + d, h * 0.17f + d)
        place(shortcut, w * 0.94f - d, h * 0.17f, w * 0.94f, h * 0.17f + d)
    }

    fun refresh() {
        for (v in listOf(scene, clock, widget, shortcut, card)) v.invalidate()
    }

    private inner class Clock : View(context) {
        private val glass = GlassText().apply { onReady = { postInvalidate() } }
        private val type = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
            textAlign = Paint.Align.LEFT
        }

        override fun onDraw(c: Canvas) {
            val st = style(Surface.LOCK_CLOCK)
            type.textSize = height * 0.72f
            val text = "9:41"
            val x = (width - type.measureText(text)) / 2
            val baseline = height * 0.80f
            var drawn = false
            if (st.enabled && glass.isAvailable) {
                glass.tint = st.body(glass.tuneFor(DEVICE_CLOCK_PX, Color.WHITE, 0.6f, st.glow, st.refraction, st.dispersion, st.vibrancy))
                glass.edgeColor = st.edgeRgb
                glass.glowColor = st.glowRgb
                glass.lightX = st.lightX
                glass.lightY = st.lightY
                glass.blurRadius = st.blurPx(20f) * width / SCREEN_PX
                glass.setText(text, type)
                drawn = glass.draw(c, this, scene, x, baseline)
            }
            if (!drawn) c.drawText(text, x, baseline, type)
        }
    }

    private inner class Dot(private val surface: Surface, private val label: String) : View(context) {
        private val lens = Looks.base(surface, context)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }

        override fun onDraw(c: Canvas) {
            val st = style(surface)
            val night = nightMode()
            val r = width / 2f
            if (st.enabled) {
                if (surface != Surface.LOCK_WIDGET) {
                    Looks.style(lens, surface, resources.displayMetrics.density, r, night = night, st = st)
                    lens.blurRadiusOverride = st.blurPx(lens.defaultBlurPx) * 0.4f
                    lens.draw(c, this, scene, null)
                }
            } else if (surface == Surface.LOCK_WIDGET) {
                ink.color = 0x40FFFFFF
                c.drawCircle(r, r, r, ink)
            }
            ink.color = Color.WHITE
            ink.textSize = r * 0.8f
            if (label.isEmpty()) c.drawCircle(r, r, r * 0.3f, ink)
            else c.drawText(label, r, r - (ink.ascent() + ink.descent()) / 2, ink)
        }
    }

    private inner class Card : View(context) {
        private val lens = Looks.base(Surface.LOCK_CARD, context)
        private val skin = GlassSkin()
        private val box = RectF()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            if (!c.isHardwareAccelerated) return
            val st = style(Surface.LOCK_CARD)
            val night = nightMode()
            val dp = resources.displayMetrics.density
            val r = height * 0.3f
            box.set(0f, 0f, width.toFloat(), height.toFloat())
            if (st.enabled) {
                Looks.style(lens, Surface.LOCK_CARD, dp * 0.6f, r, night = night, st = st)
                lens.glassTint = Looks.lockCardBody(st, night)
                lens.frostCoat = !st.tinted
                lens.blurRadiusOverride = st.blurPx(14f * dp) * scene.width / SCREEN_PX
                if (!lens.draw(c, this, scene, box)) {
                    skin.drawPanel(c, box, GlassSkin.uniform(r), Looks.lockCardBody(st, night),
                        Looks.specular(Surface.LOCK_CARD) * st.glow, dp * 0.6f, st)
                }
            } else {
                ink.color = if (night) 0xD91C1C1E.toInt() else 0xD9FFFFFF.toInt()
                c.drawRoundRect(box, r, r, ink)
            }
            ink.color = APP_COLOURS[1]
            val side = height * 0.42f
            c.drawRoundRect(side * 0.6f, (height - side) / 2, side * 1.6f, (height + side) / 2, side * 0.25f, side * 0.25f, ink)
            ink.color = if (night) Color.WHITE else 0xFF141418.toInt()
            ink.textSize = height * 0.34f
            ink.isFakeBoldText = true
            c.drawText("See you at eight", side * 2.1f, height / 2f - (ink.ascent() + ink.descent()) / 2, ink)
        }
    }

    private companion object {
        const val SCREEN_PX = 1216f
        const val DEVICE_CLOCK_PX = 300f
    }
}

/** Folder tiles preview ([Surface.FOLDER]), respecting live `folderCorner` and Frost. */
@SuppressLint("ViewConstructor")
internal class HomePreview(
    context: Context,
    private val style: () -> GlassStyle,
    private val wallpaper: () -> Bitmap?,
    private val nightMode: () -> Boolean,
) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scene = object : View(context) {
        override fun onDraw(c: Canvas) = drawHomeScene(c, width.toFloat(), height.toFloat(), wallpaper(), paint, columns = 6, sparse = true)
    }
    private val tiles = listOf(Tile(), Tile())

    init {
        roundOff(this, 20f)
        addView(scene, LayoutParams(-1, -1))
        val row = LinearLayout(context)
        val pad = (8 * resources.displayMetrics.density).toInt()
        row.setPadding(pad, pad, 0, pad)
        for (t in tiles) row.addView(t, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = pad })
        addView(row, LayoutParams(-1, -1))
    }

    fun refresh() {
        scene.invalidate()
        tiles.forEach { it.invalidate() }
    }

    private inner class Tile : View(context) {
        private val lens = Looks.base(Surface.FOLDER, context)
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val dp = resources.displayMetrics.density
            val st = style()
            val night = nightMode()
            val defaultR = minOf(width, height) * 0.24f
            val r = if (st.cornerDp >= 0) minOf(st.cornerDp * dp * 0.55f, minOf(width, height) / 2f) else defaultR
            if (st.enabled) {
                Looks.style(lens, Surface.FOLDER, dp * 0.55f, defaultR, night = night, st = st)
                lens.blurRadiusOverride = st.blurPx(Looks.FOLDER_PREBLUR_PX * 0.42f)
                lens.draw(c, this, scene, null)
            } else {
                icon.color = if (night) 0xB0303034.toInt() else 0xB0EDEDF0.toInt()
                c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, icon)
            }
            val size = minOf(width, height) * 0.28f
            val gap = size * 0.24f
            for (i in 0 until 4) {
                val x = width / 2f + (if (i % 2 == 0) -size - gap / 2 else gap / 2)
                val y = height / 2f + (if (i < 2) -size - gap / 2 else gap / 2)
                icon.color = APP_COLOURS[i]
                c.drawRoundRect(x, y, x + size, y + size, size * 0.26f, size * 0.26f, icon)
            }
        }
    }
}

/** Home screen app widget preview ([Surface.HOME_WIDGET]): refractive widget card + inner glass pills. */
@SuppressLint("ViewConstructor")
internal class WidgetPreview(
    context: Context,
    private val style: () -> GlassStyle,
    private val wallpaper: () -> Bitmap?,
    private val nightMode: () -> Boolean,
) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scene = object : View(context) {
        override fun onDraw(c: Canvas) = drawHomeScene(c, width.toFloat(), height.toFloat(), wallpaper(), paint, columns = 5, sparse = true)
    }
    private val card = Card()

    init {
        roundOff(this, 20f)
        addView(scene, LayoutParams(-1, -1))
        val padH = (10 * resources.displayMetrics.density).toInt()
        val padV = (8 * resources.displayMetrics.density).toInt()
        addView(card, LayoutParams(-1, -1).apply { setMargins(padH, padV, padH, padV) })
    }

    fun refresh() {
        scene.invalidate()
        card.invalidate()
    }

    private inner class Card : View(context) {
        private val lens = Looks.base(Surface.HOME_WIDGET, context)
        private val skin = GlassSkin()
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG)
        private val sub = RectF()

        override fun onDraw(c: Canvas) {
            val dp = resources.displayMetrics.density
            val st = style()
            val night = nightMode()
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val r = minOf(w, h) * 0.24f
            if (st.enabled) {
                Looks.style(lens, Surface.HOME_WIDGET, dp * 0.55f, r, night = night, st = st)
                lens.blurRadiusOverride = st.blurPx(Looks.FOLDER_PREBLUR_PX * 0.42f)
                lens.draw(c, this, scene, null)
            } else {
                ink.color = if (night) 0xD91C1C1E.toInt() else 0xD9F4F5F7.toInt()
                c.drawRoundRect(0f, 0f, w, h, r, r, ink)
            }
            val fg = if (Looks.dark(night)) Color.WHITE else 0xFF1A1B20.toInt()
            ink.color = fg
            ink.isFakeBoldText = true
            ink.textSize = h * 0.30f
            c.drawText("23°", w * 0.08f, h * 0.40f, ink)
            ink.isFakeBoldText = false
            ink.textSize = h * 0.16f
            c.drawText("Sunny · Thu", w * 0.08f, h * 0.60f, ink)

            // Inner widget row cells (matching LauncherWidgets.drawInner)
            val innerBase = if (Looks.dark(night)) 0x38FFFFFF else 0x73FFFFFF
            val innerTint = Looks.body(Surface.HOME_WIDGET, st, night, innerBase, 0x6B)
            for (i in 0..1) {
                val top = h * (0.15f + i * 0.39f)
                sub.set(w * 0.48f, top, w * 0.93f, top + h * 0.31f)
                val sr = sub.height() * 0.34f
                if (st.enabled) {
                    skin.draw(c, sub, GlassSkin.uniform(sr), innerTint, 0.6f * st.glow, dp * 0.55f, st)
                } else {
                    ink.color = if (night) 0xFF2C2C30.toInt() else Color.WHITE
                    c.drawRoundRect(sub, sr, sr, ink)
                }
                ink.color = fg
                ink.isFakeBoldText = true
                ink.textSize = sub.height() * 0.46f
                c.drawText(if (i == 0) "14:00 Review" else "6,859 Steps",
                    sub.left + sub.height() * 0.32f, sub.centerY() - (ink.ascent() + ink.descent()) / 2f, ink)
            }
        }
    }
}

/** Home screen dock preview ([Surface.DOCK]), reflecting live `dockWidth`, `dockPad`, `dockCorner`, and optics. */
@SuppressLint("ViewConstructor")
internal class DockPreview(
    context: Context,
    private val style: () -> GlassStyle,
    private val shapes: () -> Shapes,
    private val wallpaper: () -> Bitmap?,
    private val nightMode: () -> Boolean,
) : FrameLayout(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val scene = object : View(context) {
        override fun onDraw(c: Canvas) = drawHomeScene(c, width.toFloat(), height.toFloat(), wallpaper(), paint, columns = 4, sparse = false, iconRowsLimit = 1)
    }
    private val bar = Bar()

    init {
        roundOff(this, 20f)
        addView(scene, LayoutParams(-1, -1))
        addView(bar, LayoutParams(-1, -1))
    }

    fun refresh() {
        scene.invalidate()
        bar.invalidate()
    }

    private inner class Bar : View(context) {
        private val lens = Looks.base(Surface.DOCK, context)
        private val box = RectF()
        private val icon = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val dp = resources.displayMetrics.density
            val st = style()
            val sh = shapes()
            val night = nightMode()

            val iconSide = minOf(h * 0.30f, w * 0.13f)
            val cy = h * 0.70f
            val padPx = (sh.dockPad / 12f) * (iconSide * 0.32f)
            val fullW = w * 0.88f
            val dockW = fullW * (sh.dockWidth / 100f)
            val top = (cy - iconSide / 2f - padPx).coerceAtLeast(h * 0.32f)
            val bot = (cy + iconSide / 2f + padPx).coerceAtMost(h * 0.95f)
            box.set((w - dockW) / 2f, top, (w + dockW) / 2f, bot)

            val origR = minOf(box.height() / 2f, 30f * dp * 0.45f)
            Looks.style(lens, Surface.DOCK, dp * 0.45f, origR, night = night, st = st)
            if (st.cornerDp >= 0) {
                lens.cornerRadius = minOf(st.cornerDp * dp * 0.45f, minOf(box.width(), box.height()) / 2f)
            }
            lens.blurRadiusOverride = st.blurPx(Looks.FOLDER_PREBLUR_PX * 0.42f)
            // Always preview the glass dock bar in DockPreview so tweaking its sliders gives instant feedback.
            lens.draw(c, this, scene, box)

            val count = 4
            val innerSpan = dockW * 0.80f
            val step = innerSpan / count
            val startX = (w - innerSpan) / 2f + (step - iconSide) / 2f
            for (i in 0 until count) {
                val x = startX + i * step
                icon.color = APP_COLOURS[i]
                c.drawRoundRect(x, cy - iconSide / 2f, x + iconSide, cy + iconSide / 2f, iconSide * 0.26f, iconSide * 0.26f, icon)
            }
        }
    }
}

@SuppressLint("ViewConstructor")
internal class PanelPreview(
    context: Context,
    private val surface: Surface,
    private val style: () -> GlassStyle,
    private val wallpaper: () -> Bitmap?,
    private val nightMode: () -> Boolean,
) : View(context) {
    private val skin = GlassSkin()
    private val window = RenderNode("preview window blur")
    private val card = RenderNode("preview card blur")
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val label = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val design = when (surface) {
        Surface.CONTROL_CENTER -> RectF(60f, 20f, 1156f, 912f)
        Surface.NOTIFICATION -> RectF(22f, 10f, 1194f, 690f)
        else -> RectF(22f, 10f, 1194f, 600f)
    }
    private var k = 1f
    private var ox = 0f
    private var oy = 0f

    init { roundOff(this, 20f) }

    override fun onDraw(c: Canvas) {
        if (width == 0 || height == 0 || !c.isHardwareAccelerated) return
        k = minOf(width / design.width(), height / design.height())
        ox = (width - design.width() * k) / 2 - design.left * k
        oy = (height - design.height() * k) / 2 - design.top * k
        val st = style()
        val night = nightMode()
        val board = surface == Surface.WIDGET_BOARD
        val amount = if (st.enabled) st.blur / 100f else 1f
        val windowPx = when {
            board -> if (st.enabled) BOARD_BLUR_PX * amount else BOARD_BLUR_PX
            st.enabled -> Looks.NUBIA_BLUR_PX * Looks.PANEL_BLUR_SCALE * amount
            else -> Looks.NUBIA_BLUR_PX
        }
        val dim = when {
            !st.enabled -> 0f
            board -> Looks.backdropDim(surface, st)
            else -> Looks.panelDim(surface, st)
        }
        record(window, windowPx * k, 1f, dim)
        c.drawRenderNode(window)
        if (!board) {
            record(card, Looks.PANEL_CARD_BLUR_PX * amount * k, if (amount > 0f) Looks.panelSaturation(st) else 1f, dim)
            if (!st.enabled) c.drawColor(if (night) 0xB0101012.toInt() else 0x8AD8D8DC.toInt())
        }
        c.save()
        c.translate(ox, oy)
        when (surface) {
            Surface.CONTROL_CENTER -> controlCentre(c, st)
            Surface.NOTIFICATION -> shade(c, st)
            else -> board(c, st)
        }
        c.restore()
    }

    private fun record(node: RenderNode, radiusPx: Float, saturation: Float, dim: Float) {
        node.setPosition(0, 0, width, height)
        val colour = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(saturation) }))
        node.setRenderEffect(if (radiusPx >= 0.5f)
            RenderEffect.createChainEffect(colour, RenderEffect.createBlurEffect(radiusPx, radiusPx, Shader.TileMode.CLAMP))
        else colour)
        val c = node.beginRecording()
        try {
            drawHomeScene(c, width.toFloat(), height.toFloat(), wallpaper(), fill)
            c.drawColor(Color.argb((dim * 255).toInt(), 0, 0, 0))
        } finally {
            node.endRecording()
        }
    }

    private fun controlCentre(c: Canvas, st: GlassStyle) {
        glass(c, st, RectF(97f, 48f, 552f, 440f), 64f)
        album(c, 130f, 81f)
        text(c, st, "Centuries", 130f, 284f, 52f)
        for (x in floatArrayOf(176f, 324f, 472f)) glyph(c, st, x, 372f)
        for ((i, x) in floatArrayOf(664f, 948f).withIndex()) {
            val track = RectF(x, 48f, x + 171, 440f)
            glass(c, st, track, 52f)
            val r = tileRadius(st, track, 52f) * k
            path.reset()
            path.addRoundRect(x * k, 48 * k, (x + 171) * k, 440 * k, r, r, Path.Direction.CW)
            c.save()
            c.clipPath(path)
            fill.color = Color.WHITE
            c.drawRect(x * k, (if (i == 0) 270 else 330) * k, (x + 171) * k, 440 * k, fill)
            c.restore()
        }
        for ((i, x) in floatArrayOf(97f, 664f).withIndex()) {
            val on = i == 0
            glass(c, st, RectF(x, 490f, x + 455, 661f), 52f, on)
            glyph(c, st, x + 82, 575f, on)
            text(c, st, if (on) "Wi-Fi" else "Hotspot", x + 140, 592f, 52f, on = on)
        }
        for (i in 0 until 4) {
            val x = 97f + i * 284
            glass(c, st, RectF(x, 711f, x + 171, 882f), 56f, i == 0)
            glyph(c, st, x + 85, 796f, i == 0)
        }
    }

    private fun shade(c: Canvas, st: GlassStyle) {
        glass(c, st, RectF(52f, 40f, 1164f, 332f), 40f)
        album(c, 88f, 76f)
        text(c, st, "Centuries", 298f, 150f, 64f)
        for (x in floatArrayOf(388f, 600f, 812f)) glyph(c, st, x, 262f)
        for ((i, top) in floatArrayOf(360f, 524f).withIndex()) {
            glass(c, st, RectF(52f, top, 1164f, top + 136), 40f)
            fill.color = APP_COLOURS[i]
            c.drawRoundRect(88 * k, (top + 42) * k, 140 * k, (top + 94) * k, 14 * k, 14 * k, fill)
            text(c, st, if (i == 0) "See you at eight" else "Design review at 14:00", 172f, top + 88, 56f)
        }
    }

    private fun board(c: Canvas, st: GlassStyle) {
        glass(c, st, RectF(52f, 40f, 1164f, 240f), 52f)
        for (i in 0 until 5) {
            fill.color = APP_COLOURS[i]
            val x = 120f + i * 210
            c.drawRoundRect(x * k, 80 * k, (x + 120) * k, 200 * k, 30 * k, 30 * k, fill)
        }
        glass(c, st, RectF(52f, 280f, 588f, 570f), 52f)
        glass(c, st, RectF(628f, 280f, 1164f, 570f), 52f)
        text(c, st, "6,859", 100f, 410f, 96f)
        text(c, st, "Steps", 100f, 490f, 52f)
        text(c, st, "5 °C", 676f, 410f, 96f)
        text(c, st, "Cloudy", 676f, 490f, 52f)
    }

    private fun tileRadius(st: GlassStyle, box: RectF, default: Float): Float =
        if ((surface != Surface.CONTROL_CENTER && surface != Surface.NOTIFICATION) || st.cornerDp < 0) default
        else GlassSkin.tileCorner(st.cornerDp * SCREEN_DENSITY, box.width(), box.height())

    private fun glass(c: Canvas, st: GlassStyle, box: RectF, design: Float, on: Boolean = false) {
        val radius = tileRadius(st, box, design)
        val r = RectF(box.left * k, box.top * k, box.right * k, box.bottom * k)
        val night = nightMode()
        if (!st.enabled) {
            fill.color = when {
                on -> NUBIA_BLUE
                night -> 0xD91C1C1E.toInt()
                else -> 0xD9FFFFFF.toInt()
            }
            c.drawRoundRect(r, radius * k, radius * k, fill)
            return
        }
        val radii = GlassSkin.uniform(radius * k)
        val strength = Looks.specular(surface) * st.glow
        val density = SCREEN_DENSITY * k
        if (surface == Surface.WIDGET_BOARD) {
            skin.draw(c, r, radii, Looks.body(surface, st, night), strength, density, st)
            return
        }
        path.reset()
        path.addRoundRect(r, radius * k, radius * k, Path.Direction.CW)
        c.save()
        c.clipPath(path)
        c.translate(-ox, -oy)
        c.drawRenderNode(card)
        c.restore()
        skin.drawPanel(c, r, radii, Looks.panelBody(surface, st, night, if (on) NUBIA_BLUE else null), strength, density, st)
    }

    private fun ink(st: GlassStyle, on: Boolean): Int {
        val whiteText = on || nightMode() || (st.enabled && surface != Surface.WIDGET_BOARD)
        return if (whiteText) Color.WHITE else 0xFF202024.toInt()
    }

    private fun text(c: Canvas, st: GlassStyle, s: String, x: Float, y: Float, size: Float, on: Boolean = false) {
        label.color = ink(st, on)
        label.textSize = size * k
        label.isFakeBoldText = true
        c.drawText(s, x * k, y * k, label)
    }

    private fun glyph(c: Canvas, st: GlassStyle, x: Float, y: Float, on: Boolean = false) {
        fill.color = ink(st, on)
        c.drawCircle(x * k, y * k, 26 * k, fill)
    }

    private fun album(c: Canvas, x: Float, y: Float) {
        fill.color = 0xFFE7C9A0.toInt()
        c.drawRoundRect(x * k, y * k, (x + 138) * k, (y + 138) * k, 30 * k, 30 * k, fill)
    }

    private companion object {
        const val SCREEN_DENSITY = 442 / 160f
        const val BOARD_BLUR_PX = 100f
        val NUBIA_BLUE = 0xFF008CE6.toInt()
    }
}
