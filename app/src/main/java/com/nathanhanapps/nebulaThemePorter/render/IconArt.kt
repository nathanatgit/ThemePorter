package com.nathanhanapps.nebulaThemePorter.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.Typeface
import com.nathanhanapps.nebulaThemePorter.core.CalendarTextStyle
import com.nathanhanapps.nebulaThemePorter.core.FixedIconShape
import com.nathanhanapps.nebulaThemePorter.core.FixedIconComposition
import com.nathanhanapps.nebulaThemePorter.core.IconMaskShape
import com.nathanhanapps.nebulaThemePorter.core.LayerMode
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.source.CalendarSource
import com.nathanhanapps.nebulaThemePorter.source.ClockSource
import com.nathanhanapps.nebulaThemePorter.source.ThemeSource
import kotlin.math.cos
import kotlin.math.sin

class Layers(val back: Bitmap, val front: Bitmap, val frontIsEmpty: Boolean)

/**
 * Splits a flat icon into the _back/_front pair adaptive themes need.
 *
 * The framework (android.mifavor.IconPackHelper.readShapeDrawable) wraps the two PNGs in a stock
 * AdaptiveIconDrawable, so each layer is drawn at 150% and only its middle two thirds is visible, clipped to the
 * theme shape. Stock layers follow that: a plain full-bleed background and a glyph spanning 43–58% of the layer.
 */
object IconLayering {
    /** Visible part of an AdaptiveIconDrawable layer (72dp of 108dp). */
    const val VISIBLE_FRACTION = 2f / 3f

    /** Glyph size on a plate, as a fraction of the layer; about 72% of the visible icon. */
    private const val FOREGROUND_FRACTION = 0.48f

    /**
     * [forceShape], set only for a generated device-icon fallback, bakes that outline directly into [Layers.back]
     * instead of trusting [mode]'s auto-detection. Real pack/theme artwork is left to the system's own live
     * shape mask like normal, but a raw device icon has no shape of its own to detect, so without this it would
     * end up following whatever shape the system happens to be set to rather than the one selected in the app.
     */
    fun layer(source: Bitmap, mode: LayerMode, padColor: Int, iconBack: Bitmap?, forceShape: IconMaskShape? = null): Layers {
        val size = NebulaSpec.LAYER_SIZE
        val square = Bitmaps.trimToSquare(source) ?: return Layers(Bitmaps.solid(size, padColor), Bitmaps.square(size), true)
        if (forceShape != null) {
            val ownInset = Bitmaps.opaqueInset(square)
            return if (ownInset != null) {
                // The device icon's own artwork already fills its silhouette edge to edge (many app icons have
                // little to no transparent margin). Placing it as a small glyph on a plate would leave its own
                // fill color sitting front and center, reading as if the app's original background were still
                // there. Bleed and clip it to forceShape directly instead, same as a fills-its-shape pack icon.
                val visible = Math.round(size * VISIBLE_FRACTION)
                val bled = Bitmaps.bleed(Bitmaps.cropInset(square, ownInset + 0.01f, visible), size)
                val shapedBack = Shapes.clip(bled, forceShape, size)
                if (shapedBack !== bled) bled.recycle()
                Layers(shapedBack, Bitmaps.square(size), true)
            } else {
                val plate = platedBack(iconBack, padColor, size)
                val shapedBack = Shapes.clip(plate, forceShape, size)
                if (shapedBack !== plate) plate.recycle()
                Layers(shapedBack, Bitmaps.fit(square, size, FOREGROUND_FRACTION), false)
            }
        }
        val inset = when (mode) {
            LayerMode.PAD -> null
            LayerMode.AUTO -> Bitmaps.opaqueInset(square)
            LayerMode.CROP -> Bitmaps.opaqueInset(square) ?: 0.12f
        }
        return if (inset != null) {
            // The artwork fills the visible middle; its edge pixels extend into the hidden margin so no shape or
            // parallax shift ever reveals a hard border. One extra percent keeps anti-aliased edges out. There is
            // no separate plate here - the icon's own art is the whole layer - so the background color has
            // nothing to apply to; that is expected, not a bug.
            val visible = Math.round(size * VISIBLE_FRACTION)
            Layers(Bitmaps.bleed(Bitmaps.cropInset(square, inset + 0.01f, visible), size), Bitmaps.square(size), true)
        } else {
            Layers(platedBack(iconBack, padColor, size), Bitmaps.fit(square, size, FOREGROUND_FRACTION), false)
        }
    }

