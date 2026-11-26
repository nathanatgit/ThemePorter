// Vendored from QWEA0/Liquid-Glass-Android@ea7908990247 (LightSourceController.kt), MIT licence: third_party/liquidglass/LICENSE
/**
 * 传感器驱动的全局光源方向控制器
 *
 * Apple Liquid Glass 的镜面高光会随设备姿态移动（光源固定在"世界"里，
 * 玻璃倾斜时高光跟着走）。这里用重力传感器还原同样的行为。
 *
 * Local changes (nebulaColorize):
 *  - [tiltX]/[tiltY]: the smoothed in-screen "down" direction, neutral (0, 1) when upright or
 *    flat. [GlassStyle] rotates the user's static Glow angle by it, so "Follow device tilt" keeps
 *    the chosen angle when the phone is upright and sweeps the lit rims as it tilts/rotates.
 *  - Callback clients ([addRedraw] + [start]) for hooked processes, whose glass lives in
 *    drawables rather than views. The sensor only runs while the screen is on and at least one
 *    look has "Follow device tilt" switched on; otherwise it is unregistered (no battery cost).
 */
package dev.nebula.glass

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.display.DisplayManager
import android.view.Display
import android.view.Surface
import android.view.View
import kotlin.math.abs
import kotlin.math.sqrt

object LightSourceController : SensorEventListener {

    /** Default light direction (no sensor / lying flat): upper-left, as in iOS 26. */
    const val DEFAULT_X = 0.866f
    const val DEFAULT_Y = 0.5f

    private const val SMOOTHING = 0.14f
    private const val NOTIFY_EPSILON = 0.006f

    @Volatile var lightDirX = DEFAULT_X
        private set
    @Volatile var lightDirY = DEFAULT_Y
        private set

    /** Smoothed in-screen gravity direction (view coordinates, y down); (0, 1) = upright. */
    @Volatile var tiltX = 0f
        private set
    @Volatile var tiltY = 1f
        private set

    private val listeners = LinkedHashSet<View>()
    private val redraws = ArrayList<() -> Unit>()
    private var wanted: (() -> Boolean)? = null
    private var appCtx: Context? = null
    private var screenOn = true
    private var sensorManager: SensorManager? = null
    private var registered = false
    private var lastX = 0f
    private var lastY = 1f

    /** Rotates the static light vector ([bx], [by]) by the current tilt. */
    fun rotateX(bx: Float, by: Float): Float = bx * tiltY + by * tiltX
    fun rotateY(bx: Float, by: Float): Float = -bx * tiltX + by * tiltY

    /** A view that redraws on tilt while attached (settings previews). */
    fun register(view: View) {
        synchronized(listeners) { if (!listeners.add(view)) return }
        if (appCtx == null) appCtx = view.context.applicationContext
        update()
    }

    fun unregister(view: View) {
        synchronized(listeners) { listeners.remove(view) }
        update()
    }

    /** A redraw callback (main thread), run when the tilt changes noticeably. */
    fun addRedraw(r: () -> Unit) { synchronized(redraws) { redraws.add(r) } }

    fun removeRedraw(r: () -> Unit) { synchronized(redraws) { redraws.remove(r) } }

    /**
     * Starts following tilt in this process while [want] is true (re-checked by [update]).
     * Main thread. Safe to call more than once; the latest predicate wins.
     */
    fun start(ctx: Context, want: () -> Boolean) {
        wanted = want
        if (appCtx == null) appCtx = ctx.applicationContext
        if (!screenReceiver) {
            screenReceiver = true
            try {
                ctx.applicationContext.registerReceiver(object : BroadcastReceiver() {
                    override fun onReceive(c: Context, i: Intent) {
                        screenOn = i.action != Intent.ACTION_SCREEN_OFF
                        update()
                    }
                }, IntentFilter().apply {
                    addAction(Intent.ACTION_SCREEN_ON)
                    addAction(Intent.ACTION_SCREEN_OFF)
                }, Context.RECEIVER_NOT_EXPORTED)
            } catch (_: Throwable) {}
        }
        update()
    }

    private var screenReceiver = false

    /** Registers or unregisters the sensor to match the current need. Main thread. */
    fun update() {
        val needViews = synchronized(listeners) { listeners.isNotEmpty() }
        val need = needViews || (screenOn && try { wanted?.invoke() == true } catch (_: Throwable) { false })
        val ctx = appCtx ?: return
        if (need && !registered) {
            val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as? SensorManager ?: return
            val sensor = sm.getDefaultSensor(Sensor.TYPE_GRAVITY)
                ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_UI)
            sensorManager = sm
            registered = true
        } else if (!need && registered) {
            sensorManager?.unregisterListener(this)
            sensorManager = null
            registered = false
        }
    }

    private fun rotation(): Int {
        synchronized(listeners) { listeners.firstOrNull()?.display?.rotation?.let { return it } }
        return try {
            (appCtx?.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
                ?.getDisplay(Display.DEFAULT_DISPLAY)?.rotation ?: Surface.ROTATION_0
        } catch (_: Throwable) { Surface.ROTATION_0 }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val gx = event.values[0]
        val gy = event.values[1]
        val ux: Float
        val uy: Float
        when (rotation()) {
            Surface.ROTATION_90 -> { ux = -gy; uy = gx }
            Surface.ROTATION_180 -> { ux = -gx; uy = -gy }
            Surface.ROTATION_270 -> { ux = gy; uy = -gx }
            else -> { ux = gx; uy = gy }
        }
        val planar = sqrt(ux * ux + uy * uy)
        val tilt = (planar / SensorManager.GRAVITY_EARTH).coerceIn(0f, 1f)

        // In-screen "down" (light travel direction when light comes from above), view coordinates.
        var dx = 0f
        var dy = 1f
        var lx = DEFAULT_X
        var ly = DEFAULT_Y
        if (planar > 0.5f) {
            val sx = -ux / planar
            val sy = uy / planar
            dx = sx * tilt
            dy = sy * tilt + (1f - tilt)
            lx = sx * tilt + DEFAULT_X * (1f - tilt)
            ly = sy * tilt + DEFAULT_Y * (1f - tilt)
        }
        fun norm(x: Float, y: Float): Pair<Float, Float> {
            val l = sqrt(x * x + y * y)
            return if (l > 1e-4f) (x / l) to (y / l) else 0f to 1f
        }
        val (tdx, tdy) = norm(dx, dy)
        val (nx, ny) = norm(tiltX + (tdx - tiltX) * SMOOTHING, tiltY + (tdy - tiltY) * SMOOTHING)
        tiltX = nx; tiltY = ny
        val (tlx, tly) = norm(lx, ly)
        val (mx, my) = norm(lightDirX + (tlx - lightDirX) * SMOOTHING, lightDirY + (tly - lightDirY) * SMOOTHING)
        lightDirX = mx; lightDirY = my

        if (abs(nx - lastX) > NOTIFY_EPSILON || abs(ny - lastY) > NOTIFY_EPSILON) {
            lastX = nx; lastY = ny
            synchronized(listeners) { listeners.forEach { it.postInvalidateOnAnimation() } }
            val rs = synchronized(redraws) { redraws.toList() }
            for (r in rs) try { r() } catch (_: Throwable) {}
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
