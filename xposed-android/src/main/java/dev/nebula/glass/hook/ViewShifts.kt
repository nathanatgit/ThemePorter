package dev.nebula.glass.hook

import android.view.View
import java.util.WeakHashMap

/**
 * Moves host views by offsetting their layout position, applied again before each draw.
 *
 * Not translation: Nubia and AOSP animate translation on these same views (unlock drag, burn-in,
 * the stack's own offset). Not layout params: Nubia measures its notification limit from this
 * layout. An offset leaves both alone, and touches follow it.
 */
internal class ViewShifts {
    private class Shift {
        var baseLeft = 0
        var baseTop = 0
        var left = Int.MIN_VALUE
        var top = Int.MIN_VALUE
        var dx = 0
        var dy = 0
    }

    private val shifts = WeakHashMap<View, Shift>()

    /** [v]'s left as its parent laid it out. */
    fun left(v: View): Int {
        val s = shifts[v] ?: return v.left
        return if (v.left == s.left) s.baseLeft else v.left
    }

    /** [v]'s top as its parent laid it out. */
    fun top(v: View): Int {
        val s = shifts[v] ?: return v.top
        return if (v.top == s.top) s.baseTop else v.top
    }

    fun dy(v: View): Int {
        val s = shifts[v] ?: return 0
        return if (v.top == s.top) s.dy else 0
    }

    fun move(v: View, dx: Int, dy: Int) {
        if (dx == 0 && dy == 0 && v !in shifts) return
        val baseLeft = left(v)
        val baseTop = top(v)
        val x = baseLeft + dx - v.left
        val y = baseTop + dy - v.top
        if (x != 0) v.offsetLeftAndRight(x)
        if (y != 0) v.offsetTopAndBottom(y)
        if (dx == 0 && dy == 0) {
            shifts.remove(v)
            return
        }
        val s = shifts.getOrPut(v) { Shift() }
        s.baseLeft = baseLeft
        s.baseTop = baseTop
        s.left = v.left
        s.top = v.top
        s.dx = dx
        s.dy = dy
    }

    fun restoreAll() {
        for (v in shifts.keys.toList()) move(v, 0, 0)
    }

    /** [v]'s laid-out left in [root]'s coordinates; see [topIn]. */
    fun leftIn(v: View, root: View): Int {
        var x = 0
        var p: View = v
        while (p !== root) {
            x += left(p)
            p = p.parent as? View ?: break
        }
        return x
    }

    /**
     * [v]'s laid-out top in [root]'s coordinates, ignoring these shifts and every transform, so an
     * unlock or burn-in animation doesn't feed back into the arrangement.
     */
    fun topIn(v: View, root: View): Int {
        var y = 0
        var p: View = v
        while (p !== root) {
            y += top(p)
            p = p.parent as? View ?: break
        }
        return y
    }
}
