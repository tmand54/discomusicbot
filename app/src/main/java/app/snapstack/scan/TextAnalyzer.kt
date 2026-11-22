package app.snapstack.scan

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR for the camera feed, tuned for speed:
 *  - only the scan guide is read: its luminance is copied into a small NV21 buffer (no colour
 *    conversion, roughly half the pixels of the full frame, and no stray text from around the card);
 *  - frames much blurrier than the scene's recent best (card moving, lens refocusing) are skipped;
 *  - with KEEP_ONLY_LATEST backpressure the next frame is always the newest one.
 * The bundled ML Kit model needs no network.
 */
class TextAnalyzer(
    private val callbackExecutor: java.util.concurrent.Executor,
    /** The guide in upright frame coordinates for a frame of (width, height), or null for the whole frame. */
    private val roiProvider: (Int, Int) -> Box? = { _, _ -> null },
    private val onBlur: (Boolean) -> Unit = {},
    private val onFrame: (OcrFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val cropper = Nv21Cropper()
    private val blurGate = BlurGate()
    private var wasBlurry = false

    @Volatile
    var paused = false

    override fun analyze(imageProxy: ImageProxy) {
        if (paused) {
            imageProxy.close(); return
        }
        try {
            val rotation = imageProxy.imageInfo.rotationDegrees
            val sw = imageProxy.width
            val sh = imageProxy.height
            val (uw, uh) = if (rotation % 180 == 0) sw to sh else sh to sw
            val yPlane = imageProxy.planes[0]
            val yBuf = yPlane.buffer
            val rowStride = yPlane.rowStride

            val crop = RoiMapper.cropFor(roiProvider(uw, uh), sw, sh, rotation)
            val sharp = Sharpness.score(yBuf, rowStride, crop)
            val read = blurGate.shouldRead(sharp)
            if (blurGate.blurry != wasBlurry) {
                wasBlurry = blurGate.blurry
                onBlur(wasBlurry)
            }
            if (!read) {
                imageProxy.close(); return
            }

            val nv21 = cropper.crop(yBuf, rowStride, crop)
            val upright = RoiMapper.sensorToUpright(crop, sw, sh, rotation)
            val start = SystemClock.elapsedRealtime()
            recognizer.process(InputImage.fromByteArray(nv21, crop.width, crop.height, rotation, InputImage.IMAGE_FORMAT_NV21))
                // Parse off the main thread so the preview never stutters.
                .addOnSuccessListener(callbackExecutor) { text ->
                    if (!paused) {
                        val local = text.toFrame(upright.width, upright.height, SystemClock.elapsedRealtime() - start)
                        onFrame(local.offsetBy(upright.left, upright.top, uw, uh))
                    }
                }
                .addOnCompleteListener(callbackExecutor) { imageProxy.close() }
        } catch (e: Exception) {
            imageProxy.close()
        }
    }

    fun close() = recognizer.close()

    companion object {
        private val stillRecognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }

        /** OCR for a high-resolution still (snap button and binder pages). */
        suspend fun recognize(bitmap: Bitmap, rotation: Int): OcrFrame = suspendCancellableCoroutine { cont ->
            val (w, h) = if (rotation % 180 == 0) bitmap.width to bitmap.height else bitmap.height to bitmap.width
            val start = SystemClock.elapsedRealtime()
            stillRecognizer.process(InputImage.fromBitmap(bitmap, rotation))
                .addOnSuccessListener { cont.resume(it.toFrame(w, h, SystemClock.elapsedRealtime() - start)) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}

fun Text.toFrame(width: Int, height: Int, tookMs: Long): OcrFrame {
    val lines = ArrayList<OcrLine>()
    textBlocks.forEachIndexed { b, block ->
        for (line in block.lines) {
            val r = line.boundingBox ?: continue
            lines += OcrLine(line.text, Box(r.left.toFloat(), r.top.toFloat(), r.right.toFloat(), r.bottom.toFloat()), b)
        }
    }
    return OcrFrame(lines, width, height, tookMs)
}
