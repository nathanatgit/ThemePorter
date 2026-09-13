package com.nathanhanapps.nebulaThemePorter.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import androidx.core.graphics.PathParser
import com.nathanhanapps.nebulaThemePorter.core.IconMaskShape
import com.nathanhanapps.nebulaThemePorter.core.TintBlendMode
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object Bitmaps {
    private val filterPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun decode(bytes: ByteArray, maxSize: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSize) sample *= 2
        val options = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        if (decoded.config == Bitmap.Config.ARGB_8888) return decoded
        return decoded.copy(Bitmap.Config.ARGB_8888, false).also { decoded.recycle() }
    }

    fun png(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        out.toByteArray()
    }

    fun jpeg(bitmap: Bitmap, quality: Int = 90): ByteArray = ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        out.toByteArray()
    }

    fun square(size: Int): Bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

    fun solid(size: Int, color: Int): Bitmap = square(size).apply { eraseColor(color) }

    /**
     * Composites [src] over a solid [color] backdrop, discarding its own alpha shape. A pack-supplied icon-back
     * texture is often itself already a rounded square/squircle drawn with transparent padding around it - left
     * as-is, that baked-in silhouette survives every later shape clip unchanged (a clip can only remove pixels,
     * never restore ones the source never had), so every [FixedIconShape] ends up looking like the pack's own
     * shape. Flattening it to fully opaque first makes the later clip the only thing that decides the outline.
     */
    fun opaque(src: Bitmap, color: Int): Bitmap {
        val out = solid(src.width, color)
        Canvas(out).drawBitmap(src, 0f, 0f, filterPaint)
        return out
    }

    /** Preserves source alpha and recolors its grayscale information by combining it with [color] via [mode]. */
    fun tint(source: Bitmap, color: Int, strength: Float, mode: TintBlendMode = TintBlendMode.MULTIPLY): Bitmap {
        val ratio = strength.coerceIn(0f, 1f)
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        val tintRed = Color.red(color)
        val tintGreen = Color.green(color)
        val tintBlue = Color.blue(color)
        // [TintBlendMode.blend] is a pure function of a 0..255 grayscale and one 0..255 tint channel, so a whole
        // bitmap can only ever produce 3 * 256 distinct results. Calling it per pixel re-ran its float pipeline -
        // two divides, a branch, a round and a clamp, plus a sqrt for Soft Light - three times for every pixel,
        // which is what made every mode (Multiply included, since it lost its integer-only fast path when the
        // modes were added) several times slower than before. Tabulating it up front turns the inner loop back
        // into three array reads and leaves the output bit-for-bit identical.
        val redLut = IntArray(256) { mode.blend(it, tintRed) }
        val greenLut = IntArray(256) { mode.blend(it, tintGreen) }
        val blueLut = IntArray(256) { mode.blend(it, tintBlue) }
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            if (alpha == 0) return@forEach
            val red = Color.red(pixel)
            val green = Color.green(pixel)
            val blue = Color.blue(pixel)
            val grayscale = (red * 0.2126f + green * 0.7152f + blue * 0.0722f).roundToInt().coerceIn(0, 255)
            pixels[index] = Color.argb(
                alpha,
                (red + (redLut[grayscale] - red) * ratio).toInt().coerceIn(0, 255),
                (green + (greenLut[grayscale] - green) * ratio).toInt().coerceIn(0, 255),
                (blue + (blueLut[grayscale] - blue) * ratio).toInt().coerceIn(0, 255),
            )
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /**
     * Preserves alpha; maps each RGB channel independently through [lut] (a 256-entry 0..255 table, e.g. from
     * [com.nathanhanapps.nebulaThemePorter.core.GrayscaleCurve.lut]). Meant to run before [tint], whose
     * grayscale-multiply step is what actually turns this tone remap into a visible tint difference.
     */
    fun curve(source: Bitmap, lut: IntArray): Bitmap {
        val out = source.copy(Bitmap.Config.ARGB_8888, true)
        val pixels = IntArray(out.width * out.height)
        out.getPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        pixels.indices.forEach { index ->
            val pixel = pixels[index]
            val alpha = pixel ushr 24
            if (alpha == 0) return@forEach
            pixels[index] = Color.argb(alpha, lut[Color.red(pixel)], lut[Color.green(pixel)], lut[Color.blue(pixel)])
        }
        out.setPixels(pixels, 0, out.width, 0, 0, out.width, out.height)
        return out
    }

    /**
     * Rasterizes a drawable (e.g. an installed app's own launcher icon) into a temporary size x size bitmap.
     *
     * An [AdaptiveIconDrawable] draws its background layer and clips to the platform's own icon mask (usually a
     * circle), which would otherwise bake the app's own shape into what downstream code treats as a plain,
     * unshaped source icon - producing a shape that doesn't match the theme's chosen outline once [FixedIconArt]
     * applies its own background/tint. Drawing the layers directly (skipping the drawable's own `draw()`) avoids
     * that live system mask regardless of [includeBackground].
     *
     * By default only the foreground layer is used, yielding a full-bleed glyph like ordinary icon-pack artwork
     * so the rest of the pipeline shapes and tints it exactly the same way. [includeBackground] additionally
     * draws the adaptive icon's own background layer underneath, for apps whose foreground glyph is only legible
     * against its own designed backdrop (many are, e.g. a white glyph meant to sit on a specific colored disc)
     * and would otherwise wash out against a theme's unified plate color.
     */
    fun fromDrawable(drawable: Drawable, size: Int, includeBackground: Boolean = false): Bitmap {
        val adaptive = drawable as? AdaptiveIconDrawable
        if (adaptive == null) {
            val out = square(size)
            drawable.setBounds(0, 0, size, size)
            drawable.draw(Canvas(out))
            return out
        }
        // Each layer is drawn on a canvas (1 + 2 * extraInsetFraction) times the visible icon size; draw at that
        // larger size, then crop back to the centered, visible size x size square.
        val outer = (size * (1f + 2f * AdaptiveIconDrawable.getExtraInsetFraction())).roundToInt().coerceAtLeast(size)
        val raw = square(outer)
        val canvas = Canvas(raw)
        if (includeBackground) {
            adaptive.background?.let {
                it.setBounds(0, 0, outer, outer)
                it.draw(canvas)
            }
        }
        adaptive.foreground.setBounds(0, 0, outer, outer)
        adaptive.foreground.draw(canvas)
        val inset = (outer - size) / 2
        return Bitmap.createBitmap(raw, inset, inset, size, size)
    }

    /** Draws [src] into a size x size canvas, preserving aspect ratio, occupying [fraction] of the side. */
    fun fit(src: Bitmap, size: Int, fraction: Float = 1f): Bitmap {
        val out = square(size)
        val scale = size * fraction / max(src.width, src.height)
        val w = src.width * scale
        val h = src.height * scale
        Canvas(out).drawBitmap(src, null, RectF((size - w) / 2f, (size - h) / 2f, (size + w) / 2f, (size + h) / 2f), filterPaint)
        return out
    }

    /** Center-crops [src] to fill width x height. */
    fun cover(src: Bitmap, width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val scale = max(width / src.width.toFloat(), height / src.height.toFloat())
        val w = src.width * scale
        val h = src.height * scale
        Canvas(out).drawBitmap(src, null, RectF((width - w) / 2f, (height - h) / 2f, (width + w) / 2f, (height + h) / 2f), filterPaint)
        return out
    }

    /** Draws [src] centered at scale [scale], rotated by [degrees] around the canvas center. */
    fun drawCentered(canvas: Canvas, src: Bitmap, left: Float, size: Int, scale: Float, degrees: Float = 0f) {
        val matrix = Matrix().apply {
            postTranslate(-src.width / 2f, -src.height / 2f)
            postScale(scale, scale)
            postRotate(degrees)
            postTranslate(left + size / 2f, size / 2f)
        }
        canvas.drawBitmap(src, matrix, filterPaint)
    }

    fun alphaBounds(src: Bitmap, threshold: Int = 12): Rect? {
        val w = src.width
        val h = src.height
        val pixels = IntArray(w * h)
        src.getPixels(pixels, 0, w, 0, 0, w, h)
        var left = w
        var top = h
        var right = -1
        var bottom = -1
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) {
                if ((pixels[row + x] ushr 24) > threshold) {
                    if (x < left) left = x
                    if (x > right) right = x
                    if (y < top) top = y
                    bottom = y
                }
            }
        }
        return if (right < 0) null else Rect(left, top, right + 1, bottom + 1)
    }

    /** Crops transparent margins and centers the artwork on a square canvas. */
    fun trimToSquare(src: Bitmap): Bitmap? {
        val bounds = alphaBounds(src) ?: return null
        val side = max(bounds.width(), bounds.height())
        val out = square(side)
        val left = (side - bounds.width()) / 2
        val top = (side - bounds.height()) / 2
        Canvas(out).drawBitmap(src, bounds, Rect(left, top, left + bounds.width(), top + bounds.height()), null)
        return out
    }

    /**
     * The smallest inset (fraction of the side) at which the centered square is opaque, or null when the artwork
     * does not fill its shape. A full-bleed square gives 0, a squircle about 0.07, a circle about 0.15.
     */
    fun opaqueInset(square: Bitmap): Float? {
        val n = 96
        val small = Bitmap.createScaledBitmap(square, n, n, true)
        val pixels = IntArray(n * n)
        small.getPixels(pixels, 0, n, 0, 0, n, n)
        if (small !== square) small.recycle()
        val stride = n + 1
        val integral = IntArray(stride * stride)
        for (y in 0 until n) {
            var row = 0
            for (x in 0 until n) {
                if ((pixels[y * n + x] ushr 24) >= 220) row++
                integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row
            }
        }
        for (inset in 0..(n * 0.22f).toInt()) {
            val a = inset
            val b = n - inset
            val area = (b - a) * (b - a)
            val sum = integral[b * stride + b] - integral[a * stride + b] - integral[b * stride + a] + integral[a * stride + a]
            if (sum >= area * 0.985f) return inset / n.toFloat()
        }
        return null
    }

    /**
     * Centers the square [art] on a size x size canvas and stretches its outermost rows and columns across the
     * margin. Everything sits on the average color of the art's opaque border, so corners the art leaves
     * transparent (rounded source icons) are still filled and the layer is opaque edge to edge.
     */
    fun bleed(art: Bitmap, size: Int): Bitmap {
        val out = square(size)
        val canvas = Canvas(out)
        val a = art.width
        val near = (size - a) / 2f
        val far = near + a
        val end = size.toFloat()
        canvas.drawColor(opaqueBorderColor(art))
        canvas.drawBitmap(art, Rect(0, 0, 1, a), RectF(0f, near, near, far), filterPaint)
        canvas.drawBitmap(art, Rect(a - 1, 0, a, a), RectF(far, near, end, far), filterPaint)
        canvas.drawBitmap(art, Rect(0, 0, a, 1), RectF(near, 0f, far, near), filterPaint)
        canvas.drawBitmap(art, Rect(0, a - 1, a, a), RectF(near, far, far, end), filterPaint)
        canvas.drawBitmap(art, null, RectF(near, near, far, far), filterPaint)
        return out
    }

    /** Average of the border pixels that are at least mostly opaque; white when there are none. */
    private fun opaqueBorderColor(art: Bitmap): Int {
        val w = art.width
        val h = art.height
        var r = 0L
        var g = 0L
        var b = 0L
        var n = 0L
        fun sample(x: Int, y: Int) {
            val c = art.getPixel(x, y)
            if ((c ushr 24) >= 200) {
                r += (c shr 16) and 0xFF
                g += (c shr 8) and 0xFF
                b += c and 0xFF
                n++
            }
        }
        for (x in 0 until w) {
            sample(x, 0)
            sample(x, h - 1)
        }
        for (y in 1 until h - 1) {
            sample(0, y)
            sample(w - 1, y)
        }
        if (n == 0L) return Color.WHITE
        return Color.rgb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
    }

    fun cropInset(square: Bitmap, inset: Float, size: Int): Bitmap {
        val px = (square.width * inset).roundToInt().coerceIn(0, square.width / 3)
        val side = (square.width - px * 2).coerceAtLeast(1)
        val out = square(size)
        Canvas(out).drawBitmap(square, Rect(px, px, px + side, px + side), Rect(0, 0, size, size), filterPaint)
        return out
    }
}

