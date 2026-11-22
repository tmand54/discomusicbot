package app.tcgportfolio.scan

import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Integer rectangle, right/bottom exclusive. */
data class IntRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
}

/**
 * Maps between the camera sensor's buffer (what CameraX hands us) and the upright image (what the
 * user sees and what OCR coordinates are reported in). `rotation` is ImageInfo.rotationDegrees:
 * rotating the sensor image clockwise by it makes it upright.
 */
object RoiMapper {
    fun uprightToSensor(u: IntRect, sensorW: Int, sensorH: Int, rotation: Int): IntRect = when (((rotation % 360) + 360) % 360) {
        90 -> IntRect(u.top, sensorH - u.right, u.bottom, sensorH - u.left)
        180 -> IntRect(sensorW - u.right, sensorH - u.bottom, sensorW - u.left, sensorH - u.top)
        270 -> IntRect(sensorW - u.bottom, u.left, sensorW - u.top, u.right)
        else -> u
    }

    fun sensorToUpright(s: IntRect, sensorW: Int, sensorH: Int, rotation: Int): IntRect = when (((rotation % 360) + 360) % 360) {
        90 -> IntRect(sensorH - s.bottom, s.left, sensorH - s.top, s.right)
        180 -> IntRect(sensorW - s.right, sensorH - s.bottom, sensorW - s.left, sensorH - s.top)
        270 -> IntRect(s.top, sensorW - s.right, s.bottom, sensorW - s.left)
        else -> s
    }

    /** Clamps to the buffer and snaps to even coordinates (NV21 needs even sizes). */
    fun clampEven(r: IntRect, w: Int, h: Int): IntRect {
        val l = (r.left.coerceIn(0, w) / 2) * 2
        val t = (r.top.coerceIn(0, h) / 2) * 2
        val rr = (r.right.coerceIn(0, w) / 2) * 2
        val b = (r.bottom.coerceIn(0, h) / 2) * 2
        return IntRect(l, t, max(rr, l), max(b, t))
    }

    /** Upright guide box (floats) → sensor crop, clamped and even. */
    fun cropFor(guide: Box?, sensorW: Int, sensorH: Int, rotation: Int): IntRect {
        val full = IntRect(0, 0, sensorW, sensorH)
        guide ?: return full
        val upright = IntRect(guide.left.toInt(), guide.top.toInt(), guide.right.toInt(), guide.bottom.toInt())
        val s = clampEven(uprightToSensor(upright, sensorW, sensorH, rotation), sensorW, sensorH)
        // Too small to hold a card (view not measured yet, odd aspect…): read the whole frame.
        return if (s.width < sensorW / 4 || s.height < sensorH / 4) full else s
    }
}

/**
 * Cheap focus/motion-blur measure on the luminance plane: mean absolute difference between
 * neighbouring samples over a sparse grid. Sharp text has strong edges; a moving card doesn't.
 */
object Sharpness {
    fun score(y: ByteBuffer, rowStride: Int, crop: IntRect, step: Int = 4): Double {
        if (crop.width <= step * 2 || crop.height <= step * 2) return 0.0
        var sum = 0L
        var n = 0
        var row = crop.top
        while (row < crop.bottom - step) {
            val base = row * rowStride
            var col = crop.left
            while (col < crop.right - step) {
                val p = y.get(base + col).toInt() and 0xFF
                val right = y.get(base + col + step).toInt() and 0xFF
                val down = y.get(base + step * rowStride + col).toInt() and 0xFF
                sum += abs(p - right) + abs(p - down)
                n++
                col += step
            }
            row += step * 2
        }
        return if (n == 0) 0.0 else sum.toDouble() / n
    }
}

/**
 * Skips frames that are much blurrier than what this scene has recently shown (the card is moving
 * or the lens is refocusing), so OCR time goes to frames it can actually read. Never skips more than
 * a few frames in a row, so plain or dim scenes still get read.
 */
class BlurGate(private val ratio: Double = 0.55, private val maxSkips: Int = 3) {
    private var reference = 0.0
    private var skipped = 0
    var blurry = false
        private set

    fun shouldRead(score: Double): Boolean {
        reference = if (score > reference) score else reference * 0.97 + score * 0.03
        blurry = reference > 6.0 && score < reference * ratio
        if (blurry && skipped < maxSkips) {
            skipped++
            return false
        }
        skipped = 0
        return true
    }

    fun reset() {
        reference = 0.0; skipped = 0; blurry = false
    }
}

/** Copies the luminance of a sensor crop into a reusable NV21 buffer (chroma set to neutral grey). */
class Nv21Cropper {
    private var buffer = ByteArray(0)
    private var w = 0
    private var h = 0

    fun crop(y: ByteBuffer, rowStride: Int, crop: IntRect): ByteArray {
        val cw = crop.width
        val ch = crop.height
        if (cw != w || ch != h) {
            w = cw; h = ch
            buffer = ByteArray(cw * ch * 3 / 2)
            buffer.fill(128.toByte(), cw * ch, buffer.size)
        }
        val src = y.duplicate()
        for (row in 0 until ch) {
            src.position((crop.top + row) * rowStride + crop.left)
            src.get(buffer, row * cw, cw)
        }
        return buffer
    }
}

/** Shifts OCR boxes from a cropped upright image back into full-frame upright coordinates. */
fun OcrFrame.offsetBy(dx: Int, dy: Int, fullWidth: Int, fullHeight: Int): OcrFrame {
    if (dx == 0 && dy == 0 && fullWidth == width && fullHeight == height) return this
    val fx = dx.toFloat(); val fy = dy.toFloat()
    return OcrFrame(
        lines.map { it.copy(box = Box(it.box.left + fx, it.box.top + fy, it.box.right + fx, it.box.bottom + fy)) },
        fullWidth, fullHeight, tookMs, thumb,
    )
}

internal fun clampInt(v: Int, lo: Int, hi: Int) = min(max(v, lo), hi)
