package dev.nebula.glass

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.util.concurrent.Executors

/**
 * Backdrop source 2 (docs/LIQUID_GLASS_PORT.md §3): the static system wallpaper, painted in
 * screen coordinates. The wallpaper on the launcher and lock screen is a separate surface, so
 * recording the glass's parent would only capture transparent pixels.
 *
 * Never attached: an unattached View reports (0, 0) from getLocationOnScreen, so the lens's
 * "glass minus source" offset is the glass's screen position and this canvas is screen space
 * (same trick as upstream WindowStackBackdrop).
 *
 * Crop follows ImageWallpaper's static case: centre-crop scaled to cover the display, panned
 * by the launcher's wallpaper offsets when the scaled image is wider or taller than the screen.
 */
class WallpaperBackdrop(
    context: Context,
    private val which: Int = WallpaperManager.FLAG_SYSTEM,
    /** > 0: keep a copy downscaled ×4 and box-blurred to roughly this radius (screen px). */
    private val preBlurPx: Float = 0f,
    /** Downscale factor for the private copy (1 = full size). */
    private val downscale: Int = 1,
) : View(context) {
    private val listeners = ArrayList<() -> Unit>()

    /** Called on the main thread after the wallpaper bitmap or offsets change. */
    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun changed() { for (l in listeners.toList()) l() }

    var bitmap: Bitmap? = null
        private set
    /** True when a live wallpaper is set; there is nothing to sample and callers should fall back. */
    var isLive = false
        private set

    // Page offsets live in the companion: every copy (sharp, pre-blurred) follows the launcher scroll.
    private val xOffset get() = pageX
    private val yOffset get() = pageY
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val main = Handler(Looper.getMainLooper())
    private val wm = WallpaperManager.getInstance(context)
    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, flags ->
        if (flags and which != 0) reload()
    }

    init {
        val dm = context.resources.displayMetrics
        layoutTo(dm.widthPixels, dm.heightPixels)
    }

    /** Match the real display size (call when the host window's size is known or changes). */
    fun layoutTo(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    private var retries = 0
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // First unlock after boot: credential-encrypted wallpaper storage becomes readable only now.
            if (bitmap == null && !isLive) { retries = 0; reload() }
        }
    }

    fun start() {
        try { wm.addOnColorsChangedListener(colorsListener, main) } catch (t: Throwable) { Log.w(TAG, "colors listener", t) }
        try {
            val f = IntentFilter().apply {
                addAction(Intent.ACTION_USER_UNLOCKED); addAction(Intent.ACTION_USER_PRESENT); addAction(Intent.ACTION_SCREEN_ON)
            }
            context.registerReceiver(unlockReceiver, f)
        } catch (t: Throwable) { Log.w(TAG, "unlock receiver", t) }
        reload()
    }

    fun stop() {
        try { wm.removeOnColorsChangedListener(colorsListener) } catch (_: Throwable) {}
    }

    /** Launcher page offsets (0..1), as passed to WallpaperManager.setWallpaperOffsets. Applies to all copies. */
    fun setOffsets(x: Float, y: Float) = setPageOffsets(x, y)

    fun reload() {
        worker.execute {
            var result: Bitmap? = null
            var live = false
            try {
                live = wm.wallpaperInfo != null
                if (!live) {
                    // Prefer decoding the wallpaper file: getDrawable() returns WallpaperManager's
                    // process-wide cached bitmap, which ImageWallpaper (in SystemUI) recycles after
                    // upload; drawing a recycled bitmap aborts the process. Never keep that object.
                    val decoded = decodeFile(downscale)
                    if (decoded != null) {
                        result = if (preBlurPx > 0f) preBlur(decoded, preBlurPx) else decoded
                    } else {
                        val shared = ((wm.getDrawable(which) ?: wm.drawable) as? BitmapDrawable)?.bitmap
                            ?.takeIf { !it.isRecycled }
                        if (shared != null) result = when {
                            preBlurPx > 0f -> preBlur(shared, preBlurPx)
                            downscale > 1 -> Bitmap.createScaledBitmap(shared, maxOf(1, shared.width / downscale),
                                maxOf(1, shared.height / downscale), true)
                            else -> shared.copy(Bitmap.Config.ARGB_8888, false)
                        }
                    }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "wallpaper unavailable", t)
            }
            Log.i(TAG, "wallpaper ${result?.width}x${result?.height} ${result?.config} live=$live centre=#" +
                (result?.let { Integer.toHexString(it.getPixel(it.width / 2, it.height / 2)) } ?: "-"))
            main.post {
                bitmap = result
                isLive = live
                changed()
                // Not readable yet (e.g. before first unlock): retry a few times instead of staying empty.
                if (result == null && !live && retries++ < 6) main.postDelayed({ reload() }, 2000L * retries)
                else if (result != null) retries = 0
            }
        }
    }

    /** Private decode of the wallpaper file (lock, then home). Null if not permitted or absent. */
    private fun decodeFile(sample: Int): Bitmap? {
        for (flag in if (which == WallpaperManager.FLAG_LOCK) intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM) else intArrayOf(which)) {
            try {
                wm.getWallpaperFile(flag)?.use { fd ->
                    val opt = android.graphics.BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, sample)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    android.graphics.BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opt)?.let { return it }
                }
            } catch (t: Throwable) {
                Log.w(TAG, "wallpaper file $flag", t)
            }
        }
        return null
    }

    override fun onDraw(canvas: Canvas) {
        val b = bitmap?.takeIf { !it.isRecycled } ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val scale = maxOf(w / b.width, h / b.height)
        val sw = b.width * scale
        val sh = b.height * scale
        matrix.setScale(scale, scale)
        matrix.postTranslate(-(sw - w) * xOffset, -(sh - h) * yOffset)
        canvas.drawBitmap(b, matrix, paint)
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private var shared: WallpaperBackdrop? = null
        private var sharedBlurred: WallpaperBackdrop? = null
        private var sharedLock: WallpaperBackdrop? = null

        /** Lock-screen wallpaper (falls back to the home wallpaper when no separate lock one is set). */
        @JvmStatic fun sharedLock(context: Context): WallpaperBackdrop =
            sharedLock ?: WallpaperBackdrop(context.applicationContext, WallpaperManager.FLAG_LOCK, downscale = 2).also { sharedLock = it; it.start() }

        /** Shared pre-blurred copy for heavily frosted surfaces: one CPU blur instead of a GPU blur per frame. */
        @JvmStatic fun sharedBlurred(context: Context): WallpaperBackdrop =
            sharedBlurred ?: WallpaperBackdrop(context.applicationContext, preBlurPx = 40f).also { sharedBlurred = it; it.start() }

        /** Downscale by 4 then three box-blur passes (≈ Gaussian) at the reduced size. */
        private fun preBlur(src: Bitmap, radiusPx: Float): Bitmap {
            val w = maxOf(1, src.width / 4)
            val h = maxOf(1, src.height / 4)
            val small = Bitmap.createScaledBitmap(src, w, h, true).copy(Bitmap.Config.ARGB_8888, true)
            val px = IntArray(w * h)
            small.getPixels(px, 0, w, 0, 0, w, h)
            val r = maxOf(1, (radiusPx / 4f / 1.73f).toInt())
            val tmp = IntArray(w * h)
            repeat(3) { boxH(px, tmp, w, h, r); boxV(tmp, px, w, h, r) }
            small.setPixels(px, 0, w, 0, 0, w, h)
            return small
        }

        private fun boxH(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val n = 2 * r + 1
            for (y in 0 until h) {
                var a = 0; var rr = 0; var g = 0; var b = 0
                val row = y * w
                for (i in -r..r) { val c = src[row + i.coerceIn(0, w - 1)]; a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
                for (x in 0 until w) {
                    dst[row + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                    val o = src[row + (x - r).coerceIn(0, w - 1)]; val i = src[row + (x + r + 1).coerceIn(0, w - 1)]
                    a += (i ushr 24) - (o ushr 24); rr += ((i shr 16) and 255) - ((o shr 16) and 255)
                    g += ((i shr 8) and 255) - ((o shr 8) and 255); b += (i and 255) - (o and 255)
                }
            }
        }

        private fun boxV(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int) {
            val n = 2 * r + 1
            for (x in 0 until w) {
                var a = 0; var rr = 0; var g = 0; var b = 0
                for (i in -r..r) { val c = src[i.coerceIn(0, h - 1) * w + x]; a += c ushr 24; rr += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255 }
                for (y in 0 until h) {
                    dst[y * w + x] = ((a / n) shl 24) or ((rr / n) shl 16) or ((g / n) shl 8) or (b / n)
                    val o = src[(y - r).coerceIn(0, h - 1) * w + x]; val i = src[(y + r + 1).coerceIn(0, h - 1) * w + x]
                    a += (i ushr 24) - (o ushr 24); rr += ((i shr 16) and 255) - ((o shr 16) and 255)
                    g += ((i shr 8) and 255) - ((o shr 8) and 255); b += (i and 255) - (o and 255)
                }
            }
        }

        /** One decoded wallpaper per process, shared by every glass surface. Main thread. */
        @JvmStatic fun shared(context: Context): WallpaperBackdrop =
            // Half resolution: scrolling wallpapers are several screens wide (5376 px here), and every
            // consumer blurs it anyway.
            shared ?: WallpaperBackdrop(context.applicationContext, downscale = 2).also { shared = it; it.start() }

        @Volatile private var pageX = 0.5f
        @Volatile private var pageY = 0.5f

        /** Launcher page offsets (0..1) for every wallpaper copy in this process; redraws their users. */
        @JvmStatic fun setPageOffsets(x: Float, y: Float) {
            val nx = if (x.isNaN()) 0.5f else x.coerceIn(0f, 1f)
            val ny = if (y.isNaN()) 0.5f else y.coerceIn(0f, 1f)
            if (nx == pageX && ny == pageY) return
            pageX = nx; pageY = ny
            for (b in listOfNotNull(shared, sharedBlurred)) b.changed()
        }
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "NebulaWallpaper").apply { isDaemon = true } }
    }
}