object Shapes {
    // shape.svgPath is static per enum constant; parsing its (sometimes 60+ segment) path data is real work
    // compared to a Path copy, and this is called on every draw of every shape chip/preview in the UI. This is
    // read from both the main thread (drawing the shape picker chips) and background threads (preview/build
    // rendering on Dispatchers.Default) at once, so a plain HashMap here would risk concurrent-modification
    // corruption - a ConcurrentHashMap with an atomic computeIfAbsent avoids that.
    private val unitPaths = java.util.concurrent.ConcurrentHashMap<String, Path>()

    private fun unitPath(shape: IconMaskShape): Path =
        unitPaths.computeIfAbsent(shape.svgPath) { PathParser.createPathFromPathData(it) }

    fun path(shape: IconMaskShape, size: Float, inset: Float = 0f): Path =
        Path(unitPath(shape)).apply {
            transform(
                Matrix().apply {
                    setScale((size - inset * 2) / 100f, (size - inset * 2) / 100f)
                    postTranslate(inset, inset)
                },
            )
        }

    fun mask(shape: IconMaskShape, size: Int, color: Int = Color.BLACK): Bitmap {
        val out = Bitmaps.square(size)
        Canvas(out).drawPath(path(shape, size.toFloat()), Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
        return out
    }

    /** Scales a square [src] to size and keeps only the part inside [shape]. */
    fun clip(src: Bitmap, shape: IconMaskShape, size: Int): Bitmap {
        val out = mask(shape, size)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        }
        Canvas(out).drawBitmap(src, null, RectF(0f, 0f, size.toFloat(), size.toFloat()), paint)
        return out
    }

    /** Translucent white plate behind folder contents (stock theme_folder_icon_config: alpha 153). */
    fun folderIcon(shape: IconMaskShape, size: Int): Bitmap = mask(shape, size, Color.argb(153, 255, 255, 255))

    /** Dashed outline with a plus sign, shown in an open folder's empty slot. */
    fun folderAdd(shape: IconMaskShape, size: Int): Bitmap {
        val out = Bitmaps.square(size)
        val canvas = Canvas(out)
        val stroke = size * 0.012f
        val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = stroke
            color = Color.argb(184, 255, 255, 255)
            pathEffect = android.graphics.DashPathEffect(floatArrayOf(size * 0.03f, size * 0.022f), 0f)
        }
        canvas.drawPath(path(shape, size.toFloat(), inset = stroke), outline)
        val plus = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(230, 255, 255, 255) }
        val half = size * 0.2f
        val thick = size * 0.024f
        val c = size / 2f
        canvas.drawRoundRect(RectF(c - half, c - thick, c + half, c + thick), thick, thick, plus)
        canvas.drawRoundRect(RectF(c - thick, c - half, c + thick, c + half), thick, thick, plus)
        return out
    }
}
