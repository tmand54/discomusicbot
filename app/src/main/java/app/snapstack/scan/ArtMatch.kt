package app.snapstack.scan

import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * A tiny colour picture of a card (e.g. 36×50 cells, RGB 0..255), sampled from the camera or from a
 * catalog thumbnail. Small enough to build in well under a millisecond, big enough to tell a parallel
 * from the regular art, or a gold DON!! from a red one.
 */
class CardThumb(val cols: Int, val rows: Int, val r: FloatArray, val g: FloatArray, val b: FloatArray) {
    init {
        require(r.size == cols * rows && g.size == r.size && b.size == r.size)
    }

    companion object {
        /** Builds a thumb by sampling [sample] (x, y in 0..1 over the card) → packed 0xRRGGBB. */
        fun sample(cols: Int, rows: Int, sample: (Float, Float) -> Int): CardThumb {
            val n = cols * rows
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            for (y in 0 until rows) for (x in 0 until cols) {
                val c = sample((x + 0.5f) / cols, (y + 0.5f) / rows)
                val i = y * cols + x
                r[i] = ((c shr 16) and 0xFF).toFloat(); g[i] = ((c shr 8) and 0xFF).toFloat(); b[i] = (c and 0xFF).toFloat()
            }
            return CardThumb(cols, rows, r, g, b)
        }

        /**
         * Samples the upright rectangle [area] of a YUV_420_888 camera frame. Each cell averages a 4×4
         * spread of luma samples (a cheap low-pass, so print dots and sensor noise don't alias).
         */
        fun fromYuv(
            y: ByteBuffer, yRow: Int,
            u: ByteBuffer, v: ByteBuffer, uvRow: Int, uvPixel: Int,
            sensorW: Int, sensorH: Int, rotation: Int, area: Box,
            cols: Int = 36, rows: Int = 50,
        ): CardThumb {
            val cellW = area.w / cols
            val cellH = area.h / rows
            val n = cols * rows
            val r = FloatArray(n); val g = FloatArray(n); val b = FloatArray(n)
            for (gy in 0 until rows) for (gx in 0 until cols) {
                var ySum = 0
                var count = 0
                for (j in 0 until 4) for (i in 0 until 4) {
                    val ux = area.left + (gx + (i + 0.5f) / 4f) * cellW
                    val uy = area.top + (gy + (j + 0.5f) / 4f) * cellH
                    val sx = sensorX(ux, uy, sensorW, sensorH, rotation)
                    val sy = sensorY(ux, uy, sensorW, sensorH, rotation)
                    if (sx in 0 until sensorW && sy in 0 until sensorH) {
                        ySum += y.get(sy * yRow + sx).toInt() and 0xFF; count++
                    }
                }
                val i = gy * cols + gx
                if (count == 0) { r[i] = 0f; g[i] = 0f; b[i] = 0f; continue }
                val cx = area.left + (gx + 0.5f) * cellW
                val cy = area.top + (gy + 0.5f) * cellH
                val sx = clampInt(sensorX(cx, cy, sensorW, sensorH, rotation), 0, sensorW - 1) / 2
                val sy = clampInt(sensorY(cx, cy, sensorW, sensorH, rotation), 0, sensorH - 1) / 2
                val idx = sy * uvRow + sx * uvPixel
                val cb = (u.get(idx).toInt() and 0xFF) - 128
                val cr = (v.get(idx).toInt() and 0xFF) - 128
                val yy = ySum.toFloat() / count
                r[i] = (yy + 1.402f * cr).coerceIn(0f, 255f)
                g[i] = (yy - 0.344136f * cb - 0.714136f * cr).coerceIn(0f, 255f)
                b[i] = (yy + 1.772f * cb).coerceIn(0f, 255f)
            }
            return CardThumb(cols, rows, r, g, b)
        }

        private fun sensorX(ux: Float, uy: Float, w: Int, h: Int, rotation: Int): Int = when (rotation) {
            90 -> uy.toInt()
            180 -> (w - 1 - ux).toInt()
            270 -> (w - 1 - uy).toInt()
            else -> ux.toInt()
        }

        private fun sensorY(ux: Float, uy: Float, w: Int, h: Int, rotation: Int): Int = when (rotation) {
            90 -> (h - 1 - ux).toInt()
            180 -> (h - 1 - uy).toInt()
            270 -> ux.toInt()
            else -> uy.toInt()
        }
    }
}

