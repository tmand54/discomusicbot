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
 * On-device OCR for every camera frame. The bundled ML Kit model needs no network, and with
 * KEEP_ONLY_LATEST backpressure the scanner always works on the newest frame instead of a queue.
 */
class TextAnalyzer(
    private val callbackExecutor: java.util.concurrent.Executor,
    private val onFrame: (OcrFrame) -> Unit,
) : ImageAnalysis.Analyzer {
    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    @Volatile
    var paused = false

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    override fun analyze(imageProxy: ImageProxy) {
        val media = imageProxy.image
        if (paused || media == null) {
            imageProxy.close(); return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val (w, h) = if (rotation % 180 == 0) imageProxy.width to imageProxy.height else imageProxy.height to imageProxy.width
        val start = SystemClock.elapsedRealtime()
        recognizer.process(InputImage.fromMediaImage(media, rotation))
            // Parse off the main thread so the preview never stutters.
            .addOnSuccessListener(callbackExecutor) { text ->
                if (!paused) onFrame(text.toFrame(w, h, SystemClock.elapsedRealtime() - start))
            }
            .addOnCompleteListener(callbackExecutor) { imageProxy.close() }
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
