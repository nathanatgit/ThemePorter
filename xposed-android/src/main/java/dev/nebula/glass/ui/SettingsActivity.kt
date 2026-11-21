package dev.nebula.glass.ui

import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import dev.nebula.glass.LensGlass
import java.util.concurrent.Executors

/**
 * nebulaColorize settings: one shared glass look (intensity, edge glow, accent with palette and ink
 * picker) with a live preview over the lock wallpaper, and an on/off per surface. Changes save and
 * apply live (see [SettingsStore]).
 * Framework views only, built in code, so the module APK stays dependency-free.
 */
class SettingsActivity : Activity() {
    private lateinit var store: SettingsStore
    private lateinit var root: FrameLayout
    private var overlay: View? = null
    private var wallpaper: Bitmap? = null
    private var wallpaperColors = IntArray(0)
    private val onWallpaper = ArrayList<() -> Unit>()
    private val night get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    private val dp get() = resources.displayMetrics.density

    // Theme colours, light / dark
    private val bg get() = if (night) Color.BLACK else 0xFFF2F3F7.toInt()
    private val card get() = if (night) 0xFF1C1C1E.toInt() else Color.WHITE
    private val ink get() = if (night) Color.WHITE else 0xFF111111.toInt()
    private val sub get() = if (night) 0xFF98989F.toInt() else 0xFF6E6E73.toInt()
    private val accent = 0xFF0A84FF.toInt()

