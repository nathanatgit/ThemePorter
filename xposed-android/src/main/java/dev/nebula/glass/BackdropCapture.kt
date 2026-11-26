// Local changes: WebView backdrop cache removed; glass detection via GlassRegistry.
// Vendored from QWEA0/Liquid-Glass-Android@ea7908990247 (C:\Users\Han\AppData\Local/Temp/claude/cc9cfa003fc0e8f9/43acb8a8-3f69-4b1d-a212-96be8e25a804/scratchpad/lg/liquidglass/src/main/java/com/example/liquidglass/BackdropCapture.kt), MIT licence: third_party/liquidglass/LICENSE
/**
 * 背景捕获：把背景来源视图画进给定画布，并把玻璃自身排除掉
 *
 * 直接 `source.draw(canvas)` 只在玻璃是 source 的**直接子级**时安全。
 * source 是更高层级的祖先时，source → 玻璃 这条路径上的中间容器有两个问题：
 *
 * 1. **录制重入**：中间容器的 RenderNode 此刻正在 beginRecording 里（玻璃的
 *    onDraw 就发生在它的录制过程中），而硬件画布上 dispatchDraw → drawChild
 *    走的是 updateDisplayListIfDirty，会对同一个节点再次 beginRecording，
 *    抛 IllegalStateException 直接崩溃。
 * 2. **引用成环**：drawChild 记录的是中间容器 RenderNode 的**引用**而不是像素，
 *    而这个节点又引用着玻璃自己的节点，于是形成
 *    玻璃 → 背景 → 中间容器 → 玻璃 的环，光栅化时无限递归。
 *
 * 所以路径上的每一层都改用公开的 [View.draw]：这条路不碰该 View 自己的
 * RenderNode（既不重入也不成环），dispatchDraw 实时执行，被临时置为 INVISIBLE
 * 的下一层会被跳过。每层的定位取屏幕坐标差，各级滚动与平移自动带上。
 *
 * 已知取舍（只影响 source 是跨层级祖先的情况）：
 * - 路径上的容器带缩放/旋转/透明度时，这些变换不会作用到它的内容上；
 * - 玻璃所在的分支单独补画在最后，同层里排在它之后的兄弟视图会被它盖住。
 *
 * **嵌套采样只允许一层。** 采样时若录制区盖到了别的玻璃（邻居离得比采样外扩还近），
 * 硬件画布会顺手重录那块玻璃的显示列表，它的 onDraw 又去采样父容器、又画到自己的
 * 邻居……层数随互相靠近的玻璃数指数增长（15 块动态玻璃一帧要录上万次）。所以一旦
 * 发现自己已经处在别的玻璃的采样里，就把 host 下其余的玻璃一并藏掉：这一层录出来的
 * 邻居玻璃，其采样区里只剩背景，没有别的玻璃。玻璃本来就不该折射玻璃，视觉上无损。
 *
 * 只藏 host 的**直接子级**里的玻璃，不钻进中间容器：host 走的是公开的 [View.draw]，不会被
 * 记成"已刷新"；中间容器却会在录制中被顺带刷新——它因为里面玻璃的 invalidate 正等着
 * 刷新，框架刷新时跳过不可见的子级、把容器标成已刷新，被藏的玻璃挂着的那次重绘就丢了，
 * 之后它自己的 invalidate 也不再往上传，从此冻在旧的一帧（ChipGroup / ListGroup 里的
 * 玻璃都会这样）。而容器不脏时画的是它缓存的显示列表，藏里面的玻璃本来也不起作用。
 * 容器里的玻璃于是可能在嵌套里被重录一次，它自己的采样照样只藏它那个 host 的直接子级；
 * 采样路径上的祖先链由 [drawLevel] 逐层用公开 draw 画、把分支藏掉，不会成环。
 */
package dev.nebula.glass

import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup

class BackdropCapture {

    companion object {
        /** 进行中的采样层数（只在主线程改）。> 0 说明当前绘制发生在某块玻璃的采样里 */
        var depth = 0
            private set

        val isActive: Boolean get() = depth > 0
    }

    // 复用，避免每帧分配
    private val hostLocation = IntArray(2)
    private val childLocation = IntArray(2)
    private val nestedHidden = ArrayList<View>()

    /**
     * 把 [source] 的内容画进 [canvas]，跳过 [glass]
     *
     * @param canvas 画布原点需已对齐 [source] 左上角
     */
    fun draw(canvas: Canvas, source: View, glass: View) {
        drawLevel(canvas, source, glass)
    }

    fun release() {
    }

    private fun drawLevel(canvas: Canvas, host: View, glass: View) {
        val branch = childOnPathTo(host, glass)
        // When host is an unattached/external backdrop (branch == null && host !== glass), glass is
        // not inside host's subtree at all — never mutate glass's visibility flags mid-draw!
        val toHide = if (host === glass) glass else branch
        drawContent(canvas, host, toHide)
        if (branch == null || branch === glass) return

        // 分支刚才被跳过了，这里单独补画（坐标取屏幕差，自带滚动/平移）
        host.getLocationOnScreen(hostLocation)
        branch.getLocationOnScreen(childLocation)
        val dx = (childLocation[0] - hostLocation[0]).toFloat()
        val dy = (childLocation[1] - hostLocation[1]).toFloat()
        val clip = (host as? ViewGroup)?.clipChildren != false

        val save = canvas.save()
        if (clip) canvas.clipRect(0f, 0f, host.width.toFloat(), host.height.toFloat())
        canvas.translate(dx, dy)
        if (clip) canvas.clipRect(0f, 0f, branch.width.toFloat(), branch.height.toFloat())
        drawLevel(canvas, branch, glass)
        canvas.restoreToCount(save)
    }

    private fun drawContent(canvas: Canvas, host: View, hidden: View?) {
        val save = canvas.save()
        canvas.translate(-host.scrollX.toFloat(), -host.scrollY.toFloat())
        hidden?.setTransitionVisibility(View.INVISIBLE)
        val nested = depth > 0
        if (nested) hideOtherGlass(host, hidden)
        depth++
        try {
            host.draw(canvas)
        } finally {
            depth--
            if (nested) {
                for (v in nestedHidden) v.setTransitionVisibility(View.VISIBLE)
                nestedHidden.clear()
            }
            hidden?.setTransitionVisibility(View.VISIBLE)
            canvas.restoreToCount(save)
        }
    }

    private fun hideOtherGlass(host: View, except: View?) {
        if (host !is ViewGroup) return
        for (i in 0 until host.childCount) {
            val child = host.getChildAt(i)
            if (child === except || !GlassRegistry.isGlass(child)) continue
            if (child.visibility == View.VISIBLE) {
                child.setTransitionVisibility(View.INVISIBLE)
                nestedHidden.add(child)
            }
        }
    }

    /**
     * [host] 的直接子级里通往 [descendant] 的那一个；
     * [host] 不是 [descendant] 的祖先时返回 null
     */
    private fun childOnPathTo(host: View, descendant: View): View? {
        var child: View = descendant
        var p = descendant.parent
        while (p is View) {
            if (p === host) return child
            child = p
            p = p.parent
        }
        return null
    }
}
