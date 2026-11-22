package app.tcgportfolio.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Style
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.tcgportfolio.model.Game
import app.tcgportfolio.ui.theme.LocalMarketColors
import app.tcgportfolio.ui.theme.Numeric
import coil3.compose.SubcomposeAsyncImage
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs

private val usd: NumberFormat = NumberFormat.getCurrencyInstance(Locale.US)

fun money(v: Double?): String = if (v == null) "—" else usd.format(v)

fun signedMoney(v: Double): String = (if (v >= 0) "+" else "−") + usd.format(abs(v))

fun pct(v: Double): String = (if (v >= 0) "+" else "−") + "%.1f%%".format(abs(v))

@Composable
fun gainColor(v: Double): Color = when {
    v > 0.005 -> LocalMarketColors.current.gain
    v < -0.005 -> LocalMarketColors.current.loss
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Card artwork with the card's real 63:88 proportions and a placeholder while loading. */
@Composable
fun CardImage(
    url: String?,
    modifier: Modifier = Modifier,
    corner: Dp = 8.dp,
    contentDescription: String? = null,
    /** Tried when [url] fails to load (e.g. a large size that the CDN doesn't have). */
    fallbackUrl: String? = null,
) {
    val shape = RoundedCornerShape(corner)
    Box(
        modifier
            .aspectRatio(63f / 88f)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) {
            Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.outline)
        } else {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                error = {
                    if (fallbackUrl != null && fallbackUrl != url) {
                        SubcomposeAsyncImage(
                            model = fallbackUrl, contentDescription = contentDescription,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize(),
                            error = { Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.outline) },
                        )
                    } else {
                        Icon(Icons.Outlined.Style, null, tint = MaterialTheme.colorScheme.outline)
                    }
                },
            )
        }
    }
}

@Composable
fun GameFilterRow(
    selected: Game?,
    onSelect: (Game?) -> Unit,
    modifier: Modifier = Modifier,
    allLabel: String = "All",
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp),
) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = selected == null, onClick = { onSelect(null) }, label = { Text(allLabel) })
        Game.entries.forEach { g ->
            FilterChip(selected = selected == g, onClick = { onSelect(g) }, label = { Text(g.label) })
        }
    }
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, body: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (action != null) { Spacer(Modifier.height(8.dp)); action() }
    }
}

@Composable
fun StatTile(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Color.Unspecified, sub: String? = null) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge.merge(Numeric), color = valueColor, fontWeight = FontWeight.Bold)
        if (sub != null) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun GameBadge(game: Game, modifier: Modifier = Modifier) {
    Text(
        game.short,
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
    )
}

/** Minimal area line chart; values are plotted evenly spaced. */
@Composable
fun LineChart(values: List<Double>, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val pts = remember(values) { values }
    Canvas(modifier) {
        if (pts.isEmpty()) return@Canvas
        val min = pts.min(); val max = pts.max()
        val range = (max - min).takeIf { it > 1e-9 } ?: 1.0
        val padTop = 6.dp.toPx(); val padBottom = 6.dp.toPx()
        val h = size.height - padTop - padBottom
        fun x(i: Int) = if (pts.size == 1) size.width / 2 else i * size.width / (pts.size - 1)
        fun y(v: Double) = padTop + (h - ((v - min) / range * h)).toFloat()

        for (k in 0..2) {
            val gy = padTop + h * k / 2f
            drawLine(gridColor, Offset(0f, gy), Offset(size.width, gy), strokeWidth = 1f)
        }
        val line = Path()
        pts.forEachIndexed { i, v -> if (i == 0) line.moveTo(x(i), y(v)) else line.lineTo(x(i), y(v)) }
        if (pts.size == 1) line.lineTo(size.width, y(pts[0]))
        val fill = Path().apply {
            addPath(line)
            lineTo(if (pts.size == 1) size.width else x(pts.lastIndex), size.height)
            lineTo(if (pts.size == 1) 0f else x(0), size.height)
            close()
        }
        drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.35f), color.copy(alpha = 0f))))
        drawPath(line, color, style = Stroke(width = 2.5.dp.toPx()))
        val lastX = if (pts.size == 1) size.width else x(pts.lastIndex)
        drawCircle(color, radius = 4.dp.toPx(), center = Offset(lastX, y(pts.last())))
    }
}
