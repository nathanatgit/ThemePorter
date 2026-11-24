package dev.nebula.glass.hook

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.TextView
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.LockLayout
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.roundToInt

/**
 * iOS-style text controls inside Nubia's lock-screen editor (user requests 2026-11-23 and 11-24).
 *
 * A row of chips picks the clock, the date line or the custom text under the clock. The picked one
 * shows its box, with a handle on the box's bottom-right corner that follows the finger: the box
 * stays centred and its top stays put, so the corner sets both its width and height. The text fills the box until either side is
 * reached, never stretched. The wedge sets the picked item's boldness, and ↺ returns it to Nubia's.
 *
 * Changes save when the finger lifts, and leaving the editor without Apply puts the old values
 * back, like Nubia's own edits.
 */
internal object LockEditor {
    const val ACTIVITY = "com.zte.mifavor.keyguard.personalclock.MyDepthLockScreenEditActivity"
    private const val TAG = "NebulaGlass"
    private const val SYSTEMUI = "com.android.systemui"

    const val CLOCK = 0
    const val DATE = 1
    const val NOTE = 2

    /** Nubia's editor outlines round the clock, the custom text and the widget row. */
    private val BORDERS = listOf("clock_border", "owner_info_border", "widgetEditorView", "top_image_border", "bottom_image_border")

    private val sessions = WeakHashMap<Activity, Session>()
    private val io = Executors.newSingleThreadExecutor()

    fun install(a: Activity) {
        if (a.isFinishing) return
        val id = a.resources.getIdentifier("root", "id", SYSTEMUI)
        val root = (if (id != 0) a.findViewById<View>(id) else null) as? ViewGroup ?: return
        HookSettings.attach(a)
        sessions[a] = Session(a, root).also { it.start() }
        Log.i(TAG, "lock editor controls installed")
    }

    fun applied(a: Activity) {
        sessions[a]?.applied = true
    }

    fun closed(a: Activity) {
        sessions.remove(a)?.close(revert = !a.isChangingConfigurations)
    }

    private fun save(a: Activity, layout: LockLayout) {
        val resolver = a.applicationContext.contentResolver
        val values = Bundle().apply { putString(LockLayout.KEY_LAYOUT, layout.toJson().toString()) }
        io.execute {
            try {
                resolver.call(Uri.parse("content://${GlassSettings.LAYOUT_AUTHORITY}"), LockLayout.CALL_SAVE_LAYOUT, null, values)
            } catch (t: Throwable) { Log.e(TAG, "saving the lock layout", t) }
        }
    }

    private class Session(val activity: Activity, val root: ViewGroup) : ViewTreeObserver.OnPreDrawListener {
        private val original = GlassSettings.config.lock
        var draft = original
        var applied = false
        private val shifts = ViewShifts()
        private val controls = Controls(this)
        private val widgetRow = WidgetRow(shifts)
        private val found = HashMap<String, View>()
        private val at = IntArray(2)
        private val rootAt = IntArray(2)
        /** The clock style shown when it isn't the resizable one-line clock: its box is per style. */
        var clockStyle: String? = null
        /** Per item: what it draws, in root px (its text, or the clock's digits); null = unknown. */
        val content = arrayOfNulls<RectF>(3)
        /** Per item: its box is centred on the screen (else it keeps its left edge). */
        val centred = BooleanArray(3) { true }

        fun view(name: String): View? {
            found[name]?.let { if (it.isAttachedToWindow) return it }
            val id = root.resources.getIdentifier(name, "id", SYSTEMUI)
            // Not the style thumbnails' views, which have the same ids.
            return (if (id != 0) dev.nebula.glass.GlassTextHost.findOutsideLists(root, id) else null)?.also { found[name] = it }
        }

        fun start() {
            // Just above the preview, so Nubia's own sheets still cover the controls.
            val frame = view("phone_frame")
            val index = if (frame?.parent === root) root.indexOfChild(frame) + 1 else -1
            root.addView(controls, index, ViewGroup.LayoutParams(-1, -1))
            root.viewTreeObserver.addOnPreDrawListener(this)
        }

