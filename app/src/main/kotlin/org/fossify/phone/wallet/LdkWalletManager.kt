package org.fossify.phone.wallet

import android.content.Context
import android.util.Log
import org.fossify.commons.helpers.ensureBackgroundThread
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails

object LdkWalletManager {
    private const val TAG = "LdkWalletManager"
    private const val START_WAIT_TIMEOUT_MS = 60_000L

    private val lock = Any()

    @Volatile
    private var node: Node? = null

    @Volatile
    private var federationId: String? = null

    @Volatile
    private var lastError: Throwable? = null

    @Volatile
    private var isStarting: Boolean = false

    fun getLastErrorMessage(): String? = lastError?.let { it.toOneLineSummary() }

    fun isBusy(): Boolean = isStarting

    fun getRunningFederationId(): String? = federationId

    fun isRunning(): Boolean {
        return try {
            node?.status()?.isRunning == true
        } catch (_: Exception) {
            false
        }
    }

    fun getNodeId(): String? = try {
        node?.nodeId()
    } catch (_: Exception) {
        null
    }

    fun listBalances(): BalanceDetails? {
        return try {
            node?.listBalances()
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    fun listPayments(limit: Int = 50): List<PaymentDetails> {
        return try {
            node?.listPayments().orEmpty()
                .sortedByDescending { it.latestUpdateTimestamp }
                .take(limit)
        } catch (t: Throwable) {
            lastError = t
            emptyList()
        }
    }

    fun newOnchainAddress(): String? {
        return try {
            node?.onchainPayment()?.newAddress()
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    /**
     * Creates a BOLT11 invoice string.
     *
     * If [amountSats] is null, a variable-amount invoice is created.
     */
    fun createBolt11Invoice(amountSats: Long?, memo: String, expirySeconds: Int = 3600): String? {
        return try {
            val n = node ?: return null
            val desc = Bolt11InvoiceDescription.Direct(memo.ifBlank { "Cyber Phone" })
            val expiry = expirySeconds.toUInt()

            val invoice = if (amountSats != null) {
                if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                    lastError = IllegalArgumentException("Amount exceeds maximum transfer limit")
                    return null
                }
                val amountMsat = satsToMsatsOrNull(amountSats) ?: run {
                    lastError = IllegalArgumentException("Amount is too large")
                    return null
                }
                n.bolt11Payment().receive(amountMsat, desc, expiry)
            } else {
                n.bolt11Payment().receiveVariableAmount(desc, expiry)
            }
            invoice.toString()
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    fun isBolt11Invoice(text: String): Boolean {
        return runCatching {
            Bolt11Invoice.fromStr(text.trim())
            true
        }.getOrDefault(false)
    }

    /**
     * Pays a BOLT11 invoice.
     *
     * If the invoice is a variable-amount invoice, pass [amountSats] to pay it.
     */
    fun payBolt11Invoice(invoiceStr: String, amountSats: Long? = null): String? {
        val n = node ?: return null
        val invoice = try {
            Bolt11Invoice.fromStr(invoiceStr.trim())
        } catch (t: Throwable) {
            lastError = t
            return null
        }

        // Try without an amount first. If the invoice requires an amount, retry with one.
        val first = runCatching { n.bolt11Payment().send(invoice, null) }
        val firstId = first.getOrNull()
        if (firstId != null) {
            lastError = null
            return firstId
        }

        val sats = amountSats?.takeIf { it > 0L } ?: run {
            first.exceptionOrNull()?.let { lastError = it }
            return null
        }
        if (!WalletPolicy.isAmountWithinSingleTxLimit(sats)) {
            lastError = IllegalArgumentException("Amount exceeds maximum transfer limit")
            return null
        }
        val msat = satsToMsatsOrNull(sats) ?: run {
            lastError = IllegalArgumentException("Amount is too large")
            return null
        }
        val second = runCatching { n.bolt11Payment().sendUsingAmount(invoice, msat, null) }
        val secondId = second.getOrNull()
        if (secondId != null) {
            lastError = null
            return secondId
        }

        lastError = second.exceptionOrNull() ?: first.exceptionOrNull()
        return null
    }

    fun sendOnchain(address: String, amountSats: Long): String? {
        return try {
            val n = node ?: return null
            if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                lastError = IllegalArgumentException("Amount exceeds maximum transfer limit")
                return null
            }
            val sats = amountSats.coerceAtLeast(0L).toULong()
            n.onchainPayment().sendToAddress(address.trim(), sats, null)
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    fun ensureStarted(
        context: Context,
        federation: FederationEntry,
        callback: ((success: Boolean, error: String?) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        ensureBackgroundThread {
            val success = try {
                startInternal(appContext, federation)
                if (!waitForStartupToFinish(START_WAIT_TIMEOUT_MS)) {
                    lastError = RuntimeException("Wallet startup timed out")
                    false
                } else {
                    isRunningForFederation(federation.id)
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to start wallet", t)
                lastError = t
                false
            }

            callback?.invoke(success, if (success) null else lastError?.message)
        }
    }

    /**
     * Synchronous variant of [ensureStarted]. Call this off the UI thread.
     */
    fun ensureStartedBlocking(context: Context, federation: FederationEntry): Boolean {
        val appContext = context.applicationContext
        return try {
            startInternal(appContext, federation)
            if (!waitForStartupToFinish(START_WAIT_TIMEOUT_MS)) {
                lastError = RuntimeException("Wallet startup timed out")
                false
            } else {
                isRunningForFederation(federation.id)
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Failed to start wallet", t)
            lastError = t
            false
        }
    }

    fun stop(callback: (() -> Unit)? = null) {
        ensureBackgroundThread {
            synchronized(lock) {
                stopLocked()
            }
            callback?.invoke()
        }
    }

    fun syncWallets() {
        ensureBackgroundThread {
            try {
                node?.syncWallets()
            } catch (t: Throwable) {
                Log.w(TAG, "syncWallets() failed", t)
                lastError = t
            }
        }
    }

    /**
     * Synchronous variant of [syncWallets]. Call this off the UI thread.
     */
    fun syncWalletsBlocking(): Boolean {
        return try {
            node?.syncWallets()
            true
        } catch (t: Throwable) {
            Log.w(TAG, "syncWalletsBlocking() failed", t)
            lastError = t
            false
        }
    }

    @Throws(BuildException::class, NodeException::class)
    private fun startInternal(context: Context, federation: FederationEntry) {
        synchronized(lock) {
            if (isStarting) return

            // If we already have a node for this federation, just (re)start it.
            if (node != null && federationId == federation.id) {
                try {
                    node?.start()
                    lastError = null
                } catch (t: Throwable) {
                    lastError = t
                    throw t
                }
                return
            }

            isStarting = true
            stopLocked()
        }

        try {
            val built = buildNode(context, federation)
            built.start()

            synchronized(lock) {
                node = built
                federationId = federation.id
                lastError = null
            }

            // Run the first sync asynchronously to avoid blocking federation switches/UI refreshes.
            syncWallets()
        } finally {
            synchronized(lock) {
                isStarting = false
            }
        }
    }

    private fun stopLocked() {
        try {
            node?.stop()
        } catch (_: Throwable) {
        }

        try {
            node?.close()
        } catch (_: Throwable) {
        }

        node = null
        federationId = null
    }

    private fun buildNode(context: Context, federation: FederationEntry): Node {
        // ldk-node-android uses JNA internally; on some Android versions the JNA loader needs an
        // explicit hint (and/or extracted native libs) to find and load its native dispatch lib.
        // Do this here as a last line of defense, in addition to App.onCreate().
        configureJnaNativeLoading(context)

        val network = parseNetwork(federation.network)
        val esploraUrl = federation.esploraUrl?.trim().orEmpty().ifBlank { defaultEsploraUrl(network) }
        val rgsUrl = federation.rgsUrl?.trim().orEmpty().ifBlank { defaultRgsUrl(network) }

        val storageDir = WalletStoragePaths.ldkFederationDir(context, federation.id)
        if (!storageDir.exists()) {
            storageDir.mkdirs()
        }

        val builder = Builder()
        try {
            builder.setStorageDirPath(storageDir.absolutePath)
            builder.setNetwork(network)
            builder.setChainSourceEsplora(esploraUrl, null)
            if (rgsUrl.isNotBlank()) {
                builder.setGossipSourceRgs(rgsUrl)
            } else {
                builder.setGossipSourceP2p()
            }

            // Optional LSPS1 bootstrap (improves receive UX if the directory provides it).
            val lsps1NodeId = federation.lsps1NodeId?.trim().orEmpty()
            val lsps1Address = federation.lsps1Address?.trim().orEmpty()
            val lsps1Token = federation.lsps1Token?.trim()?.takeIf { it.isNotBlank() }
            if (lsps1NodeId.isNotBlank() && lsps1Address.isNotBlank()) {
                builder.setLiquiditySourceLsps1(lsps1NodeId, lsps1Address, lsps1Token)
            }

            builder.setLogFacadeLogger()
            return builder.buildWithFsStore()
        } finally {
            try {
                builder.close()
            } catch (_: Throwable) {
            }
        }
    }

    private fun configureJnaNativeLoading(context: Context) {
        try {
            val nativeDir = context.applicationInfo.nativeLibraryDir?.trim().orEmpty()
            if (nativeDir.isNotBlank()) {
                // Helps JNA find libjnidispatch.so on Android.
                System.setProperty("jna.boot.library.path", nativeDir)
                System.setProperty("jna.library.path", nativeDir)
            }

            // Preload the native libs explicitly; failures will be surfaced when the node starts.
            runCatching { System.loadLibrary("jnidispatch") }
            runCatching { System.loadLibrary("ldk_node") }
        } catch (_: Throwable) {
        }
    }

    private fun parseNetwork(network: String?): Network {
        return when (network?.trim()?.lowercase()) {
            "bitcoin", "mainnet", "btc" -> Network.BITCOIN
            "testnet", "test" -> Network.TESTNET
            "signet" -> Network.SIGNET
            "regtest" -> Network.REGTEST
            else -> Network.BITCOIN
        }
    }

    private fun defaultEsploraUrl(network: Network): String {
        return when (network) {
            Network.BITCOIN -> "https://blockstream.info/api"
            Network.TESTNET -> "https://blockstream.info/testnet/api"
            Network.SIGNET -> "https://blockstream.info/signet/api"
            Network.REGTEST -> "http://127.0.0.1:3002/api"
        }
    }

    private fun defaultRgsUrl(network: Network): String {
        // The RGS server endpoints are "base urls" (the node appends the last_sync_timestamp).
        return when (network) {
            Network.BITCOIN -> "https://rapidsync.lightningdevkit.org/snapshot"
            Network.TESTNET -> "https://rapidsync.lightningdevkit.org/testnet/snapshot"
            Network.SIGNET -> ""
            Network.REGTEST -> ""
        }
    }

    private fun Throwable.toOneLineSummary(): String {
        val parts = ArrayList<String>(4)

        fun describe(t: Throwable): String {
            val msg = t.message?.trim().orEmpty()
            return if (msg.isBlank()) t.javaClass.name else "${t.javaClass.name}: $msg"
        }

        parts.add(describe(this))
        var c = this.cause
        var depth = 0
        while (c != null && depth < 3) {
            parts.add("caused by ${describe(c)}")
            c = c.cause
            depth++
        }

        return parts.joinToString(" | ")
    }

    private fun waitForStartupToFinish(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(1_000L)
        while (System.currentTimeMillis() < deadline) {
            synchronized(lock) {
                if (!isStarting) return true
            }
            try {
                Thread.sleep(50L)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                lastError = e
                return false
            }
        }
        return false
    }

    private fun isRunningForFederation(expectedFederationId: String): Boolean {
        synchronized(lock) {
            if (federationId != expectedFederationId) return false
        }
        return try {
            node?.status()?.isRunning == true
        } catch (t: Throwable) {
            lastError = t
            false
        }
    }

    private fun satsToMsatsOrNull(sats: Long): ULong? {
        if (sats <= 0L) return null
        if (sats > Long.MAX_VALUE / 1000L) return null
        return (sats * 1000L).toULong()
    }
}
