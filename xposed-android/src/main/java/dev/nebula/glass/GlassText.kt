package dev.nebula.glass

import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import java.util.concurrent.Executors
import kotlin.math.max

/**
 * Liquid Glass text: the lens with `sceneSDF` sampled from a [TextSdf] distance field.
 *
 * Shader and tuned defaults come from upstream `app/.../GlassTextPrototypeView.kt`
 * (QWEA0/Liquid-Glass-Android@ea7908990247, MIT). Local changes: not a View; the SDF
 * is baked on a worker thread; the glyph box is positioned at an explicit text origin
 * so a host TextView keeps its own layout, time updates and accessibility.
 */
class GlassText {
    var bevelFactor = 0.02f
    var refractFactor = 0.45f
    var blurRadius = 20f
    var dispersion = 0.14f
    var specStrength = 2.49f
    var innerShadow = 1.47f
    var saturation = 130f
    var tint = Color.argb(58, 255, 255, 255)
    var edgeColor = Color.WHITE
    var glowColor = Color.WHITE
    var dim = 0f
    var lightX = 0.35f
    var lightY = -0.94f

    /** Called on the main thread when a new SDF is ready. */
    var onReady: (() -> Unit)? = null

    @Volatile private var sdf: TextSdf.Result? = null
    private var sdfShader: BitmapShader? = null
    private var builtKey: String? = null
    @Volatile private var pendingKey: String? = null
    private var textSize = 0f

    private val node = RenderNode("NebulaGlassText")
    private var shader: RuntimeShader? = null
    private var broken = Build.VERSION.SDK_INT < 33
    private var effectKey: List<Any>? = null
    private val loc = IntArray(2)
    private val srcLoc = IntArray(2)

    val isAvailable: Boolean get() = !broken
    val isReady: Boolean get() = sdf != null

    /** Explicit bevel / refraction in px (mask glass); null = derived from the text size. */
    var bevelPx: Float? = null
    var refractPx: Float? = null
    private val bevel get() = bevelPx ?: max(textSize * bevelFactor, 3f)
    private val refract get() = refractPx ?: max(textSize * refractFactor, 4f)

    /**
     * Glass shaped like an arbitrary alpha mask (e.g. a picture-only widget's tiles). Built off the
     * main thread; [key] identifies the mask content (rebuilt only when it changes). The mask's
     * (0,0) is the origin passed to [draw].
     */
    fun setMask(mask: android.graphics.Bitmap, key: String, threshold: Int = 16, downscale: Int = 2) {
        if (key == builtKey || key == pendingKey) return
        pendingKey = key
        val copy = mask.copy(android.graphics.Bitmap.Config.ARGB_8888, false) ?: return
        val range = (max(bevelPx ?: 6f, refractPx ?: 8f) * 1.35f + 6f).coerceIn(24f, 72f)
        worker.execute {
            val t0 = System.nanoTime()
            val result = try { TextSdf.buildFromMask(copy, threshold, range, downscale) } catch (e: Throwable) { Log.w(TAG, "mask SDF failed", e); null }
            copy.recycle()
            main.post {
                if (pendingKey != key) { result?.bitmap?.recycle(); return@post }
                pendingKey = null
                val old = sdf
                sdf = result
                textSize = 0f
                builtKey = key
                sdfShader = result?.let {
                    BitmapShader(it.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        filterMode = BitmapShader.FILTER_MODE_NEAREST
                    }
                }
                effectKey = null
                old?.bitmap?.recycle()
                Log.i(TAG, "mask SDF ${result?.bitmap?.width}x${result?.bitmap?.height} in ${(System.nanoTime() - t0) / 1_000_000}ms")
                onReady?.invoke()
            }
        }
    }