        fun clock(): TextView? = (view("clock_view") as? TextView)?.takeIf { it.isShown && LockArrange.resizable(it) }

        fun commit() = save(activity, draft)

        fun close(revert: Boolean) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            if (revert && !applied && draft.editorFields() != original.editorFields()) save(activity, original)
        }

        override fun onPreDraw(): Boolean {
            try { arrange() } catch (t: Throwable) { Log.e(TAG, "lock editor layout", t) }
            try { hideBorders() } catch (t: Throwable) { Log.e(TAG, "lock editor borders", t) }
            return true
        }

        /**
         * Nubia's outlines round each area (user request 2026-11-24): only the dashed text boxes and
         * their handles show. The views stay, as they are Nubia's tap targets for the style sheets;
         * only their background, the outline, goes. Re-applied each frame, as Nubia resets it.
         */
        private fun hideBorders() {
            for (name in BORDERS) {
                val bg = view(name)?.background ?: continue
                if (bg.alpha != 0) bg.mutate().alpha = 0
            }
        }

        /**
         * The visible text of [views] in root coordinates: the views' boxes, or with [textOnly] just
         * the lines of text in them (a box centres its text in a taller view).
         */
        private fun textBox(views: List<View>, textOnly: Boolean = false): RectF? {
            var box: RectF? = null
            root.getLocationInWindow(rootAt)
            for (v in views) {
                val tv = v as? TextView ?: continue
                if (!tv.isShown || tv.width == 0) continue
                tv.getLocationInWindow(at)
                val lineW = (tv.layout?.getLineWidth(0) ?: tv.width.toFloat()) + tv.totalPaddingLeft + tv.totalPaddingRight
                val cx = at[0] - rootAt[0] + tv.width / 2f
                val w = minOf(lineW, tv.width.toFloat())
                val top = (at[1] - rootAt[1]).toFloat() + (if (textOnly) tv.totalPaddingTop.toFloat() else 0f)
                val high = if (textOnly) (tv.layout?.height?.toFloat() ?: tv.height.toFloat()) else tv.height.toFloat()
                val r = RectF(cx - w / 2, top, cx + w / 2, top + high)
                if (box == null) box = r else box.union(r)
            }
            return box
        }

        /**
         * [natural] resized to the saved box ([w], [h] thousandths), top kept: centred when the item
         * is centred on the screen, else from its left edge (left-aligned styles).
         */
        private fun boxed(item: Int, natural: RectF?, w: Int, h: Int): RectF? {
            natural ?: return null
            centred[item] = kotlin.math.abs(natural.centerX() - root.width / 2f) < root.width * 0.08f
            if (w <= 0 || h <= 0) return natural
            val bw = w * root.width / 1000f
            val bh = h * root.height / 1000f
            val left = if (centred[item]) natural.centerX() - bw / 2 else natural.left
            return RectF(left, natural.top, left + bw, natural.top + bh)
        }

