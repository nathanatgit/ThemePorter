package dev.nebula.glass.hook

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.TextView
import dev.nebula.glass.GlassMaterial
import dev.nebula.glass.GlassRegistry
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.GlassTextHost
import dev.nebula.glass.LensGlass
import dev.nebula.glass.LockLayout
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.WallpaperBackdrop
import dev.nebula.glass.WallpaperFilter
import dev.nebula.glass.applyStyle
import dev.nebula.glass.ui.L
import java.util.WeakHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * iOS 27-style lock-screen editor enhancements inside Nubia's `MyDepthLockScreenEditActivity`:
 *  - Smooth rounded-rect glass selection frame + curved corner-arc Liquid Glass resize handle.
 *  - Live 120 fps fitting for Clock, Date, and Custom Text (`owner_info`) with accurate snap-to-ink.
 *  - Reliable 1-finger horizontal swipe split-curtain wallpaper colour filters (Natural, Black & White,
 *    Duotone, Colour Wash) using GPU `RenderEffect` (never wiped by `ExCropView.initParameters()` and
 *    never accidentally panning `ExCropView`).
 *  - Refractive `LensGlass` capsules, animated segmented `Clock | Date | Text` bar, and Liquid Glass
 *    capsule thickness slider matching `nebulaColorize`'s own glass feel.
 */
internal object LockEditor {
    const val ACTIVITY = "com.zte.mifavor.keyguard.personalclock.MyDepthLockScreenEditActivity"
    private const val TAG = "NebulaGlass"
    private const val SYSTEMUI = "com.android.systemui"

    const val CLOCK = 0
    const val DATE = 1
    const val NOTE = 2

    private val BORDERS = listOf("clock_border", "owner_info_border", "widgetEditorView", "top_image_border", "bottom_image_border")

    private val sessions = WeakHashMap<Activity, Session>()
    private val io = Executors.newSingleThreadExecutor()

    fun currentDraft(a: Activity): LockLayout? = sessions[a]?.draft

    fun install(a: Activity) {
        if (a.isFinishing) return
        val id = a.resources.getIdentifier("root", "id", SYSTEMUI)
        val root = (if (id != 0) a.findViewById<View>(id) else null) as? ViewGroup ?: return
        HookSettings.attach(a)
        sessions[a] = Session(a, root).also { it.start() }
        Log.i(TAG, "lock editor controls installed")
    }

