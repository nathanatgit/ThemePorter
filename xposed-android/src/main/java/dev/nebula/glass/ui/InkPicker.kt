package dev.nebula.glass.ui

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/** The lock-screen wallpaper for previews and the ink picker. Blocking: call off the main thread. */
object LockWallpaper {
    /**
     * Lock wallpaper, then the home one, decoded privately at about [maxPx] on the long side.
     * Null when this app may not read the wallpaper (Android needs READ_WALLPAPER_INTERNAL or
     * all-files access); callers then offer an image picker instead.
     */
    fun load(ctx: Context, maxPx: Int): Bitmap? {
        val wm = WallpaperManager.getInstance(ctx)
        for (flag in intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM)) {
            try {
                wm.getWallpaperFile(flag)?.use { fd -> decode(maxPx) { BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, it) } }
                    ?.let { return it }
            } catch (t: Throwable) { Log.i(TAG, "wallpaper file $flag: ${t.javaClass.simpleName}") }
        }
        return try {
            (wm.getDrawable(WallpaperManager.FLAG_LOCK) as? BitmapDrawable)?.bitmap?.copy(Bitmap.Config.ARGB_8888, false)
        } catch (t: Throwable) { Log.i(TAG, "wallpaper drawable: ${t.javaClass.simpleName}"); null }
    }

    fun load(ctx: Context, uri: Uri, maxPx: Int): Bitmap? = try {
        ctx.contentResolver.openFileDescriptor(uri, "r")?.use { fd ->
            decode(maxPx) { BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, it) }
        }
    } catch (t: Throwable) { Log.w(TAG, "image $uri", t); null }

    /** Two-pass decode: read bounds, then sample down to about [maxPx]. */
    private inline fun decode(maxPx: Int, read: (BitmapFactory.Options) -> Bitmap?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        read(bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
        return read(BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
    }

    /**
     * Wallpaper colours without reading the image (no permission needed): Android's own
     * primary/secondary/tertiary for the lock, then home, wallpaper.
     */
    fun systemColors(ctx: Context): IntArray {
        val wm = WallpaperManager.getInstance(ctx)
        val out = LinkedHashSet<Int>()
        for (flag in intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM)) {
            try {
                wm.getWallpaperColors(flag)?.let { wc ->
                    listOfNotNull(wc.primaryColor, wc.secondaryColor, wc.tertiaryColor).forEach { out.add(Palette.rgb(it.toArgb())) }
                }
            } catch (_: Throwable) {}
        }
        return out.toIntArray()
    }

    /** Up to [n] distinct dominant colours sampled from [b] (coarse 4-bit-per-channel histogram). */
    fun dominant(b: Bitmap, n: Int = 6): IntArray {
        val small = Bitmap.createScaledBitmap(b, 48, (48f * b.height / b.width).toInt().coerceAtLeast(1), true)
        val counts = HashMap<Int, IntArray>()   // bucket -> [count, r, g, b]
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            val key = ((Color.red(c) shr 4) shl 8) or ((Color.green(c) shr 4) shl 4) or (Color.blue(c) shr 4)
            val a = counts.getOrPut(key) { IntArray(4) }
            a[0]++; a[1] += Color.red(c); a[2] += Color.green(c); a[3] += Color.blue(c)
        }
        val picked = ArrayList<Int>()
        for (a in counts.values.sortedByDescending { it[0] }) {
            val c = Color.rgb(a[1] / a[0], a[2] / a[0], a[3] / a[0])
            if (picked.none { dist(it, c) < 60 }) picked.add(c)
            if (picked.size == n) break
        }
        return picked.toIntArray()
    }

    private fun dist(a: Int, b: Int): Int =
        Math.abs(Color.red(a) - Color.red(b)) + Math.abs(Color.green(a) - Color.green(b)) + Math.abs(Color.blue(a) - Color.blue(b))

    private const val TAG = "NebulaColorize"
}

/**
 * Full-screen eyedropper ("ink picker") over the lock wallpaper, cropped the way the lock screen
 * shows it. Drag anywhere: a magnifying loupe above the finger shows the pixel under the crosshair.
 */
