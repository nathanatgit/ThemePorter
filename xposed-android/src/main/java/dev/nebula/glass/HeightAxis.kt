package dev.nebula.glass

import android.graphics.Path
import android.graphics.PathIterator
import android.os.Build

/**
 * A height axis for fonts that have none (user request 2026-11-24): taller digits at the same
 * stroke, as iOS 27's clock does with the HGHT axis of its own variable font.
 *
 * A glyph's horizontal strokes (bars, the tops and bottoms of bowls, serifs) are found from its
 * outline: horizontal lines, and on-curve points where the curve is flat. Their two edges, no more
 * than about a bar's thickness apart, make a rigid band that keeps its thickness. Only the gaps
 * between bands stretch, so the glyph reaches the new height with its strokes as drawn: what a
 * type designer's tall master looks like. Vertical stems keep their width (x is never touched).
 *
 * Works on the outline's points (Android 14's [PathIterator]); no font files are changed or shipped.
 */
internal object HeightAxis {
    val available get() = Build.VERSION.SDK_INT >= 34

    /**
     * [glyph] (one character, its figure from y = 0 to [natural] = the baseline) stretched to
     * [target], keeping horizontal strokes about [bar] px thick. Points outside 0..natural (the
     * overshoot of round digits) move with the nearest end. Dots ([dots], a colon) only move.
     */
    @android.annotation.TargetApi(34)
    fun stretch(glyph: Path, natural: Float, target: Float, bar: Float, dots: Boolean = false): Path {
        if (natural <= 0f || kotlin.math.abs(target - natural) < 0.5f) return glyph
        val segments = read(glyph)
        if (segments.isEmpty()) return glyph
        if (dots) return moveContours(segments, natural, target).also { it.fillType = glyph.fillType }
        val map = Mapping(bands(segments, natural, bar), natural, target)
        // The glyph's fill rule too: counters that rely on even-odd would fill in under the default.
        return build(segments) { map(it) }.also { it.fillType = glyph.fillType }
    }

    private class Segment(val verb: Int, val points: FloatArray, val weight: Float)

    /** How many coordinates a segment's points hold. */
    private fun count(verb: Int) = when (verb) {
        PathIterator.VERB_MOVE -> 2
        PathIterator.VERB_LINE -> 4
        PathIterator.VERB_QUAD, PathIterator.VERB_CONIC -> 6
        PathIterator.VERB_CUBIC -> 8
        else -> 0
    }

    @android.annotation.TargetApi(34)
    private fun read(p: Path): List<Segment> {
        val out = ArrayList<Segment>()
        val it = p.pathIterator
        while (it.hasNext()) {
            val s = it.next()
            if (s.verb == PathIterator.VERB_DONE) break
            out.add(Segment(s.verb, s.points.copyOf(), s.conicWeight))
        }
        return out
    }

    private fun build(segments: List<Segment>, y: (Float) -> Float): Path {
        val p = Path()
        for (s in segments) {
            val q = s.points
            when (s.verb) {
                PathIterator.VERB_MOVE -> p.moveTo(q[0], y(q[1]))
                PathIterator.VERB_LINE -> p.lineTo(q[2], y(q[3]))
                PathIterator.VERB_QUAD -> p.quadTo(q[2], y(q[3]), q[4], y(q[5]))
                PathIterator.VERB_CONIC -> p.conicTo(q[2], y(q[3]), q[4], y(q[5]), s.weight)
                PathIterator.VERB_CUBIC -> p.cubicTo(q[2], y(q[3]), q[4], y(q[5]), q[6], y(q[7]))
                PathIterator.VERB_CLOSE -> p.close()
            }
        }
        return p
    }

