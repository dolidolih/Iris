package party.qwer.iris

import java.util.TreeSet

internal class LogCheckpointTracker(initialCheckpoint: Long = 0L) {
    private var checkpoint: Long = initialCheckpoint
    private val completedBeyondCheckpoint = TreeSet<Long>()

    fun checkpoint(): Long = checkpoint

    fun reset(newCheckpoint: Long) {
        checkpoint = newCheckpoint
        completedBeyondCheckpoint.clear()
    }

    fun shouldSkip(logId: Long): Boolean {
        return logId <= checkpoint || completedBeyondCheckpoint.contains(logId)
    }

    fun markCompleted(logId: Long): Long {
        if (logId <= checkpoint) {
            return checkpoint
        }

        completedBeyondCheckpoint.add(logId)
        while (completedBeyondCheckpoint.remove(checkpoint + 1)) {
            checkpoint += 1
        }

        return checkpoint
    }
}
