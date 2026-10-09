package org.fossify.phone.wallet.fedimint

import java.util.concurrent.atomic.AtomicInteger

/** A timeout and a queued main-thread dispatch must agree whether a wallet mutation started. */
internal class FedimintRequestState {
    private val state = AtomicInteger(0)
    val wasDispatched get() = state.get() == 1
    fun tryDispatch() = state.compareAndSet(0, 1)
    fun cancelBeforeDispatch() { state.compareAndSet(0, 2) }
}