    fun applied(a: Activity) {
        sessions[a]?.let {
            it.applied = true
            it.commit()
        }
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
        private val curtain = FilterCurtain(this)
        private val controls = Controls(this)
        private val widgetRow = WidgetRow(shifts)
        private val found = HashMap<String, View>()
        private val at = IntArray(2)
        private val rootAt = IntArray(2)
        var clockStyle: String? = null
        val content = arrayOfNulls<RectF>(3)
        val centred = BooleanArray(3) { true }

        /** Live horizontal filter swipe state: incoming mode, direction, and split X in `curtain` px (-1 = idle). */
        var incomingFilterMode = -1
        var incomingFromRight = true
        var curtainSplitFraction = -1f
        private var cachedEffectKey: Pair<Int, Int>? = null
        private var cachedRenderEffect: RenderEffect? = null

        fun view(name: String): View? {
            found[name]?.let { if (it.isAttachedToWindow) return it }
            val id = root.resources.getIdentifier(name, "id", SYSTEMUI)
            return (if (id != 0) GlassTextHost.findOutsideLists(root, id) else null)?.also { found[name] = it }
        }

        fun start() {
            val backWp = view("depth_back_wallpaper")
            val wpParent = backWp?.parent as? ViewGroup
            if (wpParent != null) {
                val wpIdx = wpParent.indexOfChild(backWp) + 1
                wpParent.addView(curtain, wpIdx, ViewGroup.LayoutParams(-1, -1))
            }
            val frame = view("phone_frame")
            val index = if (frame?.parent === root) root.indexOfChild(frame) + 1 else -1
            root.addView(controls, index, ViewGroup.LayoutParams(-1, -1))
            root.viewTreeObserver.addOnPreDrawListener(this)
            syncEditorWallpaperFilter(force = true)
        }

        fun clock(): TextView? = (view("clock_view") as? TextView)?.takeIf { it.isShown && LockArrange.resizable(it) }

        fun commit() = save(activity, draft)

        fun close(revert: Boolean) {
            if (root.viewTreeObserver.isAlive) root.viewTreeObserver.removeOnPreDrawListener(this)
            controls.release()
            curtain.release()
            if (revert && !applied) {
                WallpaperBackdrop.setLockFilter(original.filterMode, original.filterColor)
                if (draft.editorFields() != original.editorFields()) save(activity, original)
            } else {
                WallpaperBackdrop.setLockFilter(draft.filterMode, draft.filterColor)
            }
        }

        override fun onPreDraw(): Boolean {
            try { arrange() } catch (t: Throwable) { Log.e(TAG, "lock editor layout", t) }
            try { hideBorders() } catch (t: Throwable) { Log.e(TAG, "lock editor borders", t) }
            try { syncEditorWallpaperFilter(force = false) } catch (t: Throwable) { Log.e(TAG, "lock editor filter", t) }
            return true
        }

        /**
         * Applies the current filter via hardware `View.setRenderEffect` on both `depth_back_wallpaper`
         * and `depth_foreground_wallpaper`. Unlike `ExCropView.mPaint`, `RenderEffect` is never wiped
         * when `ExCropView.initParameters()` recreates `mPaint`, and leaves `saveVisibleCropToFile`
         * completely unfiltered on disk!
         */
        fun syncEditorWallpaperFilter(force: Boolean) {
            val m = draft.filterMode
            val c = draft.filterColor
            val key = m to c
            if (force || key != cachedEffectKey) {
                cachedEffectKey = key
                val cf = WallpaperFilter.filter(m, c)
                cachedRenderEffect = if (cf != null && Build.VERSION.SDK_INT >= 31) {
                    RenderEffect.createColorFilterEffect(cf)
                } else null
                WallpaperBackdrop.setLockFilter(m, c)
            }
            if (Build.VERSION.SDK_INT >= 31) {
                val eff = cachedRenderEffect
                for (name in listOf("depth_back_wallpaper", "depth_foreground_wallpaper")) {
                    val v = view(name) ?: continue
                    v.setRenderEffect(eff)
                }
            }
            if (incomingFilterMode >= 0 && curtainSplitFraction >= 0f) {
                curtain.invalidate()
            } else if (curtain.visibility == View.VISIBLE) {
                curtain.invalidate()
            }
        }

        private fun hideBorders() {
            for (name in BORDERS) {
                val bg = view(name)?.background ?: continue
                if (bg.alpha != 0) bg.mutate().alpha = 0
            }
            if (GlassSettings[Surface.LOCK_WIDGET, activity].enabled) {
                (view("widgetEditorView") as? ViewGroup)?.let { clearWidgetPanels(it) }
            }
        }

        private fun clearWidgetPanels(vg: ViewGroup) {
            for (i in 0 until vg.childCount) {
                val c = vg.getChildAt(i)
                if (GlassTextHost.idName(c) == "widget_background") {
                    c.background?.let { if (it.alpha != 0) it.mutate().alpha = 0 }
                }
                if (c is ViewGroup) clearWidgetPanels(c)
            }
        }

        private fun textBox(views: List<View>, drawnOverride: Pair<Float, Float>? = null): RectF? {
            var box: RectF? = null
            root.getLocationInWindow(rootAt)
            for (v in views) {
                val tv = v as? TextView ?: continue
                if (!tv.isShown) continue
                tv.getLocationInWindow(at)
                val cx = at[0] - rootAt[0] + tv.width / 2f
                val top = (at[1] - rootAt[1]).toFloat()
                val w = (tv.layout?.getLineWidth(0)?.let { it + tv.totalPaddingLeft + tv.totalPaddingRight } ?: tv.width.toFloat()).coerceAtLeast(1f)
                val h = tv.height.toFloat().coerceAtLeast(1f)
                val r = RectF(cx - w / 2, top, cx + w / 2, top + h)
                if (box == null) box = r else box.union(r)
            }
            if (box != null && drawnOverride != null) {
                val (dw, dh) = drawnOverride
                val cx = box.centerX()
                val cy = box.top + maxOf(box.height(), dh) / 2f
                return RectF(cx - dw / 2f, cy - dh / 2f, cx + dw / 2f, cy + dh / 2f)
            }
            return box
        }

        private fun boxed(item: Int, natural: RectF?, w: Int, h: Int): RectF? {
            natural ?: return null
            centred[item] = abs(natural.centerX() - root.width / 2f) < root.width * 0.12f
            if (w <= 0 || h <= 0) return natural
            val bw = w * root.width / 1000f
            val bh = h * root.height / 1000f
            val left = if (centred[item]) root.width / 2f - bw / 2f else natural.left
            return RectF(left, natural.top, left + bw, natural.top + bh)
        }

        private fun arrange() {
            val d = draft
            val clock = clock()
            val find = { name: String -> view(name) }
            val fitted = LockArrange.styleTexts(d, root.width, root.height, find, clock != null)
            val border = view("clock_border")
            val block = view("ower_and_widget_ll")
            val boxes = arrayOfNulls<RectF>(3)

            val dateViews = LockArrange.textViews(find, LockArrange.DATE_IDS)
            val noteViews = LockArrange.textViews(find, LockArrange.NOTE_IDS)
            val dateNatural = textBox(dateViews)
            val noteNatural = textBox(noteViews)

            boxes[DATE] = boxed(DATE, dateNatural, d.dateW, d.dateH)
            boxes[NOTE] = boxed(NOTE, noteNatural, d.noteW, d.noteH)
            content[DATE] = boxes[DATE]?.let { b ->
                val (dw, dh) = fitted.date ?: return@let dateNatural
                RectF(b.centerX() - dw / 2f, b.centerY() - dh / 2f, b.centerX() + dw / 2f, b.centerY() + dh / 2f)
            } ?: dateNatural
            content[NOTE] = boxes[NOTE]?.let { b ->
                val (dw, dh) = fitted.note ?: return@let noteNatural
                RectF(b.centerX() - dw / 2f, b.centerY() - dh / 2f, b.centerX() + dw / 2f, b.centerY() + dh / 2f)
            } ?: noteNatural

            content[CLOCK] = null
            clockStyle = null
            var down = 0
            if (clock == null) {
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
                    down = maxOf(0f, top + height + spare - shifts.topIn(block, root)).roundToInt()
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
                if (border != null && block != null && border.width > 0) {
                    val borderTop = shifts.topIn(border, root)
                    val borderBottom = shifts.topIn(block, root) + down
                    val local = border.top + (borderBottom - borderTop)
                    if (border.bottom != local) border.bottom = local
                }
            }
            val anchor = view("wallpaper_bt")?.takeIf { it.isShown && it.alpha > 0.5f }
                ?: view("edit_bottom_button_ll")?.takeIf { it.isShown && it.alpha > 0.5f }
            val sheetOpen = view("popDialogContainer")?.let { it.isShown && it.height > 40 } == true
            controls.place(if (anchor != null && !sheetOpen) boxes else null, anchor?.let { shifts.topIn(it, root).toFloat() } ?: 0f)
        }
    }

