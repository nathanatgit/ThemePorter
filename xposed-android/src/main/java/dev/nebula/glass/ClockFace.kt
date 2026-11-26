package dev.nebula.glass

import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.Log

/**
 * The resizable lock-screen clock, in the font picked in Nubia's editor so it matches the editor's
 * previews (user requests 2026-11-24).
 *
 * [ofFont] draws Nubia's own (static) font; boldness thickens or thins its outlines. [of] draws a
 * [ClockFonts] variable font (the official variable Rubik and Oswald, for Nubia's Rubik and
 * Oswald) with boldness on its weight axis; width or figure-height axes, where a font has them,
 * shape the digits to the box first.
 *
 * The box's width sets the size. Only a font with width or height axes is stretched to the box's
 * height (its axes first, then the outline-built [HeightAxis] for the rest); a font without them
 * keeps its proportions, as its designer drew it, and the clock area follows its height (user
 * decision 2026-11-24: stretching such fonts turned flags, ball ends and diagonals into spikes).
 */
class ClockFace private constructor(
    private val paint: Paint,
    /** Stem width, px. */
    val stroke: Float,
    /** Digit height, px. */
    val height: Float,
    private val key: String,
    /** Where the glyphs' baseline is: their natural height at this size. */
    private val baseline: Float,
    /** Bar thickness for [HeightAxis] (0 = off): the glyphs grow from [baseline] to [height] tall. */
    private val axisBar: Float,
    /** Px to grow (+) or shrink (-) each outline by: boldness on a font without a weight axis. */
    private val offset: Float = 0f,
    /** Text fonts sit the colon on the x-height; Nubia's clock fonts centre it already. */
    private val centreColon: Boolean = true,
    /** The fill rule the font's outlines are drawn with ([fillOf]). */
    private val fill: Path.FillType = Path.FillType.WINDING,
    /**
     * Px width of the lines for a line font (Nubia's ZlineDigit), 0 = filled. Its glyphs are solid
     * with hairline seams; Nubia shows them as hollow double lines, which is their contours stroked.
     */
    private val lines: Float = 0f,
) {
    /** Outline of one time string: x is centred on 0, y runs from 0 at the top of the digits to [height]. */
    class Shape(val path: Path, val bounds: RectF, val key: String)

    private var cached: Shape? = null
    private var cachedText: String? = null

    fun shape(text: String): Shape {
        cached?.let { if (cachedText == text) return it }
        val out = Path()
        val part = Path()
        val box = RectF()
        var x = -paint.measureText(text) / 2
        for (ch in text) {
            val s = ch.toString()
            val advance = paint.measureText(s)
            part.reset()
            paint.getTextPath(s, 0, 1, x, baseline, part)
            // The font's own fill rule (user report 2026-11-24: the line font's 6, 8 and 9 filled in).
            part.fillType = fill
            out.fillType = fill
            if (offset != 0f) offsetOutline(part, offset)
            if (ch == ':' && centreColon) {
                // Text fonts sit the colon on the x-height; a clock's is centred on the digits.
                part.computeBounds(box, true)
                if (!box.isEmpty) part.offset(0f, baseline / 2 - box.centerY())
            }
            out.addPath(when {
                axisBar <= 0f -> part
                HeightAxis.available -> HeightAxis.stretch(part, baseline, height, axisBar, dots = ch == ':')
                else -> part.apply { transform(Matrix().apply { setScale(1f, height / baseline) }) }
            })
            x += advance
        }
        val drawn = if (lines > 0f) Path().also { stroked ->
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = lines
                strokeJoin = Paint.Join.ROUND
            }.getFillPath(out, stroked)
        } else out
        val bounds = RectF().also { drawn.computeBounds(it, true) }
        return Shape(drawn, bounds, "$key|$text").also { cached = it; cachedText = text }
    }

    companion object {
        private const val TAG = "NebulaGlass"

        private val fills = java.util.WeakHashMap<android.graphics.Typeface, Path.FillType>()

        /**
         * The fill rule [p]'s font is drawn with. A text outline comes back with the default
         * (non-zero) rule, but some fonts, Nubia's line digits among them, draw their hollows with
         * the even-odd rule. The rule whose outline matches the font's own rendering of "08" wins.
         */
        private fun fillOf(p: Paint): Path.FillType {
            val face = p.typeface ?: return Path.FillType.WINDING
            fills[face]?.let { return it }
            val q = Paint(p).apply { textSize = 160f; color = android.graphics.Color.BLACK }
            fun render(draw: (android.graphics.Canvas) -> Unit): IntArray {
                val b = android.graphics.Bitmap.createBitmap(360, 220, android.graphics.Bitmap.Config.ARGB_8888)
                draw(android.graphics.Canvas(b))
                return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height); b.recycle() }
            }
            val text = render { it.drawText("08", 10f, 180f, q) }
            val outline = Path().also { q.getTextPath("08", 0, 2, 10f, 180f, it) }
            fun diff(rule: Path.FillType): Long {
                outline.fillType = rule
                val px = render { it.drawPath(outline, q) }
                var d = 0L
                for (i in px.indices) d += kotlin.math.abs((px[i] ushr 24) - (text[i] ushr 24))
                return d
            }
            val rule = if (diff(Path.FillType.EVEN_ODD) < diff(Path.FillType.WINDING)) Path.FillType.EVEN_ODD else Path.FillType.WINDING
            if (rule != Path.FillType.WINDING) Log.i(TAG, "clock font fills even-odd")
            fills[face] = rule
            return rule
        }

        /**
         * How far boldness thins or thickens a static font's strokes, as a share of digit height.
         * Capped at the user's preferred maximum (former slider 42 -> new 100) and reaching much
         * thinner hairlines at 0.
         */
        private const val MAX_OFFSET = -0.002f
        private const val MIN_OFFSET = -0.036f

        /**
         * Size and drawn height for a [maxWidth] x [height] box. Stretchable ([stretch]): the width
         * sets the size and the digits take the box's height. Else the tighter side sets the size and
         * the digits keep their natural height.
         * @param width the sample's width at size 1000; [figure] the figure height per unit of size
         */
        private fun fit(width: Float, figure: Float, maxWidth: Float, height: Float, stretch: Boolean): Pair<Float, Float> {
            var size = 1000f * maxWidth / width
            if (stretch) return size to height
            if (size * figure > height) size = height / figure
            return size to size * figure
        }

        /** Grows ([by] > 0) or erodes a glyph outline by [by] px on every side. */
        private fun offsetOutline(glyph: Path, by: Float) {
            val ring = Path()
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = kotlin.math.abs(by) * 2
                strokeJoin = Paint.Join.ROUND
            }.getFillPath(glyph, ring)
            glyph.op(ring, if (by > 0) Path.Op.UNION else Path.Op.DIFFERENCE)
        }

        private val fontCache = object : LinkedHashMap<String, ClockFace>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClockFace>?) = size > 8
        }

        /**
         * The clock in Nubia's static [font], as picked in its editor. Null when it can't be measured. Main thread.
         * @param boldness 0..100; 50 is the font as drawn
         */
        fun ofFont(font: android.graphics.Typeface, height: Float, boldness: Int, maxWidth: Float, text: String,
                   lineFont: Boolean = false): ClockFace? {
            if (height < 1f || maxWidth < 1f || text.isEmpty()) return null
            val sample = text.map { if (it.isDigit()) '0' else it }.joinToString("")
            val key = "nubia${System.identityHashCode(font)}|$lineFont|${height.toInt()}|$boldness|${maxWidth.toInt()}|$sample"
            fontCache[key]?.let { return it }
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font; textSize = 1000f }
            val path = Path()
            val box = RectF()
            p.getTextPath("0", 0, 1, 0f, 0f, path)
            path.computeBounds(box, true)
            val figure = -box.top / 1000f
            if (figure <= 0f) return null
            // Nubia's fonts are static: no width or height axis, so no stretch.
            val (size, shown) = fit(p.measureText(sample), figure, maxWidth, height, stretch = false)
            p.textSize = size
            val natural = size * figure
            val t = boldness.coerceIn(0, 100) / 100f
            // A line font's boldness is its line width; 100 = former 42 (~0.0214), 0 = ultra-thin hairline.
            val lineWidth = if (lineFont) natural * (0.0038f + 0.0176f * t) else 0f
            val rawOffset = if (lineFont) 0f else natural * (MIN_OFFSET + (MAX_OFFSET - MIN_OFFSET) * t)
            val minInk = maxOf(2.4f, natural * 0.0058f)
            val baseStem = zeroStroke(p, false)
            val offset = if (!lineFont && rawOffset < 0f && baseStem > 0f) maxOf(rawOffset, (minInk - baseStem) / 2f) else rawOffset
            val bar = (zeroStroke(p, true) + 2 * offset).coerceIn(1f, natural * 0.4f)
            val stem = if (lineFont) lineWidth.coerceAtLeast(1f) else (baseStem + 2 * offset).coerceAtLeast(1f)
            val stretch = kotlin.math.abs(natural - shown) >= 0.5f
            Log.i(TAG, "clock nubia font box ${maxWidth.toInt()}x${height.toInt()}: size=${size.toInt()} natural=${natural.toInt()} shown=${shown.toInt()}")
            return ClockFace(p, stem, shown, key, baseline = natural, axisBar = if (stretch) bar else 0f,
                offset = offset, centreColon = false, fill = fillOf(p), lines = lineWidth).also { fontCache[key] = it }
        }

        /**
         * Boldness 100 = former slider 42 (~436 weight, crisp regular-medium); boldness 0 combines
         * Display optical size (opsz 144), minimum weight (100), minimum grade (GRAD -200), and
         * gentle outline thinning to reach ultra-thin iOS 27 hairline digits.
         */
        private const val MAX_WEIGHT = 436f
        private const val MIN_WEIGHT = 100f

        /** Roboto Flex at width 25 is hairline-narrow: above regular, narrow digits close up into blobs. */
        private fun narrowest(axis: ClockFonts.Axis, weight: Float?): Float {
            if (axis.min >= 50f || weight == null) return axis.min
            return maxOf(axis.min, 25f + (weight - 400f).coerceAtLeast(0f) / 500f * 40f)
        }

        /** Run length of ink through a '0': its top bar down the centre ([vertical]), or its left stem across the middle. */
        private fun zeroStroke(p: Paint, vertical: Boolean): Float {
            val path = Path()
            p.getTextPath("0", 0, 1, 0f, 0f, path)
            val b = RectF().also { path.computeBounds(it, true) }
            if (b.isEmpty) return 0f
            val region = android.graphics.Region()
            region.setPath(path, android.graphics.Region(b.left.toInt() - 1, b.top.toInt() - 1, b.right.toInt() + 1, b.bottom.toInt() + 1))
            return if (vertical) {
                val x = b.centerX().toInt()
                var y = b.top.toInt()
                val end = b.centerY().toInt()
                while (y < end && !region.contains(x, y)) y++
                val start = y
                while (y < end && region.contains(x, y)) y++
                (y - start).toFloat()
            } else {
                val y = b.centerY().toInt()
                var x = b.left.toInt()
                val end = b.centerX().toInt()
                while (x < end && !region.contains(x, y)) x++
                val start = x
                while (x < end && region.contains(x, y)) x++
                (x - start).toFloat()
            }
        }

        private val cache = object : LinkedHashMap<String, ClockFace>(8, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ClockFace>?) = size > 8
        }

        /**
         * The clock in [font], filling a [maxWidth] x [height] box. Null when it can't be measured. Main thread.
         * @param boldness 0..100 over the font's weight range
         * @param text only its pattern counts, so the shape doesn't change from minute to minute
         */
        fun of(font: ClockFonts.Loaded, height: Float, boldness: Int, maxWidth: Float, text: String): ClockFace? {
            if (height < 1f || maxWidth < 1f || text.isEmpty()) return null
            val sample = text.map { if (it.isDigit()) '0' else it }.joinToString("")
            val key = "${font.family.id}|${height.toInt()}|$boldness|${maxWidth.toInt()}|$sample"
            cache[key]?.let { return it }
            val t0 = System.nanoTime()

            val ax = font.axes
            val wghtAxis = ax["wght"]
            val wdthAxis = ax["wdth"]
            val ytfiAxis = ax["YTFI"]
            val gradAxis = ax["GRAD"]
            val boldT = boldness.coerceIn(0, 100) / 100f
            // Display optical size (opsz = 144) has the thinnest, cleanest display strokes in Roboto Flex.
            val opsz = ax["opsz"]?.let { minOf(it.max, 144f) }
            // Below 45 % slider, map weight down to MIN_WEIGHT (100) and engage negative GRAD (-200..0)
            // + sub-minimum outline thinning so 0 reaches a true ultra-thin iOS hairline!
            val weightT = ((boldT - 0.28f) / 0.72f).coerceIn(0f, 1f)
            val weight = wghtAxis?.let {
                val lo = maxOf(it.min, MIN_WEIGHT)
                val hi = maxOf(lo, minOf(it.max, MAX_WEIGHT))
                lo + (hi - lo) * weightT
            }
            val grad = gradAxis?.let {
                val gt = (boldT / 0.45f).coerceIn(0f, 1f)
                (it.min + (0f - it.min) * gt).coerceIn(it.min, it.max)
            }
            var wdth = wdthAxis?.default
            var ytfi = ytfiAxis?.default

            // Tabular figures: the clock doesn't shift as the minutes change.
            val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = font.typeface; textSize = 1000f; fontFeatureSettings = "'tnum'" }
            fun apply() {
                val s = ArrayList<String>(6)
                weight?.let { s.add("'wght' $it") }
                opsz?.let { s.add("'opsz' $it") }
                grad?.let { s.add("'GRAD' $it") }
                wdth?.let { s.add("'wdth' $it") }
                ytfi?.let { s.add("'YTFI' $it") }
                p.fontVariationSettings = s.joinToString(", ")
            }
            val box = RectF()
            val path = Path()
            /** Height of the figures (baseline to the top of a '0') at size 1000, as a share of the size. */
            fun figure(): Float {
                path.reset()
                p.getTextPath("0", 0, 1, 0f, 0f, path)
                path.computeBounds(box, true)
                return -box.top / 1000f
            }
            fun aspect(): Float = p.measureText(sample) / 1000f / figure().coerceAtLeast(0.01f)

            val want = maxWidth / height
            /** The value in lo..hi that gives the box's aspect, or the end nearest it. */
            fun solve(lo: Float, hi: Float, set: (Float) -> Unit, widens: Boolean): Float {
                var a = lo
                var z = hi
                repeat(12) {
                    val m = (a + z) / 2
                    set(m); apply()
                    if ((aspect() < want) == widens) a = m else z = m
                }
                return ((a + z) / 2).also { set(it) }
            }

            // Only the registered axes (weight, width, optical size) and the figure height: Roboto Flex's
            // parametric stroke axes add up with them, and driven to an end (YOPQ at 25 with weight 100)
            // they broke the outlines into hairline stems and heavy diagonals.
            apply()

            val lo = wdthAxis?.let { narrowest(it, weight) }
            if (ytfiAxis != null) {
                ytfi = ytfiAxis.max; apply(); val tallest = aspect()
                ytfi = ytfiAxis.min; apply(); val shortest = aspect()
                when {
                    // Taller than the height axis reaches: narrow the digits too.
                    tallest > want -> { ytfi = ytfiAxis.max; if (wdthAxis != null) solve(lo!!, wdthAxis.default, { wdth = it }, widens = true) }
                    // Wider than it reaches: widen them.
                    shortest < want -> { ytfi = ytfiAxis.min; if (wdthAxis != null) solve(wdthAxis.default, wdthAxis.max, { wdth = it }, widens = true) }
                    else -> solve(ytfiAxis.min, ytfiAxis.max, { ytfi = it }, widens = false)
                }
            } else if (wdthAxis != null) {
                solve(lo!!, wdthAxis.max, { wdth = it }, widens = true)
            }
            apply()

            val figure = figure()
            if (figure <= 0f) return null
            // The box's width sets the size; the axes have already brought the height close.
            val (size, shown) = fit(p.measureText(sample), figure, maxWidth, height, stretch = wdthAxis != null || ytfiAxis != null)
            p.textSize = size
            val natural = size * figure
            val baseBar = zeroStroke(p, true)
            val baseStem = zeroStroke(p, false)
            // In the bottom 32 % of the slider, gently erode outlines beyond wght=100 / GRAD=-200 down
            // to a crisp hairline floor (~0.55 % of digit height, >= 2.4 px) so 0 is noticeably thinner!
            val thinShare = ((0.32f - boldT) / 0.32f).coerceIn(0f, 1f)
            val minInk = maxOf(2.4f, natural * 0.0055f)
            val rawThin = -thinShare * natural * 0.0065f
            val offset = if (rawThin < 0f && baseStem > 0f) maxOf(rawThin, (minInk - baseStem) / 2f) else 0f
            val bar = (baseBar + 2f * offset).coerceIn(1f, natural * 0.4f)
            val stem = (baseStem + 2f * offset).coerceAtLeast(1f)
            val rest = kotlin.math.abs(natural - shown) >= 0.5f
            Log.i(TAG, "clock ${font.family.id} box ${maxWidth.toInt()}x${height.toInt()}: ${p.fontVariationSettings} size=${size.toInt()} " +
                "natural=${natural.toInt()} shown=${shown.toInt()} bar=${bar.toInt()} stem=${stem.toInt()} off=${"%.1f".format(offset)} heightAxis=$rest in ${(System.nanoTime() - t0) / 1_000_000}ms")
            return ClockFace(p, stem, shown, key, baseline = natural, axisBar = if (rest) bar else 0f, offset = offset, fill = fillOf(p))
                .also { cache[key] = it }
        }
    }
}
