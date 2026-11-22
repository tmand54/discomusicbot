package app.tcgportfolio.data

import app.tcgportfolio.model.PortfolioPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class PortfolioMathTest {
    private val day = PortfolioMath.DAY
    private val now = 1_800_000_000_000L
    private fun p(daysAgo: Double, value: Double, basis: Double) = ValuePoint(now - (daysAgo * day).toLong(), value, basis)

    @Test fun priceMovesOnlyAreThePlainChange() {
        val s = PortfolioMath.stats(listOf(p(40.0, 100.0, 80.0), p(20.0, 110.0, 80.0)), ChartRange.M1, now, p(0.0, 120.0, 80.0))!!
        // The baseline is carried forward from 40 days ago to the start of the month.
        assertEquals(now - 30 * day, s.start)
        assertEquals(100.0, s.points.first().value, 1e-9)
        assertEquals(20.0, s.gain, 1e-9)
        assertEquals(20.0, s.returnPct!!, 1e-9)
        assertEquals(0.0, s.flows, 1e-9)
        assertFalse(s.partial)
    }

    @Test fun addingCardsIsNotAGain() {
        // $100 of cards, then $500 more added (at market price) with no price change.
        val s = PortfolioMath.stats(listOf(p(10.0, 100.0, 100.0), p(5.0, 600.0, 600.0)), ChartRange.M1, now, p(0.0, 600.0, 600.0))!!
        assertEquals(0.0, s.gain, 1e-9)
        assertEquals(0.0, s.returnPct!!, 1e-9)
        assertEquals(500.0, s.flows, 1e-9)
    }

    @Test fun cardsAddedAtTheStartEarnTheWholeReturn() {
        // Collection started 3 days ago at $200 (all of it the basis) and is now worth $250.
        val s = PortfolioMath.stats(listOf(p(3.0, 200.0, 200.0)), ChartRange.D5, now, p(0.0, 250.0, 200.0))!!
        assertTrue(s.partial)
        assertEquals(50.0, s.gain, 1e-9)
        assertEquals(25.0, s.returnPct!!, 1e-9)
    }

    @Test fun moneyAddedNearTheEndBarelyDilutesTheReturn() {
        // $1000 held all month gains $100; $1000 more arrives a day before the end and doesn't move.
        val pts = listOf(p(31.0, 1000.0, 1000.0), p(1.0, 2100.0, 2000.0))
        val s = PortfolioMath.stats(pts, ChartRange.M1, now, p(0.0, 2100.0, 2000.0))!!
        assertEquals(100.0, s.gain, 1e-9)
        // Modified Dietz: 100 / (1000 + 1000 × 1/30) ≈ 9.68 %
        assertEquals(100.0 / (1000.0 + 1000.0 / 30.0) * 100, s.returnPct!!, 1e-6)
    }

    @Test fun removingCardsIsNotALoss() {
        val s = PortfolioMath.stats(listOf(p(4.0, 500.0, 400.0), p(2.0, 200.0, 160.0)), ChartRange.D5, now, p(0.0, 200.0, 160.0))!!
        assertEquals(-240.0, s.flows, 1e-9)
        assertEquals(-60.0, s.gain, 1e-9) // the $60 of profit left with the cards sold
    }

    @Test fun allTimeStartsAtTheFirstSnapshot() {
        val s = PortfolioMath.stats(listOf(p(400.0, 50.0, 50.0), p(100.0, 80.0, 50.0)), ChartRange.ALL, now, p(0.0, 90.0, 50.0))!!
        assertEquals(now - 400 * day, s.start)
        assertEquals(40.0, s.gain, 1e-9)
        assertEquals(80.0, s.returnPct!!, 1e-9)
        assertFalse(s.partial)
    }

    @Test fun emptyCollectionHasNoReturn() {
        assertNull(PortfolioMath.stats(emptyList(), ChartRange.D1, now, null))
        val s = PortfolioMath.stats(emptyList(), ChartRange.D1, now, p(0.0, 0.0, 0.0))!!
        assertNull(s.returnPct)
    }

    @Test fun ticksReplaceDailyRowsForTheDaysTheyCover() {
        val zone = ZoneOffset.UTC
        val today = LocalDate.of(2026, 11, 22)
        val nowMs = today.atTime(15, 0).toInstant(zone).toEpochMilli()
        val daily = (5 downTo 0).map { ago -> PortfolioPoint(today.toEpochDay() - ago, 100.0 + ago, 0.0, 1, 90.0) }
        val ticks = listOf(
            ValuePoint(today.minusDays(1).atTime(9, 0).toInstant(zone).toEpochMilli(), 200.0, 90.0),
            ValuePoint(today.atTime(10, 0).toInstant(zone).toEpochMilli(), 210.0, 90.0),
        )
        val merged = PortfolioMath.merge(daily, ticks, nowMs, zone)
        // Days 5..2 ago from the daily table (at the end of each day), then the two snapshots.
        assertEquals(listOf(105.0, 104.0, 103.0, 102.0, 200.0, 210.0), merged.map { it.value })
        assertEquals(today.minusDays(4).atStartOfDay(zone).toInstant().toEpochMilli() - 1, merged.first().t)
        assertTrue(merged.zipWithNext().all { (a, b) -> a.t < b.t })
    }
}
