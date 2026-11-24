package dev.nebula.glass.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import dev.nebula.glass.Looks

/** Colour helpers shared by the settings app. All colours are opaque RGB. */
object Palette {
    fun rgb(c: Int) = c or 0xFF000000.toInt()
    fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)
    fun alpha(c: Int, a: Float) = Color.argb((a * 255).toInt(), Color.red(c), Color.green(c), Color.blue(c))
}

/**
 * The settings app's Material 3 colour roles, generated from a seed: the tint colour when Tinted,
 * else the system's dynamic accent. Text stays neutral, so recolouring never costs legibility.
 */
internal class UiTheme(private val ctx: Context) {
    val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    val systemAccent = Palette.rgb(ctx.getColor(android.R.color.system_accent1_500))

    var seed = 0
        private set
    var primary = 0
        private set
    var onPrimary = 0
        private set
    var container = 0
        private set
    var onContainer = 0
        private set
    var track = 0
        private set
    var surface = 0
        private set

    /** Body of the app's own glass panes. */
    var pane = 0
        private set

    val ink = if (night) 0xFFECECF0.toInt() else 0xFF1A1B20.toInt()
    val inkSoft = if (night) 0xFFBFC1C9.toInt() else 0xFF454750.toInt()
    val outline = if (night) 0xFF8E9099.toInt() else 0xFF74767F.toInt()

    init { seedFrom(systemAccent) }

    /** A Material tone (0 = black, 100 = white) of [c]. */
    fun tone(c: Int, tone: Float): Int {
        // Material tones are CIE L*; Looks.toned works in relative luminance.
        val y = if (tone > 8f) Math.pow((tone + 16.0) / 116.0, 3.0).toFloat() else tone / 903.3f
        return Looks.toned(c, y, y)
    }

    fun seedFrom(colour: Int) {
        seed = colour
        primary = tone(colour, if (night) 80f else 40f)
        onPrimary = tone(colour, if (night) 20f else 100f)
        container = tone(colour, if (night) 30f else 88f)
        onContainer = tone(colour, if (night) 92f else 12f)
        track = Palette.alpha(tone(colour, if (night) 80f else 30f), 0.20f)
        surface = tone(colour, if (night) 10f else 94f)
        pane = Palette.alpha(tone(colour, if (night) 12f else 97f), if (night) 0.55f else 0.60f)
    }
}
