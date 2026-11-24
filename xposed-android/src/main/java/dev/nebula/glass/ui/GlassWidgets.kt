package dev.nebula.glass.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import dev.nebula.glass.GlassDrawable
import dev.nebula.glass.GlassMaterial
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.LensGlass

// The settings app's Liquid Glass + Material 3 controls. Framework views only, so the module APK
// stays dependency-free. Each reads UiTheme when it draws, so invalidate() is enough to recolour.

/** What the app's glass refracts: the wallpaper when readable, else a soft wash of the seed colour. */
@SuppressLint("ViewConstructor")
internal class GlassBackdrop(context: Context, private val theme: UiTheme, private val wallpaper: () -> Bitmap?) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val box = RectF()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        c.drawColor(theme.surface)
        val b = wallpaper()
        if (b != null && !b.isRecycled) {
            val s = maxOf(w / b.width, h / b.height)
            val dx = (w - b.width * s) / 2
            val dy = (h - b.height * s) / 2
            box.set(dx, dy, dx + b.width * s, dy + b.height * s)
            paint.shader = null
            paint.alpha = 255
            c.drawBitmap(b, null, box, paint)
            c.drawColor(Palette.alpha(theme.surface, 0.55f))
            return
        }
        val hsv = FloatArray(3).also { Color.colorToHSV(theme.seed, it) }
        fun glow(x: Float, y: Float, radius: Float, shift: Float) {
            val hue = Color.HSVToColor(floatArrayOf((hsv[0] + shift + 360f) % 360f, hsv[1], hsv[2]))
            val colour = Palette.alpha(theme.tone(hue, if (theme.night) 28f else 78f), 0.75f)
            paint.shader = RadialGradient(x, y, radius, colour, colour and 0xFFFFFF, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
        }
        glow(w * 0.05f, h * 0.12f, w * 0.9f, 0f)
        glow(w * 1.05f, h * 0.45f, w * 0.85f, 18f)
        glow(w * 0.2f, h * 0.92f, w * 0.8f, -12f)
        paint.shader = null
    }
}

/** A refracting Liquid Glass panel over the [GlassBackdrop]. */
@SuppressLint("ViewConstructor")
internal class GlassPane(context: Context, private val theme: UiTheme, backdrop: View, radiusDp: Float) : LinearLayout(context) {
    private val glass: GlassDrawable

    init {
        orientation = VERTICAL
        val dp = resources.displayMetrics.density
        val lens = LensGlass().apply {
            material = GlassMaterial.REGULAR
            blurRadiusOverride = 22f
            saturation = 165f
            bevelWidth = 14 * dp
            refractionHeight = 24 * dp
            dispersion = 0.05f
            specular = 0.8f
            cornerRadius = radiusDp * dp
            refreshAccessibility(context)
        }
        val solid = GradientDrawable().apply { setColor(theme.surface); cornerRadius = radiusDp * dp }
        glass = GlassDrawable(this, lens, solid, prepare = { it.glassTint = theme.pane }) { backdrop }
        background = glass
    }

    fun recolour() { glass.invalidateSelf(); invalidate() }
}

/**
 * A capsule slider: a filled level, or an iOS-style colour bar with a ringed thumb.
 * A SeekBar underneath, so touch arbitration, keys and TalkBack are the platform's.
 */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
