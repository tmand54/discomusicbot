package app.snapstack.scan

import android.content.Context
import android.graphics.Bitmap
import app.snapstack.model.Card
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/**
 * Picks between printings that share a number (regular / parallel / alt art / promo, every DON!! card)
 * by comparing the camera picture with each candidate's catalog image. Only those few thumbnails are
 * fetched (and cached by the image loader); nothing is downloaded up front.
 */
class ArtMatcher(private val context: Context) {
    private val refs = object : LinkedHashMap<String, FloatArray>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, FloatArray>?) = size > 800
    }

    private suspend fun reference(card: Card): FloatArray? {
        synchronized(refs) { refs[card.id] }?.let { return it }
        val url = card.imageSmall ?: card.imageLarge ?: return null
        val request = ImageRequest.Builder(context).data(url).size(96, 134).allowHardware(false).build()
        val result = context.imageLoader.execute(request) as? SuccessResult ?: return null
        val bitmap = result.image.toBitmap()
        val d = withContext(Dispatchers.Default) {
            ArtMatch.descriptor(thumbFromBitmap(bitmap, 0, Box(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())))
        }
        synchronized(refs) { refs[card.id] = d }
        return d
    }

    /** Candidates ranked by look (best first). Empty when fewer than two could be compared. */
    suspend fun rank(camera: CardThumb, cards: List<Card>): List<Pair<Card, Float>> = coroutineScope {
        val gate = Semaphore(6)
        val references = cards.distinctBy { it.id }.map { c ->
            async { gate.withPermit { runCatching { reference(c) }.getOrNull()?.let { c to it } } }
        }.awaitAll().filterNotNull()
        if (references.size < 2) return@coroutineScope emptyList()
        withContext(Dispatchers.Default) { ArtMatch.rank(ArtMatch.cameraVariants(camera), references) }
    }

    companion object {
        /**
         * Samples an upright [area] of a bitmap that is stored rotated by [rotation] (camera stills are
         * delivered in sensor orientation). Each cell averages a 3×3 spread of pixels.
         */
        fun thumbFromBitmap(bitmap: Bitmap, rotation: Int, area: Box, cols: Int = 36, rows: Int = 50): CardThumb {
            val w = bitmap.width; val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val cellW = area.w / cols; val cellH = area.h / rows
            return CardThumb.sample(cols, rows) { fx, fy ->
                var r = 0; var g = 0; var b = 0; var n = 0
                for (j in -1..1) for (i in -1..1) {
                    val ux = area.left + fx * area.w + i * cellW / 3f
                    val uy = area.top + fy * area.h + j * cellH / 3f
                    val (sx, sy) = when (rotation) {
                        90 -> uy.toInt() to (h - 1 - ux).toInt()
                        180 -> (w - 1 - ux).toInt() to (h - 1 - uy).toInt()
                        270 -> (w - 1 - uy).toInt() to ux.toInt()
                        else -> ux.toInt() to uy.toInt()
                    }
                    if (sx in 0 until w && sy in 0 until h) {
                        val p = pixels[sy * w + sx]
                        if ((p ushr 24) < 128) continue // transparent rounded corners of catalog scans
                        r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                    }
                }
                if (n == 0) 0 else ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }

        /** The guide grown by [ArtMatch.CAMERA_EXPAND] around its centre, clamped to the frame. */
        fun captureArea(guide: Box, frameW: Int, frameH: Int): Box {
            val cx = guide.cx; val cy = guide.cy
            val hw = guide.w * ArtMatch.CAMERA_EXPAND / 2f; val hh = guide.h * ArtMatch.CAMERA_EXPAND / 2f
            return Box(
                (cx - hw).coerceAtLeast(0f), (cy - hh).coerceAtLeast(0f),
                (cx + hw).coerceAtMost(frameW.toFloat()), (cy + hh).coerceAtMost(frameH.toFloat()),
            )
        }
    }
}