    /**
     * The plate behind a silhouette glyph. A pack-provided [iconBack] is tinted with [padColor] (preserving its
     * own shading/pattern) rather than used as-is, so the selected background color always has a visible effect
     * instead of being silently overridden by whatever color the pack's own plate happened to ship with.
     */
    private fun platedBack(iconBack: Bitmap?, padColor: Int, size: Int): Bitmap =
        iconBack?.let {
            val covered = Bitmaps.cover(it, size, size)
            Bitmaps.tint(covered, padColor, 1f).also { covered.recycle() }
        } ?: Bitmaps.solid(size, padColor)

    /** Both layers flattened, unzoomed. */
    fun composite(layers: Layers): Bitmap {
        val out = layers.back.copy(Bitmap.Config.ARGB_8888, true)
        if (!layers.frontIsEmpty) Canvas(out).drawBitmap(layers.front, 0f, 0f, null)
        return out
    }

    /** What the launcher shows before shape clipping: the middle two thirds of [composite], scaled to [size]. */
    fun launcherView(composite: Bitmap, size: Int): Bitmap {
        val side = composite.width * VISIBLE_FRACTION
        val offset = (composite.width - side) / 2f
        val out = Bitmaps.square(size)
        Canvas(out).drawBitmap(
            composite,
            android.graphics.Rect(Math.round(offset), Math.round(offset), Math.round(offset + side), Math.round(offset + side)),
            android.graphics.Rect(0, 0, size, size),
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
        )
        return out
    }
}

/** Renders a final, pre-shaped PNG for a fixed-shape theme. No launcher config or system mask is involved. */
object FixedIconArt {
    fun render(
        source: Bitmap,
        shape: FixedIconShape,
        composition: FixedIconComposition,
        iconScale: Float,
        iconAlpha: Float,
        tintColor: Int?,
        tintStrength: Float,
        backgroundScale: Float,
        padColor: Int,
        iconBack: Bitmap?,
    ): Bitmap {
        val size = NebulaSpec.FIXED_ICON_SIZE
        val artwork = tintColor?.let { Bitmaps.tint(source, it, tintStrength) } ?: source
        if (shape.isOriginal) return Bitmaps.fit(artwork, size).also { if (artwork !== source) artwork.recycle() }
        val backgroundSize = (size * backgroundScale.coerceIn(0.35f, 1.25f)).toInt().coerceAtLeast(1)
        val iconFraction = iconScale.coerceIn(0.25f, 1.25f)
        val alpha = (iconAlpha.coerceIn(0f, 1f) * 255).toInt()
        val out = Bitmaps.square(size)
        when (composition) {
            FixedIconComposition.OVERLAY -> {
                drawCentered(Canvas(out), plate(shape, backgroundSize, padColor, iconBack))
                // Trim away the source's own transparent margin first: many pack icons are already pre-shaped
                // (their own rounded corner baked in as a transparent cutout), and left untouched that native
                // shape - not the plate underneath - is what reads as "the icon's shape" once it's on top.
                val trimmed = Bitmaps.trimToSquare(artwork) ?: artwork
                // A plate is an overlay treatment, not a veil over the imported glyph. Keep the glyph on top.
                val icon = Bitmaps.fit(trimmed, size, iconFraction)
                if (trimmed !== artwork) trimmed.recycle()
                Canvas(out).drawBitmap(icon, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
                icon.recycle()
            }
            FixedIconComposition.COVER -> {
                // A cropped icon with its own transparent margins would otherwise leave bare gaps; give every
                // icon the same plate Overlay uses so there's always a background under it.
                drawCentered(Canvas(out), plate(shape, backgroundSize, padColor, iconBack))
                // Same reasoning as Overlay: strip the icon's own pre-baked shape before re-cropping and
                // re-clipping it to the selected shape, so that shape - not the source's own outline - is what
                // ends up bounding the final result. Without this, cover() fills edge to edge with the icon's
                // own already-shaped art, and the clip below never gets a chance to be the limiting boundary.
                val trimmed = Bitmaps.trimToSquare(artwork) ?: artwork
                val covered = Bitmaps.cover(trimmed, backgroundSize, backgroundSize)
                if (trimmed !== artwork) trimmed.recycle()
                val clipped = Shapes.clip(covered, shape, backgroundSize)
                covered.recycle()
                drawCentered(Canvas(out), clipped)
            }
            FixedIconComposition.CLIP -> {
                drawCentered(Canvas(out), plate(shape, backgroundSize, padColor, iconBack))
                val icon = Bitmaps.fit(artwork, backgroundSize, iconFraction / backgroundScale.coerceAtLeast(0.35f))
                val clipped = Shapes.clip(icon, shape, backgroundSize)
                icon.recycle()
                drawCentered(Canvas(out), clipped)
            }
        }
        if (artwork !== source) artwork.recycle()
        return out
    }

    // Tinting a pack/custom iconBack (rather than using it as-is) keeps its own shading/pattern while still
    // guaranteeing the selected background color is visible, same as IconLayering.platedBack for adaptive icons.
    private fun plate(shape: FixedIconShape, size: Int, color: Int, iconBack: Bitmap?): Bitmap {
        val content = iconBack?.let {
            val covered = Bitmaps.cover(it, size, size)
            Bitmaps.tint(covered, color, 1f).also { covered.recycle() }
        } ?: Bitmaps.solid(size, color)
        return Shapes.clip(content, shape, size).also { content.recycle() }
    }

    private fun drawCentered(canvas: Canvas, bitmap: Bitmap) {
        val left = (canvas.width - bitmap.width) / 2
        val top = (canvas.height - bitmap.height) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + bitmap.width, top + bitmap.height), null)
        bitmap.recycle()
    }
}

