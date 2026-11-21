package dev.nebula.glass.lab

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.TextView
import android.graphics.RectF
import dev.nebula.glass.GlassMaterial
import dev.nebula.glass.GlassSurfaceView

/**
 * Debug-only bench for the vendored Liquid Glass pipeline (phase G0).
 *
 * A launcher-shaped layout (icon grid, dock, open folder) over a procedural wallpaper. Glass
 * samples its parent, i.e. the in-tree backdrop path; the real launcher additionally needs the
 * wallpaper-bitmap source because its wallpaper is a separate window.
 *
 * Taps: dock toggles REGULAR/CLEAR, folder hides/shows, the label toggles scene motion.
 * Drag the dock or the folder to move them over the scene.
 */
class GlassLab : Activity() {
    private fun dp(v: Float) = v * resources.displayMetrics.density

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT

        val root = FrameLayout(this)
        val scene = SceneView(this)
        root.addView(scene, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val icons = IconGrid(this)
        root.addView(icons, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))

        val folder = GlassSurfaceView(this).apply { glass.cornerRadius = dp(32f); glass.bevelWidth = 64f }
        root.addView(folder, FrameLayout.LayoutParams(dp(300f).toInt(), dp(300f).toInt(), Gravity.CENTER).apply {
            bottomMargin = dp(60f).toInt()
        })

        val label = TextView(this).apply {
            text = "Glass Lab · REGULAR · static"
            setTextColor(Color.WHITE)
            textSize = 14f
            setShadowLayer(dp(3f), 0f, dp(1f), 0x80000000.toInt())
        }
        root.addView(label, FrameLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT, Gravity.CENTER_HORIZONTAL).apply {
            topMargin = dp(80f).toInt()
        })

        val dock = GlassSurfaceView(this)
        root.addView(dock, FrameLayout.LayoutParams(MATCH_PARENT, dp(96f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = dp(16f).toInt(); rightMargin = leftMargin; bottomMargin = dp(48f).toInt()
        })
        for (g in listOf(Gravity.START, Gravity.END)) {
            val button = GlassSurfaceView(this)
            root.addView(button, FrameLayout.LayoutParams(dp(56f).toInt(), dp(56f).toInt(), Gravity.BOTTOM or g).apply {
                leftMargin = dp(40f).toInt(); rightMargin = leftMargin; bottomMargin = dp(176f).toInt()
            })
        }
        val dockIcons = IconGrid(this, rows = 1)
        root.addView(dockIcons, FrameLayout.LayoutParams(MATCH_PARENT, dp(96f).toInt(), Gravity.BOTTOM).apply {
            leftMargin = dp(16f).toInt(); rightMargin = leftMargin; bottomMargin = dp(48f).toInt()
        })
        dockIcons.isClickable = false
        setContentView(root)

        folder.setOnClickListener { folder.alpha = if (folder.alpha > .5f) 0f else 1f }
        fun describe() {
            label.text = "Glass Lab · ${dock.glass.material} · ${if (scene.animate) "moving" else "static"}"
        }
        dock.setOnClickListener {
            dock.glass.material = if (dock.glass.material == GlassMaterial.REGULAR) GlassMaterial.CLEAR else GlassMaterial.REGULAR
            describe()
        }
        label.setOnClickListener { scene.animate = !scene.animate; scene.invalidate(); describe() }
        draggable(dock); draggable(folder)
        dock.viewTreeObserver.addOnPreDrawListener { dockIcons.translationX = dock.translationX; dockIcons.translationY = dock.translationY; true }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun draggable(v: View) {
        var sx = 0f; var sy = 0f; var moved = false
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX - view.translationX; sy = e.rawY - view.translationY; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val nx = e.rawX - sx; val ny = e.rawY - sy
                    if (moved || Math.abs(nx - view.translationX) + Math.abs(ny - view.translationY) > dp(6f)) {
                        moved = true; view.translationX = nx; view.translationY = ny
                    }
                }
                MotionEvent.ACTION_UP -> if (!moved) view.performClick()
            }
            true
        }
    }

    /** Rounded-square app icons: rows of 4 below the status area, or one row for the dock. */
    private class IconGrid(context: Context, private val rows: Int = 5) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val colors = intArrayOf(0xffff7043.toInt(), 0xff42a5f5.toInt(), 0xff66bb6a.toInt(), 0xffffca28.toInt(),
            0xffab47bc.toInt(), 0xff26c6da.toInt(), 0xffec407a.toInt(), 0xff8d6e63.toInt())
        private val rect = RectF()
        override fun onDraw(c: Canvas) {
            val d = resources.displayMetrics.density
            val size = 56 * d
            val cell = width / 4f
            val top = if (rows == 1) (height - size) / 2f else 240 * d
            for (r in 0 until rows) for (col in 0 until 4) {
                val x = col * cell + (cell - size) / 2f
                val y = top + r * 104 * d
                rect.set(x, y, x + size, y + size)
                paint.color = colors[(r * 4 + col + if (rows == 1) 3 else 0) % colors.size]
                c.drawRoundRect(rect, 16 * d, 16 * d, paint)
            }
        }
    }

    /** Procedural "wallpaper": sky gradient, sun, ridges and fine type so refraction is readable. */
    private class SceneView(context: Context) : View(context) {
        var animate = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val type = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 34f; typeface = Typeface.MONOSPACE }
        private val path = Path()
        private var t = 0f

        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            paint.shader = LinearGradient(0f, 0f, 0f, h, intArrayOf(0xff1c3f8f.toInt(), 0xff6a74d8.toInt(),
                0xfff2a6c0.toInt(), 0xfffbe3c4.toInt()), floatArrayOf(0f, .4f, .72f, 1f), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, h, paint)
            val sx = w * (.7f + .15f * Math.sin(t.toDouble()).toFloat()); val sy = h * .3f
            paint.shader = RadialGradient(sx, sy, w * .22f, 0xfffff2b0.toInt(), 0x00fff2b0, Shader.TileMode.CLAMP)
            c.drawCircle(sx, sy, w * .22f, paint)
            paint.shader = null
            ridge(c, w, h, .62f, 0xff2b2f6b.toInt(), 1.0f)
            ridge(c, w, h, .74f, 0xff171a3f.toInt(), 1.7f)
            type.color = 0x55ffffff
            var y = 60f; var row = 0
            while (y < h) {
                val off = (row * 37 + t * 40).rem(160f)
                c.drawText("NebulaAIOS · ColorOS 17 · liquid glass · 0123456789 ·".repeat(3), -off, y, type)
                y += 56f; row++
            }
            for (k in 0 until 12) {
                paint.color = if (k % 2 == 0) 0xffffd54f.toInt() else 0xff4fc3f7.toInt()
                c.drawRect(k * w / 12f, h * .9f, (k + .5f) * w / 12f, h * .93f, paint)
            }
            if (animate) { t += .016f; postInvalidateOnAnimation() }
        }

        private fun ridge(c: Canvas, w: Float, h: Float, base: Float, color: Int, freq: Float) {
            path.reset(); path.moveTo(0f, h)
            var x = 0f
            while (x <= w) {
                val y = h * base + h * .05f * Math.sin((x / w * 6.3f * freq + t * .5f).toDouble()).toFloat() +
                    h * .02f * Math.sin((x / w * 23f * freq).toDouble()).toFloat()
                path.lineTo(x, y); x += 8f
            }
            path.lineTo(w, h); path.close()
            paint.color = color
            c.drawPath(path, paint)
        }
    }
}
