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
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.nathanhanapps.nebulacolorize.R
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.GlobalStyle
import dev.nebula.glass.LightSourceController
import dev.nebula.glass.Shapes
import dev.nebula.glass.Surface
import java.util.concurrent.Executors

/**
 * nebulaColorize settings, in two tabs:
 *  - **Glass**: live 6-surface preview grid (Control centre, Notifications, Lock screen, Folders,
 *    Home widgets, Dock panel), Light / Dark preset selector, separated per-surface look scopes,
 *    precise 3-channel colour controls (Accent, Edge, Edge glow + Hex/HSV/Eyedropper), Blur / Glow /
 *    Glow angle / Dim, Optics (Refraction, Dispersion, Vibrancy, Frost), and per-surface Shapes.
 *  - **Apply to**: one switch per area (Home and Lock screen expand), plus Restart and Reset.
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

    /** Which system UI theme preset is currently being edited (false = Light, true = Dark). */
    private var editingDark = false

    /** Which colour role is currently being edited in [styleCard]: 0 = Accent, 1 = Edge, 2 = Edge glow. */
    private var colorChannel = CH_ACCENT
    private var pendingDropperChannel = CH_ACCENT

    private val pages = ArrayList<View>()
    private val panes = ArrayList<GlassPane>()
    private val bindings = ArrayList<() -> Unit>()

    private val dp get() = resources.displayMetrics.density
    private fun px(v: Int) = (v * dp).toInt()
    private fun look() = store.look(scope, editingDark)

    override fun onCreate(state: Bundle?) {
        theme = UiTheme(this)
        setTheme(if (theme.night) android.R.style.Theme_DeviceDefault_NoActionBar else android.R.style.Theme_DeviceDefault_Light_NoActionBar)
        super.onCreate(state)
        store = SettingsStore(this)
        editingDark = if (state != null && state.containsKey(STATE_DARK)) state.getBoolean(STATE_DARK) else theme.night
        scope = state?.getString(STATE_SCOPE)?.let { key -> Surface.PANELS.find { it.key == key } }
        if (state == null && intent?.getStringExtra(EXTRA_SECTION) == SECTION_LOCK) scope = Surface.LOCK_WIDGET

        @Suppress("DEPRECATION")
        window.statusBarColor = Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window.navigationBarColor = Color.TRANSPARENT
        window.isNavigationBarContrastEnforced = false
        @Suppress("DEPRECATION")
        window.setDecorFitsSystemWindows(false)
        root = FrameLayout(this)
        setContentView(root)
        val darkIcons = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        window.insetsController?.setSystemBarsAppearance(if (theme.night) 0 else darkIcons, darkIcons)

        build()
        LightSourceController.addRedraw(tiltRedraw)
        LightSourceController.start(this) { resumed && store.config.anySensor() }
        showTab(if (scope == Surface.LOCK_WIDGET && state == null) 0 else state?.getInt(STATE_TAB) ?: 0, animate = false)
        sync()
        loadWallpaper()
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putInt(STATE_TAB, tabs.selected)
        out.putBoolean(STATE_DARK, editingDark)
        scope?.let { out.putString(STATE_SCOPE, it.key) }
    }

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

        val above = FrameLayout.LayoutParams(-1, -1).apply { bottomMargin = px(84) }
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

        stage = PreviewStage(this, ::previewStyle, { store.shapes }, { wallpaper }, { editingDark }) { s -> previewPicked(s) }
        stage.show(scope)
        val stageLp = LinearLayout.LayoutParams(-1, px(if (scope == null) 276 else 212)).apply {
            setMargins(px(16), 0, px(16), px(10))
        }
        page.addView(stage, stageLp)
        bindings.add {
            val wantH = px(if (scope == null) 276 else 212)
            if (stage.layoutParams.height != wantH) {
                stage.layoutParams.height = wantH
                stage.requestLayout()
            }
        }

        val col = column()
        if (!store.hooksCanRead) col.addView(banner(), gapBelow())
        // What belongs to this scope first (sizes, corners, layout), then its look.
        shapesCardView = shapesCard()
        col.addView(shapesCardView, gapBelow())
        col.addView(lockLayoutCard(), gapBelow())
        col.addView(followCard(), gapBelow())
        col.addView(presetCard(), gapBelow())
        val style = styleCard()
        val amounts = amountsCard()
        val optics = opticsCard()
        col.addView(style, gapBelow())
        col.addView(amounts, gapBelow())
        col.addView(optics, gapBelow())
        bindings.add {
            val own = scope?.let { !store.follows(it, editingDark) } ?: true
            style.visibility = if (own) View.VISIBLE else View.GONE
            amounts.visibility = if (own) View.VISIBLE else View.GONE
            optics.visibility = if (own) View.VISIBLE else View.GONE
        }
        glassScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(col) }
        page.addView(glassScroll, LinearLayout.LayoutParams(-1, 0, 1f))
        return page
    }

    /** Light / Dark system UI theme preset switcher + one-tap copy between presets. */
    private fun presetCard(): View {
        val card = pane(28f).apply { setPadding(px(14), px(10), px(14), px(10)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(px(6), 0, px(4), px(6)) }
        val title = text(L("Theme preset"), 14f, theme.inkSoft)
        val copyBtn = text("", 12f).apply {
            gravity = Gravity.CENTER
            setPadding(px(12), px(4), px(12), px(4))
            setOnClickListener {
                store.copyPreset(fromNight = !editingDark, toNight = editingDark)
                sync()
            }
        }
        head.addView(title, LinearLayout.LayoutParams(-2, -2))
        head.addView(help(L("Theme preset"), L("Switches automatically with System UI Light / Dark mode.")))
        head.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        head.addView(copyBtn, LinearLayout.LayoutParams(-2, -2))
        card.addView(head)

        val row = LinearLayout(this)
        fun modePill(label: String, isDark: Boolean) = text(label, 15f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setOnClickListener {
                if (editingDark != isDark) {
                    editingDark = isDark
                    sync()
                }
            }
        }
        val lightBtn = modePill("☀  ${L("Light theme")}", false)
        val darkBtn = modePill("☾  ${L("Dark theme")}", true)
        row.addView(lightBtn, LinearLayout.LayoutParams(0, px(44), 1f).apply { marginEnd = px(6) })
        row.addView(darkBtn, LinearLayout.LayoutParams(0, px(44), 1f))
        card.addView(row)

        bindings.add {
            title.setTextColor(theme.inkSoft)
            copyBtn.text = if (editingDark) L("Copy from Light") else L("Copy from Dark")
            copyBtn.setTextColor(theme.primary)
            copyBtn.background = round(theme.track, 14)

            lightBtn.background = round(if (!editingDark) theme.container else theme.track, 18)
            lightBtn.setTextColor(if (!editingDark) theme.onContainer else theme.inkSoft)
            darkBtn.background = round(if (editingDark) theme.container else theme.track, 18)
            darkBtn.setTextColor(if (editingDark) theme.onContainer else theme.inkSoft)
        }
        return card
    }

    /** A panel's "Follow all surfaces" switch. */
    private fun followCard(): View {
        val card = pane(28f).apply { setPadding(0, px(4), 0, px(4)) }
        card.addView(toggle(L("Follow all surfaces"), { scope?.let { store.follows(it, editingDark) } ?: true },
            helpText = L("Uses the All surfaces look. Switch off to tune this one on its own.")) { on ->
            scope?.let { store.setFollow(it, editingDark, on) }
            sync()
        })
        bindings.add { card.visibility = if (scope != null) View.VISIBLE else View.GONE }
        return card
    }

    private fun previewPicked(s: Surface) {
        setScope(s.lookKey)
    }

    private fun setScope(s: Surface?) {
        scope = s
        stage.show(scope)
        sync()
        glassScroll.post { glassScroll.smoothScrollTo(0, 0) }
    }

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

    private fun trimToSlider(v: Int) = if (v < 0) 0 else v + 1

    private fun sliderToTrim(p: Int) = if (p <= 0) GlassStyle.ORIGINAL else p - 1

    private fun trimText(p: Int) = when (val v = sliderToTrim(p)) {
        GlassStyle.ORIGINAL -> L("Nubia's")
        else -> "−$v dp"
    }

    private fun cornerText(p: Int) = when (val v = sliderToCorner(p)) {
        GlassStyle.ORIGINAL -> L("Nubia's")
        Shapes.PILL -> L("Pill")
        else -> "$v dp"
    }

    /**
     * Surface-specific shape controls:
     *  - Folder corner -> Folder scope (`Surface.FOLDER`)
     *  - Dock width / height / corner -> Dock scope (`Surface.DOCK`)
     *  - Tile corner -> Control centre scope (`Surface.CONTROL_CENTER`)
     *  - Card corner, Heads-up corner -> Notifications scope (cards and media card in the shade; over an app)
     *  - Notification corner -> Lock screen scope (`Surface.LOCK_WIDGET` page; applies to `Surface.LOCK_CARD`: cards and the lock media card)
     */
    private fun shapesCard(): View {
        val card = pane(28f).apply { setPadding(px(16), px(10), px(10), px(10)) }
        val head = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = text(L("Shapes"), 14f, theme.inkSoft)
        head.addView(title)
        val sizeHelp = help(L("Size"), L("Width and height are the cell span less this many dp, centred above the name. The same values for widgets and folders line them up. Long-press a widget and choose Widget settings for its title, zoom, margin and glass."))
        head.addView(sizeHelp)
        card.addView(head)
        val corners = Shapes.MAX_CORNER + 2
        val trims = Shapes.MAX_TRIM + 1
        valueRow(card, L("Widget width"), trims, 0, { scope == Surface.HOME_WIDGET },
            { trimToSlider(store.shapes.widgetWidth) }, ::trimText) {
            store.setShapes(store.shapes.copy(widgetWidth = sliderToTrim(it)))
        }
        valueRow(card, L("Widget height"), trims, 0, { scope == Surface.HOME_WIDGET },
            { trimToSlider(store.shapes.widgetHeight) }, ::trimText) {
            store.setShapes(store.shapes.copy(widgetHeight = sliderToTrim(it)))
        }
        valueRow(card, L("Widget corner"), corners, 0, { scope == Surface.HOME_WIDGET },
            { cornerToSlider(store.shapes.widgetCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(widgetCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Folder width"), trims, 0, { scope == Surface.FOLDER },
            { trimToSlider(store.shapes.folderWidth) }, ::trimText) {
            store.setShapes(store.shapes.copy(folderWidth = sliderToTrim(it)))
        }
        valueRow(card, L("Folder height"), trims, 0, { scope == Surface.FOLDER },
            { trimToSlider(store.shapes.folderHeight) }, ::trimText) {
            store.setShapes(store.shapes.copy(folderHeight = sliderToTrim(it)))
        }
        valueRow(card, L("Folder corner"), corners, 0, { scope == Surface.FOLDER },
            { cornerToSlider(store.shapes.folderCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(folderCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Tile corner"), corners, 0, { scope == Surface.CONTROL_CENTER },
            { cornerToSlider(store.shapes.tileCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(tileCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Card corner"), corners, 0, { scope == Surface.NOTIFICATION },
            { cornerToSlider(store.shapes.cardCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(cardCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Heads-up corner"), corners, 0, { scope == Surface.NOTIFICATION },
            { cornerToSlider(store.shapes.headsUpCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(headsUpCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Notification corner"), corners, 0, { scope == Surface.LOCK_WIDGET },
            { cornerToSlider(store.shapes.lockCardCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(lockCardCorner = sliderToCorner(it)))
        }
        valueRow(card, L("Dock width"), 100 - Shapes.MIN_DOCK_WIDTH, 100 - Shapes.MIN_DOCK_WIDTH,
            { scope == Surface.DOCK },
            { store.shapes.dockWidth - Shapes.MIN_DOCK_WIDTH }, { "${it + Shapes.MIN_DOCK_WIDTH}%" }) {
            store.setShapes(store.shapes.copy(dockWidth = it + Shapes.MIN_DOCK_WIDTH))
        }
        valueRow(card, L("Dock height"), Shapes.MAX_DOCK_PAD, Shapes.DOCK_PAD,
            { scope == Surface.DOCK },
            { store.shapes.dockPad }, { "$it dp" }) {
            store.setShapes(store.shapes.copy(dockPad = it))
        }
        valueRow(card, L("Dock corner"), corners, 0, { scope == Surface.DOCK },
            { cornerToSlider(store.shapes.dockCorner) }, ::cornerText) {
            store.setShapes(store.shapes.copy(dockCorner = sliderToCorner(it)))
        }
        bindings.add {
            val show = scope == Surface.FOLDER || scope == Surface.CONTROL_CENTER || scope == Surface.DOCK ||
                scope == Surface.NOTIFICATION || scope == Surface.LOCK_WIDGET || scope == Surface.HOME_WIDGET
            card.visibility = if (show) View.VISIBLE else View.GONE
            sizeHelp.visibility = if (scope == Surface.HOME_WIDGET || scope == Surface.FOLDER) View.VISIBLE else View.GONE
            title.setTextColor(theme.inkSoft)
        }
        return card
    }

    private fun previewStyle(s: Surface): GlassStyle = store.config.style(s, editingDark)

    private fun channelColor(g: GlobalStyle, ch: Int = colorChannel): Int = when (ch) {
        CH_EDGE -> if (g.edgeColor != GlassStyle.AUTO) Palette.rgb(g.edgeColor) else Color.WHITE
        CH_GLOW -> if (g.glowColor != GlassStyle.AUTO) Palette.rgb(g.glowColor) else Color.WHITE
        else -> colourOf(g)
    }

    private fun applyChannelColor(ch: Int, rgb: Int) {
        val c = Palette.rgb(rgb)
        edit {
            when (ch) {
                CH_EDGE -> copy(edgeColor = c)
                CH_GLOW -> copy(glowColor = c)
                else -> copy(tinted = true, color = c)
            }
        }
    }

    /**
     * Style & Precise 3-Channel Colour Controls (Accent, Edge, Edge glow), shared by Main UI
     * (`All surfaces`) and every separated Secondary surface UI.
     */
    private fun styleCard(): View {
        val card = pane(28f).apply { setPadding(px(12), px(10), px(12), px(10)) }
        val choices = LinearLayout(this)
        val clear = StyleChoice(this, theme, L("Clear")) { SWATCH_CLEAR }
        val tinted = StyleChoice(this, theme, L("Tinted")) { Palette.alpha(colourOf(look()), 0.8f) }
        clear.setOnClickListener { edit { copy(tinted = false) } }
        tinted.setOnClickListener {
            colorChannel = CH_ACCENT
            edit { copy(tinted = true, color = colourOf(this)) }
        }
        choices.addView(clear, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = px(6) })
        choices.addView(tinted, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(choices)

        // 3-channel colour selector: Accent | Edge | Edge glow
        val channelHead = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(4), px(10), px(4), px(4))
        }
        val channelTitle = text(L("Colour channels"), 13f, theme.inkSoft)
        val hexChip = text("", 12.5f).apply {
            gravity = Gravity.CENTER
            typeface = Typeface.MONOSPACE
            setPadding(px(10), px(3), px(10), px(3))
            setOnClickListener { promptHexColor() }
        }
        val whiteReset = text(L("White"), 12f).apply {
            gravity = Gravity.CENTER
            setPadding(px(10), px(3), px(10), px(3))
            setOnClickListener {
                edit {
                    when (colorChannel) {
                        CH_EDGE -> copy(edgeColor = GlassStyle.AUTO)
                        CH_GLOW -> copy(glowColor = GlassStyle.AUTO)
                        else -> copy(tinted = false)
                    }
                }
            }
        }
        channelHead.addView(channelTitle, LinearLayout.LayoutParams(0, -2, 1f))
        channelHead.addView(whiteReset, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = px(6) })
        channelHead.addView(hexChip, LinearLayout.LayoutParams(-2, -2))
        card.addView(channelHead)

        val channelTabs = LinearLayout(this).apply { setPadding(0, px(2), 0, px(4)) }
        val chLabels = arrayOf(L("Accent"), L("Edge"), L("Edge glow"))
        val chViews = ArrayList<LinearLayout>()
        val chDots = ArrayList<View>()
        val chTexts = ArrayList<TextView>()
        for (ch in 0..2) {
            val pill = LinearLayout(this).apply {
                gravity = Gravity.CENTER
                setPadding(px(8), 0, px(10), 0)
                setOnClickListener {
                    colorChannel = ch
                    if (ch == CH_ACCENT && !look().tinted) {
                        edit { copy(tinted = true, color = colourOf(this)) }
                    } else {
                        sync()
                    }
                }
            }
            val dot = View(this)
            val lbl = text(chLabels[ch], 13.5f).apply {
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            }
            pill.addView(dot, LinearLayout.LayoutParams(px(12), px(12)).apply { marginEnd = px(6) })
            pill.addView(lbl)
            channelTabs.addView(pill, LinearLayout.LayoutParams(0, px(38), 1f).apply { if (ch < 2) marginEnd = px(6) })
            chViews.add(pill); chDots.add(dot); chTexts.add(lbl)
        }
        card.addView(channelTabs)

        val colourControls = column().apply { setPadding(px(2), px(4), px(2), 0) }
        card.addView(colourControls)

        val hsv = FloatArray(3)
        var rememberedHue = 205f

        // 1. Hue slider (0..359°)
        val hueLabel = text(L("Hue"), 13f, theme.inkSoft).apply { setPadding(px(6), px(2), 0, 0) }
        val hue = GlassSlider(this, theme, 359, ramp = { HUES }, thumbColour = { channelColor(look()) }) { h ->
            Color.colorToHSV(channelColor(look()), hsv)
            hsv[0] = h.toFloat()
            rememberedHue = hsv[0]
            if (hsv[1] < 0.08f) hsv[1] = 0.65f
            if (hsv[2] < 0.20f) hsv[2] = 0.90f
            applyChannelColor(colorChannel, Color.HSVToColor(hsv))
        }.apply { contentDescription = L("Hue") }
        colourControls.addView(hueLabel)
        colourControls.addView(hue)

        // 2. Saturation slider (0..100%: 0% = pure white, 100% = vivid hue)
        val satLabel = text(L("Saturation"), 13f, theme.inkSoft).apply { setPadding(px(6), px(2), 0, 0) }
        val sat = GlassSlider(this, theme, 100,
            ramp = {
                Color.colorToHSV(channelColor(look()), hsv)
                val hVal = if (hsv[1] > 0.02f) hsv[0] else rememberedHue
                intArrayOf(Color.WHITE, Color.HSVToColor(floatArrayOf(hVal, 1f, hsv[2].coerceAtLeast(0.5f))))
            },
            thumbColour = { channelColor(look()) }) { s ->
            Color.colorToHSV(channelColor(look()), hsv)
            if (hsv[1] > 0.02f) rememberedHue = hsv[0]
            val next = Color.HSVToColor(floatArrayOf(rememberedHue, s / 100f, hsv[2].coerceAtLeast(0.25f)))
            applyChannelColor(colorChannel, next)
        }.apply { contentDescription = L("Saturation") }
        colourControls.addView(satLabel)
        colourControls.addView(sat)

        // 3. Third slider: Tint intensity (for Accent) OR Brightness (for Edge / Edge glow)
        val thirdLabel = text("", 13f, theme.inkSoft).apply { setPadding(px(6), px(2), 0, 0) }
        val third = GlassSlider(this, theme, 100,
            ramp = {
                if (colorChannel == CH_ACCENT) intArrayOf(Color.WHITE, colourOf(look()))
                else {
                    Color.colorToHSV(channelColor(look()), hsv)
                    val hVal = if (hsv[1] > 0.02f) hsv[0] else rememberedHue
                    intArrayOf(0xFF202024.toInt(), Color.HSVToColor(floatArrayOf(hVal, hsv[1], 1f)))
                }
            },
            thumbColour = { channelColor(look()) }) { v ->
            if (colorChannel == CH_ACCENT) {
                edit { copy(intensity = v) }
            } else {
                Color.colorToHSV(channelColor(look()), hsv)
                if (hsv[1] > 0.02f) rememberedHue = hsv[0]
                val vNorm = (0.20f + 0.80f * (v / 100f)).coerceIn(0.20f, 1f)
                applyChannelColor(colorChannel, Color.HSVToColor(floatArrayOf(rememberedHue, hsv[1], vNorm)))
            }
        }
        colourControls.addView(thirdLabel)
        colourControls.addView(third)

        // Quick colour sources (System, Wallpaper 1 & 2, Eyedropper)
        val sources = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, px(6), 0, px(2)) }
        fun addSource(icon: Int, description: String, colour: () -> Int?): SourceDot {
            val dot = SourceDot(this, theme, icon, description) { colour() ?: theme.container }
            dot.setOnClickListener { colour()?.let { c -> applyChannelColor(colorChannel, c) } }
            sources.addView(dot, LinearLayout.LayoutParams(px(46), px(46)).apply { marginStart = px(8); marginEnd = px(8) })
            bindings.add {
                val c = colour()
                dot.visibility = if (c == null) View.GONE else View.VISIBLE
                dot.isSelected = c != null && Palette.rgb(c) == channelColor(look())
                dot.invalidate()
            }
            return dot
        }
        addSource(R.drawable.ic_phone, L("System colour")) { theme.systemAccent }
        addSource(R.drawable.ic_image, L("Wallpaper colour")) { wallpaperColours.getOrNull(0) }
        addSource(R.drawable.ic_image, L("Second wallpaper colour")) { wallpaperColours.getOrNull(1) }
        val dropper = SourceDot(this, theme, R.drawable.ic_dropper, L("Pick from the wallpaper or an image")) { channelColor(look()) }
        dropper.setOnClickListener {
            pendingDropperChannel = colorChannel
            if (wallpaper != null) openInk(colorChannel) else pickImage()
        }
        sources.addView(dropper, LinearLayout.LayoutParams(px(46), px(46)).apply { marginStart = px(8); marginEnd = px(8) })
        colourControls.addView(sources)

        bindings.add {
            val g = look()
            clear.isSelected = !g.tinted
            tinted.isSelected = g.tinted
            clear.invalidate(); tinted.invalidate(); dropper.invalidate()
            channelTitle.setTextColor(theme.inkSoft)

            for (ch in 0..2) {
                val active = colorChannel == ch
                chViews[ch].background = round(if (active) theme.container else theme.track, 14)
                chTexts[ch].setTextColor(if (active) theme.onContainer else theme.inkSoft)
                val sw = if (ch == CH_ACCENT && !g.tinted) 0x80FFFFFF.toInt() else channelColor(g, ch)
                chDots[ch].background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(sw)
                    setStroke((1 * dp).toInt().coerceAtLeast(1), 0x55000000)
                }
            }

            val showSliders = colorChannel != CH_ACCENT || g.tinted
            colourControls.visibility = if (showSliders) View.VISIBLE else View.GONE
            val cur = channelColor(g)
            hexChip.text = Palette.hex(cur)
            hexChip.setTextColor(theme.onContainer)
            hexChip.background = round(theme.container, 12)
            whiteReset.visibility = if (colorChannel != CH_ACCENT) View.VISIBLE else View.GONE
            whiteReset.setTextColor(theme.primary)
            whiteReset.background = round(theme.track, 12)

            Color.colorToHSV(cur, hsv)
            if (hsv[1] > 0.02f) rememberedHue = hsv[0]
            hue.progress = rememberedHue.toInt().coerceIn(0, 359)
            sat.progress = Math.round(hsv[1] * 100f).coerceIn(0, 100)
            hueLabel.setTextColor(theme.inkSoft)
            satLabel.setTextColor(theme.inkSoft)
            thirdLabel.setTextColor(theme.inkSoft)
            if (colorChannel == CH_ACCENT) {
                thirdLabel.text = "${L("Tint intensity")}  ${g.intensity}%"
                third.progress = g.intensity
            } else {
                val br = Math.round(((hsv[2] - 0.20f) / 0.80f) * 100f).coerceIn(0, 100)
                thirdLabel.text = "${L("Brightness")}  ${Math.round(hsv[2] * 100f)}%"
                third.progress = br
            }
            hue.invalidate(); sat.invalidate(); third.invalidate()
        }
        return card
    }

    private fun promptHexColor() {
        val current = Palette.hex(channelColor(look()))
        val input = EditText(this).apply {
            setText(current)
            setSelection(text.length)
            filters = arrayOf(InputFilter.LengthFilter(9))
            typeface = Typeface.MONOSPACE
            setPadding(px(20), px(14), px(20), px(14))
        }
        AlertDialog.Builder(this)
            .setTitle(L("Hex colour (#RRGGBB)"))
            .setView(input)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val raw = input.text.toString().trim().let { if (it.startsWith("#")) it else "#$it" }
                try {
                    val parsed = Color.parseColor(raw)
                    applyChannelColor(colorChannel, parsed)
                } catch (_: Exception) {
                    Toast.makeText(this, L("Invalid hex colour"), Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    private fun amountsCard(): View {
        val card = pane(28f).apply { setPadding(px(16), px(8), px(10), px(8)) }
        fun amount(label: String, max: Int, detent: Int?, unit: String, applies: (Surface) -> Boolean,
                   read: (GlobalStyle) -> Int, write: GlobalStyle.(Int) -> GlobalStyle) {
            val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
            val value = text("", 13f, theme.inkSoft).apply { gravity = Gravity.END }
            val slider = GlassSlider(this, theme, max, detent = detent) { v -> edit { write(v) } }
                .apply { contentDescription = label }
            row.addView(text(label, 15f), LinearLayout.LayoutParams(px(74), -2))
            row.addView(slider, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(value, LinearLayout.LayoutParams(px(46), -2))
            card.addView(row, LinearLayout.LayoutParams(-1, px(50)))
            bindings.add {
                row.visibility = if (scope?.let(applies) != false) View.VISIBLE else View.GONE
                val v = read(look())
                slider.progress = v
                slider.invalidate()
                value.text = "$v$unit"
            }
        }
        amount(L("Blur"), GlobalStyle.MAX_BLUR, GlobalStyle.DEFAULT, "%", { it.hasBlur }, { it.blur }, { copy(blur = it) })
        amount(L("Glow"), GlobalStyle.MAX_GLOW, GlobalStyle.DEFAULT, "%", { true }, { it.glow }, { copy(glow = it) })

        // Glow direction: a fixed angle, or following how the phone is held (tilt / rotation).
        val modeRow = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0, px(4), px(6), px(2)) }
        val modeLabel = text(L("Glow direction"), 15f)
        fun modePill(label: String, sensor: Boolean) = text(label, 13.5f).apply {
            gravity = Gravity.CENTER
            setPadding(px(10), 0, px(10), 0)
            setOnClickListener { if (look().glowSensor != sensor) edit { copy(glowSensor = sensor) } }
        }
        val fixedPill = modePill(L("Static angle"), false)
        val tiltPill = modePill(L("Device tilt"), true)
        modeRow.addView(modeLabel, LinearLayout.LayoutParams(-2, -2))
        modeRow.addView(help(L("Glow direction"), L("Static angle: the lit edges stay at the glow angle. Device tilt: they follow how you hold and turn the phone; the glow angle is where they sit when the phone is upright.")))
        modeRow.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        modeRow.addView(fixedPill, LinearLayout.LayoutParams(-2, px(36)).apply { marginEnd = px(6) })
        modeRow.addView(tiltPill, LinearLayout.LayoutParams(-2, px(36)))
        card.addView(modeRow, LinearLayout.LayoutParams(-1, px(48)))
        bindings.add {
            val sensor = look().glowSensor
            fixedPill.background = round(if (!sensor) theme.container else theme.track, 16)
            fixedPill.setTextColor(if (!sensor) theme.onContainer else theme.inkSoft)
            tiltPill.background = round(if (sensor) theme.container else theme.track, 16)
            tiltPill.setTextColor(if (sensor) theme.onContainer else theme.inkSoft)
        }
        amount(L("Glow angle"), GlobalStyle.MAX_GLOW_ANGLE, GlobalStyle.DEFAULT_GLOW_ANGLE, "°", { true },
            { it.glowAngle }, { copy(glowAngle = it) })
        amount(L("Dim"), GlobalStyle.MAX_DIM, GlobalStyle.DEFAULT, "%", { it.hasDim }, { it.dim }, { copy(dim = it) })
        return card
    }

    /** iOS Liquid Glass optical controls from QWEA0/Liquid-Glass-Android. */
    private fun opticsCard(): View {
        val card = pane(28f).apply { setPadding(px(16), px(10), px(10), px(10)) }
        val title = text(L("Optics"), 14f, theme.inkSoft)
        card.addView(title)
        val lensVisible = { scope == null || scope?.hasLensOptics == true }
        val vibVisible = { scope != Surface.WIDGET_BOARD }
        valueRow(card, L("Refraction"), GlobalStyle.MAX_REFRACTION, GlobalStyle.DEFAULT, lensVisible,
            { look().refraction }, { "$it%" }) { v -> edit { copy(refraction = v) } }
        valueRow(card, L("Dispersion"), GlobalStyle.MAX_DISPERSION, GlobalStyle.DEFAULT, lensVisible,
            { look().dispersion }, { "$it%" }) { v -> edit { copy(dispersion = v) } }
        valueRow(card, L("Vibrancy"), GlobalStyle.MAX_VIBRANCY, GlobalStyle.DEFAULT, vibVisible,
            { look().vibrancy }, { "$it%" }) { v -> edit { copy(vibrancy = v) } }
        valueRow(card, L("Frost"), GlobalStyle.MAX_FROST, GlobalStyle.DEFAULT, { true },
            { look().frost }, { "$it%" }) { v -> edit { copy(frost = v) } }
        bindings.add { title.setTextColor(theme.inkSoft) }
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
            confirm(L("Reset the glass look?"), L("Clear style with Blur, Glow, Dim and Optics at 100 %, for the selected Light or Dark preset. Switches and shapes stay as they are."),
                L("Reset")) { store.resetGlobal(editingDark); setScope(null) }
        }, LinearLayout.LayoutParams(0, px(52), 1f))
        col.addView(actions, gapBelow())

        val backup = LinearLayout(this)
        backup.addView(button(L("Export settings"), R.drawable.ic_export) { exportSettings() },
            LinearLayout.LayoutParams(0, px(52), 1f).apply { marginEnd = px(8) })
        backup.addView(button(L("Import settings"), R.drawable.ic_import) { importSettings() },
            LinearLayout.LayoutParams(0, px(52), 1f))
        col.addView(backup, gapBelow())

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
        editor.addView(text(L("Clock, date and custom text"), 16f), LinearLayout.LayoutParams(-2, -2))
        editor.addView(help(L("Clock, date and custom text"), L("Box size, boldness and wallpaper colour filters, in the lock screen editor") + "\n\n" +
            L("Clock font: pick it in Nubia's editor (时钟字体). The first font is Roboto Flex, whose width and height axes fill the box. Rubik and Oswald use their variable versions. Fonts without width or height axes keep their proportions.")))
        editor.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        editor.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_expand)
            setColorFilter(theme.inkSoft)
            rotation = -90f
        }, LinearLayout.LayoutParams(px(24), px(24)))
        card.addView(editor)

        return card
    }

    private fun toggle(title: String, read: () -> Boolean, helpText: String? = null, write: (Boolean) -> Unit) = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20), 0, px(16), 0)
        layoutParams = LinearLayout.LayoutParams(-1, px(60))
        if (helpText == null) addView(text(title, 16f), LinearLayout.LayoutParams(0, -2, 1f))
        else {
            addView(text(title, 16f), LinearLayout.LayoutParams(-2, -2))
            addView(help(title, helpText))
            addView(View(this@SettingsActivity), LinearLayout.LayoutParams(0, 0, 1f))
        }
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

    private fun edit(change: GlobalStyle.() -> GlobalStyle) {
        store.setLook(scope, editingDark, look().change())
        sync()
    }

    private fun colourOf(g: GlobalStyle) = when {
        g.color != GlassStyle.AUTO -> g.color
        else -> wallpaperColours.firstOrNull() ?: theme.systemAccent
    }

    private fun chooseScope() {
        if (overlay != null) { closeOverlay(); return }
        val catcher = FrameLayout(this).apply {
            isClickable = true
            setOnClickListener { closeOverlay() }
        }
        val menu = GlassPane(this, theme, backdrop, 24f).apply { setPadding(px(6), px(6), px(6), px(6)) }
        val menuList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (s in listOf<Surface?>(null) + Surface.PANELS) {
            val own = s != null && !store.follows(s, editingDark)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(px(16), 0, px(16), 0)
                if (s == scope) background = round(theme.container, 18)
                contentDescription = scopeName(s)
                setOnClickListener { closeOverlay(); setScope(s) }
            }
            val ink = if (s == scope) theme.onContainer else theme.ink
            row.addView(text(scopeName(s), 15.5f, ink))
            if (s != null) row.addView(text(if (own) L("Own look") else L("Follows all surfaces"), 11.5f, if (s == scope) ink else theme.inkSoft))
            menuList.addView(row, LinearLayout.LayoutParams(-1, px(if (s == null) 46 else 52)))
        }
        val scroller = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(menuList) }
        menu.addView(scroller, LinearLayout.LayoutParams(-1, -2))
        val at = IntArray(2)
        val rootAt = IntArray(2)
        scopeChip.getLocationInWindow(at)
        root.getLocationInWindow(rootAt)
        val width = px(236)
        val maxMenuH = (root.height - px(140)).coerceAtLeast(px(280))
        val left = (at[0] - rootAt[0] + scopeChip.width - width).coerceAtLeast(px(12))
        catcher.addView(menu, FrameLayout.LayoutParams(width, -2).apply {
            leftMargin = left
            topMargin = at[1] - rootAt[1] + scopeChip.height + px(6)
        })
        menu.post {
            if (menu.height > maxMenuH) {
                menu.layoutParams = (menu.layoutParams as FrameLayout.LayoutParams).apply { height = maxMenuH }
            }
        }
        menu.alpha = 0f
        menu.scaleX = 0.92f; menu.scaleY = 0.92f
        menu.pivotX = width.toFloat(); menu.pivotY = 0f
        menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(160).start()
        overlay = catcher
        root.addView(catcher, FrameLayout.LayoutParams(-1, -1))
    }

    private fun scopeName(s: Surface?) = when (s) {
        Surface.FOLDER -> L("Folders")
        Surface.HOME_WIDGET -> L("Home widgets")
        Surface.DOCK -> L("Dock panel")
        Surface.NOTIFICATION -> L("Notifications")
        Surface.CONTROL_CENTER -> L("Control centre")
        Surface.WIDGET_BOARD -> L("Widget board")
        Surface.LOCK_WIDGET -> L("Lock screen")
        else -> L("All surfaces")
    }

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
        LightSourceController.update()
    }

    private fun confirm(title: String, message: String, action: String, run: () -> Unit) {
        AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(action) { _, _ -> run() }
            .show()
    }

    private fun restartPackages() {
        io.execute {
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
        resumed = true
        if (::store.isInitialized) {
            store.reload()
            sync()
        }
        if (wallpaper == null && ::store.isInitialized) loadWallpaper()
    }

    private fun pickImage() {
        startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_IMAGE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        val uri = data?.data ?: return
        if (res != RESULT_OK) return
        when (req) {
            REQ_EXPORT -> io.execute {
                val ok = try {
                    contentResolver.openOutputStream(uri, "wt")?.use { it.write(store.exportJson().toByteArray()) } != null
                } catch (_: Exception) { false }
                runOnUiThread { toast(if (ok) L("Settings exported") else L("Export failed")) }
            }
            REQ_IMPORT -> io.execute {
                val raw = try {
                    contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                } catch (_: Exception) { null }
                runOnUiThread { finishImport(raw) }
            }
            REQ_IMAGE -> io.execute {
                val b = LockWallpaper.load(this, uri, maxOf(resources.displayMetrics.heightPixels, 1600))
                runOnUiThread { if (b != null) { useWallpaper(b); openInk(pendingDropperChannel) } }
            }
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun exportSettings() {
        AlertDialog.Builder(this)
            .setTitle(L("Export settings"))
            .setItems(arrayOf(L("Save as a file"), L("Copy to clipboard"))) { _, which ->
                if (which == 0) {
                    val name = "nebulaColorize-" + java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.US).format(java.util.Date()) + ".json"
                    try {
                        startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                            .setType("application/json").putExtra(Intent.EXTRA_TITLE, name), REQ_EXPORT)
                    } catch (_: Exception) { toast(L("Export failed")) }
                } else {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm?.setPrimaryClip(android.content.ClipData.newPlainText("nebulaColorize settings", store.exportJson()))
                    toast(L("Settings copied"))
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun importSettings() {
        AlertDialog.Builder(this)
            .setTitle(L("Import settings"))
            .setMessage(L("Importing replaces all current settings."))
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(L("Paste from clipboard")) { _, _ ->
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString()
                finishImport(clip)
            }
            .setPositiveButton(L("Choose file")) { _, _ ->
                try {
                    startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "application/octet-stream")), REQ_IMPORT)
                } catch (_: Exception) { toast(L("Import failed")) }
            }
            .show()
    }

    private fun finishImport(raw: String?) {
        if (raw.isNullOrBlank() || !store.importJson(raw)) { toast(L("Not a valid nebulaColorize settings file")); return }
        sync()
        LightSourceController.update()
        toast(L("Settings imported"))
    }

    private val tiltRedraw: () -> Unit = { if (::stage.isInitialized) stage.refresh() }
    private var resumed = false

    override fun onPause() {
        super.onPause()
        resumed = false
        LightSourceController.update()
    }

    override fun onDestroy() {
        LightSourceController.removeRedraw(tiltRedraw)
        super.onDestroy()
    }

    private fun openInk(targetChannel: Int) {
        val b = wallpaper ?: return
        val picker = InkPicker(this, b, channelColor(look(), targetChannel)) { rgb ->
            closeOverlay()
            if (rgb != null) applyChannelColor(targetChannel, rgb)
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
            scope != null -> setScope(null)
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

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

    /** A small circled "?" after a label; tapping it shows [message]. Replaces notes under controls. */
    private fun help(title: String, message: String) = TextView(this).apply {
        text = "?"
        textSize = 11f
        gravity = Gravity.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        contentDescription = "$title: ${L("Help")}"
        layoutParams = LinearLayout.LayoutParams(px(18), px(18)).apply { marginStart = px(6) }
        setOnClickListener {
            AlertDialog.Builder(this@SettingsActivity).setTitle(title).setMessage(message)
                .setPositiveButton(android.R.string.ok, null).show()
        }
        bindings.add {
            setTextColor(theme.inkSoft)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((1.2f * dp).toInt().coerceAtLeast(1), theme.inkSoft)
            }
        }
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
        const val REQ_EXPORT = 8
        const val REQ_IMPORT = 9
        const val STATE_TAB = "tab"
        const val STATE_SCOPE = "scope"
        const val STATE_DARK = "dark"
        const val EXTRA_SECTION = "section"
        const val SECTION_LOCK = "lock"
        const val CH_ACCENT = 0
        const val CH_EDGE = 1
        const val CH_GLOW = 2
        const val SYSTEM_UI = "com.android.systemui"
        const val LOCK_SCREENS = "com.zte.mifavor.keyguard.personalclock.MyLockScreenSettingsActivity"
        val OTHER_PACKAGES = listOf("com.zte.mifavor.launcher", "com.zte.mfvkeyguard", "com.zte.mifavor.miboard")
        val HUES = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f, 0.85f, 0.95f)) }
        const val SWATCH_CLEAR = 0x52FFFFFF
        val io = Executors.newSingleThreadExecutor()

        val AREAS get() = listOf(
            Area(L("Home screen"), listOf(Surface.FOLDER, Surface.POPUP, Surface.HOME_CLOCK, Surface.HOME_WIDGET)),
            Area(L("Dock"), listOf(Surface.DOCK)),
            Area(L("Widget board"), listOf(Surface.WIDGET_BOARD)),
            Area(L("Lock screen"), listOf(Surface.LOCK_CLOCK, Surface.LOCK_WIDGET, Surface.LOCK_SHORTCUT, Surface.LOCK_CARD)),
            Area(L("Notifications"), listOf(Surface.NOTIFICATION)),
            Area(L("Control centre"), listOf(Surface.CONTROL_CENTER)),
        )
    }
}