    /**
     * Upstream's optics are for display digits. On small text (the date, lunar date) the inner shadow
     * and dispersion are wider than a stroke and doubled every one, so they fade out as text shrinks.
     * The lit edge and a see-through body stay: they are what makes it read as glass.
     * @param bodyColour the glyphs' colour; its alpha is scaled by the body share
     * @param luma of the backdrop, 0..1
     * @return the body colour for this size
     */
    fun tuneFor(
        size: Float,
        bodyColour: Int,
        luma: Float,
        glow: Float,
        refraction: Float = 1f,
        dispersionScale: Float = 1f,
        vibrancy: Float = 1f,
    ): Int {
        // Upstream scales refraction/bevel with text size; cap in px and scale by user's Refraction control.
        refractFactor = (minOf(0.45f, 28f / size) * refraction).coerceIn(0f, 1.2f)
        bevelFactor = (minOf(0.02f, 8f / size) * (0.5f + 0.5f * refraction)).coerceIn(0.002f, 0.15f)
        val display = ((size - SMALL_TEXT_PX) / (DISPLAY_TEXT_PX - SMALL_TEXT_PX)).coerceIn(0f, 1f)
        innerShadow = INNER_SHADOW * display
        dispersion = (DISPERSION * display * dispersionScale).coerceIn(0f, 0.85f)
        saturation = (100f + 30f * vibrancy).coerceIn(0f, 300f)
        specStrength = (if (luma > 0.5f) 1.6f else 2.49f) * glow * (SMALL_EDGE + (1 - SMALL_EDGE) * display)
        val share = DISPLAY_BODY + (SMALL_BODY - DISPLAY_BODY) * (1 - display)
        return Color.argb((share * Color.alpha(bodyColour)).toInt(), Color.red(bodyColour), Color.green(bodyColour), Color.blue(bodyColour))
    }

    /** Glass in the shape of display digits ([ClockFace]). [path]'s (0,0) is the origin passed to [draw]. */
    fun setShape(path: android.graphics.Path, key: String, stroke: Float, refraction: Float = 1f) {
        // Ultra-thin iOS hairline strokes (down to ~5 px) keep proportional bevel & refraction without folding.
        bevelPx = (minOf(8f, stroke * 0.32f) * (0.5f + 0.5f * refraction)).coerceAtLeast(1.5f)
        refractPx = (minOf(28f, stroke * 0.82f) * refraction).coerceAtLeast(2.2f)
        val id = "shape|$key"
        if (id == builtKey || id == pendingKey) return
        pendingKey = id
        val copy = android.graphics.Path(path)
        val range = (max(bevel, refract) * 1.35f + 6f).coerceIn(24f, 72f)
        val height = android.graphics.RectF().also { copy.computeBounds(it, true) }.height()
        val supersample = if (height > LARGE_SHAPE_PX) 1 else TextSdf.SUPERSAMPLE
        worker.execute {
            if (pendingKey != id) return@execute   // superseded while queued
            val t0 = System.nanoTime()
            val result = try { TextSdf.buildPath(copy, range, supersample) } catch (e: Throwable) { Log.w(TAG, "shape SDF failed", e); null }
            main.post {
                if (pendingKey != id) { result?.bitmap?.recycle(); return@post }
                pendingKey = null
                val old = sdf
                sdf = result
                builtKey = id
                sdfShader = result?.let {
                    BitmapShader(it.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        filterMode = BitmapShader.FILTER_MODE_NEAREST
                    }
                }
                effectKey = null
                old?.bitmap?.recycle()
                Log.i(TAG, "shape SDF ${result?.bitmap?.width}x${result?.bitmap?.height} in ${(System.nanoTime() - t0) / 1_000_000}ms")
                onReady?.invoke()
            }
        }
    }

    /** True once the field for [key] is the one [draw] would use. */
    fun hasShape(key: String) = builtKey == "shape|$key" && sdf != null

    private fun textKey(t: String, paint: Paint) =
        "$t|${paint.textSize}|${paint.typeface?.hashCode()}|${paint.textSkewX}|${paint.letterSpacing}|${paint.fontVariationSettings}"

    /** True once the SDF for the current text and font size has finished building. */
    fun hasText(text: CharSequence, paint: Paint) = builtKey == textKey(text.toString(), paint) && sdf != null