    /**
     * Draws the incoming wallpaper colour filter split-curtain right above `depth_back_wallpaper`
     * using `depth_back_wallpaper.draw(rc)` inside a hardware `RenderNode` with `RenderEffect`.
     * Guaranteed 100% aligned with `ExCropView`'s own matrix, pan, zoom, and bounds!
     */
    @SuppressLint("ViewConstructor")
    private class FilterCurtain(private val session: Session) : View(session.activity) {
        private val node = if (Build.VERSION.SDK_INT >= 31) RenderNode("FilterCurtain") else null
        private val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xAAFFFFFF.toInt()
            strokeWidth = 1.5f * resources.displayMetrics.density
        }
        private var lastKey: Pair<Int, Int>? = null

        fun release() {
            node?.discardDisplayList()
        }

        override fun onDraw(c: Canvas) {
            val nextMode = session.incomingFilterMode
            val frac = session.curtainSplitFraction
            if (nextMode < 0 || frac < 0f || width <= 0 || height <= 0) return
            val wp = session.view("depth_back_wallpaper") ?: return
            val rn = node ?: return
            if (Build.VERSION.SDK_INT < 31 || !c.isHardwareAccelerated) return

            val splitX = (frac * width).coerceIn(0f, width.toFloat())
            val key = nextMode to session.draft.filterColor
            if (key != lastKey) {
                lastKey = key
                val cf = WallpaperFilter.filter(nextMode, session.draft.filterColor)
                rn.setRenderEffect(if (cf != null) RenderEffect.createColorFilterEffect(cf) else null)
            }
            rn.setPosition(0, 0, width, height)
            val rc = rn.beginRecording(width, height)
            try {
                // Draw ExCropView's own unfiltered bitmap into our RenderNode using ExCropView's exact matrix!
                wp.setRenderEffect(null)
                try {
                    wp.draw(rc)
                } finally {
                    session.syncEditorWallpaperFilter(force = false)
                }
            } finally {
                rn.endRecording()
            }

            c.save()
            if (session.incomingFromRight) {
                c.clipRect(splitX, 0f, width.toFloat(), height.toFloat())
            } else {
                c.clipRect(0f, 0f, splitX, height.toFloat())
            }
            c.drawRenderNode(rn)
            c.restore()
            if (splitX > 1f && splitX < width - 1f) {
                c.drawLine(splitX, 0f, splitX, height.toFloat(), divider)
            }
        }
    }

    @SuppressLint("ViewConstructor", "ClickableViewAccessibility")
    private class Controls(private val session: Session) : View(session.activity) {
        private val dp = resources.displayMetrics.density
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val handleShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val handleBody = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val handleRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        }
        private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13.5f * dp
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }
        private val iconText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
        }
        private val path = Path()
        private val skin = GlassSkin()

        // Dedicated LensGlass instances for each floating Liquid Glass bar so RenderEffects stay cached!
        private val filterLens = LensGlass().apply { material = GlassMaterial.REGULAR; refreshAccessibility(context) }
        private val swatchBtnLens = LensGlass().apply { material = GlassMaterial.REGULAR; refreshAccessibility(context) }
        private val swatchBarLens = LensGlass().apply { material = GlassMaterial.REGULAR; refreshAccessibility(context) }
        private val segLens = LensGlass().apply { material = GlassMaterial.REGULAR; refreshAccessibility(context) }
        private val sliderBarLens = LensGlass().apply { material = GlassMaterial.REGULAR; refreshAccessibility(context) }

        private var boxes: Array<RectF?>? = null
        private var selected = CLOCK
        private var segPos = 0f
        private var segAnim: ValueAnimator? = null

        private val strip = RectF()
        private val reset = RectF()
        private val sliderTrack = RectF()
        private val sliderFill = RectF()
        private val thumbRect = RectF()
        private val segBar = RectF()
        private val segPill = RectF()
        private val chips = Array(3) { RectF() }
        private val arc = RectF()
        private val filterBar = RectF()
        private val swatchBtn = RectF()
        private val swatchPopup = RectF()
        private var showSwatches = false
        private val names get() = arrayOf(L("Clock"), L("Date"), L("Text"))
        private val filterNames get() = arrayOf(L("Natural"), L("Black & White"), L("Duotone"), L("Colour Wash"))

        private var mode = NONE
        private var downX = 0f
        private var downY = 0f
        private var downTime = 0L
        private var downW = 0f
        private var downH = 0f
        private var placedAbove = -1f
        private var curtainAnim: ValueAnimator? = null

        init {
            contentDescription = "Clock, date, text and wallpaper filter controls"
            isForceDarkAllowed = false
            GlassRegistry.register(this)
        }

        fun release() {
            curtainAnim?.cancel()
            segAnim?.cancel()
            filterLens.release()
            swatchBtnLens.release()
            swatchBarLens.release()
            segLens.release()
            sliderBarLens.release()
            GlassRegistry.unregister(this)
        }

        private fun selectTab(idx: Int, animate: Boolean) {
            if (selected == idx && !animate) { segPos = idx.toFloat(); return }
            selected = idx
            segAnim?.cancel()
            if (animate) {
                segAnim = ValueAnimator.ofFloat(segPos, idx.toFloat()).apply {
                    duration = 280
                    interpolator = OvershootInterpolator(0.85f)
                    addUpdateListener { segPos = it.animatedValue as Float; invalidate() }
                    start()
                }
            } else {
                segPos = idx.toFloat()
            }
            invalidate()
        }

        fun place(next: Array<RectF?>?, above: Float) {
            if (mode == RESIZE || mode == FILTER_SWIPE) { boxes = next; invalidate(); return }
            boxes = next
            if (next != null && next.getOrNull(selected) == null) {
                next.indexOfFirst { it != null }.takeIf { it >= 0 }?.let { selectTab(it, animate = false) }
            }
            if (next != null && above != placedAbove && width > 0) {
                placedAbove = above
                val bottom = above - 12 * dp
                val side = width * 0.065f
                // 1. Bottom thickness / boldness glass capsule
                strip.set(side, bottom - 52 * dp, width - side, bottom)
                reset.set(strip.left + 8 * dp, strip.top + 7 * dp, strip.left + 46 * dp, strip.bottom - 7 * dp)
                sliderTrack.set(reset.right + 28 * dp, strip.centerY() - 6 * dp, strip.right - 28 * dp, strip.centerY() + 6 * dp)

                // 2. Unified segmented glass bar (Clock | Date | Text)
                val gap = 9 * dp
                segBar.set(side, strip.top - gap - 42 * dp, width - side, strip.top - gap)
                val segPad = 4 * dp
                val cw = (segBar.width() - 2 * segPad) / 3f
                for (i in 0 until 3) {
                    val l = segBar.left + segPad + i * cw
                    chips[i].set(l, segBar.top + segPad, l + cw, segBar.bottom - segPad)
                }

                // 3. iOS wallpaper filter glass capsule + page dots
                val fw = 184 * dp
                val ft = segBar.top - gap - 38 * dp
                filterBar.set(width / 2f - fw / 2f, ft, width / 2f + fw / 2f, ft + 38 * dp)
                swatchBtn.set(filterBar.right + 10 * dp, ft + 2 * dp, filterBar.right + 44 * dp, ft + 36 * dp)
                val pw = (WallpaperFilter.SWATCHES.size * 34 + 14) * dp
                swatchPopup.set(width / 2f - pw / 2f, ft - 46 * dp, width / 2f + pw / 2f, ft - 6 * dp)
            }
            invalidate()
        }

        private fun box(): RectF? = boxes?.getOrNull(selected)

        /** Draws a true refractive Liquid Glass capsule over `phone_frame` (falling back to GlassSkin). */
        private fun drawGlassBar(c: Canvas, lens: LensGlass, r: RectF, cornerR: Float) {
            val night = GlassSettings.isNight(context)
            val st = GlassSettings[Surface.LOCK_WIDGET, night]
            val builtIn = if (Looks.dark(night)) 0x4C161820 else 0x4EFFFFFF
            val body = if (!st.tinted) st.frosted(builtIn) else Looks.lockCardBody(st, night)
            val backdrop = session.view("phone_frame") ?: session.root
            var drew = false
            if (lens.isAvailable && c.isHardwareAccelerated && backdrop.width > 0) {
                lens.applyStyle(
                    st, dp, cornerR, body, 1.15f, 24f * dp,
                    originalBevel = 11f * dp,
                    originalRefraction = 22f * dp,
                    originalDispersion = 0.05f,
                    originalSaturation = 180f,
                )
                lens.frostCoat = !st.tinted
                drew = lens.draw(c, this, backdrop, r)
            }
            if (!drew) {
                skin.drawPanel(c, r, GlassSkin.uniform(cornerR), body, 1.15f * st.glow, dp, st)
            }
        }

        private fun darkInkForBar(): Boolean {
            val night = GlassSettings.isNight(context)
            val st = GlassSettings[Surface.LOCK_WIDGET, night]
            return !Looks.dark(night) && st.frost > 1.35f && !st.tinted
        }

        override fun onDraw(c: Canvas) {
            if (GlassRegistry.isCapturing(this)) return
            val all = boxes ?: return
            val night = GlassSettings.isNight(context)
            val st = GlassSettings[Surface.LOCK_WIDGET, night]
            val darkInk = darkInkForBar()
            val primaryInk = if (darkInk) 0xFF14161C.toInt() else Color.WHITE
            val mutedInk = if (darkInk) 0x7714161C else 0x80FFFFFF.toInt()

            if (mode != FILTER_SWIPE) {
                for (i in listOf(CLOCK, DATE, NOTE).sortedBy { it == selected }) {
                    val b = all.getOrNull(i) ?: continue
                    if (mode == RESIZE && i != selected) continue
                    val on = i == selected
                    val grabbing = on && mode == RESIZE
                    val cornerR = minOf(24f * dp, minOf(b.width(), b.height()) * 0.34f).coerceAtLeast(10f * dp)

                    paint.style = Paint.Style.STROKE
                    paint.pathEffect = null
                    paint.strokeWidth = (if (on) 1.8f else 1.1f) * dp
                    paint.color = if (on) 0xBAE8F4FF.toInt() else 0x52FFFFFF
                    c.drawRoundRect(b, cornerR, cornerR, paint)

                    drawIosCornerHandle(c, b, cornerR, on, grabbing)
                }
            }
            if (mode == RESIZE) return

            // 1. Filter indicator Liquid Glass capsule (Natural / B&W / Duotone / Wash + chevrons + page dots)
            val fm = if (mode == FILTER_SWIPE && session.incomingFilterMode >= 0) session.incomingFilterMode else session.draft.filterMode
            drawGlassBar(c, filterLens, filterBar, filterBar.height() / 2f)
            label.color = primaryInk
            if (darkInk) label.clearShadowLayer() else label.setShadowLayer(2.5f * dp, 0f, 0.8f * dp, 0x40000000)
            label.textSize = 13f * dp
            c.drawText(filterNames[fm], filterBar.centerX(), filterBar.top + 19f * dp, label)
            // Subtle ‹ and › hints for tapping left/right or swiping
            label.color = mutedInk
            label.textSize = 14f * dp
            c.drawText("‹", filterBar.left + 15f * dp, filterBar.centerY() - (label.ascent() + label.descent()) / 2f, label)
            c.drawText("›", filterBar.right - 15f * dp, filterBar.centerY() - (label.ascent() + label.descent()) / 2f, label)

            val dotGap = 9f * dp
            val dotsStart = filterBar.centerX() - (WallpaperFilter.COUNT - 1) * dotGap / 2f
            paint.style = Paint.Style.FILL
            for (k in 0 until WallpaperFilter.COUNT) {
                paint.color = if (k == fm) primaryInk else mutedInk
                c.drawCircle(dotsStart + k * dotGap, filterBar.bottom - 7.5f * dp, (if (k == fm) 2.6f else 1.9f) * dp, paint)
            }

            // Colour swatch button & glass palette popup when Duotone or Colour Wash is active
            val hasHue = session.draft.filterMode == WallpaperFilter.DUOTONE || session.draft.filterMode == WallpaperFilter.WASH
            if (hasHue && mode != FILTER_SWIPE) {
                drawGlassBar(c, swatchBtnLens, swatchBtn, swatchBtn.height() / 2f)
                paint.style = Paint.Style.FILL
                paint.color = Color.WHITE
                c.drawCircle(swatchBtn.centerX(), swatchBtn.centerY(), swatchBtn.width() * 0.34f, paint)
                paint.color = session.draft.filterColor or 0xFF000000.toInt()
                c.drawCircle(swatchBtn.centerX(), swatchBtn.centerY(), swatchBtn.width() * 0.27f, paint)

                if (showSwatches) {
                    drawGlassBar(c, swatchBarLens, swatchPopup, swatchPopup.height() / 2f)
                    val swList = WallpaperFilter.SWATCHES
                    val step = 34f * dp
                    val startX = swatchPopup.left + 24f * dp
                    val cy = swatchPopup.centerY()
                    for (idx in swList.indices) {
                        val cx = startX + idx * step
                        val col = swList[idx]
                        if (col == session.draft.filterColor) {
                            paint.color = Color.WHITE
                            c.drawCircle(cx, cy, 13.5f * dp, paint)
                        }
                        paint.color = col
                        c.drawCircle(cx, cy, 10.8f * dp, paint)
                    }
                }
            }

            if (mode == FILTER_SWIPE) return

            // 2. Unified Segmented Liquid Glass Bar (Clock | Date | Text) with sliding glass droplet!
            drawGlassBar(c, segLens, segBar, segBar.height() / 2f)
            val segPad = 4 * dp
            val cellW = (segBar.width() - 2 * segPad) / 3f
            val travel = abs(segPos - segPos.roundToInt()).coerceAtMost(0.5f)
            val pillCx = segBar.left + segPad + cellW * (segPos + 0.5f)
            val pillHalfW = cellW / 2f + travel * 10f * dp
            segPill.set(
                (pillCx - pillHalfW).coerceAtLeast(segBar.left + segPad),
                segBar.top + segPad,
                (pillCx + pillHalfW).coerceAtMost(segBar.right - segPad),
                segBar.bottom - segPad,
            )
            val pillFill = if (Looks.dark(night)) 0x52FFFFFF else 0x7AFFFFFF
            skin.drawPanel(c, segPill, GlassSkin.uniform(segPill.height() / 2f), pillFill, 1.25f * st.glow, dp, st)

            label.textSize = 13.8f * dp
            for (i in 0 until 3) {
                val r = chips[i]
                val on = i == selected
                val enabled = all[i] != null
                label.color = when {
                    !enabled -> mutedInk
                    on && !Looks.dark(night) -> 0xFF14161C.toInt()
                    on -> Color.WHITE
                    else -> primaryInk
                }
                if (on && !Looks.dark(night)) label.clearShadowLayer()
                else label.setShadowLayer(2f * dp, 0f, 0.7f * dp, 0x38000000)
                c.drawText(names[i], r.centerX(), r.centerY() - (label.ascent() + label.descent()) / 2f, label)
            }

            // 3. Thickness / Boldness Liquid Glass Capsule Slider (Reset pill + thin/bold A + glass droplet thumb)
            drawGlassBar(c, sliderBarLens, strip, strip.height() / 2f)

            // Reset circle button with glass rim
            val resetFill = if (isCustom()) 0x42FFFFFF else 0x1EFFFFFF
            skin.drawPanel(c, reset, GlassSkin.uniform(reset.height() / 2f), resetFill, 0.9f * st.glow, dp, st)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.0f * dp
            paint.color = if (isCustom()) primaryInk else mutedInk
            val q = 7.5f * dp
            arc.set(reset.centerX() - q, reset.centerY() - q, reset.centerX() + q, reset.centerY() + q)
            c.drawArc(arc, -50f, 290f, false, paint)
            val ax = arc.centerX() - q * 0.5f
            val ay = arc.centerY() - q * 0.87f
            c.drawLine(ax, ay, ax - 3.8f * dp, ay - 1.8f * dp, paint)
            c.drawLine(ax, ay, ax - 0.9f * dp, ay + 4.2f * dp, paint)

            // Thin 'A' and Bold 'A' glyph hints flanking the capsule track
            iconText.color = mutedInk
            iconText.textSize = 12f * dp
            iconText.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
            c.drawText("A", reset.right + 14 * dp, strip.centerY() - (iconText.ascent() + iconText.descent()) / 2f, iconText)
            iconText.textSize = 13.5f * dp
            iconText.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            c.drawText("A", strip.right - 14 * dp, strip.centerY() - (iconText.ascent() + iconText.descent()) / 2f, iconText)

            // Capsule slider track + luminous progress fill
            val trackR = sliderTrack.height() / 2f
            paint.style = Paint.Style.FILL
            paint.color = if (darkInk) 0x26000000 else 0x2EFFFFFF
            c.drawRoundRect(sliderTrack, trackR, trackR, paint)

            val frac = boldness() / 100f
            val tx = sliderTrack.left + sliderTrack.width() * frac
            sliderFill.set(sliderTrack.left, sliderTrack.top, maxOf(sliderTrack.left + sliderTrack.height(), tx + trackR), sliderTrack.bottom)
            paint.color = if (st.tinted) (0xB8 shl 24) or (st.color and 0x00FFFFFF) else 0x8CFFFFFF.toInt()
            c.drawRoundRect(sliderFill, trackR, trackR, paint)

            // Plumping Liquid Glass droplet thumb
            val draggingSlider = mode == BOLDNESS
            val thumbW = (if (draggingSlider) 32f else 26f) * dp
            val thumbH = (if (draggingSlider) 22f else 18f) * dp
            thumbRect.set(tx - thumbW / 2f, strip.centerY() - thumbH / 2f, tx + thumbW / 2f, strip.centerY() + thumbH / 2f)
            // Soft shadow under thumb
            paint.color = 0x2E000000
            c.save()
            c.translate(0f, 1.2f * dp)
            c.drawRoundRect(thumbRect, thumbH / 2f, thumbH / 2f, paint)
            c.restore()
            val thumbBody = if (draggingSlider) 0x70FFFFFF else 0xBAFFFFFF.toInt()
            skin.drawPanel(c, thumbRect, GlassSkin.uniform(thumbH / 2f), thumbBody, 1.4f * st.glow, dp, st)
        }

        private fun drawIosCornerHandle(c: Canvas, b: RectF, cornerR: Float, on: Boolean, grabbing: Boolean) {
            val r = maxOf(cornerR, 15f * dp)
            val tail = if (on) 6f * dp else 3f * dp
            val cx = b.right - r
            val cy = b.bottom - r
            arc.set(cx - r, cy - r, cx + r, cy + r)
            path.reset()
            path.moveTo(b.right, (cy - tail).coerceAtLeast(b.top + cornerR * 0.5f))
            path.lineTo(b.right, cy)
            path.arcTo(arc, 0f, 90f, false)
            path.lineTo((cx - tail).coerceAtLeast(b.left + cornerR * 0.5f), b.bottom)

            val tubeW = (when { grabbing -> 10.5f; on -> 7.5f; else -> 5.5f }) * dp
            handleShadow.strokeWidth = tubeW + 2.5f * dp
            handleShadow.color = if (on) 0x38000000 else 0x22000000
            c.save()
            c.translate(0.8f * dp, 1.2f * dp)
            c.drawPath(path, handleShadow)
            c.restore()

            handleBody.strokeWidth = tubeW
            handleBody.color = when {
                grabbing -> 0xEBFFFFFF.toInt()
                on -> 0xA8EBF6FF.toInt()
                else -> 0x68FFFFFF
            }
            c.drawPath(path, handleBody)

            handleRim.strokeWidth = (if (grabbing) 3.2f else 2.0f) * dp
            handleRim.color = if (on) Color.WHITE else 0x99FFFFFF.toInt()
            c.drawPath(path, handleRim)
        }

        private fun isCustom(): Boolean {
            val d = session.draft
            return when (selected) {
                CLOCK -> session.clockStyle?.let { it in d.styleBoxes } ?: (d.hasCustomClock || d.clockWidth > 0)
                DATE -> d.dateW > 0 || d.dateWeight != LockLayout.DEFAULT_DATE_WEIGHT
                else -> d.noteW > 0 || d.noteWeight > 0
            }
        }

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
                    val hasHue = session.draft.filterMode == WallpaperFilter.DUOTONE || session.draft.filterMode == WallpaperFilter.WASH
                    if (showSwatches && hasHue && swatchPopup.contains(e.x, e.y)) {
                        val idx = ((e.x - (swatchPopup.left + 7f * dp)) / (34f * dp)).toInt().coerceIn(0, WallpaperFilter.SWATCHES.lastIndex)
                        update(session.draft.copy(filterColor = WallpaperFilter.SWATCHES[idx]))
                        session.syncEditorWallpaperFilter(force = true)
                        session.commit()
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        return true
                    }
                    if (hasHue && swatchBtn.contains(e.x, e.y)) {
                        showSwatches = !showSwatches
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        invalidate()
                        return true
                    }
                    showSwatches = false
                    if (filterBar.contains(e.x, e.y)) {
                        val step = if (e.x < filterBar.left + 44 * dp) WallpaperFilter.COUNT - 1 else 1
                        val next = (session.draft.filterMode + step) % WallpaperFilter.COUNT
                        animateFilterCurtainTo(next, fromRight = step == 1)
                        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        return true
                    }

                    val grabbed = listOf(selected, CLOCK, DATE, NOTE).firstOrNull { i ->
                        val r = all.getOrNull(i)
                        r != null && abs(e.x - r.right) < 40 * dp && abs(e.y - r.bottom) < 40 * dp
                    }
                    if (grabbed != null) selectTab(grabbed, animate = true)
                    val b = box()
                    val chip = if (segBar.contains(e.x, e.y)) ((e.x - segBar.left) / (segBar.width() / 3f)).toInt().coerceIn(0, 2) else -1
                    mode = when {
                        grabbed != null -> RESIZE
                        chip >= 0 -> if (all[chip] != null) CHIP else NONE
                        reset.contains(e.x, e.y) -> RESET
                        strip.contains(e.x, e.y) -> if (e.x > reset.right) BOLDNESS else NONE
                        e.y < filterBar.top && e.y > height * 0.08f -> FILTER_CANDIDATE
                        else -> return false
                    }
                    downX = e.x; downY = e.y; downTime = e.eventTime
                    b?.let { downW = it.width(); downH = it.height() }
                    if (mode == FILTER_CANDIDATE) {
                        // Do NOT forward single-finger DOWN to ExCropView yet: ExCropView pans on the
                        // very first MOVE and ignores ACTION_CANCEL! Wait to see if it's a tap, 2-finger
                        // pinch/pan, or 1-finger horizontal filter swipe.
                        return true
                    }
                    parent?.requestDisallowInterceptTouchEvent(true)
                    if (mode == CHIP) selectTab(chip, animate = true)
                    if (mode == BOLDNESS) setBoldness(e.x)
                    if (mode != NONE) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    // 2-finger pinch/pan -> hand gesture cleanly to Nubia's ExCropView!
                    if (mode == FILTER_CANDIDATE) {
                        mode = PASSTHROUGH
                        synthesizeDownAndForward(e)
                    } else if (mode == PASSTHROUGH) {
                        dispatchToUnderlying(e)
                    }
                }
                MotionEvent.ACTION_MOVE -> when (mode) {
                    RESIZE -> resize(downW + (if (session.centred[selected]) 2 else 1) * (e.x - downX), downH + (e.y - downY))
                    BOLDNESS -> { setBoldness(e.x); invalidate() }
                    FILTER_CANDIDATE -> {
                        val dx = e.x - downX
                        val dy = e.y - downY
                        if (abs(dx) > slop && abs(dx) > abs(dy) * 1.15f) {
                            curtainAnim?.cancel()
                            mode = FILTER_SWIPE
                            parent?.requestDisallowInterceptTouchEvent(true)
                            val fromRight = dx < 0
                            session.incomingFromRight = fromRight
                            session.incomingFilterMode = (session.draft.filterMode + (if (fromRight) 1 else WallpaperFilter.COUNT - 1)) % WallpaperFilter.COUNT
                            session.curtainSplitFraction = if (fromRight) (1f + dx / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                                else (dx / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            session.syncEditorWallpaperFilter(force = false)
                            performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                            invalidate()
                        } else if (abs(dy) > slop * 1.6f) {
                            // Vertical drag -> pass cleanly to underlying view
                            mode = PASSTHROUGH
                            synthesizeDownAndForward(e)
                        }
                    }
                    FILTER_SWIPE -> {
                        val dx = e.x - downX
                        val fromRight = dx < 0
                        if (fromRight != session.incomingFromRight) {
                            session.incomingFromRight = fromRight
                            session.incomingFilterMode = (session.draft.filterMode + (if (fromRight) 1 else WallpaperFilter.COUNT - 1)) % WallpaperFilter.COUNT
                        }
                        session.curtainSplitFraction = if (fromRight) (1f + dx / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                            else (dx / width.coerceAtLeast(1)).coerceIn(0f, 1f)
                        session.syncEditorWallpaperFilter(force = false)
                        invalidate()
                    }
                    PASSTHROUGH -> dispatchToUnderlying(e)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    val done = mode
                    mode = NONE
                    when (done) {
                        FILTER_CANDIDATE -> {
                            // Clean single tap -> deliver tap (DOWN + UP) to Nubia's clock_border / owner_info / widgetEditorView!
                            if (e.actionMasked == MotionEvent.ACTION_UP && abs(e.x - downX) <= slop && abs(e.y - downY) <= slop) {
                                val d = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
                                val u = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, e.x, e.y, 0)
                                try {
                                    dispatchToUnderlying(d)
                                    dispatchToUnderlying(u)
                                } finally {
                                    d.recycle(); u.recycle()
                                }
                            }
                        }
                        PASSTHROUGH -> dispatchToUnderlying(e)
                        FILTER_SWIPE -> finishFilterSwipe(e.x - downX)
                        else -> {
                            if (done == RESET && e.actionMasked == MotionEvent.ACTION_UP && reset.contains(e.x, e.y)) resetSelected()
                            if (done == RESIZE) snap()
                            if (done == RESIZE || done == BOLDNESS || done == RESET) session.commit()
                        }
                    }
                    invalidate()
                }
            }
            return true
        }

        private var forwarding = false

        private fun synthesizeDownAndForward(current: MotionEvent) {
            val d = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, downX, downY, 0)
            try { dispatchToUnderlying(d) } finally { d.recycle() }
            dispatchToUnderlying(current)
        }

        private fun dispatchToUnderlying(e: MotionEvent) {
            if (forwarding) return
            forwarding = true
            setTransitionVisibility(INVISIBLE)
            try { session.root.dispatchTouchEvent(e) } catch (_: Throwable) {}
            setTransitionVisibility(VISIBLE)
            forwarding = false
        }

        private fun animateFilterCurtainTo(targetMode: Int, fromRight: Boolean) {
            curtainAnim?.cancel()
            session.incomingFromRight = fromRight
            session.incomingFilterMode = targetMode
            val start = if (fromRight) 1f else 0f
            val end = if (fromRight) 0f else 1f
            curtainAnim = ValueAnimator.ofFloat(start, end).apply {
                duration = 260
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    session.curtainSplitFraction = it.animatedValue as Float
                    session.syncEditorWallpaperFilter(force = false)
                    invalidate()
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        session.incomingFilterMode = -1
                        session.curtainSplitFraction = -1f
                        update(session.draft.copy(filterMode = targetMode))
                        session.syncEditorWallpaperFilter(force = true)
                        session.commit()
                        invalidate()
                    }
                })
                start()
            }
        }

        private fun finishFilterSwipe(dx: Float) {
            val commit = abs(dx) > width * 0.15f
            val fromRight = session.incomingFromRight
            val startFrac = session.curtainSplitFraction.coerceIn(0f, 1f)
            val endFrac = if (commit) (if (fromRight) 0f else 1f) else (if (fromRight) 1f else 0f)
            val targetMode = session.incomingFilterMode
            curtainAnim?.cancel()
            curtainAnim = ValueAnimator.ofFloat(startFrac, endFrac).apply {
                duration = 210
                interpolator = DecelerateInterpolator()
                addUpdateListener {
                    session.curtainSplitFraction = it.animatedValue as Float
                    session.syncEditorWallpaperFilter(force = false)
                }
                addListener(object : AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: Animator) {
                        session.incomingFilterMode = -1
                        session.curtainSplitFraction = -1f
                        if (commit && targetMode >= 0) {
                            update(session.draft.copy(filterMode = targetMode))
                            session.commit()
                        }
                        session.syncEditorWallpaperFilter(force = true)
                        invalidate()
                    }
                })
                start()
            }
        }

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

        private fun snap() {
            val c = session.content[selected] ?: return
            val b = box() ?: return
            if (c.width() <= 4f || c.height() <= 4f) return
            val pad = 6f * dp
            val w = minOf(b.width(), c.width() + pad)
            val h = minOf(b.height(), c.height() + pad)
            if (w < b.width() - 6f * dp || h < b.height() - 6f * dp) resize(w, h)
        }

        private fun styleBox(d: LockLayout, style: String, w: Int, h: Float): LockLayout {
            val high = (h * 1000).roundToInt().coerceIn(LockLayout.MIN_TEXT_H, (LockLayout.MAX_CLOCK * 1000).toInt())
            return d.copy(styleBoxes = d.styleBoxes + (style to (w to high)))
        }

        private fun setBoldness(x: Float) {
            val b = ((x - sliderTrack.left) / sliderTrack.width().coerceAtLeast(1f) * 100).roundToInt().coerceIn(0, 100)
            val d = session.draft
            update(when (selected) {
                CLOCK -> session.clockStyle?.let { style ->
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
            const val FILTER_CANDIDATE = 5
            const val FILTER_SWIPE = 6
            const val PASSTHROUGH = 7
        }
    }
}
