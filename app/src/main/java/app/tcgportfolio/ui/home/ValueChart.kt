package app.tcgportfolio.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.tcgportfolio.data.ValuePoint
import kotlin.math.abs

/**
 * The collection's value over time ([lineColor]) and, dashed, the invested basis. X is proportional to
 * time, so a sparse day of snapshots isn't stretched. Press or drag to read a point: [onScrub] gets
 * the nearest one, then null on release.
 */
@Composable
fun ValueChart(
    points: List<ValuePoint>,
    start: Long,
    end: Long,
    lineColor: Color,
    basisColor: Color,
    gridColor: Color,
    modifier: Modifier = Modifier,
    showBasis: Boolean = true,
    onScrub: (ValuePoint?) -> Unit = {},
) {
    var scrubbed by remember(points) { mutableStateOf<ValuePoint?>(null) }
    val span = (end - start).coerceAtLeast(1L).toFloat()

    fun nearest(x: Float, width: Float): ValuePoint? {
        if (points.isEmpty() || width <= 0f) return null
        val t = start + (x / width).coerceIn(0f, 1f) * span
        return points.minBy { abs(it.t - t) }
    }

    Canvas(
        modifier
            .pointerInput(points, start, end) {
                detectTapGestures(onPress = { o ->
                    scrubbed = nearest(o.x, size.width.toFloat()); onScrub(scrubbed)
                    tryAwaitRelease()
                    scrubbed = null; onScrub(null)
                })
            }
            .pointerInput(points, start, end) {
                // Horizontal drags only, so the page still scrolls vertically over the chart.
                detectHorizontalDragGestures(
                    onDragStart = { o -> scrubbed = nearest(o.x, size.width.toFloat()); onScrub(scrubbed) },
                    onDragEnd = { scrubbed = null; onScrub(null) },
                    onDragCancel = { scrubbed = null; onScrub(null) },
                ) { change, _ ->
                    change.consume()
                    val p = nearest(change.position.x, size.width.toFloat())
                    if (p != scrubbed) { scrubbed = p; onScrub(p) }
                }
            },
    ) {
        if (points.isEmpty()) return@Canvas
        val values = points.map { it.value } + if (showBasis) points.mapNotNull { it.basis } else emptyList()
        var lo = values.min(); var hi = values.max()
        if (hi - lo < 1e-6) { lo -= 1.0; hi += 1.0 }
        val pad = (hi - lo) * 0.08
        lo -= pad; hi += pad
        val top = 4.dp.toPx(); val bottom = size.height - 4.dp.toPx()
        fun x(t: Long) = (t - start) / span * size.width
        fun y(v: Double) = (bottom - (v - lo) / (hi - lo) * (bottom - top)).toFloat()

        for (k in 0..3) {
            val gy = top + (bottom - top) * k / 3f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }

        // Invested basis: changes only when cards come or go, so drawn as steps.
        if (showBasis) {
            val basis = Path()
            var started = false
            var lastY = 0f
            for (p in points) {
                val b = p.basis ?: continue
                val px = x(p.t); val py = y(b)
                if (!started) { basis.moveTo(px, py); started = true } else { basis.lineTo(px, lastY); basis.lineTo(px, py) }
                lastY = py
            }
            if (started) drawPath(
                basis, basisColor,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))),
            )
        }

        val line = Path()
        points.forEachIndexed { i, p -> if (i == 0) line.moveTo(x(p.t), y(p.value)) else line.lineTo(x(p.t), y(p.value)) }
        if (points.size == 1) line.lineTo(size.width, y(points[0].value))
        val lastX = if (points.size == 1) size.width else x(points.last().t)
        val fill = Path().apply {
            addPath(line)
            lineTo(lastX, size.height)
            lineTo(x(points.first().t), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(lineColor.copy(alpha = 0.28f), lineColor.copy(alpha = 0f))))
        drawPath(line, lineColor, style = Stroke(width = 2.5.dp.toPx()))

        val marker = scrubbed ?: points.last()
        val mx = if (scrubbed == null) lastX else x(marker.t)
        if (scrubbed != null) drawLine(gridColor.copy(alpha = 1f), Offset(mx, 0f), Offset(mx, size.height), strokeWidth = 1.5.dp.toPx())
        drawCircle(lineColor, radius = 4.5.dp.toPx(), center = Offset(mx, y(marker.value)))
    }
}
