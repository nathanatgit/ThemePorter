package com.nathanhanapps.nebulaThemePorter.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.GeneratedWallpaper
import com.nathanhanapps.nebulaThemePorter.core.GradientMix
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.cos
import kotlin.math.sin

/** Theme-card preview pictures (preview00..), drawn at the stock 1080x2400 size. */
object PreviewRenderer {
    private const val W = NebulaSpec.PREVIEW_WIDTH
    private const val H = NebulaSpec.PREVIEW_HEIGHT
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Draws the editable fallback wallpaper. Working at a smaller resolution makes colour boundaries soft. */
    fun gradientWallpaper(width: Int, height: Int, settings: GeneratedWallpaper = GeneratedWallpaper()): Bitmap {
        val scale = (0.36f - settings.blur.coerceIn(0f, 1f) * 0.23f).coerceAtLeast(0.13f)
        val workW = max(72, (width * scale).toInt())
        val workH = max(72, (height * scale).toInt())
        val work = Bitmap.createBitmap(workW, workH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(work)
        val colors = intArrayOf(
            settings.colorA.toInt(), settings.colorB.toInt(), settings.colorC.toInt(),
        )
        when (settings.mix) {
            GradientMix.LINEAR -> drawLinearMix(canvas, workW, workH, colors, settings.angle, settings.seed)
            GradientMix.RADIAL -> drawRadialMix(canvas, workW, workH, colors, settings.angle, settings.seed)
            GradientMix.BLOBS -> drawBlobs(canvas, workW, workH, colors, settings.seed)
        }
        return Bitmap.createScaledBitmap(work, width, height, true).also { work.recycle() }
    }

    private fun drawLinearMix(canvas: Canvas, width: Int, height: Int, colors: IntArray, angle: Float, seed: Int) {
        val radians = Math.toRadians(angle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val side = max(width, height).toFloat()
        val shift = floatArrayOf(-0.18f, -0.06f, 0.08f, 0.19f)[Math.floorMod(seed, 4)] * side
        val centerX = width / 2f - dy * shift
        val centerY = height / 2f + dx * shift
        canvas.drawRect(
            0f, 0f, width.toFloat(), height.toFloat(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(
                    centerX - dx * side,
                    centerY - dy * side,
                    centerX + dx * side,
                    centerY + dy * side,
                    colors,
                    null,
                    Shader.TileMode.CLAMP,
                )
            },
        )
    }

    private fun drawRadialMix(canvas: Canvas, width: Int, height: Int, colors: IntArray, angle: Float, seed: Int) {
        canvas.drawColor(colors[1])
        val radians = Math.toRadians(angle.toDouble())
        val dx = cos(radians).toFloat()
        val dy = sin(radians).toFloat()
        val side = max(width, height).toFloat()
        val offset = floatArrayOf(-0.16f, -0.05f, 0.08f, 0.18f)[Math.floorMod(seed, 4)] * side
        val centerX = width / 2f - dy * offset
        val centerY = height / 2f + dx * offset
        softBlob(canvas, centerX - dx * side * 0.29f, centerY - dy * side * 0.29f, side * 0.68f, colors[0])
        softBlob(canvas, centerX + dx * side * 0.29f, centerY + dy * side * 0.29f, side * 0.72f, colors[2])
    }

    private fun drawBlobs(canvas: Canvas, width: Int, height: Int, colors: IntArray, seed: Int) {
        canvas.drawColor(blend(colors[0], colors[1], 0.46f))
        val patterns = arrayOf(
            floatArrayOf(0.13f, 0.16f, 0.76f, 0.78f, 0.52f, 0.86f),
            floatArrayOf(0.74f, 0.15f, 0.30f, 0.68f, 0.86f, 0.70f),
            floatArrayOf(0.18f, 0.72f, 0.75f, 0.34f, 0.50f, 0.12f),
        )
        val p = patterns[Math.floorMod(seed, patterns.size)]
        val radius = max(width, height) * 0.72f
        softBlob(canvas, width * p[0], height * p[1], radius, colors[0])
        softBlob(canvas, width * p[2], height * p[3], radius, colors[1])
        softBlob(canvas, width * p[4], height * p[5], radius, colors[2])
    }

    private fun softBlob(canvas: Canvas, x: Float, y: Float, radius: Float, color: Int) {
        val transparent = color and 0x00FFFFFF
        canvas.drawCircle(
            x, y, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = RadialGradient(x, y, radius, intArrayOf(color, transparent), floatArrayOf(0f, 1f), Shader.TileMode.CLAMP)
            },
        )
    }

    private fun blend(first: Int, second: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        return Color.rgb(
            (Color.red(first) + (Color.red(second) - Color.red(first)) * t).toInt(),
            (Color.green(first) + (Color.green(second) - Color.green(first)) * t).toInt(),
            (Color.blue(first) + (Color.blue(second) - Color.blue(first)) * t).toInt(),
        )
    }

    fun homeScreen(wallpaper: Bitmap, icons: List<Bitmap>): Bitmap {
        val out = Bitmaps.cover(wallpaper, W, H)
        val canvas = Canvas(out)
        val cell = W / 4f
        val iconSize = 176f
        icons.take(20).forEachIndexed { index, icon ->
            val left = (index % 4) * cell + (cell - iconSize) / 2f
            val top = 330f + (index / 4) * 300f
            canvas.drawBitmap(icon, null, RectF(left, top, left + iconSize, top + iconSize), iconPaint)
        }
        val dockTop = H - 400f
        canvas.drawRoundRect(RectF(40f, dockTop, W - 40f, dockTop + 280f), 84f, 84f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(70, 255, 255, 255) })
        icons.drop(20).take(4).forEachIndexed { index, icon ->
            val left = index * cell + (cell - iconSize) / 2f
            val top = dockTop + (280f - iconSize) / 2f
            canvas.drawBitmap(icon, null, RectF(left, top, left + iconSize, top + iconSize), iconPaint)
        }
        return out
    }

    fun iconSheet(icons: List<Bitmap>): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawRect(
            0f, 0f, W.toFloat(), H.toFloat(),
            Paint().apply { shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0xFFF4F6FB.toInt(), 0xFFE3E8F4.toInt(), Shader.TileMode.CLAMP) },
        )
        val columns = 5
        val cell = W / columns.toFloat()
        val iconSize = 150f
        icons.take(45).forEachIndexed { index, icon ->
            val left = (index % columns) * cell + (cell - iconSize) / 2f
            val top = 220f + (index / columns) * 230f
            canvas.drawBitmap(icon, null, RectF(left, top, left + iconSize, top + iconSize), iconPaint)
        }
        return out
    }

    fun lockScreen(wallpaper: Bitmap, now: LocalDateTime = LocalDateTime.now()): Bitmap {
        val out = Bitmaps.cover(wallpaper, W, H)
        val canvas = Canvas(out)
        val shadow = Color.argb(90, 0, 0, 0)
        val time = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 250f
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            setShadowLayer(12f, 0f, 4f, shadow)
        }
        val date = Paint(time).apply {
            textSize = 58f
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL)
        }
        canvas.drawText(now.format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)), W / 2f, 620f, time)
        canvas.drawText(now.format(DateTimeFormatter.ofPattern("M月d日 EEEE", Locale.CHINA)), W / 2f, 720f, date)
        return out
    }
}