    /** Rebuild the distance field when text, size or typeface change. Cheap if unchanged. */
    fun setText(text: CharSequence, paint: Paint) {
        val t = text.toString()
        val key = textKey(t, paint)
        if (key == builtKey || key == pendingKey) return
        pendingKey = key
        val copy = Paint(paint).apply { isAntiAlias = true; color = Color.WHITE; style = Paint.Style.FILL }
        val size = paint.textSize
        val range = (max(max(size * bevelFactor, 3f), max(size * refractFactor, 4f)) * 1.35f + 6f).coerceIn(24f, 72f)
        worker.execute {
            if (pendingKey != key) return@execute   // superseded while queued (a size being dragged)
            val t0 = System.nanoTime()
            val result = try { TextSdf.build(t, copy, range) } catch (e: Throwable) { Log.w(TAG, "SDF failed", e); null }
            main.post {
                if (pendingKey != key) { result?.bitmap?.recycle(); return@post }
                pendingKey = null
                val old = sdf
                sdf = result
                textSize = size
                builtKey = key
                sdfShader = result?.let {
                    BitmapShader(it.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        filterMode = BitmapShader.FILTER_MODE_NEAREST
                    }
                }
                effectKey = null
                old?.bitmap?.recycle()
                Log.d(TAG, "SDF \"$t\" @${size}px ${result?.bitmap?.width}x${result?.bitmap?.height} in ${(System.nanoTime() - t0) / 1_000_000}ms")
                onReady?.invoke()
            }
        }
    }

