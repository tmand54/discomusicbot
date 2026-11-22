package app.snapstack.ui.scan

import app.snapstack.scan.Box
import kotlin.math.max

/**
 * Geometry of the on-screen scan guide, shared by the overlay (view coordinates) and the
 * analyzer (camera-frame coordinates), so the scanner only reads what is inside the guide.
 */
enum class GuideKind { CARD, BINDER, SLAB }

object Guide {
    /** A graded slab (PSA / BGS / TAG…) is about 3.3 × 5.4 in, label on top, card below. */
    private const val SLAB_ASPECT = 5.4f / 3.3f

    /** Guide rectangle in view pixels. */
    fun inView(viewW: Float, viewH: Float, kind: GuideKind): Box {
        var gw: Float
        var gh: Float
        when (kind) {
            GuideKind.BINDER -> { gw = viewW * 0.94f; gh = viewH * 0.62f }
            GuideKind.SLAB -> {
                gw = viewW * 0.72f; gh = gw * SLAB_ASPECT
                if (gh > viewH * 0.68f) { gh = viewH * 0.68f; gw = gh / SLAB_ASPECT }
            }
            GuideKind.CARD -> { gw = viewW * 0.74f; gh = gw * 88f / 63f }
        }
        val left = (viewW - gw) / 2f
        val top = (viewH - gh) / 2f - viewH * 0.04f
        return Box(left, top, left + gw, top + gh)
    }

    fun inView(viewW: Float, viewH: Float, binder: Boolean): Box = inView(viewW, viewH, if (binder) GuideKind.BINDER else GuideKind.CARD)

    /** Where the card sits inside a slab guide (below the label), for artwork matching. */
    fun cardInSlab(slab: Box): Box = Box(slab.left + slab.w * 0.08f, slab.top + slab.h * 0.31f, slab.right - slab.w * 0.08f, slab.bottom - slab.h * 0.04f)

    /**
     * The guide mapped into a camera frame of [frameW]×[frameH] shown with FILL_CENTER, grown by
     * [grow] (fraction of its size) to forgive imperfect framing.
     */
    fun inFrame(viewW: Int, viewH: Int, frameW: Int, frameH: Int, binder: Boolean, grow: Float = 0.08f): Box? =
        inFrame(viewW, viewH, frameW, frameH, if (binder) GuideKind.BINDER else GuideKind.CARD, grow)

    fun inFrame(viewW: Int, viewH: Int, frameW: Int, frameH: Int, kind: GuideKind, grow: Float = 0.08f): Box? {
        if (viewW <= 0 || viewH <= 0 || frameW <= 0 || frameH <= 0) return null
        val g = inView(viewW.toFloat(), viewH.toFloat(), kind)
        val scale = max(viewW.toFloat() / frameW, viewH.toFloat() / frameH)
        val dx = (viewW - frameW * scale) / 2f
        val dy = (viewH - frameH * scale) / 2f
        val l = (g.left - dx) / scale
        val t = (g.top - dy) / scale
        val r = (g.right - dx) / scale
        val b = (g.bottom - dy) / scale
        val gx = (r - l) * grow
        val gy = (b - t) * grow
        return Box(
            (l - gx).coerceAtLeast(0f), (t - gy).coerceAtLeast(0f),
            (r + gx).coerceAtMost(frameW.toFloat()), (b + gy).coerceAtMost(frameH.toFloat()),
        )
    }

    fun contains(region: Box?, box: Box?): Boolean =
        region == null || box == null || (box.cx in region.left..region.right && box.cy in region.top..region.bottom)
}