/**
 * Artwork comparison by normalised correlation of tiny colour pictures — the "is it the regular print
 * or the parallel?" step that a printed number can't answer. Brightness/contrast differences (lighting,
 * sleeves) cancel out; small framing errors are absorbed by trying a few shifts and zooms of the camera
 * picture. Pure Kotlin, so it's unit-tested.
 */
object ArtMatch {
    const val W = 16
    const val H = 22

    /** Camera thumbs are sampled from the guide grown by this much, so zoom-out variants exist. */
    const val CAMERA_EXPAND = 1.12f

    /** A descriptor: W×H×3, each channel z-normalised. */
    fun descriptor(src: CardThumb, x0: Float = 0f, y0: Float = 0f, w: Float = src.cols.toFloat(), h: Float = src.rows.toFloat()): FloatArray {
        val out = FloatArray(W * H * 3)
        for ((c, plane) in listOf(src.r, src.g, src.b).withIndex()) {
            val base = c * W * H
            for (y in 0 until H) for (x in 0 until W) {
                // Box-average the source cells this output cell covers.
                val sx0 = x0 + x * w / W; val sx1 = x0 + (x + 1) * w / W
                val sy0 = y0 + y * h / H; val sy1 = y0 + (y + 1) * h / H
                var sum = 0f; var n = 0
                var yy = max(0, sy0.toInt())
                while (yy < min(src.rows, kotlin.math.ceil(sy1).toInt())) {
                    var xx = max(0, sx0.toInt())
                    while (xx < min(src.cols, kotlin.math.ceil(sx1).toInt())) {
                        sum += plane[yy * src.cols + xx]; n++; xx++
                    }
                    yy++
                }
                out[base + y * W + x] = if (n == 0) 0f else sum / n
            }
            normalize(out, base, W * H)
        }
        return out
    }

    private fun normalize(a: FloatArray, from: Int, len: Int) {
        var mean = 0f
        for (i in from until from + len) mean += a[i]
        mean /= len
        var v = 0f
        for (i in from until from + len) { val d = a[i] - mean; v += d * d }
        val sd = sqrt(v / len).coerceAtLeast(4f) // flat channels (grey cards) stay near zero instead of blowing up noise
        for (i in from until from + len) a[i] = (a[i] - mean) / sd
    }

    /** Shifted / zoomed crops of a camera thumb that was sampled over the guide × [CAMERA_EXPAND]. */
    fun cameraVariants(cam: CardThumb): List<FloatArray> {
        val out = ArrayList<FloatArray>()
        for (scale in floatArrayOf(1.12f, 1.06f, 1.0f, 0.94f, 0.88f)) {
            val w = cam.cols * scale / CAMERA_EXPAND
            val h = cam.rows * scale / CAMERA_EXPAND
            val cx = (cam.cols - w) / 2f
            val cy = (cam.rows - h) / 2f
            val step = cam.cols * 0.025f
            for (dy in -1..1) for (dx in -1..1) {
                if (scale > 1.1f && (dx != 0 || dy != 0)) continue // the full capture can't shift
                out += descriptor(cam, cx + dx * step, cy + dy * step, w, h)
            }
        }
        return out
    }

    /** Correlation in -1..1 (per-channel Pearson, averaged). */
    fun similarity(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s / a.size
    }

    /** Candidates ranked by how much they look like the camera picture (best first). */
    fun <T> rank(variants: List<FloatArray>, references: List<Pair<T, FloatArray>>): List<Pair<T, Float>> =
        references.map { (item, ref) -> item to variants.maxOf { similarity(it, ref) } }.sortedByDescending { it.second }

    /** The top match is clearly the one: good absolute look-alike and a gap to the runner-up. */
    fun decisive(ranked: List<Pair<*, Float>>, minScore: Float = 0.5f, minGap: Float = 0.06f): Boolean {
        val top = ranked.firstOrNull()?.second ?: return false
        val next = ranked.getOrNull(1)?.second ?: -1f
        return top >= minScore && top - next >= minGap
    }
}