    /** The y of every horizontal stroke edge: horizontal lines, and flat on-curve points (curve extremes). */
    private fun edges(segments: List<Segment>, flat: Float): List<Float> {
        val ys = ArrayList<Float>()
        for (s in segments) {
            val q = s.points
            when (s.verb) {
                PathIterator.VERB_LINE -> {
                    val dx = kotlin.math.abs(q[2] - q[0])
                    val dy = kotlin.math.abs(q[3] - q[1])
                    if (dy <= flat && dx > dy * 4) { ys.add(q[1]); ys.add(q[3]) }
                }
                PathIterator.VERB_QUAD, PathIterator.VERB_CONIC -> {
                    if (kotlin.math.abs(q[3] - q[1]) <= flat) ys.add(q[1])   // flat leaving the start
                    if (kotlin.math.abs(q[3] - q[5]) <= flat) ys.add(q[5])   // flat arriving at the end
                }
                PathIterator.VERB_CUBIC -> {
                    if (kotlin.math.abs(q[3] - q[1]) <= flat) ys.add(q[1])
                    if (kotlin.math.abs(q[5] - q[7]) <= flat) ys.add(q[7])
                }
            }
        }
        ys.sort()
        // Merge levels a pixel apart.
        val merged = ArrayList<Float>()
        for (y in ys) if (merged.isEmpty() || y - merged.last() > 1f) merged.add(y)
        return merged
    }

    /** Rigid bands: pairs of neighbouring edges no more than ~a bar apart, clipped to 0..natural. */
    private fun bands(segments: List<Segment>, natural: Float, bar: Float): List<ClosedFloatingPointRange<Float>> {
        val levels = edges(segments, maxOf(0.5f, natural * 0.004f))
        val reach = maxOf(bar * 1.6f, natural * 0.02f)
        val out = ArrayList<ClosedFloatingPointRange<Float>>()
        var i = 0
        while (i < levels.size - 1) {
            val a = levels[i]
            val b = levels[i + 1]
            if (b - a <= reach) {
                val lo = a.coerceIn(0f, natural)
                val hi = b.coerceIn(0f, natural)
                if (hi > lo) out.add(lo..hi)
                i += 2
            } else i++
        }
        return out
    }

    /** Piecewise-linear y map: bands keep their length, the gaps between them share the rest. */
    private class Mapping(bands: List<ClosedFloatingPointRange<Float>>, private val natural: Float, private val target: Float) {
        private val from = ArrayList<Float>()
        private val to = ArrayList<Float>()

        init {
            val sorted = bands.sortedBy { it.start }
            val rigid = sorted.sumOf { (it.endInclusive - it.start).toDouble() }.toFloat()
            val gaps = natural - rigid
            // Too little gap to stretch (all bars): scale evenly instead.
            val k = if (gaps > natural * 0.05f) ((target - rigid) / gaps).coerceAtLeast(0.05f) else target / natural
            val rigidScale = if (gaps > natural * 0.05f) 1f else k
            var y = 0f
            var out = 0f
            from.add(0f); to.add(0f)
            for (b in sorted) {
                if (b.start < y) continue
                out += (b.start - y) * k
                from.add(b.start); to.add(out)
                out += (b.endInclusive - b.start) * rigidScale
                from.add(b.endInclusive); to.add(out)
                y = b.endInclusive
            }
            out += (natural - y) * k
            from.add(natural); to.add(out)
            // Rounding leaves the last point a hair off: land it on the target exactly.
            to[to.size - 1] = target
        }

        operator fun invoke(y: Float): Float {
            if (y <= 0f) return y                               // overshoot above the figure moves with the top
            if (y >= natural) return target + (y - natural)     // and below the baseline with the bottom
            var i = 1
            while (i < from.size - 1 && from[i] < y) i++
            val f0 = from[i - 1]; val f1 = from[i]
            val t0 = to[i - 1]; val t1 = to[i]
            return if (f1 <= f0) t0 else t0 + (y - f0) * (t1 - t0) / (f1 - f0)
        }
    }

    /** Each contour (a dot) moves to where its centre maps under an even stretch; dots stay round. */
    private fun moveContours(segments: List<Segment>, natural: Float, target: Float): Path {
        val k = target / natural
        val p = Path()
        var start = 0
        while (start < segments.size) {
            var end = start + 1
            while (end < segments.size && segments[end].verb != PathIterator.VERB_MOVE) end++
            val contour = segments.subList(start, end)
            var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
            for (s in contour) for (j in 1 until count(s.verb) step 2) { lo = minOf(lo, s.points[j]); hi = maxOf(hi, s.points[j]) }
            val shift = (lo + hi) / 2 * (k - 1)
            p.addPath(build(contour) { it + shift })
            start = end
        }
        return p
    }
}
