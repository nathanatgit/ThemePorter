package dev.nebula.glass.hook

import android.view.View
import android.view.ViewGroup
import java.util.WeakHashMap
import kotlin.math.roundToInt

/**
 * Nubia's lock-screen widget row as a 5 x 1 grid across the notification cards' width (user request
 * 2026-11-23). Nubia's editor already allows five units: a round widget is one, a wide one two.
 *
 *  - A round widget is always one column and a wide one exactly two, whatever else is in the row.
 *  - Five units fill the grid, the outer panels on the cards' edges.
 *  - Fewer keep the grid's gap and sit centred: no stretched widgets, no holes between them.
 *
 * Used by the live lock screen ([LockArrange]) and the editor's preview ([LockEditor]).
 */
internal class WidgetRow(private val shifts: ViewShifts) {
    /** A child of the row and the visible panel inside it. */
    private class Item(val child: View, val panel: View)

    private val stockWidths = WeakHashMap<View, Int>()

    /**
     * @param root what [left] is relative to
     * @param left the cards' left edge
     * @param width the cards' width
     */
    fun arrange(row: ViewGroup, root: View, left: Float, width: Float) {
        val id = root.resources.getIdentifier("widget_background", "id", "com.android.systemui")
        if (id == 0) return
        val items = ArrayList<Item>()
        for (i in 0 until row.childCount) {
            val child = row.getChildAt(i)
            if (child.visibility != View.VISIBLE || child.width == 0) continue
            val panel = child.findViewById<View>(id) ?: continue   // the editor's "add" placeholder
            if (panel.width == 0 || panel.height == 0) return      // not laid out yet
            items += Item(child, panel)
        }
        if (items.isEmpty()) return
        // The outer panels sit within the row's side padding.
        if (row.clipToPadding) row.clipToPadding = false

        val circle = items[0].panel.height
        val wide = BooleanArray(items.size) { stock(items[it].panel) > items[it].panel.height * WIDE }
        val cells = cells(wide, circle.toFloat(), width)
        for ((i, item) in items.withIndex()) {
            if (!wide[i]) continue
            val target = cells[i][1].roundToInt()
            val extra = target - stock(item.panel)
            // Everything from the panel out to the row changes by the same amount.
            var v: View = item.panel
            while (true) {
                val lp = v.layoutParams
                if (lp != null && lp.width >= 0) setWidth(v, stock(v) + extra)
                if (v === item.child) break
                v = v.parent as? View ?: break
            }
            // The content has a fixed width of its own.
            (item.panel as? ViewGroup)?.getChildAt(0)?.let { if ((it.layoutParams?.width ?: -1) >= 0) setWidth(it, target) }
        }
        val rowLeft = shifts.leftIn(row, root)
        for ((i, item) in items.withIndex()) {
            var inset = 0
            var v: View = item.panel
            while (v !== item.child) {
                inset += v.left
                v = v.parent as? View ?: return
            }
            shifts.move(item.child, (left + cells[i][0] - inset - rowLeft - shifts.left(item.child)).roundToInt(), shifts.dy(item.child))
        }
    }

    private fun stock(v: View): Int = stockWidths.getOrPut(v) {
        v.layoutParams?.width?.takeIf { it >= 0 } ?: v.width
    }

    private fun setWidth(v: View, width: Int) {
        val lp = v.layoutParams ?: return
        if (lp.width == width) return
        lp.width = width
        v.layoutParams = lp
    }

    companion object {
        private const val COLUMNS = 5
        private const val WIDE = 1.4f

        /**
         * Each widget's `[x, width]` across [width], x from its left edge.
         * @param wide per widget, in order
         * @param circle a round widget's diameter
         */
        fun cells(wide: BooleanArray, circle: Float, width: Float): Array<FloatArray> {
            val gap = ((width - COLUMNS * circle) / (COLUMNS - 1)).coerceAtLeast(0f)
            val widths = FloatArray(wide.size) { if (wide[it]) 2 * circle + gap else circle }
            var x = (width - widths.sum() - gap * (wide.size - 1)) / 2
            return Array(wide.size) {
                val cell = floatArrayOf(x, widths[it])
                x += widths[it] + gap
                cell
            }
        }

        /** The notification cards' side margin on this screen, as a share of its width. */
        const val CARD_MARGIN = 52f / 1216f
    }
}
