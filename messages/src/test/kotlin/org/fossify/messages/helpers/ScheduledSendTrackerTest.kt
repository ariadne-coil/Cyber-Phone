package org.fossify.messages.helpers

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class ScheduledSendTrackerTest {
    private fun tracker() = ScheduledSendTracker(RuntimeEnvironment.getApplication().getSharedPreferences("tracker", Context.MODE_PRIVATE))

    @Test fun pendingAndDuplicateCallbacksDoNotDeleteScheduledContent() {
        val tracker = tracker()
        val attempt = tracker.begin(1, 2)
        assertFalse(tracker.complete(1, attempt, 0, true))
        assertFalse(tracker.complete(1, attempt, 0, true))
        assertTrue(tracker.complete(1, attempt, 1, true))
    }

    @Test fun failedPartPreservesScheduledContentAcrossTrackerRecreation() {
        val attempt = tracker().begin(1, 2)
        assertFalse(tracker().complete(1, attempt, 0, false))
        assertFalse(tracker().complete(1, attempt, 1, true))
    }

    @Test fun staleCallbacksCannotCompleteANewAttempt() {
        val tracker = tracker()
        val oldAttempt = tracker.begin(1, 1)
        val newAttempt = tracker.begin(1, 1)
        assertFalse(tracker.complete(1, oldAttempt, 0, true))
        assertTrue(tracker.complete(1, newAttempt, 0, true))
    }

    @Test fun deletingScheduledContentClearsItsTrackingState() {
        val tracker = tracker()
        val attempt = tracker.begin(1, 1)
        tracker.forget(1)
        assertFalse(tracker.complete(1, attempt, 0, true))
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("tracker", Context.MODE_PRIVATE)
        assertTrue(prefs.all.isEmpty())
    }
}
