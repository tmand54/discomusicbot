package app.snapstack.ui.scan

import android.annotation.SuppressLint
import android.util.Size
import android.view.MotionEvent
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * CameraX preview + OCR analysis + full-resolution capture.
 * Analysis runs at ~1600×1200 (4:3): enough pixels for the tiny collector numbers while
 * keeping per-frame OCR fast.
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraPreview(
    analyzer: ImageAnalysis.Analyzer,
    torch: Boolean,
    onCapture: (ImageCapture?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var camera by remember { mutableStateOf<Camera?>(null) }
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
        }
    }

    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val fourThree = AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY
            val preview = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(fourThree).build())
                .build()
                .also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(fourThree)
                        .setResolutionStrategy(
                            ResolutionStrategy(Size(1600, 1200), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER)
                        )
                        .build()
                )
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
                .also { it.setAnalyzer(analysisExecutor, analyzer) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setAspectRatioStrategy(fourThree)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                        .build()
                )
                .build()
            try {
                p.unbindAll()
                camera = try {
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis, capture)
                        .also { onCapture(capture) }
                } catch (_: Exception) {
                    // Some older devices can't run three streams at once: drop the still capture.
                    p.unbindAll()
                    onCapture(null)
                    p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                }
            } catch (_: Exception) {
                onCapture(null)
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            camera = null
            onCapture(null)
            analysisExecutor.shutdown()
        }
    }

    LaunchedEffect(camera, torch) {
        camera?.let { c -> if (c.cameraInfo.hasFlashUnit()) c.cameraControl.enableTorch(torch) }
    }

    AndroidView(
        factory = {
            previewView.apply {
                setOnTouchListener { v, event ->
                    if (event.action == MotionEvent.ACTION_UP) {
                        val point = meteringPointFactory.createPoint(event.x, event.y)
                        val action = FocusMeteringAction.Builder(point)
                            .setAutoCancelDuration(3, TimeUnit.SECONDS)
                            .build()
                        camera?.cameraControl?.startFocusAndMetering(action)
                        v.performClick()
                    }
                    true
                }
            }
        },
        modifier = modifier,
    )
}
