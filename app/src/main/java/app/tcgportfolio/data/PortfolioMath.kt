package app.tcgportfolio.data

import app.tcgportfolio.model.PortfolioPoint
import java.time.LocalDate
import java.time.ZoneId

/** A point on the value chart: epoch millis, the collection's value, and its invested basis. */
data class ValuePoint(val t: Long, val value: Double, val basis: Double?)

enum class ChartRange(val label: String, val caption: String, val millis: Long?) {
    D1("1D", "today", PortfolioMath.DAY),
    D5("5D", "past 5 days", 5 * PortfolioMath.DAY),
    M1("1M", "past month", 30 * PortfolioMath.DAY),
    M6("6M", "past 6 months", 182 * PortfolioMath.DAY),
    Y1("1Y", "past year", 365 * PortfolioMath.DAY),
    ALL("All", "all time", null),
}

/** Everything the home chart shows for one interval. */
data class RangeStats(
    /** The chart's points, from the start of the interval to now. */
    val points: List<ValuePoint>,
    val start: Long,
    val end: Long,
    /** Market gain over the interval: the change in value minus cards added or removed. */
    val gain: Double,
    /** [gain] as a return on the money in the collection over the interval (Modified Dietz), in %. */
    val returnPct: Double?,
    /** Basis of cards added (+) or removed (−) during the interval. */
    val flows: Double,
    /** The collection is younger than the interval, so it starts with the first snapshot. */
    val partial: Boolean,
)

/**
 * Collection performance, separated from cards coming and going. Adding a $100 card raises the value
 * by $100 but isn't a gain, so every snapshot also records the invested basis (price paid, or the
 * market price when added), and a basis change between snapshots counts as money in or out.
 */
object PortfolioMath {
    const val DAY = 86_400_000L

    /**
     * One time series from the daily history (one row per day, placed at the end of that day) and
     * the timestamped snapshots of the last week, which replace the daily rows for the days they cover.
     */
    fun merge(daily: List<PortfolioPoint>, ticks: List<ValuePoint>, now: Long, zone: ZoneId = ZoneId.systemDefault()): List<ValuePoint> {
        val firstTickDay = ticks.minOfOrNull { java.time.Instant.ofEpochMilli(it.t).atZone(zone).toLocalDate().toEpochDay() }
        val days = daily.filter { firstTickDay == null || it.day < firstTickDay }.map { p ->
            val endOfDay = LocalDate.ofEpochDay(p.day + 1).atStartOfDay(zone).toInstant().toEpochMilli() - 1
            ValuePoint(minOf(endOfDay, now), p.value, p.basis)
        }
        return (days + ticks).sortedBy { it.t }
    }

    /**
     * The interval's chart and numbers. [live] is the collection right now and closes the series, so the
     * chart always ends at the value shown above it.
     */
    fun stats(series: List<ValuePoint>, range: ChartRange, now: Long, live: ValuePoint?): RangeStats? {
        val all = (series.filter { it.t <= now } + listOfNotNull(live?.copy(t = now))).sortedBy { it.t }
        if (all.isEmpty()) return null
        val start = range.millis?.let { now - it } ?: all.first().t
        val before = all.lastOrNull { it.t <= start }
        val inside = all.filter { it.t > start }
        val points = if (before != null) listOf(before.copy(t = start)) + inside else inside
        if (points.isEmpty()) return null

        val first = points.first()
        val last = points.last()
        val begin = first.t
        val span = (last.t - begin).coerceAtLeast(1L)
        var flows = 0.0
        var weighted = 0.0
        var basis = first.basis
        for (p in points.drop(1)) {
            val b = p.basis ?: continue
            if (basis != null) {
                val f = b - basis
                if (f != 0.0) {
                    flows += f
                    // Money that arrived earlier in the interval was at work for longer.
                    weighted += f * (last.t - p.t).toDouble() / span
                }
            }
            basis = b
        }
        val gain = last.value - first.value - flows
        val capital = first.value + weighted
        return RangeStats(
            points = points, start = begin, end = last.t, gain = gain,
            returnPct = if (capital > 0.005) gain / capital * 100 else null,
            flows = flows, partial = before == null && range != ChartRange.ALL,
        )
    }
}
