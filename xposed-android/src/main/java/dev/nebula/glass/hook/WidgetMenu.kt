package dev.nebula.glass.hook

import android.app.AlertDialog
import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.WidgetTweak
import dev.nebula.glass.ui.L
import java.util.concurrent.Executors

/**
 * "Widget settings" in a home widget's long-press menu, and its dialog: title (Nubia's / shown / hidden,
 * and its text), zoom, margin and glass background for that one widget. Edits preview live in the
 * launcher ([GlassSettings.updateLocal]); Done saves them to the module's settings through
 * [dev.nebula.glass.ui.LayoutProvider], so they are part of export / import.
 *
 * The row is added when the popup attaches, before Nubia measures and places it, as a copy of
 * Nubia's own "移除" row (`system_shortcuts_container` > `system_shortcut` > `bubble_text`).
 */
object WidgetMenu {
    private const val TAG = "NebulaGlass"
    const val ROW = "nebula.widgetSettings"
    private val io = Executors.newSingleThreadExecutor()

    fun extend(popup: ViewGroup) {
        val widgets = LauncherWidgets.instance ?: return
        val host = widgets.hostFor(popup) ?: return
        val list = find(popup, "system_shortcuts_container") as? LinearLayout ?: return
        if (list.findViewWithTag<View>(ROW) != null) return
        val template = (0 until list.childCount).map { list.getChildAt(it) }.firstOrNull { it is ViewGroup } as? ViewGroup ?: return
        val label = (find(template, "bubble_text") ?: template.getChildAt(0)) as? TextView ?: return
        val ctx = popup.context
        val row = FrameLayout(ctx).apply {
            tag = ROW
            background = template.background?.constantState?.newDrawable()?.mutate()
            foreground = template.foreground?.constantState?.newDrawable()?.mutate()
            setPadding(template.paddingLeft, template.paddingTop, template.paddingRight, template.paddingBottom)
            minimumHeight = template.minimumHeight
            isClickable = true
            isFocusable = true
            contentDescription = L("Widget settings")
        }
        val text = TextView(ctx).apply {
            text = L("Widget settings")
            setTextSize(TypedValue.COMPLEX_UNIT_PX, label.textSize)
            setTextColor(label.textColors)
            typeface = label.typeface
            gravity = label.gravity
            setPadding(label.paddingLeft, label.paddingTop, label.paddingRight, label.paddingBottom)
            compoundDrawablePadding = label.compoundDrawablePadding
            val icon = label.compoundDrawablesRelative[0] ?: label.compoundDrawables[0]
            if (icon != null) {
                val tune = TuneIcon(label.currentTextColor)
                tune.bounds = icon.copyBounds()
                setCompoundDrawablesRelative(tune, null, null, null)
            }
        }
        val textLp = (label.layoutParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) } ?: FrameLayout.LayoutParams(-1, -1)
        row.addView(text, textLp)
        // Nubia draws the row icon as its own view next to the text: copy its place.
        (find(template, "icon") ?: (0 until template.childCount).map { template.getChildAt(it) }.firstOrNull { it !is TextView })?.let { icon ->
            val lp = (icon.layoutParams as? FrameLayout.LayoutParams)?.let { FrameLayout.LayoutParams(it) } ?: return@let
            row.addView(View(ctx).apply {
                background = TuneIcon(label.currentTextColor)
                setPadding(icon.paddingLeft, icon.paddingTop, icon.paddingRight, icon.paddingBottom)
            }, lp)
        }
        // One menu, two items: a hairline between them (LauncherPopup draws the list as one glass panel).
        val dp = ctx.resources.displayMetrics.density
        val hair = maxOf(1, (0.5f * dp).toInt())
        list.dividerDrawable = android.graphics.drawable.GradientDrawable().apply {
            setColor((label.currentTextColor and 0xFFFFFF) or (0x1F shl 24))
            setSize(1, hair)
        }
        list.showDividers = LinearLayout.SHOW_DIVIDER_MIDDLE
        list.dividerPadding = (20 * dp).toInt()
        val rowLp = (template.layoutParams as? LinearLayout.LayoutParams)?.let { LinearLayout.LayoutParams(it) }
            ?: LinearLayout.LayoutParams(-1, template.height.coerceAtLeast(-2))
        row.setOnClickListener {
            close(popup)
            try { show(host) } catch (t: Throwable) { Log.e(TAG, "widget settings dialog", t) }
        }
        list.addView(row, rowLp)
        // Nubia gives the list a fixed height for its own rows: once laid out, grow it by one row. A popup
        // above the widget moves up by as much, so its arrow still points at the widget.
        popup.viewTreeObserver.addOnPreDrawListener(object : android.view.ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val th = template.height
                if (th <= 0) return true
                popup.viewTreeObserver.removeOnPreDrawListener(this)
                if (row.height >= th) return true
                rowLp.height = th
                row.layoutParams = rowLp
                list.layoutParams?.let { if (it.height > 0) { it.height += th + hair; list.layoutParams = it } }
                val above = try { de.robv.android.xposed.XposedHelpers.getBooleanField(popup, "mIsAboveIcon") } catch (_: Throwable) { false }
                if (above) popup.translationY -= th + hair
                Log.i(TAG, "widget menu row sized ${th}px above=$above")
                return false
            }
        })
    }

    private fun close(popup: View) {
        try { de.robv.android.xposed.XposedHelpers.callMethod(popup, "close", false); return } catch (_: Throwable) {}
        try { de.robv.android.xposed.XposedHelpers.callMethod(popup, "close"); return } catch (_: Throwable) {}
        (popup.parent as? ViewGroup)?.removeView(popup)
    }

    private fun show(host: AppWidgetHostView) {
        val widgets = LauncherWidgets.instance ?: return
        val id = host.appWidgetId
        val ctx = host.context
        val night = GlassSettings.isNight(ctx)
        val themed = ContextThemeWrapper(ctx, if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert)
        val dp = ctx.resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val original = widgets.tweakOf(host)
        var draft = original

        fun preview() {
            val cfg = GlassSettings.config
            val map = cfg.widgets.toMutableMap()
            if (draft.isDefault) map.remove(id) else map[id] = draft
            GlassSettings.updateLocal(cfg.copy(widgets = map))
            widgets.refresh(host)
        }

        val body = LinearLayout(themed).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(24), px(8), px(24), 0)
        }
        fun caption(t: String) = TextView(themed).apply {
            text = t
            textSize = 14f
            setPadding(0, px(12), 0, px(2))
        }

        // Title: Nubia's choice, shown or hidden, and its text.
        body.addView(caption(L("Title")))
        val modes = RadioGroup(themed).apply { orientation = RadioGroup.HORIZONTAL }
        val modeIds = IntArray(3)
        for ((i, name) in listOf(L("Default"), L("Show"), L("Hide")).withIndex()) {
            val b = RadioButton(themed).apply { text = name; this.id = View.generateViewId() }
            modeIds[i] = b.id
            modes.addView(b, RadioGroup.LayoutParams(0, -2, 1f))
        }
        modes.check(modeIds[draft.title])
        modes.setOnCheckedChangeListener { _, checked ->
            draft = draft.copy(title = modeIds.indexOf(checked).coerceAtLeast(0)); preview()
        }
        body.addView(modes)
        val name = EditText(themed).apply {
            setSingleLine()
            hint = widgets.titleText(host)
            setText(draft.label)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun afterTextChanged(s: Editable?) {
                    val v = s?.toString().orEmpty().take(40)
                    if (v != draft.label) { draft = draft.copy(label = v); preview() }
                }
            })
        }
        body.addView(name)

        fun slider(title: String, min: Int, max: Int, read: () -> Int, show: (Int) -> String, write: (Int) -> Unit): SeekBar {
            val head = caption("")
            body.addView(head)
            val bar = SeekBar(themed).apply {
                this.max = max - min
                progress = read() - min
                head.text = "$title  ${show(read())}"
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                        head.text = "$title  ${show(p + min)}"
                        if (fromUser) write(p + min)
                    }
                    override fun onStartTrackingTouch(s: SeekBar) {}
                    override fun onStopTrackingTouch(s: SeekBar) {}
                })
            }
            body.addView(bar, LinearLayout.LayoutParams(-1, px(40)))
            return bar
        }
        val zoom = slider(L("Zoom"), WidgetTweak.MIN_SCALE, WidgetTweak.MAX_SCALE, { draft.scale }, { "$it%" }) {
            draft = draft.copy(scale = it); preview()
        }
        // Container size: the cell span less N dp; "Default" uses the Home widgets setting.
        fun trimText(v: Int) = if (v < 0) L("Default") else "−$v dp"
        val width = slider(L("Container width"), -1, dev.nebula.glass.Shapes.MAX_TRIM, { draft.width }, ::trimText) {
            draft = draft.copy(width = it); preview()
        }
        val height = slider(L("Container height"), -1, dev.nebula.glass.Shapes.MAX_TRIM, { draft.height }, ::trimText) {
            draft = draft.copy(height = it); preview()
        }
        // Negative margins push the content past the container (cropped).
        val marginX = slider(L("Horizontal margin"), -WidgetTweak.MAX_MARGIN, WidgetTweak.MAX_MARGIN, { draft.marginX }, { "$it px" }) {
            draft = draft.copy(marginX = it); preview()
        }
        val marginY = slider(L("Vertical margin"), -WidgetTweak.MAX_MARGIN, WidgetTweak.MAX_MARGIN, { draft.marginY }, { "$it px" }) {
            draft = draft.copy(marginY = it); preview()
        }
        val glass = Switch(themed).apply {
            text = L("Glass background")
            textSize = 16f
            isChecked = draft.glass
            setPadding(0, px(12), 0, px(8))
            setOnCheckedChangeListener { _, on -> draft = draft.copy(glass = on); preview() }
        }
        body.addView(glass)

        var saved = false
        val dialog = AlertDialog.Builder(themed)
            .setTitle(widgets.titleText(host).ifEmpty { L("Widget settings") })
            .setView(android.widget.ScrollView(themed).apply { addView(body) })
            .setPositiveButton(L("Done")) { _, _ -> saved = true; save(ctx) }
            .setNegativeButton(L("Cancel"), null)
            .setNeutralButton(L("Reset"), null)
            .create()
        dialog.setOnDismissListener {
            if (!saved) { draft = original; preview() }
        }
        dialog.setOnShowListener {
            // Reset stays open: it only returns the controls to their defaults.
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.setOnClickListener {
                draft = WidgetTweak()
                modes.check(modeIds[0])
                name.setText("")
                zoom.progress = 100 - WidgetTweak.MIN_SCALE
                width.progress = 0
                height.progress = 0
                marginX.progress = WidgetTweak.MAX_MARGIN
                marginY.progress = WidgetTweak.MAX_MARGIN
                glass.isChecked = true
                draft = WidgetTweak()
                preview()
            }
        }
        dialog.window?.let { w ->
            // Keep the widget in view: the dialog sits in the other half of the screen.
            val at = IntArray(2).also { host.getLocationOnScreen(it) }
            val lower = at[1] + host.height / 2 > ctx.resources.displayMetrics.heightPixels / 2
            w.setGravity(if (lower) Gravity.TOP else Gravity.BOTTOM)
            w.setDimAmount(0.1f)
        }
        dialog.show()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, (ctx.resources.displayMetrics.heightPixels * 0.56f).toInt())
    }

    private fun save(ctx: Context) {
        val values = Bundle().apply { putString(WidgetTweak.KEY, WidgetTweak.mapToJson(GlassSettings.config.widgets).toString()) }
        val resolver = ctx.applicationContext.contentResolver
        io.execute {
            try {
                resolver.call(Uri.parse("content://${GlassSettings.LAYOUT_AUTHORITY}"), WidgetTweak.CALL_SAVE, null, values)
            } catch (t: Throwable) { Log.e(TAG, "saving widget settings", t) }
        }
    }

    private fun find(v: View, idName: String): View? {
        try {
            if (v.id != View.NO_ID && v.resources.getResourceEntryName(v.id) == idName) return v
        } catch (_: Exception) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) find(v.getChildAt(i), idName)?.let { return it }
        return null
    }

    /** A disc with two slider lines, in the menu text's colour, sized like Nubia's row icons. */
    private class TuneIcon(private val ink: Int) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(c: Canvas) {
            val b = bounds
            val r = minOf(b.width(), b.height()) / 2f
            val cx = b.exactCenterX()
            val cy = b.exactCenterY()
            // Nubia's icon discs: near-white under dark ink, faint white under light ink.
            val darkInk = android.graphics.Color.luminance(ink) < 0.5f
            paint.style = Paint.Style.FILL
            paint.color = if (darkInk) 0xE6FFFFFF.toInt() else 0x2EFFFFFF
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = r * 0.09f
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = ink
            val half = r * 0.42f
            for ((dy, knob) in listOf(-0.2f to -0.15f, 0.2f to 0.18f)) {
                val y = cy + dy * r
                c.drawLine(cx - half, y, cx + half, y, paint)
                paint.style = Paint.Style.FILL
                c.drawCircle(cx + knob * r * 1.6f, y, r * 0.11f, paint)
                paint.style = Paint.Style.STROKE
            }
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(cf: ColorFilter?) { paint.colorFilter = cf }
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
