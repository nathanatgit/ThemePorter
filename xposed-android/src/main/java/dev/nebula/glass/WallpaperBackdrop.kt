package dev.nebula.glass

import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.View
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * Backdrop source 2: the static system or lock-screen wallpaper, painted in screen coordinates.
 * For `FLAG_LOCK` in SystemUI, prefers Nubia's exact screen-cropped lock wallpaper file from
 * `MY_CLOCK_STYLE` (`depth_background_wp_*`) and observes style changes live.
 */
class WallpaperBackdrop(
    context: Context,
    private val which: Int = WallpaperManager.FLAG_SYSTEM,
    private val preBlurPx: Float = 0f,
    private val downscale: Int = 1,
) : View(context) {
    private val listeners = ArrayList<() -> Unit>()

    fun addListener(l: () -> Unit) { listeners.add(l) }

    private fun changed() { for (l in listeners.toList()) l() }

    var bitmap: Bitmap? = null
        private set
    var isLive = false
        private set
    private var exactLockCrop = false

    private val xOffset get() = pageX
    private val yOffset get() = pageY
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val main = Handler(Looper.getMainLooper())
    private val wm = WallpaperManager.getInstance(context)
    private val colorsListener = WallpaperManager.OnColorsChangedListener { _, flags ->
        if (flags and which != 0) reload()
    }
    private val styleObserver = object : ContentObserver(main) {
        override fun onChange(selfChange: Boolean) {
            if (which == WallpaperManager.FLAG_LOCK) reload()
        }
    }

    init {
        val dm = context.resources.displayMetrics
        layoutTo(dm.widthPixels, dm.heightPixels)
    }

    fun layoutTo(width: Int, height: Int) {
        if (width == this.width && height == this.height) return
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY))
        layout(0, 0, width, height)
    }

    private var retries = 0
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (bitmap == null && !isLive) { retries = 0; reload() }
        }
    }

    fun start() {
        try { wm.addOnColorsChangedListener(colorsListener, main) } catch (t: Throwable) { Log.w(TAG, "colors listener", t) }
        if (which == WallpaperManager.FLAG_LOCK) {
            try {
                context.contentResolver.registerContentObserver(
                    Settings.System.getUriFor("MY_CLOCK_STYLE"), false, styleObserver
                )
            } catch (_: Throwable) {}
        }
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
        try { context.contentResolver.unregisterContentObserver(styleObserver) } catch (_: Throwable) {}
    }

    fun setOffsets(x: Float, y: Float) = setPageOffsets(x, y)

    fun reload() {
        worker.execute {
            var result: Bitmap? = null
            var live = false
            var exactCrop = false
            try {
                if (which == WallpaperManager.FLAG_LOCK) {
                    val nubia = decodeNubiaLockCrop(downscale)
                    if (nubia != null) {
                        live = nubia.first
                        if (!live && nubia.second != null) {
                            result = if (preBlurPx > 0f) preBlur(nubia.second!!, preBlurPx) else nubia.second
                            exactCrop = true
                        }
                    }
                }
                if (result == null && !live) {
                    live = wm.wallpaperInfo != null && which != WallpaperManager.FLAG_LOCK
                    if (!live) {
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
                }
            } catch (t: Throwable) {
                Log.w(TAG, "wallpaper unavailable", t)
            }
            Log.i(TAG, "wallpaper which=$which ${result?.width}x${result?.height} exact=$exactCrop live=$live")
            main.post {
                bitmap = result
                isLive = live
                exactLockCrop = exactCrop
                changed()
                if (result == null && !live && retries++ < 6) main.postDelayed({ reload() }, 2000L * retries)
                else if (result != null) retries = 0
            }
        }
    }

    /**
     * Reads Nubia's exact visible lock-screen crop saved by `MyDepthLockScreenEditActivity` in
     * `MY_CLOCK_STYLE` (`/data/user_de/0/com.android.systemui/files/depth_background_wp_*`).
     * Returns `(isLive, bitmap)`.
     */
    private fun decodeNubiaLockCrop(sample: Int): Pair<Boolean, Bitmap?>? = try {
        val raw = Settings.System.getString(context.contentResolver, "MY_CLOCK_STYLE")
        if (raw.isNullOrBlank()) null else {
            val json = JSONObject(raw)
            val wpType = json.optInt("wallpaperType", json.optInt("mWallpaperType", 0))
            if (wpType != 0) {
                true to null
            } else {
                val path = json.optString("wallpaperPath").ifBlank { json.optString("mWallpaperPath") }
                val f = if (path.isNotBlank()) File(path) else null
                if (f != null && f.isFile && f.canRead()) {
                    val opt = BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, sample)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    false to BitmapFactory.decodeFile(f.absolutePath, opt)
                } else null
            }
        }
    } catch (_: Throwable) { null }

    private fun decodeFile(sample: Int): Bitmap? {
        for (flag in if (which == WallpaperManager.FLAG_LOCK) intArrayOf(WallpaperManager.FLAG_LOCK, WallpaperManager.FLAG_SYSTEM) else intArrayOf(which)) {
            try {
                wm.getWallpaperFile(flag)?.use { fd ->
                    val opt = BitmapFactory.Options().apply {
                        inSampleSize = maxOf(1, sample)
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                    BitmapFactory.decodeFileDescriptor(fd.fileDescriptor, null, opt)?.let { return it }
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
        paint.colorFilter = if (which == WallpaperManager.FLAG_LOCK) lockColorFilter else null
        val scale = maxOf(w / b.width, h / b.height)
        val sw = b.width * scale
        val sh = b.height * scale
        // Lock-screen ImageWallpaper crops wide multi-screen bitmaps from x=0 (mCropHint 0..1216), not launcher scroll!
        val effX = when {
            exactLockCrop -> 0.5f
            which == WallpaperManager.FLAG_LOCK && b.width > b.height * 1.2f -> 0f
            else -> xOffset
        }
        val effY = if (which == WallpaperManager.FLAG_LOCK) 0.5f else yOffset
        matrix.setScale(scale, scale)
        matrix.postTranslate(-(sw - w) * effX, -(sh - h) * effY)
        canvas.drawBitmap(b, matrix, paint)
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private var shared: WallpaperBackdrop? = null
        private var sharedBlurred: WallpaperBackdrop? = null
        private var sharedLock: WallpaperBackdrop? = null
        @Volatile private var lockFilterMode = WallpaperFilter.NATURAL
        @Volatile private var lockFilterColor = WallpaperFilter.DEFAULT_COLOR
        @Volatile private var lockColorFilter: android.graphics.ColorMatrixColorFilter? = null

        @JvmStatic fun setLockFilter(mode: Int, color: Int) {
            val m = mode.coerceIn(0, WallpaperFilter.COUNT - 1)
            val c = color or 0xFF000000.toInt()
            if (m == lockFilterMode && c == lockFilterColor) return
            lockFilterMode = m
            lockFilterColor = c
            lockColorFilter = WallpaperFilter.filter(m, c)
            sharedLock?.let { it.main.post { it.changed() } }
        }

        @JvmStatic fun sharedLock(context: Context): WallpaperBackdrop =
            sharedLock ?: WallpaperBackdrop(context.applicationContext, WallpaperManager.FLAG_LOCK, downscale = 2).also { sharedLock = it; it.start() }

        @JvmStatic fun sharedBlurred(context: Context): WallpaperBackdrop =
            sharedBlurred ?: WallpaperBackdrop(context.applicationContext, preBlurPx = 40f).also { sharedBlurred = it; it.start() }

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

        @JvmStatic fun shared(context: Context): WallpaperBackdrop =
            shared ?: WallpaperBackdrop(context.applicationContext, downscale = 2).also { shared = it; it.start() }

        @Volatile private var pageX = 0.5f
        @Volatile private var pageY = 0.5f

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