class CalendarArt(
    val days: Map<Int, Bitmap>,
    val background: Bitmap?,
    val textColor: Int?,
    val textSizeRatio: Float?,
    val showWeek: Boolean,
    val fromSource: Boolean,
) {
    val hasCompleteDays: Boolean get() = days.size >= 28
}

class ClockArt(val dial: Bitmap?, val hour: Bitmap?, val minute: Bitmap?, val canvasSize: Int)

/** theme_dynamic_calendar (32 frames: days 1..31, background) and theme_dynamic_clock (hour, minute, dial). */
object DynamicIcons {
    private const val F = NebulaSpec.ASSET_SIZE
    private const val GENERATED_WEEK_COLOR = 0xFFE53935

    /** Decodes an image id, then applies the same fixed-icon tint app icons get, so both stay in sync. */
    private fun decodeTinted(source: ThemeSource, id: String, tintColor: Int?, tintStrength: Float): Bitmap? {
        val bitmap = source.decode(id, F * 2) ?: return null
        if (tintColor == null) return bitmap
        return Bitmaps.tint(bitmap, tintColor, tintStrength).also { bitmap.recycle() }
    }

    fun loadCalendar(source: ThemeSource, calendar: CalendarSource?, tintColor: Int? = null, tintStrength: Float = 1f): CalendarArt {
        if (calendar == null) return CalendarArt(emptyMap(), null, null, null, showWeek = true, fromSource = false)
        val days = calendar.dayImages.mapNotNull { (day, id) -> decodeTinted(source, id, tintColor, tintStrength)?.let { day to it } }.toMap()
        val background = calendar.background?.let { decodeTinted(source, it, tintColor, tintStrength) }
        return CalendarArt(days, background, calendar.textColor?.toInt(), calendar.textSizeRatio, calendar.showWeek, fromSource = true)
    }

    fun loadClock(source: ThemeSource, clock: ClockSource?, tintColor: Int? = null, tintStrength: Float = 1f): ClockArt? {
        clock ?: return null
        return ClockArt(
            dial = clock.dial?.let { decodeTinted(source, it, tintColor, tintStrength) },
            hour = decodeTinted(source, clock.hour, tintColor, tintStrength),
            minute = decodeTinted(source, clock.minute, tintColor, tintStrength),
            canvasSize = clock.canvasSize,
        )
    }

    fun calendarTextStyle(art: CalendarArt): CalendarTextStyle = when {
        art.hasCompleteDays -> CalendarTextStyle(showWeekInfo = false)
        art.fromSource -> CalendarTextStyle(showWeekInfo = art.showWeek, textColor = (art.textColor ?: Color.WHITE).toLong() and 0xFFFFFFFFL)
        else -> CalendarTextStyle(showWeekInfo = true, textColor = GENERATED_WEEK_COLOR)
    }

