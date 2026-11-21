package dev.nebula.glass.hook

import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.RectF
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import dev.nebula.glass.GlassSettings
import dev.nebula.glass.GlassSkin
import dev.nebula.glass.GlassStyle
import dev.nebula.glass.Looks
import dev.nebula.glass.Surface
import java.lang.ref.WeakReference
import java.util.WeakHashMap

/**
 * Liquid Glass for Nubia's widget board (`com.zte.mifavor.miboard`, the page left of home).
 *
 * Trace (docs/LIQUID_GLASS_PORT.md): every card (`contents.*Card`) extends the obfuscated base
 * `contents.views.g`, whose `onDraw` paints its field `A` (a GradientDrawable: the white card, corner
 * radius 52 px). No view has a card background. Inner tiles (search bar, hosted-widget grid tiles,
 * round buttons) are GradientDrawable view backgrounds. The board window floats over a system blur
 * of the home screen, which cannot be sampled, so these get a [GlassSkin] (frost from the system blur).
 *
 * Nothing is replaced: `GradientDrawable.draw` is hooked and filtered to the tracked instances.
 */
class BoardGlass : IXposedHookLoadPackage {
    /** @param inner a tile inside a card (lighter glass); @param radius corner for fills without their own shape */
    private class Target(host: View, val inner: Boolean, val radius: Float = 0f) { val host = WeakReference(host) }

