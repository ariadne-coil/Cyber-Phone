package org.fossify.phone.wallet

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class WalletOperationGateTest {
    @Test fun simultaneousSendAttemptsStartOnlyOneSpend() {
        val gate = WalletOperationGate()
        val start = CountDownLatch(1)
        val done = CountDownLatch(8)
        val spends = AtomicInteger()
        val executor = Executors.newFixedThreadPool(8)
        try {
            repeat(8) { executor.execute { start.await(); if (gate.acquire()) spends.incrementAndGet(); done.countDown() } }
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(1, spends.get())
            assertTrue(gate.isBusy)
            gate.release()
            assertTrue(gate.acquire())
        } finally { executor.shutdownNow() }
    }
}
