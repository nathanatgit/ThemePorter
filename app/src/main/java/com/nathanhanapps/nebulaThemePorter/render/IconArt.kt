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
import com.nathanhanapps.nebulaThemePorter.core.NebulaSpec
import com.nathanhanapps.nebulaThemePorter.core.TintBlendMode
import com.nathanhanapps.nebulaThemePorter.source.CalendarSource
import com.nathanhanapps.nebulaThemePorter.source.ClockSource
import com.nathanhanapps.nebulaThemePorter.source.ThemeSource
import kotlin.math.cos
import kotlin.math.sin

// Tinting a pack/custom iconBack (rather than using it as-is) keeps its own shading/pattern while still
// guaranteeing the selected background color is visible. File-level (not a member of FixedIconArt) so
// DynamicIcons can build the same background plate behind generated calendar/clock artwork.
private fun plate(
    shape: FixedIconShape,
    size: Int,
    color: Int,
    iconBack: Bitmap?,
    flatten: Boolean,
    tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
): Bitmap {
    val content = iconBack?.let {
        val covered = Bitmaps.cover(it, size, size)
        val tinted = Bitmaps.tint(covered, color, 1f, tintBlendMode).also { covered.recycle() }
        if (flatten) Bitmaps.opaque(tinted, color).also { tinted.recycle() } else tinted
    } ?: Bitmaps.solid(size, color)
    return Shapes.clip(content, shape, size).also { content.recycle() }
}

