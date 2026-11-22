package app.snapstack.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameVoterTest {
    @Test fun needsTwoMatchingFrames() {
        val v = FrameVoter(requiredVotes = 2)
        assertNull(v.offer("A", 0))
        assertEquals("A", v.offer("A", 70))
    }

    @Test fun turboCommitsOnFirstFrame() {
        val v = FrameVoter(requiredVotes = 1)
        assertEquals("A", v.offer("A", 0))
    }

    @Test fun flickerDoesNotCommit() {
        val v = FrameVoter(requiredVotes = 2, window = 3)
        assertNull(v.offer("A", 0))
        assertNull(v.offer("B", 70))
        assertNull(v.offer(null, 140))
        assertNull(v.offer("C", 210))
    }

    @Test fun cardHeldStillIsCountedOnce() {
        val v = FrameVoter(requiredVotes = 2)
        v.offer("A", 0); assertEquals("A", v.offer("A", 70))
        for (t in 140L..3000L step 70) assertNull(v.offer("A", t))
    }

    @Test fun sameCardAgainAfterLeavingCountsAgain() {
        val v = FrameVoter(requiredVotes = 2, goneMs = 1000)
        v.offer("A", 0); assertEquals("A", v.offer("A", 70))
        for (t in 140L..1400L step 70) v.offer(null, t)
        assertNull(v.offer("A", 1500))
        assertEquals("A", v.offer("A", 1570))
    }

    @Test fun briefDropoutDoesNotDoubleCount() {
        val v = FrameVoter(requiredVotes = 2, goneMs = 1000)
        v.offer("A", 0); v.offer("A", 70)
        v.offer(null, 140); v.offer(null, 210)
        assertNull(v.offer("A", 280))
        assertNull(v.offer("A", 350))
    }

    @Test fun stackOfDifferentCards() {
        val v = FrameVoter(requiredVotes = 2)
        val committed = mutableListOf<String>()
        var t = 0L
        for (card in listOf("A", "B", "A", "C")) {
            repeat(4) { v.offer(card, t)?.let(committed::add); t += 70 }
        }
        assertEquals(listOf("A", "B", "A", "C"), committed)
    }

    @Test fun markCommittedSkipsLiveRead() {
        val v = FrameVoter(requiredVotes = 1)
        v.markCommitted("A", 0)
        assertNull(v.offer("A", 50))
    }
}