internal class GlassSlider(
    context: Context,
    private val theme: UiTheme,
    max: Int,
    /** The built-in value: marked, and the thumb snaps to it. */
    private val detent: Int? = null,
    /** A colour bar's gradient; null for a level slider. */
    private val ramp: (() -> IntArray)? = null,
    private val thumbColour: (() -> Int)? = null,
    private val change: (Int) -> Unit,
) : SeekBar(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val box = RectF()
    private val thumbR = (if (ramp == null) 14 else 18) * dp

    init {
        this.max = max
        background = null
        progressDrawable = null
        thumb = null
        splitTrack = false
        setPadding(thumbR.toInt(), 0, thumbR.toInt(), 0)
        setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            private var snapped = false
            override fun onProgressChanged(b: SeekBar, value: Int, user: Boolean) {
                if (!user) return
                var v = value
                if (detent != null && kotlin.math.abs(v - detent) * 100 <= b.max * SNAP_PERCENT) {
                    if (!snapped) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    snapped = true
                    v = detent
                    if (b.progress != v) b.progress = v
                } else snapped = false
                change(v)
                invalidate()
            }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (44 * dp).toInt())

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val halfTrack = (if (ramp == null) 8 else 16) * dp
        val travel = width - 2 * thumbR
        val x = thumbR + travel * progress / max.coerceAtLeast(1)
        box.set(thumbR - halfTrack, cy - halfTrack, width - thumbR + halfTrack, cy + halfTrack)
        paint.style = Paint.Style.FILL
        val colours = ramp?.invoke()
        if (colours != null) {
            paint.color = Color.WHITE   // a shader is drawn at the paint's alpha
            paint.shader = LinearGradient(box.left, 0f, box.right, 0f, colours, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(box, halfTrack, halfTrack, paint)
            paint.shader = null
        } else {
            paint.color = theme.track
            c.drawRoundRect(box, halfTrack, halfTrack, paint)
            detent?.let {
                paint.color = theme.outline
                c.drawCircle(thumbR + travel * it / max, cy, 2 * dp, paint)
            }
            box.right = x + halfTrack
            paint.color = theme.primary
            c.drawRoundRect(box, halfTrack, halfTrack, paint)
        }
        paint.color = 0x26000000
        c.drawCircle(x, cy + dp, thumbR, paint)
        paint.color = Color.WHITE
        c.drawCircle(x, cy, thumbR, paint)
        paint.color = thumbColour?.invoke() ?: theme.container
        c.drawCircle(x, cy, thumbR - 3.5f * dp, paint)
    }

    private companion object { const val SNAP_PERCENT = 3 }
}

/** A Material 3 switch. */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
internal class GlassSwitch(context: Context, private val theme: UiTheme) : CompoundButton(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        buttonDrawable = null
        background = null
        isClickable = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) = setMeasuredDimension((52 * dp).toInt(), (40 * dp).toInt())

    override fun getAccessibilityClassName(): CharSequence = "android.widget.Switch"

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val r = 16 * dp
        paint.style = Paint.Style.FILL
        paint.color = if (isChecked) theme.primary else theme.track
        c.drawRoundRect(0f, cy - r, width.toFloat(), cy + r, r, r, paint)
        if (!isChecked) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2 * dp
            paint.color = theme.outline
            c.drawRoundRect(dp, cy - r + dp, width - dp, cy + r - dp, r - dp, r - dp, paint)
            paint.style = Paint.Style.FILL
        } else paint.color = theme.onPrimary
        c.drawCircle(if (isChecked) width - r else r, cy, (if (isChecked) 12 else 8) * dp, paint)
    }
}