/** Perceptual-luminance dark/light split, used to pick a contrasting hand/tick color against an arbitrary plate. */
private fun isDark(color: Int): Boolean {
    val luminance = 0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)
    return luminance < 140
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
        /** [com.nathanhanapps.nebulaThemePorter.core.GrayscaleCurve.lut], applied to [source] before [tintColor]
         * multiplies it, so a per-app tone tweak can even out how differently-lit source icons end up looking
         * once every icon shares the same tint. */
        curveLut: IntArray? = null,
        tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
        /** Lets an in-app preview render far smaller than the exported PNG - see [NebulaSpec.PREVIEW_ICON_SIZE]. */
        size: Int = NebulaSpec.FIXED_ICON_SIZE,
        /** Precomputed background plate from [buildPlate], reused across many calls that share
         * shape/backgroundScale/padColor/iconBack/tintBlendMode - e.g. every icon in a grid list - instead of
         * rebuilding the identical plate bitmap once per icon. Must already be sized for this call's
         * backgroundSize (`size * backgroundScale`); a mismatched size silently stretches. Ownership stays with
         * the caller: unlike a plate this function builds itself, a supplied one is never recycled here. */
        plate: Bitmap? = null,
    ): Bitmap {
        val prepared = curveLut?.let { Bitmaps.curve(source, it) } ?: source
        val artwork = tintColor?.let { Bitmaps.tint(prepared, it, tintStrength, tintBlendMode) } ?: prepared
        if (prepared !== source && prepared !== artwork) prepared.recycle()
        if (shape.isOriginal) return Bitmaps.fit(artwork, size).also { if (artwork !== source) artwork.recycle() }
        val backgroundSize = (size * backgroundScale.coerceIn(0.35f, 1.25f)).toInt().coerceAtLeast(1)
        val iconFraction = iconScale.coerceIn(0.25f, 1.25f)
        val alpha = (iconAlpha.coerceIn(0f, 1f) * 255).toInt()
        val out = Bitmaps.square(size)
        // Flattened to opaque: the plate is the only thing on screen showing the chosen shape (Overlay's glyph
        // sits smaller on top, untouched), so a pack iconBack's own baked-in silhouette must not survive the clip
        // below unflattened, or every shape choice ends up looking like the pack's own.
        val builtPlate = plate ?: plate(shape, backgroundSize, padColor, iconBack, flatten = true, tintBlendMode)
        when (composition) {
            FixedIconComposition.OVERLAY -> {
                drawCentered(Canvas(out), builtPlate, recycle = plate == null)
                // A plate is an overlay treatment, not a veil over the imported glyph. Keep the glyph on top,
                // with its own outline untouched, so the selected shape reads from the plate's margin instead.
                val icon = Bitmaps.fit(artwork, size, iconFraction)
                Canvas(out).drawBitmap(icon, 0f, 0f, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.alpha = alpha })
                icon.recycle()
            }
            FixedIconComposition.COVER -> {
                drawCentered(Canvas(out), builtPlate, recycle = plate == null)
                // Many pack icons are themselves pre-shaped artwork - a full square canvas with a rounded
                // background already baked in and transparent padding around it - not a bare glyph. Covering
                // that raw canvas only rescales its outer bounding box, so the baked-in shape (and the padding
                // around it) survives untouched and the later clip has nothing real left to cut: every chosen
                // shape ends up looking like the pack's own. Trimming to the actual opaque content first means
                // the cover scales the real artwork, not its padding, so the clip governs the outline again.
                val trimmed = Bitmaps.trimToSquare(artwork) ?: artwork
                val covered = Bitmaps.cover(trimmed, backgroundSize, backgroundSize)
                if (trimmed !== artwork) trimmed.recycle()
                val clipped = Shapes.clip(covered, shape, backgroundSize)
                covered.recycle()
                drawCentered(Canvas(out), clipped)
            }
            FixedIconComposition.CLIP -> {
                drawCentered(Canvas(out), builtPlate, recycle = plate == null)
                val icon = Bitmaps.fit(artwork, backgroundSize, iconFraction / backgroundScale.coerceAtLeast(0.35f))
                val clipped = Shapes.clip(icon, shape, backgroundSize)
                icon.recycle()
                drawCentered(Canvas(out), clipped)
            }
        }
        if (artwork !== source) artwork.recycle()
        return out
    }

    /**
     * Builds the same background plate [render] draws behind an icon (shape-clipped, opaque-flattened
     * iconBack/padColor). Exposed so a caller rendering many icons that share shape/backgroundScale/padColor/
     * iconBack/tintBlendMode - a grid of thumbnails, most obviously - can build it once and pass it back into
     * [render]'s `plate` parameter instead of paying for the identical cover/tint/flatten/clip work per icon.
     */
    fun buildPlate(shape: FixedIconShape, size: Int, padColor: Int, iconBack: Bitmap?, tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY): Bitmap =
        plate(shape, size, padColor, iconBack, flatten = true, tintBlendMode)

    private fun drawCentered(canvas: Canvas, bitmap: Bitmap, recycle: Boolean = true) {
        val left = (canvas.width - bitmap.width) / 2
        val top = (canvas.height - bitmap.height) / 2
        canvas.drawBitmap(bitmap, null, Rect(left, top, left + bitmap.width, top + bitmap.height), null)
        if (recycle) bitmap.recycle()
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

    /** The plate side [FixedIconArt.render] derives for a size-[F] frame, so a prebuilt plate matches it exactly. */
    private fun plateSize(backgroundScale: Float): Int =
        (F * backgroundScale.coerceIn(0.35f, 1.25f)).toInt().coerceAtLeast(1)

    /** Decodes an image id, then applies the same fixed-icon tint app icons get, so both stay in sync. */
    private fun decodeTinted(source: ThemeSource, id: String, tintColor: Int?, tintStrength: Float, tintBlendMode: TintBlendMode): Bitmap? {
        val bitmap = source.decode(id, F * 2) ?: return null
        if (tintColor == null) return bitmap
        return Bitmaps.tint(bitmap, tintColor, tintStrength, tintBlendMode).also { bitmap.recycle() }
    }

    fun loadCalendar(
        source: ThemeSource,
        calendar: CalendarSource?,
        tintColor: Int? = null,
        tintStrength: Float = 1f,
        tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
    ): CalendarArt {
        if (calendar == null) return CalendarArt(emptyMap(), null, null, null, showWeek = true, fromSource = false)
        val days = calendar.dayImages.mapNotNull { (day, id) -> decodeTinted(source, id, tintColor, tintStrength, tintBlendMode)?.let { day to it } }.toMap()
        val background = calendar.background?.let { decodeTinted(source, it, tintColor, tintStrength, tintBlendMode) }
        return CalendarArt(days, background, calendar.textColor?.toInt(), calendar.textSizeRatio, calendar.showWeek, fromSource = true)
    }

    fun loadClock(
        source: ThemeSource,
        clock: ClockSource?,
        tintColor: Int? = null,
        tintStrength: Float = 1f,
        tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
    ): ClockArt? {
        clock ?: return null
        return ClockArt(
            dial = clock.dial?.let { decodeTinted(source, it, tintColor, tintStrength, tintBlendMode) },
            hour = decodeTinted(source, clock.hour, tintColor, tintStrength, tintBlendMode),
            minute = decodeTinted(source, clock.minute, tintColor, tintStrength, tintBlendMode),
            canvasSize = clock.canvasSize,
        )
    }

    fun calendarTextStyle(art: CalendarArt): CalendarTextStyle = when {
        art.hasCompleteDays -> CalendarTextStyle(showWeekInfo = false)
        art.fromSource -> CalendarTextStyle(showWeekInfo = art.showWeek, textColor = (art.textColor ?: Color.WHITE).toLong() and 0xFFFFFFFFL)
        else -> CalendarTextStyle(showWeekInfo = true, textColor = GENERATED_WEEK_COLOR)
    }

    /**
     * [clipSource] shapes source artwork to [shape]; generated artwork is always drawn in [shape]. When
     * [clipSource] is true (a fixed shape is active), every frame gets the same [padColor]/[iconBack] plate a real
     * app icon gets in Icon composition drawn behind it first - a pack's own calendar art (per-day tiles or a
     * shared background frame) is very often only a numeral/decoration layer with transparent padding, meant to
     * be layered over a background rather than treated as a complete, already-opaque icon. Layering an opaque
     * source frame over the plate is a visual no-op, so this is safe to always do, not just when the pack has no
     * calendar art of its own.
     */
    fun calendarStrip(
        art: CalendarArt,
        shape: FixedIconShape,
        clipSource: Boolean,
        padColor: Int = Color.WHITE,
        iconBack: Bitmap? = null,
        tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
        composition: FixedIconComposition = FixedIconComposition.OVERLAY,
        iconScale: Float = 1f,
        iconAlpha: Float = 1f,
        backgroundScale: Float = 1f,
    ): Bitmap {
        val strip = Bitmap.createBitmap(F * NebulaSpec.CALENDAR_FRAMES, F, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        val plate = if (clipSource) FixedIconArt.buildPlate(shape, plateSize(backgroundScale), padColor, iconBack, tintBlendMode) else null
        // Each frame goes through the very same call an app icon does, so composition, icon size and background
        // size reach the calendar exactly as they reach everything else. Composing the frame here by hand instead
        // meant a full-size plate with full-size art stacked on it no matter what those three controls said.
        // tintColor is null because loadCalendar already tinted this artwork - render() would otherwise tint twice.
        fun compose(bitmap: Bitmap): Bitmap =
            if (!clipSource) Bitmaps.fit(bitmap, F)
            else FixedIconArt.render(
                source = bitmap, shape = shape, composition = composition, iconScale = iconScale,
                iconAlpha = iconAlpha, tintColor = null, tintStrength = 1f, backgroundScale = backgroundScale,
                padColor = padColor, iconBack = iconBack, tintBlendMode = tintBlendMode, size = F, plate = plate,
            )

        if (art.hasCompleteDays) {
            // Each MIUI/icon-pack day is a complete icon. Like other community calendar stitchers, the background frame
            // repeats day 1 so the launcher never shows an empty tile behind the date.
            var previous: Bitmap? = null
            var first: Bitmap? = null
            for (day in 1..31) {
                val tile = art.days[day]?.let(::compose)?.also { previous = it } ?: previous ?: continue
                if (first == null) first = tile
                canvas.drawBitmap(tile, (day - 1) * F.toFloat(), 0f, null)
            }
            first?.let { canvas.drawBitmap(it, 31 * F.toFloat(), 0f, null) }
            return strip
        }

        val background = art.background?.let(::compose)
        when {
            background != null -> canvas.drawBitmap(background, 31 * F.toFloat(), 0f, null)
            // No calendar art of its own, so the frame is the plate alone - centered where render() would put it,
            // so a background size under 100% leaves the same margin an app icon gets.
            plate != null -> canvas.drawBitmap(plate, 31 * F + (F - plate.width) / 2f, (F - plate.height) / 2f, null)
            else -> canvas.drawBitmap(Shapes.clip(Bitmaps.solid(F, Color.WHITE), shape, F), 31 * F.toFloat(), 0f, null)
        }
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
     * tinted by [loadClock] before it reaches this function, same as any other imported artwork. When [clipSource]
     * is true, the dial gets the same [padColor]/[iconBack] plate a real app icon gets in Icon composition drawn
     * behind it - under a source dial too, not just the generated fallback, since a source dial is often only a
     * face/ticks layer with transparent padding. Hand/tick color contrasts against the dial's actual rendered
     * color (sampled from the plate itself when there is one) rather than assuming "any tint means a dark dial",
     * which previously turned the hands invisible-white against a still-light/white plate.
     */
    fun clockStrip(
        art: ClockArt?,
        shape: FixedIconShape,
        clipSource: Boolean,
        tintColor: Int? = null,
        padColor: Int = Color.WHITE,
        iconBack: Bitmap? = null,
        tintBlendMode: TintBlendMode = TintBlendMode.MULTIPLY,
        composition: FixedIconComposition = FixedIconComposition.OVERLAY,
        iconScale: Float = 1f,
        iconAlpha: Float = 1f,
        backgroundScale: Float = 1f,
    ): Bitmap {
        val strip = Bitmap.createBitmap(F * NebulaSpec.CLOCK_FRAMES, F, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(strip)
        val plate = if (clipSource) FixedIconArt.buildPlate(shape, plateSize(backgroundScale), padColor, iconBack, tintBlendMode) else null
        val dialIsDark = when {
            plate != null -> isDark(plate.getPixel(plate.width / 2, plate.height / 2))
            tintColor != null -> isDark(tintColor)
            else -> false
        }
        // Hands are their own layer, rotated over the dial by the launcher, so they have to shrink with it - a
        // dial at 35% with full-length hands would point well outside its own face.
        val handScale = backgroundScale.coerceIn(0.35f, 1.25f)
        if (art?.hour != null && art.minute != null) {
            val scale = F * handScale / art.canvasSize.toFloat()
            // MIUI hands point at 12 o'clock; Nebula strips draw them at 3 o'clock.
            Bitmaps.drawCentered(canvas, art.hour, 0f, F, scale, 90f)
            Bitmaps.drawCentered(canvas, art.minute, F.toFloat(), F, scale, 90f)
        } else {
            val handColor = if (dialIsDark) Color.WHITE else 0xFF202124.toInt()
            val accentColor = if (dialIsDark) Color.WHITE else 0xFF3E6CF0.toInt()
            drawHand(canvas, 0f, length = 0.25f, width = 0.05f, color = handColor, pivot = false, scale = handScale)
            drawHand(canvas, F.toFloat(), length = 0.35f, width = 0.034f, color = accentColor, pivot = true, scale = handScale)
        }
        val dial = art?.dial?.let { source ->
            // The same composition call an app icon gets; already tinted by loadClock, hence tintColor = null.
            if (clipSource) FixedIconArt.render(
                source = source, shape = shape, composition = composition, iconScale = iconScale,
                iconAlpha = iconAlpha, tintColor = null, tintStrength = 1f, backgroundScale = backgroundScale,
                padColor = padColor, iconBack = iconBack, tintBlendMode = tintBlendMode, size = F, plate = plate,
            )
            else Bitmaps.square(F).also { Bitmaps.drawCentered(Canvas(it), source, 0f, F, F / art.canvasSize.toFloat()) }
        } ?: generatedDial(shape, dialIsDark, plate, tintColor)
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

    private fun generatedDial(shape: FixedIconShape, dark: Boolean, plate: Bitmap?, tintColor: Int?): Bitmap {
        // Drawn onto its own frame rather than straight onto the caller's plate: that plate is shared with the
        // dial-composition path above, and is smaller than F whenever background size drops below 100%.
        val dial = Bitmaps.square(F)
        val canvas = Canvas(dial)
        val face = plate ?: Shapes.clip(Bitmaps.solid(F, tintColor ?: Color.WHITE), shape, F)
        canvas.drawBitmap(face, (F - face.width) / 2f, (F - face.height) / 2f, null)
        val scale = face.width / F.toFloat()
        val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = if (dark) Color.WHITE else 0xFFB0B4BA.toInt()
            strokeWidth = F * 0.018f * scale
            strokeCap = Paint.Cap.ROUND
        }
        val c = F / 2f
        for (hour in 0 until 12) {
            val angle = Math.toRadians(hour * 30.0)
            val outer = F * 0.36f * scale
            val inner = F * (if (hour % 3 == 0) 0.29f else 0.32f) * scale
            canvas.drawLine(
                c + (sin(angle) * inner).toFloat(), c - (cos(angle) * inner).toFloat(),
                c + (sin(angle) * outer).toFloat(), c - (cos(angle) * outer).toFloat(),
                tick,
            )
        }
        return dial
    }

    private fun drawHand(canvas: Canvas, left: Float, length: Float, width: Float, color: Int, pivot: Boolean, scale: Float = 1f) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
        val c = F / 2f
        val half = F * width * scale / 2f
        canvas.drawRoundRect(RectF(left + c - F * 0.05f * scale, c - half, left + c + F * length * scale, c + half), half, half, paint)
        if (pivot) {
            canvas.drawCircle(left + c, c, F * 0.045f * scale, paint)
            canvas.drawCircle(left + c, c, F * 0.02f * scale, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.WHITE })
        }
    }
}
