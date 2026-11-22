package app.tcgportfolio.ui.scan

import app.tcgportfolio.scan.Box
import kotlin.math.max

/**
 * Geometry of the on-screen scan guide, shared by the overlay (view coordinates) and the
 * analyzer (camera-frame coordinates), so the scanner only reads what is inside the guide.
 */
object Guide {
    /** Guide rectangle in view pixels. */
    fun inView(viewW: Float, viewH: Float, binder: Boolean): Box {
        val gw: Float
        val gh: Float
        if (binder) {
            gw = viewW * 0.94f; gh = viewH * 0.62f
        } else {
            gw = viewW * 0.74f; gh = gw * 88f / 63f
        }
        val left = (viewW - gw) / 2f
        val top = (viewH - gh) / 2f - viewH * 0.04f
        return Box(left, top, left + gw, top + gh)
    }

    /**
     * The guide mapped into a camera frame of [frameW]×[frameH] shown with FILL_CENTER, grown by
     * [grow] (fraction of its size) to forgive imperfect framing.
     */
    fun inFrame(viewW: Int, viewH: Int, frameW: Int, frameH: Int, binder: Boolean, grow: Float = 0.08f): Box? {
        if (viewW <= 0 || viewH <= 0 || frameW <= 0 || frameH <= 0) return null
        val g = inView(viewW.toFloat(), viewH.toFloat(), binder)
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