/** One of the two styles: a small glass swatch and its name, as a picture choice. */
@SuppressLint("ViewConstructor")
internal class StyleChoice(
    context: Context,
    private val theme: UiTheme,
    private val name: String,
    private val swatch: () -> Int,
) : View(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val skin = GlassSkin()
    private val box = RectF()

    init {
        contentDescription = name
        isClickable = true
        isFocusable = true
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) =
        setMeasuredDimension(MeasureSpec.getSize(widthSpec), (64 * dp).toInt())

    override fun getAccessibilityClassName(): CharSequence = "android.widget.RadioButton"

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.isCheckable = true
        info.isChecked = isSelected
    }

    override fun onDraw(c: Canvas) {
        val r = 22 * dp
        paint.shader = null
        if (isSelected) {
            paint.color = theme.container
            c.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, paint)
        }
        box.set(12 * dp, 10 * dp, 56 * dp, height - 10 * dp)
        val sr = 14 * dp
        // The swatch sits on a bit of "wallpaper", or Clear would show nothing.
        paint.color = Color.WHITE
        paint.shader = LinearGradient(box.left, box.top, box.right, box.bottom,
            theme.tone(theme.systemAccent, 72f), theme.tone(theme.systemAccent, 30f), Shader.TileMode.CLAMP)
        c.drawRoundRect(box, sr, sr, paint)
        paint.shader = null
        skin.drawPanel(c, box, GlassSkin.uniform(sr), swatch(), 1.2f, dp)
        paint.color = Color.WHITE
        c.drawCircle(box.centerX(), box.centerY(), 7 * dp, paint)
        paint.color = if (isSelected) theme.onContainer else theme.ink
        paint.textSize = 16 * resources.configuration.fontScale * dp
        paint.isFakeBoldText = isSelected
        c.drawText(name, box.right + 12 * dp, height / 2f - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

/** A colour source, as a ringed dot with a glyph. */
@SuppressLint("ViewConstructor", "AppCompatCustomView")
internal class SourceDot(context: Context, private val theme: UiTheme, icon: Int, description: String, private val colour: () -> Int) : ImageView(context) {
    private val dp = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    init {
        setImageResource(icon)
        contentDescription = description
        isClickable = true
        isFocusable = true
        val p = (14 * dp).toInt()
        setPadding(p, p, p, p)
    }

    override fun onDraw(c: Canvas) {
        val colour = colour()
        val r = minOf(width, height) / 2f
        paint.color = if (isSelected) theme.ink else theme.track
        c.drawCircle(width / 2f, height / 2f, r, paint)
        paint.color = colour
        c.drawCircle(width / 2f, height / 2f, r - 3.5f * dp, paint)
        drawable?.mutate()?.setTint(if (Color.luminance(colour) > 0.4f) 0xFF202024.toInt() else Color.WHITE)
        super.onDraw(c)
    }
}

/** The floating bottom bar, with a glass droplet that slides to the selected tab. */
@SuppressLint("ViewConstructor")
internal class GlassTabs(context: Context, private val theme: UiTheme, items: List<Pair<Int, String>>, private val pick: (Int) -> Unit) : FrameLayout(context) {
    private val dp = resources.displayMetrics.density
    private val row = LinearLayout(context)
    private val icons = ArrayList<ImageView>()
    private val labels = ArrayList<TextView>()
    private val skin = GlassSkin()
    private val box = RectF()
    private var position = 0f
    private var animator: android.animation.ValueAnimator? = null

    var selected = 0
        private set

    init {
        setWillNotDraw(false)
        addView(row, LayoutParams(-1, -1))
        for ((i, item) in items.withIndex()) {
            val tab = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isFocusable = true
                contentDescription = item.second
                setOnClickListener { if (i != selected) { select(i, true); pick(i) } }
            }
            val icon = ImageView(context).apply { setImageResource(item.first) }
            val label = TextView(context).apply { text = item.second; textSize = 12f }
            tab.addView(icon, LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt()))
            tab.addView(label, LinearLayout.LayoutParams(-2, -2))
            row.addView(tab, LinearLayout.LayoutParams(0, -1, 1f))
            icons.add(icon)
            labels.add(label)
        }
        recolour()
    }

    fun select(index: Int, animate: Boolean) {
        selected = index
        animator?.cancel()
        if (animate) {
            animator = android.animation.ValueAnimator.ofFloat(position, index.toFloat()).apply {
                duration = 380
                interpolator = OvershootInterpolator(0.9f)
                addUpdateListener { position = it.animatedValue as Float; invalidate() }
                start()
            }
        } else position = index.toFloat()
        recolour()
    }

    fun recolour() {
        for (i in icons.indices) {
            val c = if (i == selected) theme.onContainer else theme.inkSoft
            icons[i].setColorFilter(c)
            labels[i].setTextColor(c)
            (row.getChildAt(i) as View).isSelected = i == selected
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        if (labels.isEmpty()) return
        val cell = width / labels.size.toFloat()
        // The droplet stretches while it's between tabs.
        val travel = kotlin.math.abs(position - Math.round(position)).coerceAtMost(0.5f)
        val cx = cell * (position + 0.5f)
        val half = cell / 2 - 6 * dp + travel * 18 * dp
        box.set((cx - half).coerceAtLeast(4 * dp), 5 * dp, (cx + half).coerceAtMost(width - 4 * dp), height - 5 * dp)
        skin.drawPanel(c, box, GlassSkin.uniform(box.height() / 2), Palette.alpha(theme.container, 0.9f), 1.2f, dp)
    }
}