        private fun arrange() {
            val d = draft
            val clock = clock()
            val find = { name: String -> view(name) }
            LockArrange.styleTexts(d, root.width, root.height, find, clock != null)
            val border = view("clock_border")
            val block = view("ower_and_widget_ll")
            val boxes = arrayOfNulls<RectF>(3)
            content[DATE] = textBox(LockArrange.textViews(find, LockArrange.DATE_IDS), textOnly = true)
            content[NOTE] = textBox(LockArrange.textViews(find, LockArrange.NOTE_IDS), textOnly = true)
            content[CLOCK] = null
            boxes[DATE] = boxed(DATE, textBox(LockArrange.textViews(find, LockArrange.DATE_IDS)), d.dateW, d.dateH)
            boxes[NOTE] = boxed(NOTE, textBox(LockArrange.textViews(find, LockArrange.NOTE_IDS)), d.noteW, d.noteH)
            clockStyle = null
            var down = 0
            if (clock == null) {
                // Other clock styles: their digits are text, fitted to the clock's box like the date.
                shifts.restoreAll()
                val digits = LockArrange.textViews(find, LockArrange.CLOCK_TEXT_IDS)
                clockStyle = LockArrange.styleOf(digits)
                val box = clockStyle?.let { d.styleBoxes[it] }
                boxes[CLOCK] = boxed(CLOCK, textBox(digits), box?.first ?: 0, box?.second ?: 0)
            } else {
                val height = if (d.hasCustomClock) d.clockHeightPx(root.height) else null
                val width = d.clockWidthPx(root.width)
                LockClock.set(clock, height, d.clockBoldness, width)
                val top = shifts.topIn(clock, root) + LockClock.TOP_INSET
                if (height != null && block != null) {
                    val spare = root.height * 0.004f
                    val shown = height   // the digits are centred in the box
                    down = maxOf(0f, top + shown + spare - shifts.topIn(block, root)).roundToInt()
                }
                root.getLocationInWindow(rootAt)
                clock.getLocationInWindow(at)
                val cx = at[0] - rootAt[0] + clock.width / 2f
                val h = height ?: d.clockHeightPx(root.height)
                boxes[CLOCK] = RectF(cx - width / 2, top, cx + width / 2, top + h)
                if (height != null) LockClock.drawnSize(clock, height, width, d.clockBoldness)?.let { (dw, dh) ->
                    val dy = maxOf(0f, (height - dh) / 2)
                    content[CLOCK] = RectF(cx - dw / 2, top + dy, cx + dw / 2, top + dy + dh)
                }
                centred[CLOCK] = true
                block?.let { shifts.move(it, 0, down) }
                view("widgetEditorView")?.let {
                    shifts.move(it, 0, down)
                    val margin = root.width * WidgetRow.CARD_MARGIN
                    (it as? ViewGroup)?.let { row -> widgetRow.arrange(row, root, margin, root.width - 2 * margin) }
                }
                // Nubia's outline round the clock follows the block it now ends at.
                if (border != null && block != null && border.width > 0) {
                    val borderTop = shifts.topIn(border, root)
                    val borderBottom = shifts.topIn(block, root) + down
                    val local = border.top + (borderBottom - borderTop)
                    if (border.bottom != local) border.bottom = local
                }
            }
            // The controls sit just above Nubia's Wallpaper button and go when that does.
            val wallpaper = view("wallpaper_bt")?.takeIf { it.isShown && it.alpha > 0.5f }
            controls.place(if (wallpaper != null) boxes else null, wallpaper?.let { shifts.topIn(it, root).toFloat() } ?: 0f)
        }
    }

    /** Full-screen overlay that only takes touches on its own controls. */
    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Controls(private val session: Session) : View(session.activity) {
        private val dp = resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * dp; textAlign = Paint.Align.CENTER }
        private val dash = DashPathEffect(floatArrayOf(10f, 8f), 0f)
        private val path = Path()
        private val skin = GlassSkin()
        private var boxes: Array<RectF?>? = null
        private var selected = CLOCK
        private val handle = RectF()
        private val strip = RectF()
        private val reset = RectF()
        private val wedge = RectF()
        private val arc = RectF()
        private val knob = RectF()
        private val chips = Array(3) { RectF() }
        private val names = arrayOf("Clock", "Date", "Text")
        private var mode = NONE
        private var downX = 0f
        private var downY = 0f
        private var downW = 0f
        private var downH = 0f
        private var placedAbove = -1f

        init {
            contentDescription = "Clock, date and custom text size and boldness"
            // The editor's force-dark turned the picked chip's dark label pale.
            isForceDarkAllowed = false
        }

        fun place(next: Array<RectF?>?, above: Float) {
            if (mode == RESIZE) { boxes = next; invalidate(); return }   // the strip doesn't move mid-drag
            boxes = next
            // The picked item isn't in this style: pick one that is.
            if (next != null && next.getOrNull(selected) == null) next.indexOfFirst { it != null }.takeIf { it >= 0 }?.let { selected = it }
            if (next != null && above != placedAbove) {
                placedAbove = above
                val bottom = above - 14 * dp
                val side = width * 0.07f
                strip.set(side, bottom - 56 * dp, width - side, bottom)
                reset.set(strip.left + 8 * dp, strip.top + 8 * dp, strip.left + 48 * dp, strip.bottom - 8 * dp)
                wedge.set(reset.right + 24 * dp, strip.top, strip.right - 28 * dp, strip.bottom)
                val gap = 8 * dp
                val cw = (strip.width() - 2 * gap) / 3
                for (i in 0 until 3) {
                    val l = strip.left + i * (cw + gap)
                    chips[i].set(l, strip.top - gap - 40 * dp, l + cw, strip.top - gap)
                }
            }
            invalidate()
        }

        private fun box(): RectF? = boxes?.getOrNull(selected)

        override fun onDraw(c: Canvas) {
            val all = boxes ?: return
            // Every item's box and corner handle; the picked one brighter. While dragging, only it.
            for (i in listOf(CLOCK, DATE, NOTE).sortedBy { it == selected }) {
                val b = all.getOrNull(i) ?: continue
                if (mode == RESIZE && i != selected) continue
                val on = i == selected
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (if (on) 1.5f else 1f) * dp
                paint.pathEffect = dash
                paint.color = if (on) 0xCCFFFFFF.toInt() else 0x66FFFFFF
                c.drawRoundRect(b, 10 * dp, 10 * dp, paint)
                paint.pathEffect = null
                // The handle: a glass disc on the bottom-right corner with a bracket along the corner.
                val r = (if (on) 20 else 16) * dp
                handle.set(b.right - r, b.bottom - r, b.right + r, b.bottom + r)
                skin.drawPanel(c, handle, GlassSkin.uniform(r), STRIP_BODY, 1.2f, dp)
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = (if (on) 4 else 3) * dp
                paint.color = when { on && mode == RESIZE -> Color.WHITE; on -> 0xE6FFFFFF.toInt(); else -> 0x99FFFFFF.toInt() }
                val arm = (if (on) 9 else 7) * dp
                path.reset()
                path.moveTo(b.right + 3 * dp, b.bottom - arm)
                path.lineTo(b.right + 3 * dp, b.bottom + 3 * dp)
                path.lineTo(b.right - arm, b.bottom + 3 * dp)
                c.drawPath(path, paint)
            }
            if (mode == RESIZE) return   // an unobstructed look while dragging

            for (i in 0 until 3) {
                val r = chips[i]
                val on = i == selected
                val enabled = all[i] != null
                skin.drawPanel(c, r, GlassSkin.uniform(r.height() / 2), if (on) 0xE6FFFFFF.toInt() else STRIP_BODY, 1f, dp)
                label.color = when { on -> 0xFF1C1C1E.toInt(); enabled -> Color.WHITE; else -> 0x66FFFFFF }
                c.drawText(names[i], r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2, label)
            }

            skin.drawPanel(c, strip, GlassSkin.uniform(strip.height() / 2), STRIP_BODY, 1.2f, dp)
            paint.style = Paint.Style.FILL
            paint.color = 0x30FFFFFF
            c.drawCircle(reset.centerX(), reset.centerY(), reset.width() / 2, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.2f * dp
            paint.color = if (isCustom()) Color.WHITE else 0x66FFFFFF
            val q = 8 * dp
            arc.set(reset.centerX() - q, reset.centerY() - q, reset.centerX() + q, reset.centerY() + q)
            c.drawArc(arc, -50f, 290f, false, paint)
            val ax = arc.centerX() - q * 0.5f
            val ay = arc.centerY() - q * 0.87f
            c.drawLine(ax, ay, ax - 4 * dp, ay - 2 * dp, paint)
            c.drawLine(ax, ay, ax - 1 * dp, ay + 4.5f * dp, paint)

            paint.style = Paint.Style.FILL
            val thin = 2 * dp
            val thick = 8 * dp
            val y = wedge.centerY()
            path.reset()
            path.moveTo(wedge.left, y - thin)
            path.lineTo(wedge.right, y - thick)
            path.arcTo(wedge.right - thick, y - thick, wedge.right + thick, y + thick, -90f, 180f, false)
            path.lineTo(wedge.left, y + thin)
            path.close()
            paint.color = 0x70FFFFFF
            c.drawPath(path, paint)
            val x = wedge.left + wedge.width() * boldness() / 100f
            knob.set(x - 20 * dp, y - 14 * dp, x + 20 * dp, y + 14 * dp)
            paint.color = Color.WHITE
            c.drawRoundRect(knob, 14 * dp, 14 * dp, paint)
        }

        private fun isCustom(): Boolean {
            val d = session.draft
            return when (selected) {
                CLOCK -> session.clockStyle?.let { it in d.styleBoxes } ?: (d.hasCustomClock || d.clockWidth > 0)
                DATE -> d.dateW > 0 || d.dateWeight != LockLayout.DEFAULT_DATE_WEIGHT
                else -> d.noteW > 0 || d.noteWeight > 0
            }
        }

        /** The picked item's boldness, 0..100. */
        private fun boldness(): Int {
            val d = session.draft
            return when (selected) {
                CLOCK -> d.clockBoldness
                DATE -> weightToBoldness(d.dateWeight)
                else -> weightToBoldness(if (d.noteWeight > 0) d.noteWeight else 400)
            }
        }

        private fun weightToBoldness(w: Int) = ((w - 100) / 8f).roundToInt().coerceIn(0, 100)
        private fun boldnessToWeight(b: Int) = (100 + b * 8).coerceIn(100, 900)

        override fun onTouchEvent(e: MotionEvent): Boolean {
            val all = boxes ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    // Any item's corner handle, the picked one first; grabbing it picks that item.
                    val grabbed = listOf(selected, CLOCK, DATE, NOTE).firstOrNull { i ->
                        val r = all.getOrNull(i)
                        r != null && kotlin.math.abs(e.x - r.right) < 36 * dp && kotlin.math.abs(e.y - r.bottom) < 36 * dp
                    }
                    if (grabbed != null) selected = grabbed
                    val b = box()
                    val chip = chips.indexOfFirst { it.contains(e.x, e.y) }
                    mode = when {
                        grabbed != null -> RESIZE
                        chip >= 0 -> if (all[chip] != null) CHIP else NONE
                        reset.contains(e.x, e.y) -> RESET
                        strip.contains(e.x, e.y) -> if (e.x > reset.right) BOLDNESS else NONE
                        else -> return false   // everything else is Nubia's
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    downX = e.x; downY = e.y
                    b?.let { downW = it.width(); downH = it.height() }
                    if (mode == CHIP) { selected = chip; invalidate() }
                    if (mode == BOLDNESS) setBoldness(e.x)
                    if (mode != NONE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                MotionEvent.ACTION_MOVE -> when (mode) {
                    RESIZE -> resize(downW + (if (session.centred[selected]) 2 else 1) * (e.x - downX), downH + (e.y - downY))
                    BOLDNESS -> setBoldness(e.x)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val done = mode
                    mode = NONE
                    if (done == RESET && e.actionMasked == MotionEvent.ACTION_UP && reset.contains(e.x, e.y)) resetSelected()
                    if (done == RESIZE) snap()
                    if (done == RESIZE || done == BOLDNESS || done == RESET) session.commit()
                    invalidate()
                }
            }
            return true
        }

        /** New box size in px for the picked item, kept within its range. */
        private fun resize(w: Float, h: Float) {
            val sw = session.root.width.toFloat()
            val sh = session.root.height.toFloat()
            val wide = LockLayout.width((w / sw * 1000).roundToInt().coerceAtLeast(LockLayout.MIN_WIDTH))
            val d = session.draft
            val style = session.clockStyle
            update(when (selected) {
                CLOCK -> if (style != null) styleBox(d, style, wide, h / sh)
                    else d.copy(clockWidth = wide,
                    clockHeight = ((h / sh).coerceIn(LockLayout.MIN_CLOCK, LockLayout.MAX_CLOCK) * 1000).roundToInt())
                DATE -> d.copy(dateW = wide, dateH = LockLayout.textHeight((h / sh * 1000).roundToInt().coerceAtLeast(LockLayout.MIN_TEXT_H)))
                else -> d.copy(noteW = wide, noteH = LockLayout.textHeight((h / sh * 1000).roundToInt().coerceAtLeast(LockLayout.MIN_TEXT_H)))
            })
        }

        /**
         * On release (user request 2026-11-24): a box larger than what it draws shrinks to fit it, so
         * no empty space is left on either side. The text keeps its size.
         */
        private fun snap() {
            val c = session.content[selected] ?: return
            val b = box() ?: return
            // A few px over, so rounding to thousandths of the screen never shrinks the text.
            val w = minOf(b.width(), c.width() + 3)
            val h = minOf(b.height(), c.height() + 3)
            if (w < b.width() - 4 || h < b.height() - 4) resize(w, h)
        }

        /** [d] with the clock box of a style whose digits are text: [w] thousandths, [h] a share of the height. */
        private fun styleBox(d: LockLayout, style: String, w: Int, h: Float): LockLayout {
            val high = (h * 1000).roundToInt().coerceIn(LockLayout.MIN_TEXT_H, (LockLayout.MAX_CLOCK * 1000).toInt())
            return d.copy(styleBoxes = d.styleBoxes + (style to (w to high)))
        }

        private fun setBoldness(x: Float) {
            val b = ((x - wedge.left) / wedge.width() * 100).roundToInt().coerceIn(0, 100)
            val d = session.draft
            update(when (selected) {
                CLOCK -> session.clockStyle?.let { style ->
                    // Boldness applies to a style's digits once they have a box: start it at their size.
                    val withBox = if (style in d.styleBoxes) d else box()?.let { r ->
                        styleBox(d, style, LockLayout.width((r.width() / session.root.width * 1000).roundToInt().coerceAtLeast(LockLayout.MIN_WIDTH)),
                            r.height() / session.root.height)
                    } ?: d
                    withBox.copy(clockBoldness = b)
                } ?: d.copy(clockHeight = d.clockHeight.takeIf { it > 0 } ?: (LockLayout.NUBIA_CLOCK * 1000).roundToInt(), clockBoldness = b)
                DATE -> d.copy(dateWeight = boldnessToWeight(b))
                else -> d.copy(noteWeight = boldnessToWeight(b))
            })
        }

        private fun resetSelected() {
            val d = session.draft
            update(when (selected) {
                CLOCK -> session.clockStyle?.let { d.copy(styleBoxes = d.styleBoxes - it) }
                    ?: d.copy(clockHeight = 0, clockWidth = 0, clockBoldness = LockLayout.DEFAULT_BOLDNESS)
                DATE -> d.copy(dateW = 0, dateH = 0, dateWeight = LockLayout.DEFAULT_DATE_WEIGHT)
                else -> d.copy(noteW = 0, noteH = 0, noteWeight = 0)
            })
        }

        private fun update(next: LockLayout) {
            if (next == session.draft) return
            session.draft = next
            session.root.invalidate()
            invalidate()
        }

        private companion object {
            const val NONE = 0
            const val RESIZE = 1
            const val BOLDNESS = 2
            const val RESET = 3
            const val CHIP = 4
            const val STRIP_BODY = 0x66202830
        }
    }
}