    override fun onCreate(state: Bundle?) {
        setTheme(if (night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar)
        super.onCreate(state)
        store = SettingsStore(this)
        root = FrameLayout(this).apply { setBackgroundColor(bg) }
        setContentView(root)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        showPage()
        loadWallpaper()
    }

    // ---------- wallpaper ----------

    private fun loadWallpaper() {
        wallpaperColors = LockWallpaper.systemColors(this)
        io.execute {
            val b = LockWallpaper.load(this, maxOf(resources.displayMetrics.heightPixels, 1600))
            runOnUiThread { if (b != null) useWallpaper(b) }
        }
    }

    private fun useWallpaper(b: Bitmap) {
        wallpaper = b
        val extra = LockWallpaper.dominant(b)
        wallpaperColors = (wallpaperColors.toList() + extra.toList()).distinct().take(8).toIntArray()
        onWallpaper.toList().forEach { it() }
    }

    /** No permission to read the wallpaper: let the user choose the image (system picker, no permission). */
    private fun pickImage() {
        startActivityForResult(Intent(MediaStore.ACTION_PICK_IMAGES).setType("image/*"), REQ_IMAGE)
    }

    override fun onResume() {
        super.onResume()
        // Coming back from the all-files access page: try the real wallpaper again.
        if (wallpaper == null && ::store.isInitialized) loadWallpaper()
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

    // ---------- page ----------

    /**
     * One page: the shared look (applies to every Liquid Glass surface) and an on/off per surface.
     * User decision 2026-11-21: unified intensity, edge glow and accent; surfaces are toggles only.
     */
    private fun showPage() {
        onWallpaper.clear()
        val col = column()
        col.addView(title("nebulaColorize", 30f), lp(top = 24))
        col.addView(text("Liquid Glass for the Nubia launcher, lock screen, notifications and control centre", 14f, sub), lp(bottom = 8))
        if (!store.hooksCanRead) col.addView(banner(
            "Enable nebulaColorize in Vector, then reopen this app. Until then the hooks can't read these settings; changes still apply live while SystemUI and the launcher are running."), lp(bottom = 8))

        val preview = Preview()
        col.addView(preview, LinearLayout.LayoutParams(-1, (200 * dp).toInt()).apply { topMargin = (12 * dp).toInt() })
        val refresh = { preview.refresh() }

        col.addView(section("Glass look · all surfaces"), lp(top = 20, bottom = 6))
        val look = cardBox()
        look.addView(sliderRow("Glass intensity", store.global.intensity, GlassStyle.MAX_BLUR, "%") { v ->
            store.setGlobal(store.global.copy(intensity = v)); refresh()
        })
        look.addView(sliderRow("Edge glow", store.global.edge, GlassStyle.MAX_EDGE, "%") { v ->
            store.setGlobal(store.global.copy(edge = v)); refresh()
        })
        col.addView(look, lp())

        col.addView(section("Accent colour · all surfaces"), lp(top = 20, bottom = 6))
        col.addView(colourPanel(refresh), lp())
        col.addView(text("Folder tiles use a folder's own Nubia colour when you have set one; otherwise they follow this accent.", 12f, sub)
            .apply { setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) }, lp(top = 6))

        for (where in Surface.values().map { it.where }.distinct()) {
            col.addView(section(where), lp(top = 22, bottom = 6))
            val group = cardBox()
            for (s in Surface.values().filter { it.where == where }) {
                group.addView(toggleRow(s.title, store.isOn(s)) { on -> store.setOn(s, on); refresh() })
                if (s in Surface.PANELS) group.addView(panelLook(s))
            }
            col.addView(group, lp())
        }

        col.addView(section("Restart scoped apps"), lp(top = 22, bottom = 6))
        val restart = cardBox()
        for ((label, pkg) in SCOPED) restart.addView(restartRow(label, listOf(pkg)))
        restart.addView(restartRow("All of the above", SCOPED.map { it.second }))
        col.addView(restart, lp())
        col.addView(text("Needs root. Restarting System UI briefly blanks the screen.", 12f, sub)
            .apply { setPadding((8 * dp).toInt(), 0, (8 * dp).toInt(), 0) }, lp(top = 6))

        col.addView(text("Reset glass look", 16f, 0xFFFF453A.toInt()).apply {
            gravity = Gravity.CENTER; setPadding(0, (14 * dp).toInt(), 0, (14 * dp).toInt())
            background = round(card, 16)
            setOnClickListener { store.resetGlobal(); showPage() }
        }, lp(top = 24, bottom = 32))
        show(col)
    }

    /**
     * Auto · ink picker · wallpaper colours · colourful palette · tonal ramp · custom HSV · strength.
     * Picking any colour keeps the current strength (or 35 % coming from Auto).
     */
    private fun colourPanel(refresh: () -> Unit): View {
        val box = cardBox().apply { setPadding((14 * dp).toInt(), (12 * dp).toInt(), (14 * dp).toInt(), (14 * dp).toInt()) }
        val current = { store.global.accent }
        val strength = { if (current() == GlassStyle.AUTO) 35 else Palette.strengthOf(current()) }
        fun setAccent(a: Int) = store.setGlobal(store.global.copy(accent = a))
        val label = text("", 14f, sub)
        val grids = ArrayList<SwatchGrid>()
        val ramp = SwatchGrid(this, 9).apply { ringColor = ink }
        val hsv = HsvPicker(this)
        val strengthBar = SeekBar(this).apply { max = 100; min = 1 }
        val autoChip = chip("Auto")

        fun sync() {
            val a = current()
            val rgb = if (a == GlassStyle.AUTO) null else Palette.rgb(a)
            grids.forEach { it.selected = rgb }
            ramp.colors = Palette.ramp(rgb ?: Palette.COLORFUL[13])
            ramp.selected = rgb
            if (rgb != null) hsv.setColor(rgb)
            strengthBar.isEnabled = rgb != null
            strengthBar.progress = strength()
            autoChip.isSelected = rgb == null
            styleChip(autoChip)
            label.text = if (rgb == null) "Auto · each surface keeps its built-in tint" else "${Palette.hex(rgb)} · strength ${strength()} %"
            refresh()
        }
        fun pick(rgb: Int, rampToo: Boolean = true) {
            setAccent(Palette.withStrength(rgb, strength()))
            if (!rampToo) { val keep = ramp.colors; sync(); ramp.colors = keep; ramp.selected = rgb } else sync()
        }

        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        autoChip.setOnClickListener { setAccent(GlassStyle.AUTO); sync() }
        tools.addView(autoChip)
        tools.addView(View(this), LinearLayout.LayoutParams((8 * dp).toInt(), 1))
        tools.addView(chip("◉  Ink picker").apply {
            setOnClickListener {
                inkTarget = { rgb -> pick(rgb) }
                if (wallpaper != null) openInk() else pickImage()
            }
        })
        box.addView(tools)
        val access = text("Ink picker uses an image you choose. Allow wallpaper access to pick from your real lock screen ›", 13f, accent).apply {
            setOnClickListener {
                startActivity(Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    android.net.Uri.parse("package:$packageName")))
            }
            visibility = if (wallpaper == null) View.VISIBLE else View.GONE
        }
        onWallpaper.add { access.visibility = View.GONE }
        box.addView(access, lp(top = 8))
        box.addView(label, lp(top = 8, bottom = 4))

        val wallTitle = text("From your wallpaper", 13f, sub)
        val wallGrid = SwatchGrid(this, 8).apply { ringColor = ink; onPick = { pick(it) } }
        grids.add(wallGrid)
        val fillWall = {
            wallGrid.colors = wallpaperColors
            wallTitle.visibility = if (wallpaperColors.isEmpty()) View.GONE else View.VISIBLE
            wallGrid.visibility = wallTitle.visibility
        }
        fillWall(); onWallpaper.add { fillWall(); sync() }
        box.addView(wallTitle, lp(top = 8, bottom = 4))
        box.addView(wallGrid, lp())

        box.addView(text("Palette", 13f, sub), lp(top = 10, bottom = 4))
        val palette = SwatchGrid(this, 12).apply { ringColor = ink; colors = Palette.COLORFUL; onPick = { pick(it) } }
        grids.add(palette)
        box.addView(palette, lp())

        box.addView(text("Tones", 13f, sub), lp(top = 10, bottom = 4))
        ramp.onPick = { pick(it, rampToo = false) }
        box.addView(ramp, lp())

        box.addView(text("Custom", 13f, sub), lp(top = 12, bottom = 6))
        hsv.onChange = { pick(it) }
        box.addView(hsv, lp())

        box.addView(text("Strength", 13f, sub), lp(top = 12))
        strengthBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) {
                if (!user || current() == GlassStyle.AUTO) return
                setAccent(Palette.withStrength(Palette.rgb(current()), v))
                label.text = "${Palette.hex(current())} · strength $v %"
                refresh()
            }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        box.addView(strengthBar, lp())
        sync()
        return box
    }

    /**
     * "Own look" for a panel surface: while off it follows the shared look; while on it has its own
     * glass intensity, edge glow and accent (colour from a compact palette plus strength).
     */
    private fun panelLook(s: Surface): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun cur() = store.look(s)
        fun strengthOf(a: Int) = if (a == GlassStyle.AUTO) 35 else Palette.strengthOf(a)
        val label = text("", 13f, sub).apply { setPadding((16 * dp).toInt(), 0, (16 * dp).toInt(), 0) }
        val grid = SwatchGrid(this, 12).apply { ringColor = ink; colors = Palette.COLORFUL }
        val bar = SeekBar(this).apply { max = 100; min = 1 }
        val auto = chip("Auto")
        fun sync() {
            val a = cur().accent
            val rgb = if (a == GlassStyle.AUTO) null else Palette.rgb(a)
            grid.selected = rgb
            bar.isEnabled = rgb != null
            bar.progress = strengthOf(a)
            auto.isSelected = rgb == null; styleChip(auto)
            label.text = if (rgb == null) "Accent · Auto" else "Accent · ${Palette.hex(rgb)} · strength ${strengthOf(a)} %"
        }
        body.addView(sliderRow("Intensity", cur().intensity, GlassStyle.MAX_BLUR, "%") { v ->
            store.setOwn(s, cur().copy(intensity = v))
        })
        body.addView(sliderRow("Edge glow", cur().edge, GlassStyle.MAX_EDGE, "%") { v ->
            store.setOwn(s, cur().copy(edge = v))
        })
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * dp).toInt(), (6 * dp).toInt(), (16 * dp).toInt(), (4 * dp).toInt()) }
        head.addView(auto)
        head.addView(label, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(head)
        grid.onPick = { rgb -> store.setOwn(s, cur().copy(accent = Palette.withStrength(rgb, strengthOf(cur().accent)))); sync() }
        body.addView(grid, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = (12 * dp).toInt(); rightMargin = (12 * dp).toInt() })
        auto.setOnClickListener { store.setOwn(s, cur().copy(accent = GlassStyle.AUTO)); sync() }
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) {
                if (!user || cur().accent == GlassStyle.AUTO) return
                store.setOwn(s, cur().copy(accent = Palette.withStrength(Palette.rgb(cur().accent), v))); sync()
            }
            override fun onStartTrackingTouch(b: SeekBar) {}
            override fun onStopTrackingTouch(b: SeekBar) {}
        })
        body.addView(bar, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = (16 * dp).toInt(); rightMargin = (16 * dp).toInt(); bottomMargin = (8 * dp).toInt() })
        sync()
        body.visibility = if (store.hasOwn(s)) View.VISIBLE else View.GONE
        box.addView(toggleRow("   Own look (else shared)", store.hasOwn(s)) { on ->
            store.setHasOwn(s, on)
            body.visibility = if (on) View.VISIBLE else View.GONE
            if (on) { sync() }
        })
        box.addView(body)
        return box
    }

    private var inkTarget: ((Int) -> Unit)? = null

    private fun openInk() {
        val b = wallpaper ?: return
        val start = store.global.accent.takeIf { it != GlassStyle.AUTO }?.let { Palette.rgb(it) }
        val picker = InkPicker(this, b, start) { rgb ->
            closeOverlay()
            if (rgb != null) inkTarget?.invoke(rgb)
        }
        overlay = picker
        root.addView(picker, FrameLayout.LayoutParams(-1, -1))
    }

    private fun closeOverlay() { overlay?.let { root.removeView(it) }; overlay = null }

    /** Wallpaper with two samples: a refracting lens panel (launcher, lock screen) and a capsule. */
    private inner class Preview : FrameLayout(this@SettingsActivity) {
        private val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        private val panel = GlassSample(Surface.LOCK_WIDGET, 18f)
        private val capsule = GlassSample(Surface.POPUP, 999f)

        init {
            background = round(0xFF3A3F4B.toInt(), 22)
            clipToOutline = true
            addView(image, LayoutParams(-1, -1))
            addView(panel, LayoutParams((200 * dp).toInt(), (110 * dp).toInt(), Gravity.CENTER_VERTICAL or Gravity.START).apply { leftMargin = (24 * dp).toInt() })
            addView(capsule, LayoutParams((110 * dp).toInt(), (56 * dp).toInt(), Gravity.CENTER_VERTICAL or Gravity.END).apply { rightMargin = (24 * dp).toInt() })
            onWallpaper.add { showImage() }
            showImage()
        }

        private fun showImage() {
            val b = wallpaper
            if (b != null) image.setImageBitmap(b)
            else image.setImageDrawable(GradientDrawable(GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF6D83F2.toInt(), 0xFFF29BC3.toInt(), 0xFFFFD27A.toInt())))
            refresh()
        }

        fun refresh() { panel.invalidate(); capsule.invalidate() }
    }

    private inner class GlassSample(private val s: Surface, private val cornerDp: Float) : View(this@SettingsActivity) {
        private val lens: LensGlass = Looks.base(s, context, night)
        override fun onDraw(c: Canvas) {
            val st = store.config.style(s).copy(enabled = true)
            Looks.style(lens, s, dp, cornerDp * dp, night, st = st)
            if (!lens.draw(c, this, parent as View, null)) lens.drawOpaque(c, this)
        }
    }

    // ---------- small builders ----------

    private fun show(content: View) {
        closeOverlay()
        root.removeAllViews()
        root.addView(ScrollView(this).apply { addView(content); isFillViewport = true }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (24 * dp).toInt(), (16 * dp).toInt(), (24 * dp).toInt())
    }

    private fun lp(top: Int = 0, bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply {
        topMargin = (top * dp).toInt(); bottomMargin = (bottom * dp).toInt()
    }

    private fun round(color: Int, radiusDp: Int) = GradientDrawable().apply { setColor(color); cornerRadius = radiusDp * dp }

    private fun cardBox() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = round(card, 16) }

    private fun text(t: String, size: Float, color: Int) = TextView(this).apply { text = t; textSize = size; setTextColor(color) }

    private fun title(t: String, size: Float) = text(t, size, ink).apply { typeface = Typeface.DEFAULT_BOLD }

    private fun section(t: String) = text(t.uppercase(), 13f, sub).apply { setPadding((8 * dp).toInt(), 0, 0, 0) }

    private fun banner(t: String) = text(t, 13f, 0xFF8A5A00.toInt()).apply {
        setPadding((14 * dp).toInt(), (10 * dp).toInt(), (14 * dp).toInt(), (10 * dp).toInt())
        background = round(0xFFFFF3D6.toInt(), 12)
    }

    private fun swatchDot(argb: Int, sizeDp: Int) = View(this).apply {
        background = if (argb == GlassStyle.AUTO) GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFFFF6B6B.toInt(), 0xFFFFD93D.toInt(), 0xFF6BCB77.toInt(), 0xFF4D96FF.toInt())).apply { shape = GradientDrawable.OVAL }
        else GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Palette.rgb(argb)) }
        minimumWidth = (sizeDp * dp).toInt(); minimumHeight = (sizeDp * dp).toInt()
    }

    private fun chip(t: String) = text(t, 15f, ink).apply {
        gravity = Gravity.CENTER
        setPadding((14 * dp).toInt(), (8 * dp).toInt(), (14 * dp).toInt(), (8 * dp).toInt())
        styleChip(this)
    }

    private fun styleChip(v: TextView) {
        v.background = GradientDrawable().apply {
            cornerRadius = 18 * dp
            if (v.isSelected) { setColor(accent); v.setTextColor(Color.WHITE) }
            else { setColor(if (night) 0xFF2C2C2E.toInt() else 0xFFEDEDF0.toInt()); v.setTextColor(ink) }
        }
    }

    private fun toggleRow(label: String, on: Boolean, change: (Boolean) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        addView(text(label, 16f, ink), LinearLayout.LayoutParams(0, -2, 1f))
        addView(Switch(this@SettingsActivity).apply { isChecked = on; setOnCheckedChangeListener { _, v -> change(v) } })
    }

    private fun restartRow(label: String, pkgs: List<String>) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPadding((16 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt(), (12 * dp).toInt())
        addView(text(label, 16f, ink), LinearLayout.LayoutParams(0, -2, 1f))
        addView(text("Restart", 15f, accent).apply {
            setPadding((12 * dp).toInt(), (6 * dp).toInt(), (12 * dp).toInt(), (6 * dp).toInt())
            setOnClickListener { restartPackages(pkgs) }
        })
    }

    /** Kills each package's processes as root; the system respawns them with the hooks reloaded. */
    private fun restartPackages(pkgs: List<String>) {
        io.execute {
            // Restart System UI last: it takes this app's toast with it on some builds.
            val ordered = pkgs.sortedBy { it == "com.android.systemui" }
            val script = ordered.joinToString("; ") {
                if (it == "com.android.systemui") "kill \$(pidof $it)" else "am force-stop $it"
            }
            val ok = try {
                val p = ProcessBuilder("su", "-c", script).redirectErrorStream(true).start()
                p.inputStream.readBytes(); p.waitFor() == 0
            } catch (e: Exception) { false }
            runOnUiThread {
                Toast.makeText(this, if (ok) "Restarted" else "Restart failed: root (su) unavailable", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun sliderRow(label: String, value: Int, max: Int, unit: String, change: (Int) -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding((16 * dp).toInt(), (10 * dp).toInt(), (16 * dp).toInt(), (6 * dp).toInt())
        val head = LinearLayout(this@SettingsActivity).apply { orientation = LinearLayout.HORIZONTAL }
        val valueText = text("$value$unit", 15f, sub)
        head.addView(text(label, 16f, ink), LinearLayout.LayoutParams(0, -2, 1f))
        head.addView(valueText)
        addView(head)
        addView(SeekBar(this@SettingsActivity).apply {
            this.max = max; progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(b: SeekBar, v: Int, user: Boolean) { if (user) { valueText.text = "$v$unit"; change(v) } }
                override fun onStartTrackingTouch(b: SeekBar) {}
                override fun onStopTrackingTouch(b: SeekBar) {}
            })
        })
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            overlay != null -> closeOverlay()
            else -> @Suppress("DEPRECATION") super.onBackPressed()
        }
    }

    companion object {
        private const val REQ_IMAGE = 7
        private val SCOPED = listOf(
            "Launcher" to "com.zte.mifavor.launcher",
            "System UI (notifications, control centre)" to "com.android.systemui",
            "Lock screen" to "com.zte.mfvkeyguard",
            "Widget board" to "com.zte.mifavor.miboard",
        )
        private val io = Executors.newSingleThreadExecutor()
    }
}