class InkPicker(ctx: Context, private val image: Bitmap, initial: Int?,
                private val onDone: (Int?) -> Unit) : FrameLayout(ctx) {
    private val dp = resources.displayMetrics.density
    private var picked: Int? = initial
    private var fx = -1f
    private var fy = -1f
    private val matrix = Matrix()
    private val inverse = Matrix()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val loupePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val chip = View(ctx)
    private val hex = TextView(ctx)

    init {
        setWillNotDraw(false)
        setBackgroundColor(Color.BLACK)
        isClickable = true
        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (12 * dp).toInt(), (16 * dp).toInt(), (12 * dp).toInt())
            background = GradientDrawable().apply { setColor(0xE61C1C1E.toInt()); cornerRadius = 28 * dp }
        }
        chip.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke((2 * dp).toInt(), Color.WHITE) }
        bar.addView(chip, LinearLayout.LayoutParams((32 * dp).toInt(), (32 * dp).toInt()))
        hex.setTextColor(Color.WHITE); hex.textSize = 15f
        hex.setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
        bar.addView(hex, LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(pill(L("Cancel"), 0x33FFFFFF) { onDone(null) })
        bar.addView(View(ctx), LinearLayout.LayoutParams((8 * dp).toInt(), 1))
        bar.addView(pill(L("Use colour"), 0xFF0A84FF.toInt()) { onDone(picked) })
        addView(bar, LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            setMargins((16 * dp).toInt(), 0, (16 * dp).toInt(), (40 * dp).toInt())
        })
        val hint = TextView(ctx).apply {
            text = L("Drag to pick a colour from your lock-screen wallpaper")
            setTextColor(Color.WHITE); textSize = 14f; gravity = Gravity.CENTER
            setShadowLayer(6 * dp, 0f, 0f, Color.BLACK)
        }
        addView(hint, LayoutParams(-1, -2, Gravity.TOP).apply { topMargin = (64 * dp).toInt() })
        updateChip()
    }

    private fun pill(label: String, color: Int, action: () -> Unit) = TextView(context).apply {
        text = label; setTextColor(Color.WHITE); textSize = 15f; gravity = Gravity.CENTER
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (10 * dp).toInt())
        background = GradientDrawable().apply { setColor(color); cornerRadius = 20 * dp }
        setOnClickListener { action() }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        // Centre-crop like ImageWallpaper on the lock screen.
        val s = maxOf(w / image.width.toFloat(), h / image.height.toFloat())
        matrix.setScale(s, s)
        matrix.postTranslate((w - image.width * s) / 2f, (h - image.height * s) / 2f)
        matrix.invert(inverse)
        if (fx < 0) { fx = w / 2f; fy = h * 0.4f }
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(image, matrix, bitmapPaint)
        if (fx < 0) return
        // Loupe 100 dp above the finger, 4x zoom, crosshair on the sampled pixel.
        val r = 52 * dp
        val lx = fx
        val ly = (fy - 100 * dp).coerceAtLeast(r + 8 * dp)
        val zoom = 4f
        val shader = BitmapShader(image, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val m = Matrix(matrix)
        m.postScale(zoom, zoom, fx, fy)
        m.postTranslate(lx - fx, ly - fy)
        shader.setLocalMatrix(m)
        loupePaint.shader = shader
        c.drawCircle(lx, ly, r, loupePaint)
        ring.strokeWidth = 6 * dp; ring.color = picked ?: Color.WHITE
        c.drawCircle(lx, ly, r + 3 * dp, ring)
        ring.strokeWidth = 1.5f * dp; ring.color = Color.WHITE
        c.drawCircle(lx, ly, r + 6 * dp, ring)
        cross.strokeWidth = 1.5f * dp
        c.drawLine(lx - 8 * dp, ly, lx + 8 * dp, ly, cross)
        c.drawLine(lx, ly - 8 * dp, lx, ly + 8 * dp, cross)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
            fx = e.x; fy = e.y
            picked = sample(fx, fy)
            updateChip()
            invalidate()
        }
        return true
    }

    /** Average of the 3x3 image pixels under the screen point. */
    private fun sample(x: Float, y: Float): Int {
        val p = floatArrayOf(x, y)
        inverse.mapPoints(p)
        val cx = p[0].toInt().coerceIn(1, image.width - 2)
        val cy = p[1].toInt().coerceIn(1, image.height - 2)
        var r = 0; var g = 0; var b = 0
        for (dy in -1..1) for (dx in -1..1) {
            val c = image.getPixel(cx + dx, cy + dy)
            r += Color.red(c); g += Color.green(c); b += Color.blue(c)
        }
        return Color.rgb(r / 9, g / 9, b / 9)
    }

    private fun updateChip() {
        val c = picked
        (chip.background as GradientDrawable).setColor(c ?: Color.TRANSPARENT)
        hex.text = if (c == null) L("Touch the wallpaper") else Palette.hex(c)
    }
}
