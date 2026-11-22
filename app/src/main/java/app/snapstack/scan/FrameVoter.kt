package app.snapstack.scan

/**
 * Decides when a card read from the live camera feed should be committed.
 *
 *  - A key must win [requiredVotes] of the last [window] frames (filters OCR flicker).
 *  - A committed card is not committed again while it stays in view, so holding a card still
 *    never double-counts. Once it has been out of view for [goneMs] it can be scanned again
 *    (that is how you scan a second copy).
 *  - Committing a new card means the previous one left the frame, so A → B → A counts three cards.
 */
class FrameVoter(
    var requiredVotes: Int = 2,
    private val window: Int = 4,
    private val goneMs: Long = 1200,
) {
    private val recent = ArrayDeque<String?>()
    private val lastSeen = HashMap<String, Long>()
    private val committed = LinkedHashSet<String>()

    /** Offer one frame's best key (or null). Returns the key id to commit, if any. */
    fun offer(id: String?, now: Long): String? {
        committed.removeAll { now - (lastSeen[it] ?: 0L) > goneMs }
        recent.addLast(id)
        while (recent.size > window) recent.removeFirst()
        if (id == null) return null
        lastSeen[id] = now
        if (id in committed) return null
        val votes = recent.count { it == id }
        if (votes < requiredVotes.coerceAtLeast(1)) return null
        committed.clear()
        committed += id
        return id
    }

    /** Marks a key as already handled (e.g. added through the snap button) so the live feed skips it. */
    fun markCommitted(id: String, now: Long) {
        lastSeen[id] = now
        committed += id
    }

    fun reset() {
        recent.clear(); lastSeen.clear(); committed.clear()
    }
}
