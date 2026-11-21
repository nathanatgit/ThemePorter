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
    var dim = 0f
    var lightX = 0.35f
    var lightY = -0.94f

    /** Called on the main thread when a new SDF is ready. */
    var onReady: (() -> Unit)? = null

    @Volatile private var sdf: TextSdf.Result? = null
    private var sdfShader: BitmapShader? = null
    private var builtKey: String? = null
    private var pendingKey: String? = null
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

    /** Rebuild the distance field when text, size or typeface change. Cheap if unchanged. */
    fun setText(text: CharSequence, paint: Paint) {
        val t = text.toString()
        val key = "$t|${paint.textSize}|${paint.typeface?.hashCode()}|${paint.textSkewX}|${paint.letterSpacing}|${paint.fontVariationSettings}"
        if (key == builtKey || key == pendingKey) return
        pendingKey = key
        val copy = Paint(paint).apply { isAntiAlias = true; color = Color.WHITE; style = Paint.Style.FILL }
        val size = paint.textSize
        val range = (max(max(size * bevelFactor, 3f), max(size * refractFactor, 4f)) * 1.35f + 6f).coerceIn(24f, 72f)
        worker.execute {
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
    fun draw(canvas: Canvas, glass: View, backdrop: View?, originX: Float, baseline: Float): Boolean {
        if (broken || !canvas.isHardwareAccelerated) return false
        if (GlassRegistry.isCapturing(glass)) return false
        val s = sdf ?: return false
        val bmp = sdfShader ?: return false
        val source = backdrop ?: glass.parent as? View ?: return false
        val w = glass.width
        val h = glass.height
        if (w <= 0 || h <= 0) return false
        val sh = shader ?: try {
            RuntimeShader(TEXT_LENS_AGSL).also { shader = it }
        } catch (e: Exception) {
            Log.e(TAG, "Text lens AGSL compile failed", e); broken = true; return false
        }
        val margin = (refract + blurRadius * 3f).toInt().coerceAtLeast(8)
        val offX = originX - s.originX
        val offY = baseline - s.originY
        val key = listOf(s, offX, offY, w, h, margin, bevel, refract, blurRadius, dispersion,
            specStrength, innerShadow, saturation, tint, dim, lightX, lightY)
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
        val dx = (loc[0] - srcLoc[0]).toFloat()
        val dy = (loc[1] - srcLoc[1]).toFloat()
        node.setPosition(-margin, -margin, w + margin, h + margin)
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

                float bandW = clamp(bevel * 0.3, 2.0, 9.0);
                float rim = clamp(1.0 - (-d - 0.5) / bandW, 0.0, 1.0) * cov;
                float lobe = 0.55 * pow(facingPos, 5.0) + 0.18 * pow(facingNeg, 5.0) + 0.05;
                float hair = clamp(1.0 - abs(d + 1.0) / 1.5, 0.0, 1.0);
                float spec = (rim * lobe + hair * (0.22 + 0.35 * facingPos)) * specStrength;
                col += float3(spec);

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
