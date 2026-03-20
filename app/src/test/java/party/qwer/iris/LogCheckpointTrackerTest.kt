package party.qwer.iris

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LogCheckpointTrackerTest {
    @Test
    fun `does not advance checkpoint across a failed gap`() {
        val tracker = LogCheckpointTracker(initialCheckpoint = 10L)

        tracker.markCompleted(12L)

        assertEquals(10L, tracker.checkpoint())
        assertTrue(tracker.shouldSkip(12L))
        assertFalse(tracker.shouldSkip(11L))
    }

    @Test
    fun `advances checkpoint once the missing row succeeds`() {
        val tracker = LogCheckpointTracker(initialCheckpoint = 10L)

        tracker.markCompleted(12L)
        tracker.markCompleted(11L)

        assertEquals(12L, tracker.checkpoint())
        assertTrue(tracker.shouldSkip(11L))
        assertTrue(tracker.shouldSkip(12L))
    }

    @Test
    fun `reset clears pending completions`() {
        val tracker = LogCheckpointTracker(initialCheckpoint = 10L)

        tracker.markCompleted(12L)
        tracker.reset(20L)

        assertEquals(20L, tracker.checkpoint())
        assertTrue(tracker.shouldSkip(12L))
        assertFalse(tracker.shouldSkip(21L))
    }
}