    private val tracked = WeakHashMap<Drawable, Target>()
    private val skin = GlassSkin()
    private val rect = RectF()
    private val hookedDraws = HashSet<java.lang.reflect.Method>()
    private var cardField: java.lang.reflect.Field? = null
    private var baseClass: Class<*>? = null
    private val loggedFields = HashSet<String>()
    private val artPaint = android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG)
    private var settingsAttached = false

    override fun handleLoadPackage(app: XC_LoadPackage.LoadPackageParam) {
        if (app.packageName != BOARD || app.processName != BOARD) return
        HookSettings.load()
        GlassSettings.addListener { for (t in tracked.values.toList()) t.host.get()?.invalidate() }
        val base = XposedHelpers.findClassIfExists("com.zte.mifavor.miboard.contents.views.g", app.classLoader)
        if (base == null) { Log.w(TAG, "board card base not found"); return }
        val cardField = XposedHelpers.findFieldIfExists(base, "A")
        this.cardField = cardField
        this.baseClass = base
        Log.i(TAG, "board glass: base=${base.name} cardField=${cardField?.type?.simpleName}")
        try {
            // Track each card's fill right before it is painted (cards may swap it).
            XposedHelpers.findAndHookMethod(base, "onDraw", Canvas::class.java, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    try {
                        (cardField?.get(v) as? GradientDrawable)?.let { track(it, v, inner = false) }
                    } catch (t: Throwable) { Log.e(TAG, "board card track", t) }
                }
            })
            // Inner tiles: flat GradientDrawable backgrounds inside a card.
            val inner = object : XC_MethodHook() {
                override fun afterHookedMethod(p: MethodHookParam) {
                    val v = p.thisObject as View
                    v.post { check(v, base) }
                }
            }
            XposedHelpers.findAndHookMethod(View::class.java, "onAttachedToWindow", inner)
            XposedHelpers.findAndHookMethod(View::class.java, "setBackgroundDrawable", Drawable::class.java, inner)
            XposedHelpers.findAndHookMethod(android.widget.ImageView::class.java, "setImageDrawable", Drawable::class.java, inner)
            // Probe: what ExpressCard keeps (its own onDraw does not go through the card base).
            XposedHelpers.findClassIfExists("com.zte.mifavor.miboard.contents.express.ExpressCard", app.classLoader)?.let { ec ->
                Log.i(TAG, "board ExpressCard fields: " + ec.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
                    .joinToString { "${it.name}:${it.type.simpleName}" }.take(600))
            }
            Log.i(TAG, "board glass hooks installed")
        } catch (t: Throwable) {
            Log.e(TAG, "board hooks failed", t)
        }
    }

    private fun check(v: View, base: Class<*>) {
                    try {
                        if (base.isInstance(v)) {
                            // The card fill: the base's `A`, or a subclass's own (ExpressCard paints `P`).
                            var c: Class<*>? = v.javaClass
                            while (c != null && base.isAssignableFrom(c)) {
                                for (f in c.declaredFields) {
                                    if (java.lang.reflect.Modifier.isStatic(f.modifiers) || !Drawable::class.java.isAssignableFrom(f.type)) continue
                                    f.isAccessible = true
                                    when (val d = f.get(v)) {
                                        is GradientDrawable -> track(d, v, inner = false)
                                        is ColorDrawable -> if (isLightPanel(d, v, v)) track(d, v, inner = false, radius = cardRadius(v))
                                        // A subclass's own card picture (ExpressCard.P: white card + faint map art); not the base's B.
                                        is android.graphics.drawable.BitmapDrawable -> if (c != base) track(d, v, inner = false, radius = cardRadius(v))
                                        null -> {}
                                        else -> if (loggedFields.add("${c.simpleName}.${f.name}")) Log.i(TAG, "board card field ${c.simpleName}.${f.name} = ${d.javaClass.name}")
                                    }
                                }
                                c = c.superclass
                            }
                            return
                        }
                        val card = cardOf(v, base) ?: return
                        val dp = v.resources.displayMetrics.density
                        if (v.width in 1 until (16 * dp).toInt() || v.height in 1 until (16 * dp).toInt()) return
                        val bg = v.background
                        if (bg is GradientDrawable) { track(bg, v, inner = true); return }
                        // A plain light fill covering most of the card is the card's own panel (speed-up widget).
                        val fill = (v as? android.widget.ImageView)?.drawable as? ColorDrawable ?: bg as? ColorDrawable ?: return
                        if (isLightPanel(fill, v, card)) track(fill, v, inner = false, radius = cardRadius(card))
                    } catch (t: Throwable) { Log.e(TAG, "board tile track", t) }
    }

    private fun cardOf(v: View, base: Class<*>): View? {
        var p = v.parent
        while (p is View) { if (base.isInstance(p)) return p; p = p.parent }
        return null
    }

    private fun isLightPanel(d: ColorDrawable, v: View, card: View): Boolean {
        val c = d.color
        if ((c ushr 24) < 0x80) return false
        val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(c, it) }
        if (hsv[1] > 0.15f || hsv[2] < 0.85f) return false
        return v.width.toLong() * v.height * 2 >= card.width.toLong() * card.height
    }

    private fun cardRadius(card: View): Float {
        val a = cardField?.get(card) as? GradientDrawable
        return a?.cornerRadius?.takeIf { it > 0f } ?: (19 * card.resources.displayMetrics.density)
    }

    private fun track(d: Drawable, host: View, inner: Boolean, radius: Float = 0f) {
        if (tracked[d]?.host?.get() === host) return
        if (!settingsAttached) { settingsAttached = true; HookSettings.attach(host.context) }
        tracked[d] = Target(host, inner, radius)
        val draw = d.javaClass.getMethod("draw", Canvas::class.java)
        if (hookedDraws.add(draw)) {
            XposedBridge.hookMethod(draw, object : XC_MethodHook() {
                override fun beforeHookedMethod(p: MethodHookParam) {
                    val t = tracked[p.thisObject] ?: return
                    try { if (drawSkin(p.thisObject as Drawable, p.args[0] as Canvas, t)) p.result = null }
                    catch (e: Throwable) { Log.e(TAG, "board skin", e) }
                }
            })
        }
        host.invalidate()
    }

    private fun drawSkin(d: Drawable, c: Canvas, t: Target): Boolean {
        val host = t.host.get() ?: return false
        val st = GlassSettings[Surface.WIDGET_BOARD]
        if (!st.enabled) return false
        val g = d as? GradientDrawable
        val color = when (d) { is ColorDrawable -> d.color; else -> g?.color?.defaultColor ?: g?.colors?.firstOrNull() }
        // Solid saturated fills are deliberate marks (an accent button): keep them.
        if (color != null && (color ushr 24) >= 0xE0) {
            val hsv = FloatArray(3).also { android.graphics.Color.colorToHSV(color, it) }
            if (hsv[1] > 0.25f && hsv[2] > 0.2f) return false
        }
        rect.set(d.bounds)
        if (rect.isEmpty) return false
        val dp = host.resources.displayMetrics.density
        val radii = when {
            st.cornerDp != GlassStyle.ORIGINAL -> GlassSkin.uniform(st.cornerDp * dp)
            g == null -> GlassSkin.uniform(t.radius)
            g.shape == GradientDrawable.OVAL -> GlassSkin.uniform(minOf(rect.width(), rect.height()) / 2f)
            else -> g.cornerRadii?.takeIf { it.size == 8 } ?: GlassSkin.uniform(g.cornerRadius)
        }
        val night = (host.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        var tint = when {
            st.accent != GlassStyle.AUTO -> st.accent
            else -> Looks.tint(Surface.WIDGET_BOARD, night)
        }
        if (t.inner) tint = (tint and 0xFFFFFF) or (((tint ushr 24) * 6 / 10) shl 24)
        val a = ((tint ushr 24) * st.blur / 100).coerceIn(0, 255)
        skin.draw(c, rect, radii, (tint and 0xFFFFFF) or (a shl 24),
            Looks.specular(Surface.WIDGET_BOARD) * st.edge / 100f * (if (t.inner) 0.6f else 1f), dp)
        // A picture card keeps its artwork faintly on the glass.
        (d as? android.graphics.drawable.BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }?.let { bmp ->
            artPaint.alpha = 0x38
            c.drawBitmap(bmp, null, rect, artPaint)
        }
        return true
    }

    companion object {
        private const val TAG = "NebulaGlass"
        private const val BOARD = "com.zte.mifavor.miboard"
    }
}
