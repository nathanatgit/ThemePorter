package dev.nebula.glass.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import kotlin.math.ceil

/** Colour maths shared by the pickers. All colours are opaque RGB; strength (alpha) is separate. */
object Palette {
    /**
     * The colourful default palette: 12 hues (every 30°) in pastel, vivid and deep tones,
     * then 6 neutrals. Laid out 12 per row, so each column is one hue family.
     */
    val COLORFUL: IntArray by lazy {
        val tones = listOf(floatArrayOf(0.32f, 1f), floatArrayOf(0.82f, 1f), floatArrayOf(0.88f, 0.62f))
        val out = ArrayList<Int>()
        for ((s, v) in tones) for (i in 0 until 12) out.add(Color.HSVToColor(floatArrayOf(i * 30f, s, v)))
        out.addAll(listOf(0xFFFFFFFF, 0xFFE5E5EA, 0xFFAEAEB2, 0xFF636366, 0xFF2C2C2E, 0xFF000000).map { it.toInt() })
        out.toIntArray()
    }

    /** ColorOS-style tonal ramp: the same hue from near-white to near-black (9 steps). */
    fun ramp(rgb: Int): IntArray {
        val hsv = FloatArray(3).also { Color.colorToHSV(rgb, it) }
        val sat = if (hsv[1] < 0.08f) 0f else maxOf(hsv[1], 0.55f)
        return IntArray(9) { i ->
            val t = i / 8f                      // 0 = lightest … 1 = darkest
            val s = sat * (0.25f + 0.75f * minOf(1f, t * 1.6f))
            val v = 1f - 0.82f * maxOf(0f, (t - 0.35f) / 0.65f)
            Color.HSVToColor(floatArrayOf(hsv[0], s.coerceIn(0f, 1f), v.coerceIn(0.1f, 1f)))
        }
    }

    fun rgb(c: Int) = c or 0xFF000000.toInt()
    fun withStrength(rgb: Int, percent: Int) = (rgb and 0xFFFFFF) or ((percent.coerceIn(1, 100) * 255 / 100) shl 24)
    fun strengthOf(argb: Int) = ((argb ushr 24) * 100 + 127) / 255
    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
}

/** A wrapping grid of round swatches drawn in one view; tap to pick. */
class SwatchGrid(ctx: Context, private val columns: Int = 12) : View(ctx) {
    var colors: IntArray = IntArray(0)
        set(v) { field = v; requestLayout(); invalidate() }
    /** Opaque RGB of the selected colour, or null. */
    var selected: Int? = null
        set(v) { field = v; invalidate() }
    var onPick: ((Int) -> Unit)? = null
    var ringColor = Color.BLACK

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x22000000 }
    private val density = resources.displayMetrics.density

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val cell = w / columns.toFloat()
        val rows = ceil(colors.size / columns.toFloat()).toInt()
        setMeasuredDimension(w, (rows * cell).toInt())
    }

    override fun onDraw(c: Canvas) {
        val cell = width / columns.toFloat()
        val r = cell * 0.36f
        ring.strokeWidth = 2.2f * density
        ring.color = ringColor
        hair.strokeWidth = 1f * density
        for ((i, col) in colors.withIndex()) {
            val cx = (i % columns + 0.5f) * cell
            val cy = (i / columns + 0.5f) * cell
            fill.color = Palette.rgb(col)
            c.drawCircle(cx, cy, r, fill)
            c.drawCircle(cx, cy, r, hair)
            if (selected != null && Palette.rgb(col) == selected) c.drawCircle(cx, cy, r + 3.5f * density, ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val cell = width / columns.toFloat()
            val i = (e.y / cell).toInt() * columns + (e.x / cell).toInt()
            if (i in colors.indices) {
                selected = Palette.rgb(colors[i])
                performClick()
                onPick?.invoke(Palette.rgb(colors[i]))
            }
        }
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

/** Saturation/value square over a hue bar. Emits opaque RGB while dragging. */
class HsvPicker(ctx: Context) : View(ctx) {
    var onChange: ((Int) -> Unit)? = null
    private val hsv = floatArrayOf(210f, 0.7f, 0.9f)
    private val dp = resources.displayMetrics.density
    private val square = RectF()
    private val bar = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumb = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Color.WHITE }
    private val thumbShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55000000 }
    private var dragging = 0     // 1 = square, 2 = bar

    fun setColor(rgb: Int) { Color.colorToHSV(rgb, hsv); invalidate() }
    val color get() = Color.HSVToColor(hsv)

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (196 * dp).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        square.set(0f, 0f, w.toFloat(), h - 40 * dp)
        bar.set(0f, h - 26 * dp, w.toFloat(), h.toFloat())
    }

    override fun onDraw(c: Canvas) {
        val r = 14 * dp
        val pure = Color.HSVToColor(floatArrayOf(hsv[0], 1f, 1f))
        paint.shader = ComposeShader(
            LinearGradient(square.left, 0f, square.right, 0f, Color.WHITE, pure, Shader.TileMode.CLAMP),
            LinearGradient(0f, square.top, 0f, square.bottom, Color.TRANSPARENT, Color.BLACK, Shader.TileMode.CLAMP),
            PorterDuff.Mode.SRC_OVER)
        c.drawRoundRect(square, r, r, paint)
        paint.shader = LinearGradient(bar.left, 0f, bar.right, 0f,
            IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 1f, 1f)) }, null, Shader.TileMode.CLAMP)
        c.drawRoundRect(bar, bar.height() / 2, bar.height() / 2, paint)
        paint.shader = null
        thumb.strokeWidth = 3 * dp; thumbShadow.strokeWidth = 5 * dp
        val sx = square.left + hsv[1] * square.width()
        val sy = square.top + (1 - hsv[2]) * square.height()
        c.drawCircle(sx, sy, 10 * dp, thumbShadow); c.drawCircle(sx, sy, 10 * dp, thumb)
        val hx = bar.left + hsv[0] / 360f * bar.width()
        c.drawCircle(hx, bar.centerY(), 11 * dp, thumbShadow); c.drawCircle(hx, bar.centerY(), 11 * dp, thumb)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y <= square.bottom + 6 * dp) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = 0; return true }
        }
        if (dragging == 1) {
            hsv[1] = ((e.x - square.left) / square.width()).coerceIn(0f, 1f)
            hsv[2] = 1f - ((e.y - square.top) / square.height()).coerceIn(0f, 1f)
        } else if (dragging == 2) {
            hsv[0] = ((e.x - bar.left) / bar.width()).coerceIn(0f, 1f) * 359.9f
        }
        invalidate()
        onChange?.invoke(color)
        return true
    }
}