    /** [clipSource] shapes source artwork to [shape]; generated artwork is always drawn in [shape]. */
    fun calendarStrip(art: CalendarArt, shape: IconMaskShape, clipSource: Boolean): Bitmap {
        val strip = Bitmap.createBitmap(F * NebulaSpec.CALENDAR_FRAMES, F, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        fun prepare(bitmap: Bitmap): Bitmap =
            if (clipSource) Shapes.clip(Bitmaps.trimToSquare(bitmap) ?: bitmap, shape, F) else Bitmaps.fit(bitmap, F)

        if (art.hasCompleteDays) {
            // Each MIUI/icon-pack day is a complete icon. Like other community calendar stitchers, the background frame
            // repeats day 1 so the launcher never shows an empty tile behind the date.
            var previous: Bitmap? = null
            var first: Bitmap? = null
            for (day in 1..31) {
                val tile = art.days[day]?.let(::prepare)?.also { previous = it } ?: previous ?: continue
                if (first == null) first = tile
                canvas.drawBitmap(tile, ((day - 1) * F).toFloat(), 0f, null)
            }
            first?.let { canvas.drawBitmap(it, (31 * F).toFloat(), 0f, null) }
            return strip
        }

        val background = art.background?.let(::prepare) ?: Shapes.clip(Bitmaps.solid(F, Color.WHITE), shape, F)
        canvas.drawBitmap(background, (31 * F).toFloat(), 0f, null)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (art.fromSource) art.textColor ?: Color.WHITE else 0xFF202124.toInt()
            textSize = F * (art.textSizeRatio ?: if (art.fromSource) 0.33f else 0.42f)
            typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val metrics = paint.fontMetrics
        val centerY = F * if (art.showWeek) 0.58f else 0.5f
        val baseline = centerY - (metrics.ascent + metrics.descent) / 2f
        for (day in 1..31) {
            canvas.drawText(day.toString(), (day - 1) * F + F / 2f, baseline, paint)
        }
        return strip
    }

    /**
     * [tintColor], when set, only affects hands/ticks drawn from scratch - a source dial/hands bitmap is already
     * tinted by [loadClock] before it reaches this function, same as any other imported artwork.
     */
    fun clockStrip(art: ClockArt?, shape: IconMaskShape, clipSource: Boolean, tintColor: Int? = null): Bitmap {
        val strip = Bitmap.createBitmap(F * NebulaSpec.CLOCK_FRAMES, F, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        if (art?.hour != null && art.minute != null) {
            val scale = F / art.canvasSize.toFloat()
            // MIUI hands point at 12 o'clock; Nebula strips draw them at 3 o'clock.
            Bitmaps.drawCentered(canvas, art.hour, 0f, F, scale, 90f)
            Bitmaps.drawCentered(canvas, art.minute, F.toFloat(), F, scale, 90f)
        } else {
            // The dial face itself becomes tintColor below, so the hands need to contrast against it instead of
            // also turning that same color.
            val handColor = if (tintColor != null) Color.WHITE else 0xFF202124.toInt()
            val accentColor = if (tintColor != null) Color.WHITE else 0xFF3E6CF0.toInt()
            drawHand(canvas, 0f, length = 0.25f, width = 0.05f, color = handColor, pivot = false)
            drawHand(canvas, F.toFloat(), length = 0.35f, width = 0.034f, color = accentColor, pivot = true)
        }
        val dial = art?.dial?.let { source ->
            if (clipSource) Shapes.clip(Bitmaps.trimToSquare(source) ?: source, shape, F)
            else Bitmaps.square(F).also { Bitmaps.drawCentered(Canvas(it), source, 0f, F, F / art.canvasSize.toFloat()) }
        } ?: generatedDial(shape, tintColor)
        canvas.drawBitmap(dial, (2 * F).toFloat(), 0f, null)
        return strip
    }

    fun shortcutBackground(size: Int, shape: IconMaskShape?): Bitmap {
        val plate = Bitmaps.square(size)
        Canvas(plate).drawRect(
            0f, 0f, size.toFloat(), size.toFloat(),
            Paint().apply {
                shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), 0xFF6A9BFF.toInt(), 0xFF3E6CF0.toInt(), Shader.TileMode.CLAMP)
            },
        )
        return shape?.let { Shapes.clip(plate, it, size) } ?: plate
    }

    private fun generatedDial(shape: IconMaskShape, tintColor: Int? = null): Bitmap {
        val dial = Shapes.clip(Bitmaps.solid(F, tintColor ?: Color.WHITE), shape, F)
        val canvas = Canvas(dial)
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (tintColor != null) Color.WHITE else 0xFFB0B4BA.toInt()
            strokeWidth = F * 0.018f
            strokeCap = Paint.Cap.ROUND
        }
        val c = F / 2f
        for (hour in 0 until 12) {
            val angle = Math.toRadians(hour * 30.0)
            val outer = F * 0.36f
            val inner = F * if (hour % 3 == 0) 0.29f else 0.32f
            canvas.drawLine(
                c + (sin(angle) * inner).toFloat(), c - (cos(angle) * inner).toFloat(),
                c + (sin(angle) * outer).toFloat(), c - (cos(angle) * outer).toFloat(),
                tick,
            )
        }
        return dial
    }

    private fun drawHand(canvas: Canvas, left: Float, length: Float, width: Float, color: Int, pivot: Boolean) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val c = F / 2f
        val half = F * width / 2f
        canvas.drawRoundRect(RectF(left + c - F * 0.05f, c - half, left + c + F * length, c + half), half, half, paint)
        if (pivot) {
            canvas.drawCircle(left + c, c, F * 0.045f, paint)
            canvas.drawCircle(left + c, c, F * 0.02f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        }
    }
}