    /**
     * Draws the glass glyphs with the text origin (x = 0, baseline) at ([originX], [baseline])
     * in [glass]-local coordinates. Returns false when the caller must draw stock text.
     */
    fun draw(
        canvas: Canvas, glass: View, backdrop: View?, originX: Float, baseline: Float,
        /** The area the glyphs may cover, in [glass]'s coordinates; null = the view. May reach past it. */
        area: android.graphics.Rect? = null,
    ): Boolean {
        if (broken || !canvas.isHardwareAccelerated) return false
        // Never overwrite `node` while another glass surface (e.g. a lock-screen notification card or
        // long-press menu) is capturing the scene behind itself: let the host draw fast vector glyphs
        // into that blurred backdrop so `node`'s live display list never tears or flickers!
        if (GlassRegistry.isCapturing(glass) || BackdropCapture.isActive) return false
        val s = sdf ?: return false
        val bmp = sdfShader ?: return false
        val source = backdrop ?: glass.parent as? View ?: return false
        val left = area?.left ?: 0
        val top = area?.top ?: 0
        val w = area?.width() ?: glass.width
        val h = area?.height() ?: glass.height
        if (w <= 0 || h <= 0) return false
        val sh = shader ?: try {
            RuntimeShader(TEXT_LENS_AGSL).also { shader = it }
        } catch (e: Exception) {
            Log.e(TAG, "Text lens AGSL compile failed", e); broken = true; return false
        }
        val margin = (refract + blurRadius * 3f).toInt().coerceAtLeast(8)
        val offX = originX - left - s.originX
        val offY = baseline - top - s.originY
        val key = listOf(s, offX, offY, w, h, margin, bevel, refract, blurRadius, dispersion,
            specStrength, innerShadow, saturation, tint, edgeColor, glowColor, dim, lightX, lightY)
        if (key != effectKey) {
            try {
                sh.setInputShader("sdfTex", bmp)
                sh.setFloatUniform("sdfSize", s.bitmap.width.toFloat(), s.bitmap.height.toFloat())
                sh.setFloatUniform("sdfOffset", offX, offY)
                sh.setFloatUniform("sdfScale", s.scale.toFloat())
                sh.setFloatUniform("sdfRange", s.range)
                sh.setFloatUniform("margin", margin.toFloat())
                sh.setFloatUniform("viewSize", w.toFloat(), h.toFloat())
                sh.setFloatUniform("bevel", bevel)
                sh.setFloatUniform("refractPx", refract)
                sh.setFloatUniform("dispersion", dispersion)
                sh.setFloatUniform("lightDir", lightX, lightY)
                sh.setFloatUniform("specStrength", specStrength)
                sh.setFloatUniform("innerShadow", innerShadow)
                sh.setFloatUniform("edgeColor", Color.red(edgeColor) / 255f, Color.green(edgeColor) / 255f, Color.blue(edgeColor) / 255f)
                sh.setFloatUniform("edgeColored", if ((edgeColor and 0x00FFFFFF) != 0x00FFFFFF) 1f else 0f)
                sh.setFloatUniform("glowColor", Color.red(glowColor) / 255f, Color.green(glowColor) / 255f, Color.blue(glowColor) / 255f)
                sh.setFloatUniform("glowColored", if ((glowColor and 0x00FFFFFF) != 0x00FFFFFF) 1f else 0f)
                sh.setFloatUniform("tintColor", Color.red(tint) / 255f, Color.green(tint) / 255f,
                    Color.blue(tint) / 255f, Color.alpha(tint) / 255f)
                sh.setFloatUniform("dimAmount", dim)
                sh.setFloatUniform("satFactor", saturation / 100f)
                val lens = RenderEffect.createRuntimeShaderEffect(sh, "content")
                node.setRenderEffect(
                    if (blurRadius > 0.01f) RenderEffect.createChainEffect(lens,
                        RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
                    else lens
                )
            } catch (e: Exception) {
                Log.e(TAG, "Text lens effect failed", e); broken = true; return false
            }
            effectKey = key
        }
        glass.getLocationOnScreen(loc)
        source.getLocationOnScreen(srcLoc)
        val dx = (loc[0] - srcLoc[0] + left).toFloat()
        val dy = (loc[1] - srcLoc[1] + top).toFloat()
        node.setPosition(left - margin, top - margin, left + w + margin, top + h + margin)
        val rc = node.beginRecording(w + 2 * margin, h + 2 * margin)
        try {
            rc.translate(margin - dx, margin - dy)
            GlassRegistry.capture(glass) { capture.draw(rc, source, glass) }
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
        return true
    }

    fun release() {
        pendingKey = null
        builtKey = null
        node.discardDisplayList()
        capture.release()
        sdf?.bitmap?.recycle()
        sdf = null
        sdfShader = null
        effectKey = null
    }

    private val capture = BackdropCapture()

    companion object {
        private const val TAG = "NebulaGlass"
        private const val LARGE_SHAPE_PX = 420f
        private const val SMALL_TEXT_PX = 48f
        private const val DISPLAY_TEXT_PX = 140f
        private const val INNER_SHADOW = 1.47f
        private const val DISPERSION = 0.14f
        /** Share of the text colour in the body of display digits; the rest is refracted wallpaper. */
        private const val DISPLAY_BODY = 0.62f
        private const val SMALL_BODY = 0.66f
        /** Share of the lit edge small text keeps. */
        private const val SMALL_EDGE = 0.75f
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "NebulaGlassSdf").apply { isDaemon = true } }
        private val main = Handler(Looper.getMainLooper())

        // Upstream TEXT_LENS_AGSL, unchanged.
        private const val TEXT_LENS_AGSL = """
            uniform shader content;
            uniform shader sdfTex;
            uniform float2 sdfSize;
            uniform float2 sdfOffset;
            uniform float  sdfScale;
            uniform float  sdfRange;
            uniform float  margin;
            uniform float2 viewSize;
            uniform float  bevel;
            uniform float  refractPx;
            uniform float  dispersion;
            uniform float2 lightDir;
            uniform float  specStrength;
            uniform float  innerShadow;
            uniform float3 edgeColor;
            uniform float  edgeColored;
            uniform float3 glowColor;
            uniform float  glowColored;
            uniform float4 tintColor;
            uniform float  dimAmount;
            uniform float  satFactor;

            // sqrt 压缩编码的逆变换：近场码字密、边缘才不会台阶化
            float decodeSdf(float2 texel) {
                float s = 2.0 * sdfTex.eval(texel).r - 1.0;
                return s * abs(s) * sdfRange;
            }

            float sceneSDF(float2 p) {
                // 距离场是超采样分辨率，视图像素 → 纹素要乘 sdfScale
                float2 uv = (p - sdfOffset) * sdfScale;
                // 纹理之外一律视为远在形状外部
                if (uv.x < 0.0 || uv.y < 0.0 || uv.x > sdfSize.x || uv.y > sdfSize.y) {
                    return sdfRange;
                }
                // 自己做双线性：插值的必须是解码后的距离。
                // 直接让采样器线性插值编码值在 sqrt 压缩下是有偏的，
                // 且 RenderEffect 里子着色器的 filterMode 是否生效不可依赖
                float2 t = uv - 0.5;
                float2 f = fract(t);
                float2 b = floor(t) + 0.5;
                float d00 = decodeSdf(b);
                float d10 = decodeSdf(b + float2(1.0, 0.0));
                float d01 = decodeSdf(b + float2(0.0, 1.0));
                float d11 = decodeSdf(b + float2(1.0, 1.0));
                return mix(mix(d00, d10, f.x), mix(d01, d11, f.x), f.y);
            }

            half4 main(float2 coord) {
                float2 p = coord - float2(margin, margin);
                float d = sceneSDF(p);

                // 距离场已是亚像素精度，1px 斜坡即可，不必像圆角矩形那样羽化 1.5px
                float cov = clamp(0.5 - d, 0.0, 1.0);
                if (cov <= 0.004) {
                    return half4(0.0);
                }

                float2 n = float2(
                    sceneSDF(p + float2(1.0, 0.0)) - sceneSDF(p - float2(1.0, 0.0)),
                    sceneSDF(p + float2(0.0, 1.0)) - sceneSDF(p - float2(0.0, 1.0))
                );
                float nLen = length(n);
                if (nLen > 0.0001) {
                    n = n / nLen;
                } else {
                    n = float2(0.0, -1.0);
                }

                float t = clamp(-d / max(bevel, 1.0), 0.0, 1.0);
                float edge = 1.0 - t;
                float slope = edge * edge;

                // 只能向内采样：RenderEffect 的子输入只保证输出裁剪区内可采
                float2 offset = -n * (slope * refractPx);

                float2 cR = coord + offset * (1.0 - dispersion * slope);
                float2 cG = coord + offset;
                float2 cB = coord + offset * (1.0 + dispersion * slope);

                float2 lo = float2(margin + 1.0, margin + 1.0);
                float2 hi = lo + viewSize - float2(2.0, 2.0);
                cR = clamp(cR, lo, hi);
                cG = clamp(cG, lo, hi);
                cB = clamp(cB, lo, hi);
                float3 col = float3(
                    content.eval(cR).r,
                    content.eval(cG).g,
                    content.eval(cB).b
                );

                float lum = dot(col, float3(0.2126, 0.7152, 0.0722));
                if (satFactor <= 1.0) {
                    col = mix(float3(lum), col, satFactor);
                } else {
                    float satNow = max(col.r, max(col.g, col.b)) - min(col.r, min(col.g, col.b));
                    float room = 1.0 - smoothstep(0.2, 0.85, satNow);
                    float hl = 1.0 - smoothstep(0.75, 0.98, lum);
                    float amount = 1.0 + (satFactor - 1.0) * mix(0.3, 1.0, room * hl);
                    col = clamp(mix(float3(lum), col, amount), float3(0.0), float3(1.0));
                }

                col = mix(col, tintColor.rgb, tintColor.a);
                col = col * (1.0 - dimAmount);

                float facing = dot(n, -lightDir);
                float facingPos = max(facing, 0.0);
                float facingNeg = max(-facing, 0.0);

                float bandW = (glowColored > 0.5) ? clamp(bevel * 0.85, 5.0, 18.0) : clamp(bevel * 0.3, 2.0, 9.0);
                float rim = clamp(1.0 - (-d - 0.5) / bandW, 0.0, 1.0) * cov;
                float lobe = 0.55 * pow(facingPos, 4.0) + 0.22 * pow(facingNeg, 4.0) + 0.08;
                if (glowColored > 0.5) {
                    float gAmt = clamp(rim * lobe * specStrength * 0.55, 0.0, 0.85);
                    col = mix(col, glowColor, gAmt) + glowColor * (gAmt * 0.16);
                } else {
                    col += glowColor * (rim * lobe * specStrength);
                }
                float hairW = (edgeColored > 0.5) ? 2.4 : 1.5;
                float hair = clamp(1.0 - abs(d + 1.0) / hairW, 0.0, 1.0);
                if (edgeColored > 0.5) {
                    float eAmt = clamp(hair * (0.34 + 0.45 * facingPos) * specStrength * 0.65, 0.0, 0.95);
                    col = mix(col, edgeColor, eAmt) + edgeColor * (eAmt * 0.18);
                } else {
                    col += edgeColor * (hair * (0.22 + 0.35 * facingPos) * specStrength);
                }

                float shadowW = clamp(bevel, 4.0, 28.0);
                float shadowBand = pow(clamp(1.0 + d / shadowW, 0.0, 1.0), 1.5);
                float ish = shadowBand * facingNeg * innerShadow;
                col = col * (1.0 - 0.45 * ish);

                col = clamp(col, float3(0.0), float3(1.0));
                return half4(half3(col * cov), half(cov));
            }
        """
    }
}
