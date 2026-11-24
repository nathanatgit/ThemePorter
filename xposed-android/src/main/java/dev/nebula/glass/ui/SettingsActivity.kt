package dev.nebula.glass.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nathanhanapps.nebulacolorize.R
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.GlobalStyle
import dev.nebula.glass.LockLayout
import dev.nebula.glass.Looks
import dev.nebula.glass.Shapes
import dev.nebula.glass.Surface
import java.util.concurrent.Executors

/**
 * nebulaColorize settings, kept to what's needed (user decision 2026-11-23), in two tabs:
 *  - **Glass**: a pinned live preview, Clear / Tinted (colour bar + intensity bar), and Blur, Glow, Dim.
 *    The chip beside the title (a glass menu) picks whether that edits every surface or one panel;
 *    a panel follows the shared look until its "Follow all surfaces" switch is off. Tapping a part
 *    of the shared preview goes to its settings. Shapes (folder, dock, control-centre tiles) and the
 *    lock screen's layout and text sit with the scope they belong to.
 *  - **Apply to**: one switch per area (Home and Lock screen expand), plus Restart and Reset. The
 *    clock's size and boldness live in Nubia's editor only ([dev.nebula.glass.hook.LockEditor]).
 * Changes save and apply live (see [SettingsStore]).
 */
class SettingsActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var theme: UiTheme
    private lateinit var root: FrameLayout
    private lateinit var backdrop: GlassBackdrop
    private lateinit var tabs: GlassTabs
    private lateinit var stage: PreviewStage
    private lateinit var scopeChip: TextView
    private lateinit var glassScroll: ScrollView
    private lateinit var shapesCardView: View

    private var overlay: View? = null
    private var wallpaper: Bitmap? = null
    private var wallpaperColours = IntArray(0)

    /** The panel whose look is being edited; null = all surfaces. */
    private var scope: Surface? = null
    private val pages = ArrayList<View>()
    private val panes = ArrayList<GlassPane>()

    /** Re-read the settings into a control, or recolour it. */
    private val bindings = ArrayList<() -> Unit>()

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun look() = store.look(scope)

    override fun onCreate(state: Bundle?) {
        theme = UiTheme(this)
        setTheme(if (theme.night) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(state)
        store = SettingsStore(this)
        scope = state?.getString(STATE_SCOPE)?.let { key -> Surface.PANELS.find { it.key == key } }
        if (state == null && intent?.getStringExtra(EXTRA_SECTION) == SECTION_LOCK) scope = Surface.LOCK_WIDGET

        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this)
        setContentView(root)
        val darkIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (theme.night) 0 else darkIcons, darkIcons)

        build()
        showTab(if (scope == Surface.LOCK_WIDGET && state == null) 0 else state?.getInt(STATE_TAB) ?: 0, animate = false)
        sync()
        loadWallpaper()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(STATE_TAB, tabs.selected)
        scope?.let { out.putString(STATE_SCOPE, it.key) }
    }

    // ---------- layout

    private fun build() {
        backdrop = GlassBackdrop(this, theme) { wallpaper }
        root.addView(backdrop, FrameLayout.LayoutParams(-1, -1))
        val screen = FrameLayout(this)
        root.addView(screen, FrameLayout.LayoutParams(-1, -1))
        screen.setOnApplyWindowInsetsListener { v, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        val above = FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = px(84) }   // clear of the floating bar
        for (page in listOf(glassPage(), applyPage())) {
            screen.addView(page, FrameLayout.LayoutParams(above as android.view.ViewGroup.MarginLayoutParams))
            pages.add(page)
        }

        val bar = pane(32f)
        tabs = GlassTabs(this, theme, listOf(R.drawable.ic_drop to L("Glass"), R.drawable.ic_grid to L("Apply to"))) { showTab(it, animate = true) }
        bindings.add { tabs.recolour() }
        bar.addView(tabs, LinearLayout.LayoutParams(-1, -1))
        screen.addView(bar, FrameLayout.LayoutParams(px(240), px(64), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = px(12) })
    }

    private fun showTab(index: Int, animate: Boolean) {
        tabs.select(index, animate)
        for ((i, page) in pages.withIndex()) page.visibility = if (i == index) View.VISIBLE else View.GONE
    }

    private fun header(title: String, trailing: View? = null) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(22), px(6), px(16), px(6))
        addView(text(title, 26f).apply { typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) },
            LinearLayout.LayoutParams(0, px(44), 1f))
        trailing?.let { addView(it, LinearLayout.LayoutParams(-2, px(40))) }
    }

    private fun glassPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scopeChip = text("", 14f).apply {
            gravity = Gravity.CENTER
            setPadding(px(16), 0, px(16), 0)
            setOnClickListener { chooseScope() }
        }
        page.addView(header(L("Liquid Glass"), scopeChip))

        stage = PreviewStage(this, ::previewStyle, { wallpaper }) { s -> previewPicked(s) }
        stage.show(scope)
        // Pinned: only the controls below scroll, so the preview is in view while dragging.
        page.addView(stage, LinearLayout.LayoutParams(-1, px(236)).apply { setMargins(px(16), 0, px(16), px(12)) })

        val col = column()
        if (!store.hooksCanRead) col.addView(banner(), gapBelow())
        col.addView(followCard(), gapBelow())
        val style = styleCard()
        val amounts = amountsCard()
        col.addView(style, gapBelow())
        col.addView(amounts, gapBelow())
        bindings.add {
            val own = scope?.let { !store.follows(it) } ?: true
            style.visibility = if (own) View.VISIBLE else View.GONE
            amounts.visibility = if (own) View.VISIBLE else View.GONE
        }
        shapesCardView = shapesCard()
        col.addView(shapesCardView, gapBelow())
        col.addView(lockLayoutCard(), gapBelow())
        glassScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        page.addView(glassScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    /** A panel's "Follow all surfaces" switch (user request 2026-11-24). */
    private fun followCard(): View {
        val card = pane(28f).apply { setPadding(0, px(4), 0, px(4)) }
        card.addView(toggle(L("Follow all surfaces"), { scope?.let { store.follows(it) } ?: true }) { on ->
            scope?.let { store.setFollow(it, on) }
            sync()
        })
        val note = text(L("Uses the All surfaces look. Switch off to tune this one on its own."), 13f, theme.inkSoft)
            .apply { setPadding(px(20), 0, px(20), px(10)) }
        card.addView(note)
        bindings.add {
            card.visibility = if (scope != null) View.VISIBLE else View.GONE
            note.visibility = if (scope?.let { store.follows(it) } == true) View.VISIBLE else View.GONE
            note.setTextColor(theme.inkSoft)
        }
        return card
    }

    /** Tapping the shared preview: a panel opens its own settings, the home screen scrolls to its shapes. */
    private fun previewPicked(s: Surface) {
        if (s == Surface.FOLDER) {
            glassScroll.post { glassScroll.smoothScrollTo(0, shapesCardView.top) }
            shapesCardView.animate().alpha(0.55f).setDuration(140).withEndAction {
                shapesCardView.animate().alpha(1f).setDuration(220).start()
            }.start()
            return
        }
        setScope(s.lookKey)
    }

    private fun setScope(s: Surface?) {
        scope = s
        stage.show(scope)
        sync()
        glassScroll.post { glassScroll.smoothScrollTo(0, 0) }
    }

    /**
     * One labelled slider: the label and value on a line, the slider under it.
     * @param read the slider position; [show] turns it into the value's text
     */
    private fun valueRow(into: LinearLayout, label: String, max: Int, detent: Int?, visible: () -> Boolean,
                         read: () -> Int, show: (Int) -> String, write: (Int) -> Unit) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(4), px(6), 0, 0) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val value = text("", 13f, theme.inkSoft).apply { gravity = Gravity.END }
        head.addView(text(label, 15f), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(value, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(10) })
        val slider = GlassSlider(this, theme, max, detent = detent) { v -> write(v); sync() }.apply { contentDescription = label }
        row.addView(head)
        row.addView(slider)
        into.addView(row)
        bindings.add {
            row.visibility = if (visible()) View.VISIBLE else View.GONE
            val v = read()
            slider.progress = v
            slider.invalidate()
            value.text = show(v)
            value.setTextColor(theme.inkSoft)
        }
    }

    /** Corner sliders: far left keeps Nubia's shape, far right is a pill. */
    private fun cornerToSlider(v: Int) = when {
        v < 0 -> 0
        v >= Shapes.PILL -> Shapes.MAX_CORNER + 2
        else -> v + 1
    }

    private fun sliderToCorner(p: Int) = when {
        p <= 0 -> GlassStyle.ORIGINAL
        p >= Shapes.MAX_CORNER + 2 -> Shapes.PILL
        else -> p - 1
    }

    private fun cornerText(p: Int) = when (val v = sliderToCorner(p)) {
        GlassStyle.ORIGINAL -> L("Nubia's")
        Shapes.PILL -> L("Pill")
        else -> "$v dp"
    }

    /** Folder and dock shapes with the shared look; tile corners with the control centre (user request 2026-11-24). */
    private fun shapesCard(): View {
        val card = pane(28f).apply { setPadding(px(16), px(10), px(10), px(10)) }
        val title = text(L("Shapes"), 14f, theme.inkSoft)
        card.addView(title)
        val home = { scope == null }
        val corners = Shapes.MAX_CORNER + 2
        valueRow(card, L("Folder corner"), corners, 0, home, { cornerToSlider(store.shapes.folderCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(folderCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Tile corner"), corners, 0, { scope == Surface.CONTROL_CENTER },
            { cornerToSlider(store.shapes.tileCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(tileCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Dock width"), 100 - Shapes.MIN_DOCK_WIDTH, 100 - Shapes.MIN_DOCK_WIDTH, home,
            { store.shapes.dockWidth - Shapes.MIN_DOCK_WIDTH }, { "${it + Shapes.MIN_DOCK_WIDTH}%" }) {
            store.setShapes(store.shapes.copy(dockWidth = it + Shapes.MIN_DOCK_WIDTH))
        }
        valueRow(card, L("Dock height"), Shapes.MAX_DOCK_PAD, Shapes.DOCK_PAD, home,
            { store.shapes.dockPad }, { "$it dp around icons" }) {
            store.setShapes(store.shapes.copy(dockPad = it))
        }
        valueRow(card, L("Dock corner"), corners, 0, home, { cornerToSlider(store.shapes.dockCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(dockCorner = sliderToCorner(it)))
        }
        bindings.add {
            card.visibility = if (scope == null || scope == Surface.CONTROL_CENTER) View.VISIBLE else View.GONE
            title.setTextColor(theme.inkSoft)
        }
        return card
    }

    private fun previewStyle(s: Surface): GlassStyle {
        // The shared preview shows the shared look, even where a panel has its own.
        val st = store.config.style(s)
        return if (scope == null) st.copy(look = store.config.global) else st
    }

    private fun styleCard(): View {
        val card = pane(28f).apply { setPadding(px(10), px(10), px(10), px(10)) }
        val choices = LinearLayout(this)
        val clear = StyleChoice(this, theme, L("Clear")) { SWATCH_CLEAR }
        val tinted = StyleChoice(this, theme, L("Tinted")) { Palette.alpha(colourOf(look()), 0.8f) }
        clear.setOnClickListener { edit { copy(tinted = false) } }
        tinted.setOnClickListener { edit { copy(tinted = true, color = colourOf(this)) } }
        choices.addView(clear, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(6) })
        choices.addView(tinted, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(choices)

        val colourControls = column().apply { setPadding(px(2), px(8), px(2), 0) }
        card.addView(colourControls)

        val hsv = FloatArray(3)
        var lastHueColour = 0
        val hue = GlassSlider(this, theme, 359, ramp = { HUES }, thumbColour = { colourOf(look()) }) { h ->
            Color.colorToHSV(colourOf(look()), hsv)
            hsv[0] = h.toFloat()
            // From grey, black or white there's no hue to move: give it some colour.
            if (hsv[1] < 0.15f) hsv[1] = 0.6f
            if (hsv[2] < 0.35f) hsv[2] = 0.8f
            val c = Color.HSVToColor(hsv)
            lastHueColour = c
            edit { copy(tinted = true, color = c) }
        }.apply { contentDescription = L("Tint colour") }
        colourControls.addView(hue)

        val intensity = GlassSlider(this, theme, GlobalStyle.MAX_INTENSITY,
            ramp = { intArrayOf(Color.WHITE, colourOf(look())) }, thumbColour = { colourOf(look()) }) { v ->
            edit { copy(intensity = v) }
        }.apply { contentDescription = L("Tint intensity") }
        colourControls.addView(intensity)

        val sources = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(2)) }
        fun addSource(icon: Int, description: String, colour: () -> Int?): SourceDot {
            val dot = SourceDot(this, theme, icon, description) { colour() ?: theme.container }
            dot.setOnClickListener { colour()?.let { c -> edit { copy(tinted = true, color = c) } } }
            sources.addView(dot, LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(10); marginEnd = px(10) })
            bindings.add {
                val c = colour()
                dot.visibility = if (c == null) View.GONE else View.VISIBLE
                dot.isSelected = c == look().color
                dot.invalidate()
            }
            return dot
        }
        addSource(R.drawable.ic_phone, L("System colour")) { theme.systemAccent }
        addSource(R.drawable.ic_image, L("Wallpaper colour")) { wallpaperColours.getOrNull(0) }
        addSource(R.drawable.ic_image, L("Second wallpaper colour")) { wallpaperColours.getOrNull(1) }
        val dropper = SourceDot(this, theme, R.drawable.ic_dropper, L("Pick from the wallpaper or an image")) { colourOf(look()) }
        dropper.setOnClickListener { if (wallpaper != null) openInk() else pickImage() }
        sources.addView(dropper, LinearLayout.LayoutParams(px(48), px(48)).apply { marginStart = px(10); marginEnd = px(10) })
        colourControls.addView(sources)

        bindings.add {
            val g = look()
            clear.isSelected = !g.tinted
            tinted.isSelected = g.tinted
            clear.invalidate(); tinted.invalidate(); dropper.invalidate()
            colourControls.visibility = if (g.tinted) View.VISIBLE else View.GONE
            val c = colourOf(g)
            if (c != lastHueColour) {   // not while dragging it: rounding would make the thumb jitter
                Color.colorToHSV(c, hsv)
                hue.progress = hsv[0].toInt()
                lastHueColour = c
            }
            intensity.progress = g.intensity
            hue.invalidate(); intensity.invalidate()
        }
        return card
    }

    private fun amountsCard(): View {
        val card = pane(28f).apply { setPadding(px(16), px(8), px(10), px(8)) }
        fun amount(label: String, max: Int, applies: (Surface) -> Boolean,
                   read: (GlobalStyle) -> Int, write: GlobalStyle.(Int) -> GlobalStyle) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val value = text("", 13f, theme.inkSoft).apply { gravity = Gravity.END }
            val slider = GlassSlider(this, theme, max, detent = GlobalStyle.DEFAULT) { v -> edit { write(v) } }
                .apply { contentDescription = label }
            row.addView(text(label, 16f), LinearLayout.LayoutParams(px(48), -2))
            row.addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(value, LinearLayout.LayoutParams(px(46), -2))
            card.addView(row, LinearLayout.LayoutParams(-1, px(52)))
            bindings.add {
                row.visibility = if (scope?.let(applies) != false) View.VISIBLE else View.GONE
                val v = read(look())
                slider.progress = v
                slider.invalidate()
                value.text = "$v%"
            }
        }
        amount(L("Blur"), GlobalStyle.MAX_BLUR, { it.hasBlur }, { it.blur }, { copy(blur = it) })
        amount(L("Glow"), GlobalStyle.MAX_GLOW, { true }, { it.glow }, { copy(glow = it) })
        amount(L("Dim"), GlobalStyle.MAX_DIM, { it.hasDim }, { it.dim }, { copy(dim = it) })
        return card
    }

    private fun applyPage(): View {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        page.addView(header(L("Apply to")))
        val col = column()
        val areas = pane(28f).apply { setPadding(0, px(6), 0, px(6)) }
        for (area in AREAS) addArea(areas, area)
        col.addView(areas, gapBelow())

        val actions = LinearLayout(this)
        actions.addView(button(L("Restart apps"), R.drawable.ic_restart) {
            confirm(L("Restart apps?"), L("System UI, launcher, lock screen and widget board restart. Needs root; the screen blanks briefly."),
                L("Restart")) { restartPackages() }
        }, LinearLayout.LayoutParams(0, px(52), 1f).apply { marginEnd = px(8) })
        actions.addView(button(L("Reset look"), R.drawable.ic_reset) {
            confirm(L("Reset the glass look?"), L("Clear style with Blur, Glow and Dim at 100 %, for every surface, all following it. Switches and shapes stay as they are."),
                L("Reset")) { store.resetGlobal(); setScope(null) }
        }, LinearLayout.LayoutParams(0, px(52), 1f))
        col.addView(actions, gapBelow())

        val access = text(L("Use my real wallpaper in previews and the eyedropper"), 14f).apply {
            gravity = Gravity.CENTER
            minimumHeight = px(48)
            setOnClickListener {
                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            }
        }
        bindings.add {
            access.setTextColor(theme.primary)
            access.visibility = if (wallpaper == null) View.VISIBLE else View.GONE
        }
        col.addView(access)
        page.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    /** An area's switch. An area of several surfaces expands to theirs. */
    private fun addArea(into: LinearLayout, area: Area) {
        val detail = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val row = switchRow(area.title, area.surfaces, indent = 20, textSize = 17f, height = 64)
        into.addView(row)
        if (area.surfaces.size < 2) return
        val arrow = ImageView(this).apply { setImageResource(R.drawable.ic_expand); setColorFilter(theme.inkSoft) }
        row.addView(arrow, 1, LinearLayout.LayoutParams(px(24), px(24)).apply { marginEnd = px(12) })
        row.contentDescription = "${area.title}, show details"
        row.setOnClickListener {
            val open = detail.visibility != View.VISIBLE
            detail.visibility = if (open) View.VISIBLE else View.GONE
            arrow.animate().rotation(if (open) 180f else 0f).setDuration(180).start()
        }
        for (s in area.surfaces) detail.addView(switchRow(s.title, listOf(s), indent = 36, textSize = 15f, height = 52))
        into.addView(detail)
    }

    private fun switchRow(title: String, surfaces: List<Surface>, indent: Int, textSize: Float, height: Int) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(indent), 0, px(16), 0)
        layoutParams = LinearLayout.LayoutParams(-1, px(height))
        addView(text(title, textSize), LinearLayout.LayoutParams(0, -2, 1f))
        val switch = GlassSwitch(this@SettingsActivity, theme).apply { contentDescription = title }
        addView(switch, LinearLayout.LayoutParams(-2, -2))
        var syncing = false
        switch.setOnCheckedChangeListener { _, on ->
            switch.invalidate()
            if (!syncing) { store.setOn(surfaces, on); sync() }
        }
        setOnClickListener { switch.toggle() }
        bindings.add {
            syncing = true
            switch.isChecked = surfaces.any { store.isOn(it) }
            syncing = false
            switch.invalidate()
        }
    }

    private fun lockLayoutCard(): View {
        val card = pane(28f).apply { setPadding(0, px(6), 0, px(6)) }
        val title = text(L("Lock screen layout"), 14f, theme.inkSoft).apply { setPadding(px(20), px(4), 0, px(2)) }
        card.addView(title)
        bindings.add {
            card.visibility = if (scope == Surface.LOCK_WIDGET) View.VISIBLE else View.GONE
            title.setTextColor(theme.inkSoft)
        }
        card.addView(toggle(L("Widgets and shortcuts at bottom"), { store.lock.bottomWidgets }) {
            store.setLock(store.lock.copy(bottomWidgets = it))
        })
        card.addView(toggle(L("Notifications at bottom"), { store.lock.bottomNotifications }) {
            store.setLock(store.lock.copy(bottomNotifications = it))
        })
        val editor = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(20), 0, px(22), 0)
            layoutParams = LinearLayout.LayoutParams(-1, px(60))
            setOnClickListener { openLockEditor() }
        }
        val label = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        label.addView(text(L("Clock, date and custom text"), 16f))
        label.addView(text(L("Box size and boldness, in the lock screen editor"), 12f, theme.inkSoft))
        editor.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        editor.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_expand)
            setColorFilter(theme.inkSoft)
            rotation = -90f
        }, LinearLayout.LayoutParams(px(24), px(24)))
        card.addView(editor)
        val fontNote = text(L("Clock font: pick it in Nubia's editor (时钟字体). The first font is Roboto Flex, whose width and height axes fill the box. Rubik and Oswald use their variable versions. Fonts without width or height axes keep their proportions."),
            12f, theme.inkSoft).apply { setPadding(px(20), px(4), px(20), px(10)) }
        card.addView(fontNote)
        bindings.add { fontNote.setTextColor(theme.inkSoft) }

        return card
    }


    private fun toggle(title: String, read: () -> Boolean, write: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20), 0, px(16), 0)
        layoutParams = LinearLayout.LayoutParams(-1, px(60))
        addView(text(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        val switch = GlassSwitch(this@SettingsActivity, theme).apply { contentDescription = title }
        addView(switch, LinearLayout.LayoutParams(-2, -2))
        var syncing = false
        switch.setOnCheckedChangeListener { _, on ->
            switch.invalidate()
            if (!syncing) write(on)
        }
        setOnClickListener { switch.toggle() }
        bindings.add {
            syncing = true
            switch.isChecked = read()
            syncing = false
            switch.invalidate()
        }
    }

    private fun openLockEditor() {
        try {
            startActivity(Intent().setClassName(SYSTEM_UI, LOCK_SCREENS))
        } catch (_: Exception) {
            Toast.makeText(this, L("Long-press the lock screen to edit it"), Toast.LENGTH_SHORT).show()
        }
    }

    // ---------- behaviour

    private fun edit(change: GlobalStyle.() -> GlobalStyle) {
        store.setLook(scope, look().change())
        sync()
    }

    /** The colour Tinted uses, or would start from. */
    private fun colourOf(g: GlobalStyle) = when {
        g.color != GlassStyle.AUTO -> g.color
        else -> wallpaperColours.firstOrNull() ?: theme.systemAccent
    }

    /** The scope menu: a glass sheet under the chip. */
    private fun chooseScope() {
        if (overlay != null) { closeOverlay(); return }
        val catcher = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { closeOverlay() }
        }
        val menu = GlassPane(this, theme, backdrop, 24f).apply { setPadding(px(6), px(6), px(6), px(6)) }
        for (s in listOf<Surface?>(null) + Surface.PANELS) {
            val own = s != null && !store.follows(s)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(16), 0)
                if (s == scope) background = round(theme.container, 18)
                contentDescription = scopeName(s)
                setOnClickListener { closeOverlay(); setScope(s) }
            }
            val ink = if (s == scope) theme.onContainer else theme.ink
            row.addView(text(scopeName(s), 16f, ink))
            if (s != null) row.addView(text(if (own) L("Own look") else L("Follows all surfaces"), 12f, if (s == scope) ink else theme.inkSoft))
            menu.addView(row, LinearLayout.LayoutParams(-1, px(if (s == null) 48 else 56)))
        }
        val at = IntArray(2)
        val rootAt = IntArray(2)
        scopeChip.getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        val width = px(232)
        val left = (at[0] - rootAt[0] + scopeChip.width - width).coerceAtLeast(px(12))
        catcher.addView(menu, FrameLayout.LayoutParams(width, -2).apply {
            leftMargin = left
            topMargin = at[1] - rootAt[1] + scopeChip.height + px(6)
        })
        menu.alpha = 0f
        menu.scaleX = 0.92f; menu.scaleY = 0.92f
        menu.pivotX = width.toFloat(); menu.pivotY = 0f
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        overlay = catcher
        root.addView(catcher, FrameLayout.LayoutParams(-1, -1))
    }

    private fun scopeName(s: Surface?) = when (s) {
        Surface.NOTIFICATION -> L("Notifications")
        Surface.CONTROL_CENTER -> L("Control centre")
        Surface.WIDGET_BOARD -> L("Widget board")
        Surface.LOCK_WIDGET -> L("Lock screen")
        else -> L("All surfaces")
    }

    /** Re-read every control from the settings and recolour the app from the look being edited. */
    private fun sync() {
        val g = look()
        val seed = if (g.tinted) colourOf(g) else theme.systemAccent
        if (seed != theme.seed) theme.seedFrom(seed)
        bindings.forEach { it() }
        scopeChip.text = "${scopeName(scope)}  ▾"
        scopeChip.setTextColor(theme.onContainer)
        scopeChip.background = round(theme.container, 20)
        backdrop.invalidate()
        panes.forEach { it.recolour() }
        stage.refresh()
    }

    private fun confirm(title: String, message: String, action: String, run: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> run() }
            .show()
    }

    /** Kills the scoped packages as root; the system respawns them with the hooks reloaded. */
    private fun restartPackages() {
        io.execute {
            // System UI last: it takes this app's toast with it on some builds.
            val script = OTHER_PACKAGES.joinToString("; ") { "am force-stop $it" } + "; kill \$(pidof $SYSTEM_UI)"
            val ok = try {
                val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                p.inputStream.readBytes(); p.waitFor() == 0
            } catch (_: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) L("Restarted") else L("Restart failed: root (su) unavailable"), Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ---------- wallpaper and eyedropper

    private fun loadWallpaper() {
        wallpaperColours = LockWallpaper.systemColors(this)
        sync()
        io.execute {
            val b = LockWallpaper.load(this, maxOf(resources.displayMetrics.heightPixels, 1600))
            runOnUiThread { if (b != null) useWallpaper(b) }
        }
    }

    private fun useWallpaper(b: Bitmap) {
        wallpaper = b
        wallpaperColours = (wallpaperColours.toList() + LockWallpaper.dominant(b).toList()).distinct().toIntArray()
        sync()
    }

    override fun onResume() {
        super.onResume()
        if (::store.isInitialized) {
            store.reload()   // the lock-screen editor saves there too
            sync()
        }
        // Coming back from the all-files access page: try the real wallpaper again.
        if (wallpaper == null && ::store.isInitialized) loadWallpaper()
    }

    /** No permission to read the wallpaper: the system picker needs none. */
    private fun pickImage() {
        startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (req != REQ_IMAGE || res != RESULT_OK) return
        io.execute {
            val b = LockWallpaper.load(this, uri, maxOf(resources.displayMetrics.heightPixels, 1600))
            runOnUiThread { if (b != null) { useWallpaper(b); openInk() } }
        }
    }

    private fun openInk() {
        val b = wallpaper ?: return
        val picker = InkPicker(this, b, colourOf(look())) { rgb ->
            closeOverlay()
            if (rgb != null) edit { copy(tinted = true, color = Palette.rgb(rgb)) }
        }
        overlay = picker
        root.addView(picker, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeOverlay() {
        overlay?.let { root.removeView(it) }
        overlay = null
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlay != null -> closeOverlay()
            tabs.selected != 0 -> showTab(0, animate = true)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    // ---------- small builders

    private fun pane(radiusDp: Float) = GlassPane(this, theme, backdrop, radiusDp).also { panes.add(it) }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(px(16), 0, px(16), 0) }

    private fun gapBelow() = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = px(10) }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * dp }

    private fun text(t: String, size: Float, color: Int = theme.ink) = TextView(this).apply {
        text = t
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun banner() = text(L("Enable nebulaColorize in Vector, then reopen this app."), 14f, 0xFF6B4500.toInt()).apply {
        setPadding(px(16), px(10), px(16), px(10))
        background = round(0xFFFFE8B8.toInt(), 20)
    }

    private fun button(label: String, icon: Int, action: () -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER
        contentDescription = label
        setOnClickListener { action() }
        val image = ImageView(context).apply { setImageResource(icon) }
        val title = text(label, 15f)
        addView(image, LinearLayout.LayoutParams(px(20), px(20)).apply { marginEnd = px(8) })
        addView(title)
        bindings.add {
            background = round(theme.container, 26)
            image.setColorFilter(theme.onContainer)
            title.setTextColor(theme.onContainer)
        }
    }

    private class Area(val title: String, val surfaces: List<Surface>)

    private companion object {
        const val REQ_IMAGE = 7
        const val STATE_TAB = "tab"
        const val STATE_SCOPE = "scope"
        /** From the lock screen's long-press menu (hook/LockLongPress): open on the lock-screen settings. */
        const val EXTRA_SECTION = "section"
        const val SECTION_LOCK = "lock"
        const val SYSTEM_UI = "com.android.systemui"
        const val LOCK_SCREENS = "com.zte.mifavor.keyguard.personalclock.MyLockScreenSettingsActivity"
        const val SWATCH_CLEAR = 0x28FFFFFF
        val OTHER_PACKAGES = listOf("com.zte.mifavor.launcher", "com.zte.mfvkeyguard", "com.zte.mifavor.miboard")
        val HUES = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.72f, 0.96f)) }

        // The dock stays on its own: it is opt-in, so the Home screen switch mustn't turn it on.
        val AREAS = listOf(
            Area(L("Home screen"), listOf(Surface.FOLDER, Surface.POPUP, Surface.HOME_CLOCK, Surface.HOME_WIDGET)),
            Area(L("Dock"), listOf(Surface.DOCK)),
            Area(L("Widget board"), listOf(Surface.WIDGET_BOARD)),
            Area(L("Lock screen"), listOf(Surface.LOCK_CLOCK, Surface.LOCK_WIDGET, Surface.LOCK_SHORTCUT, Surface.LOCK_CARD)),
            Area(L("Notifications"), listOf(Surface.NOTIFICATION)),
            Area(L("Control centre"), listOf(Surface.CONTROL_CENTER)),
        )
        val io = Executors.newSingleThreadExecutor()
    }
}
