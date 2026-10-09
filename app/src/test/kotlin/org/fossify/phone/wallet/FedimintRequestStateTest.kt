package org.fossify.phone.wallet

import org.fossify.phone.wallet.fedimint.FedimintRequestState
import org.junit.Assert.*
import org.junit.Test

class FedimintRequestStateTest {
    @Test fun timedOutQueuedRequestCannotDispatchLater() {
        val request = FedimintRequestState()
        request.cancelBeforeDispatch()
        assertFalse(request.tryDispatch())
        assertFalse(request.wasDispatched)
    }

    @Test fun timeoutAfterDispatchDoesNotAllowASecondMutation() {
        val request = FedimintRequestState()
        assertTrue(request.tryDispatch())
        request.cancelBeforeDispatch()
        assertTrue(request.wasDispatched)
        assertFalse(request.tryDispatch())
    }
}
