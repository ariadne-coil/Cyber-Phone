package org.fossify.phone.wallet

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossify.commons.helpers.ensureBackgroundThread
import org.lightningdevkit.ldknode.Builder
import org.lightningdevkit.ldknode.BuildException
import org.lightningdevkit.ldknode.BalanceDetails
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.Bolt11InvoiceDescription
import org.lightningdevkit.ldknode.Lsps1OrderStatus
import org.lightningdevkit.ldknode.Network
import org.lightningdevkit.ldknode.Node
import org.lightningdevkit.ldknode.NodeException
import org.lightningdevkit.ldknode.PaymentDetails
import org.lightningdevkit.ldknode.PaymentDirection
import org.lightningdevkit.ldknode.PaymentKind
import org.lightningdevkit.ldknode.PaymentState
import org.lightningdevkit.ldknode.PaymentStatus
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object LdkWalletManager {
    private const val TAG = "LdkWalletManager"
    private const val START_WAIT_TIMEOUT_MS = 60_000L
    private const val LIQUIDITY_BOOTSTRAP_TIMEOUT_MS = 45_000L
    private const val LIQUIDITY_BOOTSTRAP_POLL_MS = 2_500L
    private const val LIQUIDITY_BOOTSTRAP_MIN_CHANNEL_SATS = 50_000L
    private const val LIQUIDITY_BOOTSTRAP_CHANNEL_EXPIRY_BLOCKS = 2_016U
    private const val GATEWAY_BOOTSTRAP_MAX_CANDIDATES = 6
    private const val GATEWAY_BOOTSTRAP_MAX_ADDRS_PER_NODE = 3
    private const val GATEWAY_BOOTSTRAP_MAX_RANKING_NODES = 12
    private const val GATEWAY_BOOTSTRAP_PENDING_TTL_MS = 45L * 60L * 1000L
    private const val BOOTSTRAP_HTTP_TIMEOUT_SEC = 8L
    private const val ESPLORA_PREFLIGHT_TIMEOUT_MS = 2_500L
    private const val LIGHTNING_SEND_AWAIT_TIMEOUT_MS = 25_000L
    private const val LIGHTNING_SEND_AWAIT_POLL_MS = 1_000L
    private const val LIGHTNING_RECEIVE_AWAIT_TIMEOUT_MS = 45_000L
    private const val LIGHTNING_RECEIVE_AWAIT_POLL_MS = 1_000L
    private const val BALANCE_SNAPSHOT_MAX_AGE_MS = 10L * 60L * 1000L
    private const val FEERATE_RECOVERY_FAILURE_WINDOW_MS = 2L * 60L * 1000L
    private const val FEERATE_RECOVERY_ATTEMPT_COOLDOWN_MS = 90_000L
    private const val FEERATE_RECOVERY_MIN_FAILURES = 1
    private const val MEMPOOL_MAINNET_NODE_API = "https://mempool.space/api/v1/lightning/nodes/"
    private const val MEMPOOL_TESTNET_NODE_API = "https://mempool.space/testnet/api/v1/lightning/nodes/"
    private const val MEMPOOL_SIGNET_NODE_API = "https://mempool.space/signet/api/v1/lightning/nodes/"
    private const val MEMPOOL_MAINNET_CONNECTIVITY_RANKING = "https://mempool.space/api/v1/lightning/nodes/rankings/connectivity"
    private const val MEMPOOL_TESTNET_CONNECTIVITY_RANKING = "https://mempool.space/testnet/api/v1/lightning/nodes/rankings/connectivity"
    private const val MEMPOOL_SIGNET_CONNECTIVITY_RANKING = "https://mempool.space/signet/api/v1/lightning/nodes/rankings/connectivity"

    private val lock = Any()
    private val bootstrapHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(BOOTSTRAP_HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(BOOTSTRAP_HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(BOOTSTRAP_HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .callTimeout(BOOTSTRAP_HTTP_TIMEOUT_SEC, TimeUnit.SECONDS)
            .build()
    }

    @Volatile
    private var node: Node? = null

    @Volatile
    private var federationId: String? = null

    @Volatile
    private var activeLiquidityProviderId: String? = null

    @Volatile
    private var lastError: Throwable? = null

    @Volatile
    private var isStarting: Boolean = false

    @Volatile
    private var gatewayBootstrapPendingReference: String? = null

    @Volatile
    private var gatewayBootstrapPendingUntilMs: Long = 0L

    @Volatile
    private var appContextForRecovery: Context? = null

    @Volatile
    private var federationSnapshotForRecovery: FederationEntry? = null

    @Volatile
    private var activeEsploraUrl: String? = null

    @Volatile
    private var cachedBalanceSnapshot: BalanceDetails? = null

    @Volatile
    private var cachedBalanceSnapshotUpdatedAtMs: Long = 0L

    @Volatile
    private var consecutiveFeerateFailures: Int = 0

    @Volatile
    private var lastFeerateFailureAtMs: Long = 0L

    @Volatile
    private var lastFeerateRecoveryAttemptAtMs: Long = 0L

    private data class BootstrapPeer(
        val nodeId: String,
        val address: String,
    )

    private data class PendingGatewayReferenceStatus(
        val hasTrackedChannel: Boolean,
        val readyForSpend: Boolean,
        val shouldKeepWaiting: Boolean,
        val message: String? = null,
    )

    private val builtInBootstrapPeersByNetwork = mapOf(
        "bitcoin" to listOf(
            BootstrapPeer(
                nodeId = "03864ef025fde8fb587d989186ce6a4a186895ee44a926bfc370e2c366597a3f8f",
                address = "3.33.236.230:9735",
            ),
            BootstrapPeer(
                nodeId = "0217890e3aad8d35bc054f43acc00084b25229ecff0ab68debd82883ad65ee8266",
                address = "66.109.24.41:9735",
            ),
            BootstrapPeer(
                nodeId = "0242a4ae0c5bef18048fbecf995094b74bfb0f7391418d71ed394784373f41e4f3",
                address = "3.124.63.44:9735",
            ),
            BootstrapPeer(
                nodeId = "02f1a8c87607f415c8f22c00593002775941dea48869ce23096af27b0cfdcc0b69",
                address = "193.118.169.108:9735",
            ),
        ),
        "testnet" to listOf(
            BootstrapPeer(
                nodeId = "038863cf8ab91046230f561cd5b386cbff8309fa02e3f0c3ed161a3aeb64a643b9",
                address = "203.132.94.196:9735",
            ),
            BootstrapPeer(
                nodeId = "02312627fdf07fbdd7e5ddb136611bdde9b00d26821d14d94891395452f67af248",
                address = "66.109.24.42:9735",
            ),
            BootstrapPeer(
                nodeId = "03d2fc638243a9bdaf4a4244510c73e5af874e6fcf99deb3d532019ba3e3f57e4d",
                address = "3.212.213.41:9735",
            ),
            BootstrapPeer(
                nodeId = "02eadbd9e7557375161df8b646776a547c5cbc2e95b3071ec81553f8ec2cea3b8c",
                address = "18.118.231.3:9735",
            ),
        ),
        "signet" to listOf(
            BootstrapPeer(
                nodeId = "03ddab321b760433cbf561b615ef62ac7d318630c5f51d523aaf5395b90b751956",
                address = "103.99.170.201:39735",
            ),
            BootstrapPeer(
                nodeId = "02499ed23027d4698a6904ff4ec1b6085a61f10b9a6937f90438f9947e38e8ea86",
                address = "103.99.171.203:39735",
            ),
            BootstrapPeer(
                nodeId = "02dfb81e2f7a3c4c9e8a51b70ef82b4a24549cc2fab1f5b2fd636501774a918991",
                address = "103.99.171.201:39735",
            ),
        ),
    )

    data class LiquidityBootstrapResult(
        val success: Boolean,
        val pending: Boolean = false,
        val fundingReference: String? = null,
        val errorMessage: String? = null,
    )

    data class LightningToOnchainPlan(
        val requestedOnchainSats: Long,
        val currentOnchainSats: Long,
        val neededTopupSats: Long,
        val estimatedReleaseSats: Long,
        val channelsToClose: Int,
    )

    data class LightningToOnchainConvertResult(
        val success: Boolean,
        val pending: Boolean = false,
        val closedChannels: Int = 0,
        val estimatedReleaseSats: Long = 0L,
        val errorMessage: String? = null,
    )

    private data class ClosableChannel(
        val userChannelId: String,
        val counterpartyNodeId: String,
        val estimatedLocalSats: Long,
    )

    fun estimateLiquidityBootstrapFundingSats(requiredSats: Long): Long {
        if (requiredSats <= 0L) return 0L
        return (requiredSats + maxOf(10_000L, requiredSats / 10L))
            .coerceAtLeast(LIQUIDITY_BOOTSTRAP_MIN_CHANNEL_SATS)
    }

    fun estimateLightningToOnchainChannelCloseSats(requiredOnchainSats: Long): LightningToOnchainPlan? {
        if (requiredOnchainSats <= 0L) {
            lastError = IllegalArgumentException("Requested amount must be positive.")
            return null
        }
        if (!WalletPolicy.isAmountWithinSingleTxLimit(requiredOnchainSats)) {
            lastError = IllegalArgumentException("Requested amount exceeds maximum transfer limit.")
            return null
        }

        val n = runningNodeOrNull(operation = "estimate Lightning to on-chain exchange") ?: return null
        val planned = buildLightningToOnchainClosePlan(n, requiredOnchainSats)
        if (planned == null) {
            if (lastError == null) {
                lastError = IllegalStateException("Could not prepare channel-close conversion plan.")
            }
            return null
        }
        val plan = planned.first
        if (plan.channelsToClose <= 0 || plan.estimatedReleaseSats <= 0L) {
            lastError = IllegalStateException("No closable Lightning channels found for on-chain conversion.")
            return null
        }
        lastError = null
        return plan
    }

    fun convertLightningToOnchainByClosingChannelsBlocking(requiredOnchainSats: Long): LightningToOnchainConvertResult {
        if (requiredOnchainSats <= 0L) {
            val msg = "Requested amount must be positive."
            lastError = IllegalArgumentException(msg)
            return LightningToOnchainConvertResult(success = false, errorMessage = msg)
        }
        if (!WalletPolicy.isAmountWithinSingleTxLimit(requiredOnchainSats)) {
            val msg = "Requested amount exceeds maximum transfer limit."
            lastError = IllegalArgumentException(msg)
            return LightningToOnchainConvertResult(success = false, errorMessage = msg)
        }

        val n = runningNodeOrNull(operation = "convert Lightning to on-chain") ?: run {
            val msg = lastError?.toOneLineSummary() ?: "Wallet node unavailable"
            return LightningToOnchainConvertResult(success = false, errorMessage = msg)
        }

        val planned = buildLightningToOnchainClosePlan(n, requiredOnchainSats)
        if (planned == null) {
            val msg = lastError?.toOneLineSummary() ?: "Could not prepare channel-close conversion plan."
            return LightningToOnchainConvertResult(success = false, errorMessage = msg)
        }
        val plan = planned.first
        val channels = planned.second

        if (channels.isEmpty()) {
            val msg = "No closable Lightning channels found for on-chain conversion."
            lastError = IllegalStateException(msg)
            return LightningToOnchainConvertResult(success = false, errorMessage = msg)
        }

        val errors = mutableListOf<String>()
        var closedChannels = 0
        var estimatedRelease = 0L
        for (channel in channels) {
            val result = runCatching {
                n.closeChannel(channel.userChannelId, channel.counterpartyNodeId)
            }
            if (result.isSuccess) {
                closedChannels++
                estimatedRelease = saturatingAdd(estimatedRelease, channel.estimatedLocalSats)
            } else {
                val err = result.exceptionOrNull()?.toOneLineSummary()
                if (!err.isNullOrBlank()) {
                    errors.add(err)
                }
            }
        }

        if (closedChannels > 0) {
            val msg = buildString {
                append("Channel close submitted for ")
                append(closedChannels)
                append(" channel")
                if (closedChannels != 1) append('s')
                append(". Wait for confirmations for funds to become spendable on-chain.")
                if (errors.isNotEmpty()) {
                    append(" Some channels could not be closed: ")
                    append(errors.joinToString(" | "))
                }
            }
            lastError = RuntimeException(msg)
            return LightningToOnchainConvertResult(
                success = false,
                pending = true,
                closedChannels = closedChannels,
                estimatedReleaseSats = estimatedRelease,
                errorMessage = msg,
            )
        }

        val msg = errors.firstOrNull().orEmpty().ifBlank {
            "Could not close a Lightning channel for on-chain conversion."
        }
        lastError = RuntimeException(msg)
        return LightningToOnchainConvertResult(success = false, pending = false, errorMessage = msg)
    }

    fun getLastErrorMessage(): String? = lastError?.let { it.toOneLineSummary() }

    fun summarizeThrowableForUi(error: Throwable?): String? = error?.toOneLineSummary()

    fun normalizeExternalErrorMessage(raw: String?): String? {
        val text = raw?.trim().orEmpty()
        if (text.isBlank()) return null
        return mapKnownWalletErrorSummary(className = "", message = text)
            ?: if (looksLikeRawExceptionClass(text)) {
                "Wallet operation failed. Tap Sync and retry."
            } else {
                text
            }
    }

    fun isBusy(): Boolean = isStarting

    fun getRunningFederationId(): String? = federationId

    fun getActiveLiquidityProviderId(): String? = activeLiquidityProviderId

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
        return listBalancesInternal(allowFeerateRecovery = true)
    }

    private fun listBalancesInternal(allowFeerateRecovery: Boolean): BalanceDetails? {
        return try {
            val n = runningNodeOrNull(operation = "list balances") ?: return getFreshBalanceSnapshotOrNull()
            val balances = n.listBalances()
            cacheBalanceSnapshot(balances)
            resetFeerateFailureTracking()
            lastError = null
            balances
        } catch (t: Throwable) {
            if (allowFeerateRecovery && t.isLikelyFeerateFailure()) {
                if (noteFeerateFailureAndShouldRecover() &&
                    recoverFromFeerateFailureBlocking(
                        operation = "list balances",
                        allowRunningNodeRebuild = true,
                    )
                ) {
                    return listBalancesInternal(allowFeerateRecovery = false)
                }

                getFreshBalanceSnapshotOrNull()?.let { snapshot ->
                    // Keep the wallet usable in the UI if current feerate fetch is transiently failing.
                    lastError = t
                    return snapshot
                }
            }
            lastError = t
            null
        }
    }

    fun listPayments(limit: Int = 50): List<PaymentDetails> {
        return try {
            val n = runningNodeOrNull(operation = "list payments") ?: return emptyList()
            val items = n.listPayments().orEmpty()
                .sortedByDescending { it.latestUpdateTimestamp }
                .take(limit)
            lastError = null
            items
        } catch (t: Throwable) {
            lastError = t
            emptyList()
        }
    }

    fun newOnchainAddress(): String? {
        return try {
            val n = runningNodeOrNull(operation = "create on-chain address") ?: return null
            val address = n.onchainPayment().newAddress()
            lastError = null
            address
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
    fun createBolt11Invoice(
        amountSats: Long?,
        memo: String,
        expirySeconds: Int = 3600,
        preferJitChannel: Boolean = false,
    ): String? {
        return createBolt11InvoiceInternal(
            amountSats = amountSats,
            memo = memo,
            expirySeconds = expirySeconds,
            preferJitChannel = preferJitChannel,
            allowFeerateRecovery = true,
        )
    }

    private fun createBolt11InvoiceInternal(
        amountSats: Long?,
        memo: String,
        expirySeconds: Int,
        preferJitChannel: Boolean,
        allowFeerateRecovery: Boolean,
    ): String? {
        return try {
            val n = runningNodeOrNull(operation = "create invoice") ?: return null
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
                if (preferJitChannel) {
                    val jitFeeLimit = suggestedJitFeeLimitMsat(amountMsat)
                    val jitAttempt = runCatching {
                        n.bolt11Payment().receiveViaJitChannel(
                            amountMsat,
                            desc,
                            expiry,
                            jitFeeLimit,
                        )
                    }
                    val jitInvoice = jitAttempt.getOrNull()
                    if (jitInvoice != null) {
                        lastError = null
                        return jitInvoice.toString()
                    }
                    val jitFailure = runCatching {
                        n.bolt11Payment().receiveViaJitChannel(
                            amountMsat,
                            desc,
                            expiry,
                            null,
                        )
                    }
                    val jitFallbackInvoice = jitFailure.getOrNull()
                    if (jitFallbackInvoice != null) {
                        lastError = null
                        return jitFallbackInvoice.toString()
                    }
                    val inboundSats = runCatching { totalUsableInboundLiquiditySats(n.listChannels().orEmpty()) }
                        .getOrDefault(0L)
                    if (inboundSats < amountSats) {
                        val jitErr = jitFailure.exceptionOrNull() ?: jitAttempt.exceptionOrNull()
                        if (allowFeerateRecovery &&
                            jitErr != null &&
                            jitErr.isLikelyFeerateFailure() &&
                            recoverFromFeerateFailureBlocking("create invoice")
                        ) {
                            return createBolt11InvoiceInternal(
                                amountSats = amountSats,
                                memo = memo,
                                expirySeconds = expirySeconds,
                                preferJitChannel = preferJitChannel,
                                allowFeerateRecovery = false,
                            )
                        }
                        val reason = jitErr?.toOneLineSummary()
                        val message = buildString {
                            append("Incoming Lightning liquidity is too low for this invoice.")
                            append(" Available inbound: ")
                            append(inboundSats)
                            append(" sats.")
                            if (!reason.isNullOrBlank()) {
                                append(" ")
                                append(reason)
                            }
                        }
                        lastError = RuntimeException(message)
                        return null
                    }
                }
                n.bolt11Payment().receive(amountMsat, desc, expiry)
            } else {
                if (preferJitChannel) {
                    runCatching {
                        n.bolt11Payment().receiveVariableAmountViaJitChannel(
                            desc,
                            expiry,
                            null,
                        )
                    }.getOrNull() ?: n.bolt11Payment().receiveVariableAmount(desc, expiry)
                } else {
                    n.bolt11Payment().receiveVariableAmount(desc, expiry)
                }
            }
            lastError = null
            invoice.toString()
        } catch (t: Throwable) {
            lastError = t
            if (allowFeerateRecovery && t.isLikelyFeerateFailure() &&
                recoverFromFeerateFailureBlocking("create invoice")
            ) {
                return createBolt11InvoiceInternal(
                    amountSats = amountSats,
                    memo = memo,
                    expirySeconds = expirySeconds,
                    preferJitChannel = preferJitChannel,
                    allowFeerateRecovery = false,
                )
            }
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
        val n = runningNodeOrNull(operation = "pay invoice") ?: return null
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

    /**
     * Waits for an outgoing Lightning payment to reach a terminal state.
     *
     * Returns:
     * - SUCCEEDED when settled.
     * - FAILED when the node marks it failed.
     * - PENDING on timeout (still unresolved).
     * - null on lookup errors.
     */
    fun awaitOutgoingLightningPaymentStatusBlocking(
        paymentId: String,
        timeoutMs: Long = LIGHTNING_SEND_AWAIT_TIMEOUT_MS,
        pollMs: Long = LIGHTNING_SEND_AWAIT_POLL_MS,
    ): PaymentStatus? {
        val normalizedId = paymentId.trim()
        if (normalizedId.isBlank()) {
            lastError = IllegalArgumentException("Payment id is missing.")
            return null
        }

        val n = runningNodeOrNull(operation = "check payment status") ?: return null
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(5_000L)
        val pollDelay = pollMs.coerceAtLeast(250L)

        while (System.currentTimeMillis() < deadline) {
            val details = runCatching { n.payment(normalizedId) }.getOrElse {
                lastError = it
                return null
            }
            when (details?.status) {
                PaymentStatus.SUCCEEDED -> {
                    lastError = null
                    return PaymentStatus.SUCCEEDED
                }

                PaymentStatus.FAILED -> {
                    val msg = "Lightning payment failed before settlement."
                    lastError = RuntimeException(msg)
                    return PaymentStatus.FAILED
                }

                PaymentStatus.PENDING,
                null,
                -> {
                    runCatching { n.syncWallets() }
                    try {
                        Thread.sleep(pollDelay)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        lastError = e
                        return null
                    }
                }
            }
        }

        val pendingMsg = "Payment was submitted but is still pending."
        lastError = RuntimeException(pendingMsg)
        return PaymentStatus.PENDING
    }

    /**
     * Waits for an inbound invoice (identified by its BOLT11 payment hash) to settle.
     *
     * Returns:
     * - SUCCEEDED when settled.
     * - FAILED when a terminal failure is observed.
     * - PENDING on timeout (still unresolved).
     * - null on lookup/parsing errors.
     */
    fun awaitIncomingLightningInvoiceStatusBlocking(
        invoiceStr: String,
        timeoutMs: Long = LIGHTNING_RECEIVE_AWAIT_TIMEOUT_MS,
        pollMs: Long = LIGHTNING_RECEIVE_AWAIT_POLL_MS,
        failOnTimeout: Boolean = false,
    ): PaymentStatus? {
        val invoice = runCatching { Bolt11Invoice.fromStr(invoiceStr.trim()) }.getOrElse {
            lastError = it
            return null
        }
        val paymentHash = runCatching { invoice.paymentHash() }.getOrElse {
            lastError = it
            return null
        }.trim()
        if (paymentHash.isBlank()) {
            lastError = IllegalArgumentException("Invoice payment hash is missing.")
            return null
        }

        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(5_000L)
        val pollDelay = pollMs.coerceAtLeast(250L)
        var sawPending = false

        while (System.currentTimeMillis() < deadline) {
            val n = runningNodeOrNull(operation = "check incoming invoice status") ?: run {
                if (recoverFromFeerateFailureBlocking("check incoming invoice status")) {
                    continue
                }
                return null
            }
            var recoveredAfterListFailure = false
            val details = runCatching {
                n.listPayments()
                    .orEmpty()
                    .asSequence()
                    .filter { it.direction == PaymentDirection.INBOUND }
                    .filter { paymentMatchesHash(it, paymentHash) }
                    .maxByOrNull { it.latestUpdateTimestamp }
            }.getOrElse {
                if (it.isLikelyFeerateFailure() &&
                    recoverFromFeerateFailureBlocking("check incoming invoice status")
                ) {
                    recoveredAfterListFailure = true
                    return@getOrElse null
                }
                lastError = it
                return null
            }
            if (recoveredAfterListFailure) {
                continue
            }

            when (details?.status) {
                PaymentStatus.SUCCEEDED -> {
                    lastError = null
                    return PaymentStatus.SUCCEEDED
                }

                PaymentStatus.FAILED -> {
                    val msg = "Incoming invoice payment failed before settlement."
                    lastError = RuntimeException(msg)
                    return PaymentStatus.FAILED
                }

                PaymentStatus.PENDING -> {
                    sawPending = true
                }

                null -> {
                    // Keep polling; the payment may not have reached the local list yet.
                }
            }

            var recoveredAfterSync = false
            runCatching { n.syncWallets() }.onFailure { syncErr ->
                if (syncErr.isLikelyFeerateFailure() &&
                    recoverFromFeerateFailureBlocking("check incoming invoice status")
                ) {
                    recoveredAfterSync = true
                }
            }
            if (recoveredAfterSync) {
                continue
            }
            try {
                Thread.sleep(pollDelay)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                lastError = e
                return null
            }
        }

        if (failOnTimeout) {
            runCatching {
                runningNodeOrNull(operation = "fail inbound invoice", setError = false)
                    ?.bolt11Payment()
                    ?.failForHash(paymentHash)
            }
        }
        val pendingMsg = if (sawPending) {
            "Incoming invoice payment is still pending."
        } else {
            "No incoming payment was observed for the invoice before timeout."
        }
        lastError = RuntimeException(pendingMsg)
        return PaymentStatus.PENDING
    }

    fun getUsableInboundLiquiditySats(): Long? {
        return try {
            val n = runningNodeOrNull(operation = "check inbound liquidity") ?: return null
            val channels = n.listChannels().orEmpty()
            val total = totalUsableInboundLiquiditySats(channels)
            lastError = null
            total
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    fun discoverLspsCandidatesFromGatewayHints(
        network: String?,
        gatewayNodeIds: List<String>,
        maxCandidates: Int = 6,
    ): List<Pair<String, String>> {
        val normalizedNodeIds = gatewayNodeIds
            .asSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
            .toList()
        if (normalizedNodeIds.isEmpty()) return emptyList()

        val normalizedNetwork = normalizeNetworkLabel(network)
        val candidates = fetchMempoolSocketCandidates(
            network = normalizedNetwork,
            nodeIds = normalizedNodeIds,
            maxPerNode = GATEWAY_BOOTSTRAP_MAX_ADDRS_PER_NODE,
            maxTotal = maxCandidates.coerceAtLeast(1),
        )
        return candidates.take(maxCandidates.coerceAtLeast(1))
    }

    fun sendOnchain(address: String, amountSats: Long): String? {
        return try {
            val n = runningNodeOrNull(operation = "send on-chain payment") ?: return null
            if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                lastError = IllegalArgumentException("Amount exceeds maximum transfer limit")
                return null
            }
            val sats = amountSats.coerceAtLeast(0L).toULong()
            val txId = n.onchainPayment().sendToAddress(address.trim(), sats, null)
            lastError = null
            txId
        } catch (t: Throwable) {
            lastError = t
            null
        }
    }

    /**
     * Attempts to bootstrap outbound Lightning liquidity from the node's configured LSPS source.
     *
     * This is best-effort and may require on-chain confirmation before liquidity becomes spendable.
     */
    fun bootstrapOutboundLiquidityBlocking(
        context: Context,
        requiredSats: Long,
        timeoutMs: Long = LIQUIDITY_BOOTSTRAP_TIMEOUT_MS,
    ): LiquidityBootstrapResult {
        if (requiredSats <= 0L) {
            return LiquidityBootstrapResult(success = true)
        }

        val n = runningNodeOrNull(operation = "bootstrap Lightning liquidity") ?: run {
            val msg = lastError?.toOneLineSummary() ?: "Wallet node unavailable"
            return LiquidityBootstrapResult(success = false, errorMessage = msg)
        }
        val providerId = activeLiquidityProviderId?.trim()

        val desiredChannelSats = (requiredSats + maxOf(10_000L, requiredSats / 10L))
            .coerceAtLeast(LIQUIDITY_BOOTSTRAP_MIN_CHANNEL_SATS)
        if (!WalletPolicy.isAmountWithinSingleTxLimit(desiredChannelSats)) {
            val msg = "Requested liquidity amount exceeds the configured transfer limit."
            lastError = IllegalArgumentException(msg)
            return LiquidityBootstrapResult(success = false, errorMessage = msg)
        }

        val order = try {
            n.lsps1Liquidity().requestChannel(
                0UL,
                desiredChannelSats.toULong(),
                LIQUIDITY_BOOTSTRAP_CHANNEL_EXPIRY_BLOCKS,
                false,
            )
        } catch (t: Throwable) {
            lastError = t
            FederationDirectoryManager.recordLiquidityProviderOutcome(
                context = context,
                providerId = providerId,
                success = false,
            )
            return LiquidityBootstrapResult(success = false, errorMessage = t.toOneLineSummary())
        }

        val fundingReference = try {
            payExpectedLiquidityOrderPayment(n, order)
        } catch (t: Throwable) {
            lastError = t
            FederationDirectoryManager.recordLiquidityProviderOutcome(
                context = context,
                providerId = providerId,
                success = false,
            )
            return LiquidityBootstrapResult(success = false, errorMessage = t.toOneLineSummary())
        }

        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(15_000L)
        while (System.currentTimeMillis() < deadline) {
            val lightningNow = runCatching {
                n.listBalances().totalLightningBalanceSats.toLongSaturated()
            }.getOrDefault(0L)
            if (lightningNow >= requiredSats) {
                lastError = null
                FederationDirectoryManager.recordLiquidityProviderOutcome(
                    context = context,
                    providerId = providerId,
                    success = true,
                )
                return LiquidityBootstrapResult(
                    success = true,
                    fundingReference = fundingReference,
                )
            }

            val status = runCatching {
                n.lsps1Liquidity().checkOrderStatus(order.orderId)
            }.getOrNull()

            if (status != null) {
                if (status.channelState != null) {
                    runCatching { n.syncWallets() }
                    val postSync = runCatching {
                        n.listBalances().totalLightningBalanceSats.toLongSaturated()
                    }.getOrDefault(0L)
                    if (postSync >= requiredSats) {
                        lastError = null
                        return LiquidityBootstrapResult(
                            success = true,
                            fundingReference = fundingReference,
                        )
                    }
                }

                val refunded = (
                    status.paymentOptions.bolt11?.state == PaymentState.REFUNDED ||
                        status.paymentOptions.onchain?.state == PaymentState.REFUNDED
                    )
                if (refunded) {
                    val msg = "Liquidity order was refunded by the provider."
                    lastError = RuntimeException(msg)
                    FederationDirectoryManager.recordLiquidityProviderOutcome(
                        context = context,
                        providerId = providerId,
                        success = false,
                    )
                    return LiquidityBootstrapResult(success = false, errorMessage = msg)
                }
            }

            try {
                Thread.sleep(LIQUIDITY_BOOTSTRAP_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                lastError = e
                FederationDirectoryManager.recordLiquidityProviderOutcome(
                    context = context,
                    providerId = providerId,
                    success = false,
                )
                return LiquidityBootstrapResult(success = false, errorMessage = e.toOneLineSummary())
            }
        }

        val pendingMsg = buildString {
            append("Liquidity funding was submitted, but the channel is not ready yet.")
            append(" Wait for confirmations and retry.")
            if (!fundingReference.isNullOrBlank()) {
                append(" Reference: ")
                append(fundingReference)
                append(".")
            }
        }
        lastError = RuntimeException(pendingMsg)
        FederationDirectoryManager.recordLiquidityProviderOutcome(
            context = context,
            providerId = providerId,
            success = false,
        )
        return LiquidityBootstrapResult(
            success = false,
            pending = true,
            fundingReference = fundingReference,
            errorMessage = pendingMsg,
        )
    }

    /**
     * Attempts a no-provider outbound-liquidity bootstrap by opening a direct channel to
     * gateway node hints (typically derived from invoice route hints / federation metadata).
     *
     * This avoids manual provider setup, but channel usability may still depend on peer policy
     * and confirmation timing.
     */
    fun bootstrapOutboundLiquidityViaGatewayHintsBlocking(
        context: Context,
        gatewayNodeIds: List<String>,
        requiredSats: Long,
        network: String? = null,
        timeoutMs: Long = LIQUIDITY_BOOTSTRAP_TIMEOUT_MS,
    ): LiquidityBootstrapResult {
        if (requiredSats <= 0L) {
            return LiquidityBootstrapResult(success = true)
        }

        val n = runningNodeOrNull(operation = "bootstrap Lightning liquidity via gateway hints") ?: run {
            val msg = lastError?.toOneLineSummary() ?: "Wallet node unavailable"
            return LiquidityBootstrapResult(success = false, errorMessage = msg)
        }

        val nowMs = System.currentTimeMillis()
        val pendingRef = gatewayBootstrapPendingReference
        if (!pendingRef.isNullOrBlank() && nowMs < gatewayBootstrapPendingUntilMs) {
            runCatching { n.syncWallets() }
            val pendingStatus = inspectPendingGatewayReference(
                n = n,
                reference = pendingRef,
                requiredSats = requiredSats,
            )
            if (pendingStatus.readyForSpend || hasEnoughSpendableLiquidity(n, requiredSats)) {
                gatewayBootstrapPendingReference = null
                gatewayBootstrapPendingUntilMs = 0L
                lastError = null
                Log.i(TAG, "Gateway bootstrap pending reference is now spendable: $pendingRef")
                return LiquidityBootstrapResult(
                    success = true,
                    fundingReference = pendingRef,
                )
            }

            if (pendingStatus.hasTrackedChannel && pendingStatus.shouldKeepWaiting) {
                val pendingMsg = pendingStatus.message
                    ?: "Automatic liquidity channel bootstrap is still pending ($pendingRef). Wait for confirmations and retry."
                lastError = RuntimeException(pendingMsg)
                Log.i(TAG, pendingMsg)
                return LiquidityBootstrapResult(
                    success = false,
                    pending = true,
                    fundingReference = pendingRef,
                    errorMessage = pendingMsg,
                )
            }

            // The tracked pending channel is stale/disappeared or known-not-viable; retry bootstrap.
            Log.w(TAG, "Discarding stale pending gateway bootstrap reference: $pendingRef")
            gatewayBootstrapPendingReference = null
            gatewayBootstrapPendingUntilMs = 0L
        }

        val normalizedNodeIds = gatewayNodeIds
            .asSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
            .toList()

        val desiredChannelSats = (requiredSats + maxOf(10_000L, requiredSats / 10L))
            .coerceAtLeast(LIQUIDITY_BOOTSTRAP_MIN_CHANNEL_SATS)
        if (!WalletPolicy.isAmountWithinSingleTxLimit(desiredChannelSats)) {
            val msg = "Requested liquidity amount exceeds the configured transfer limit."
            lastError = IllegalArgumentException(msg)
            return LiquidityBootstrapResult(success = false, errorMessage = msg)
        }

        val normalizedNetwork = normalizeNetworkLabel(network)
        val candidates = linkedSetOf<Pair<String, String>>()
        val graph = runCatching { n.networkGraph() }.getOrNull()
        if (graph != null) {
            for (nodeId in normalizedNodeIds) {
                val addresses = runCatching { graph.node(nodeId)?.announcementInfo?.addresses.orEmpty() }
                    .getOrDefault(emptyList())
                    .mapNotNull { normalizeSocketAddress(it) }
                    .distinct()
                    .take(GATEWAY_BOOTSTRAP_MAX_ADDRS_PER_NODE)
                for (address in addresses) {
                    candidates.add(nodeId to address)
                    if (candidates.size >= GATEWAY_BOOTSTRAP_MAX_CANDIDATES) {
                        break
                    }
                }
                if (candidates.size >= GATEWAY_BOOTSTRAP_MAX_CANDIDATES) {
                    break
                }
            }
        }

        if (candidates.isEmpty()) {
            fetchMempoolSocketCandidates(
                network = normalizedNetwork,
                nodeIds = normalizedNodeIds,
                maxPerNode = GATEWAY_BOOTSTRAP_MAX_ADDRS_PER_NODE,
                maxTotal = GATEWAY_BOOTSTRAP_MAX_CANDIDATES,
            ).forEach { candidates.add(it) }
        }

        if (candidates.isEmpty()) {
            val fallback = fetchPublicBootstrapCandidates(
                network = normalizedNetwork,
                excludedNodeIds = normalizedNodeIds.toSet(),
                maxTotal = GATEWAY_BOOTSTRAP_MAX_CANDIDATES,
            )
            fallback.forEach { candidates.add(it) }
        }

        if (candidates.isEmpty()) {
            val msg = "Gateway hints were found, but no reachable public Lightning peers were available for channel bootstrap."
            lastError = IllegalStateException(msg)
            return LiquidityBootstrapResult(success = false, errorMessage = msg)
        }

        if (hasEnoughSpendableLiquidity(n, requiredSats)) {
            gatewayBootstrapPendingReference = null
            gatewayBootstrapPendingUntilMs = 0L
            lastError = null
            return LiquidityBootstrapResult(success = true)
        }

        val errors = linkedSetOf<String>()
        val submittedChannels = mutableListOf<String>()
        val perCandidateProbeMs = 3_000L

        for ((nodeId, address) in candidates.take(GATEWAY_BOOTSTRAP_MAX_CANDIDATES)) {
            val channelRefLegacy = "$nodeId@$address"

            val openResult = runCatching {
                n.openChannel(nodeId, address, desiredChannelSats.toULong(), null, null)
            }
            if (!openResult.isSuccess) {
                val msg = openResult.exceptionOrNull()?.toOneLineSummary()
                if (!msg.isNullOrBlank()) {
                    errors.add(msg)
                }
                continue
            }
            val openedUserChannelId = openResult.getOrNull()?.toString().orEmpty()
            val channelRef = encodeGatewayPendingReference(
                userChannelId = openedUserChannelId,
                nodeId = nodeId,
                address = address,
            ).ifBlank { channelRefLegacy }
            submittedChannels.add(channelRef)
            Log.i(TAG, "Opened bootstrap channel candidate $channelRef")

            // Channel opening usually requires confirmation time; only do a short immediate probe,
            // then return a pending result so the UI does not block for minutes.
            val pollDeadline = System.currentTimeMillis() + perCandidateProbeMs
            while (System.currentTimeMillis() < pollDeadline) {
                if (hasEnoughSpendableLiquidity(n, requiredSats)) {
                    gatewayBootstrapPendingReference = null
                    gatewayBootstrapPendingUntilMs = 0L
                    lastError = null
                    return LiquidityBootstrapResult(
                        success = true,
                        fundingReference = channelRef,
                    )
                }

                try {
                    Thread.sleep(LIQUIDITY_BOOTSTRAP_POLL_MS)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    lastError = e
                    return LiquidityBootstrapResult(success = false, errorMessage = e.toOneLineSummary())
                }
            }
        }

        if (submittedChannels.isNotEmpty()) {
            val firstRef = submittedChannels.first()
            val pendingStatus = inspectPendingGatewayReference(
                n = n,
                reference = firstRef,
                requiredSats = requiredSats,
            )
            val pendingMsg = buildString {
                append("Automatic liquidity channel bootstrap was started")
                append(" (")
                append(firstRef.substringAfter('|', firstRef))
                if (submittedChannels.size > 1) {
                    append(" +")
                    append(submittedChannels.size - 1)
                    append(" more")
                }
                append(")")
                val details = pendingStatus.message
                    ?.substringAfter(").", missingDelimiterValue = "")
                    ?.trim()
                    .orEmpty()
                if (details.isNotBlank()) {
                    append(". ")
                    append(details)
                } else {
                    append(", but liquidity is not spendable yet. Wait for confirmations and retry.")
                }
            }
            gatewayBootstrapPendingReference = firstRef
            gatewayBootstrapPendingUntilMs = System.currentTimeMillis() + GATEWAY_BOOTSTRAP_PENDING_TTL_MS
            lastError = RuntimeException(pendingMsg)
            Log.i(TAG, pendingMsg)
            return LiquidityBootstrapResult(
                success = false,
                pending = true,
                fundingReference = firstRef,
                errorMessage = pendingMsg,
            )
        }

        val msg = if (errors.isEmpty()) {
            "Could not open a bootstrap Lightning channel to any reachable peer."
        } else {
            errors.first()
        }
        gatewayBootstrapPendingReference = null
        gatewayBootstrapPendingUntilMs = 0L
        lastError = RuntimeException(msg)
        return LiquidityBootstrapResult(
            success = false,
            pending = false,
            errorMessage = msg,
        )
    }

    private fun hasEnoughSpendableLiquidity(n: Node, requiredSats: Long): Boolean {
        if (requiredSats <= 0L) return true
        val channels = runCatching { n.listChannels() }.getOrDefault(emptyList())
        val outboundSats = totalUsableOutboundLiquiditySats(channels)
        if (outboundSats >= requiredSats) {
            return true
        }
        val balanceSats = runCatching {
            n.listBalances().totalLightningBalanceSats.toLongSaturated()
        }.getOrDefault(0L)
        return balanceSats >= requiredSats
    }

    private fun inspectPendingGatewayReference(
        n: Node,
        reference: String,
        requiredSats: Long,
    ): PendingGatewayReferenceStatus {
        val channels = runCatching { n.listChannels() }.getOrDefault(emptyList())
        if (channels.isEmpty()) {
            return PendingGatewayReferenceStatus(
                hasTrackedChannel = false,
                readyForSpend = false,
                shouldKeepWaiting = false,
            )
        }

        val totalUsableSats = totalUsableOutboundLiquiditySats(channels)
        if (totalUsableSats >= requiredSats) {
            return PendingGatewayReferenceStatus(
                hasTrackedChannel = true,
                readyForSpend = true,
                shouldKeepWaiting = false,
            )
        }

        val expectedUserChannelId = parseGatewayPendingUserChannelId(reference)
        val expectedNodeId = parseGatewayPendingNodeId(reference)
        val tracked = channels.filter { channel ->
            when {
                !expectedUserChannelId.isNullOrBlank() -> channel.userChannelId.equals(expectedUserChannelId, ignoreCase = true)
                !expectedNodeId.isNullOrBlank() -> channel.counterpartyNodeId.equals(expectedNodeId, ignoreCase = true)
                else -> false
            }
        }
        if (tracked.isEmpty()) {
            return PendingGatewayReferenceStatus(
                hasTrackedChannel = false,
                readyForSpend = false,
                shouldKeepWaiting = false,
            )
        }

        val candidate = tracked.maxByOrNull { channel ->
            val readyWeight = if (channel.isChannelReady) 2 else 0
            val usableWeight = if (channel.isUsable) 1 else 0
            val capacity = msatsToSats(minOf(channel.nextOutboundHtlcLimitMsat, channel.outboundCapacityMsat))
            (readyWeight + usableWeight) * 1_000_000L + capacity
        } ?: tracked.first()

        val candidateSats = msatsToSats(minOf(candidate.nextOutboundHtlcLimitMsat, candidate.outboundCapacityMsat))
        val confirmations = candidate.confirmations?.toLong()
        val confirmationsRequired = candidate.confirmationsRequired?.toLong()
        val confirmationsReady = confirmations != null &&
            confirmationsRequired != null &&
            confirmationsRequired > 0L &&
            confirmations >= confirmationsRequired

        if (candidate.isUsable && candidate.isChannelReady && candidateSats >= requiredSats) {
            return PendingGatewayReferenceStatus(
                hasTrackedChannel = true,
                readyForSpend = true,
                shouldKeepWaiting = false,
            )
        }

        val shouldKeepWaiting = when {
            !candidate.isChannelReady -> true
            !candidate.isUsable && confirmationsRequired != null && confirmations != null && confirmations < confirmationsRequired -> true
            !candidate.isUsable && confirmationsReady -> false
            candidate.isUsable && candidateSats <= 0L -> false
            else -> !candidate.isUsable
        }

        val message = buildString {
            append("Automatic liquidity channel bootstrap is still pending (")
            append(reference.substringAfter('|', reference))
            append("). ")
            when {
                !candidate.isChannelReady -> {
                    append("Channel opening is in progress")
                }

                !candidate.isUsable && confirmationsRequired != null && confirmations != null && confirmations < confirmationsRequired -> {
                    append("Waiting for channel confirmations")
                }

                !candidate.isUsable -> {
                    append("Channel is not usable yet")
                }

                candidateSats > 0L && candidateSats < requiredSats -> {
                    append("Channel capacity is below the required amount")
                }

                else -> {
                    append("Liquidity is not spendable yet")
                }
            }
            if (confirmationsRequired != null && confirmationsRequired > 0L && confirmations != null) {
                append(" (")
                append(confirmations.coerceAtLeast(0L))
                append("/")
                append(confirmationsRequired)
                append(" confirmations)")
            }
            append(". Wait for confirmations and retry.")
        }

        return PendingGatewayReferenceStatus(
            hasTrackedChannel = true,
            readyForSpend = false,
            shouldKeepWaiting = shouldKeepWaiting,
            message = message,
        )
    }

    private fun buildLightningToOnchainClosePlan(
        n: Node,
        requiredOnchainSats: Long,
    ): Pair<LightningToOnchainPlan, List<ClosableChannel>>? {
        val balances = runCatching { n.listBalances() }.getOrElse {
            lastError = it
            return null
        }
        val currentOnchain = balances.spendableOnchainBalanceSats.toLongSaturated()
        val needed = requiredOnchainSats.coerceAtLeast(0L)

        val channels = runCatching { n.listChannels() }.getOrElse {
            lastError = it
            return null
        }
        if (channels.isEmpty()) {
            lastError = IllegalStateException("No Lightning channels available for on-chain conversion.")
            return null
        }

        val candidates = channels
            .asSequence()
            .mapNotNull { channel ->
                val userChannelId = channel.userChannelId.trim()
                val counterparty = channel.counterpartyNodeId.trim()
                if (userChannelId.isBlank() || counterparty.isBlank()) return@mapNotNull null
                val estimated = msatsToSats(channel.outboundCapacityMsat)
                if (estimated <= 0L) return@mapNotNull null
                ClosableChannel(
                    userChannelId = userChannelId,
                    counterpartyNodeId = counterparty,
                    estimatedLocalSats = estimated,
                )
            }
            .sortedBy { it.estimatedLocalSats }
            .toList()

        if (candidates.isEmpty()) {
            lastError = IllegalStateException("No spendable Lightning channel balance is available to move on-chain.")
            return null
        }

        val selected = mutableListOf<ClosableChannel>()
        var accumulated = 0L
        for (candidate in candidates) {
            selected.add(candidate)
            accumulated = saturatingAdd(accumulated, candidate.estimatedLocalSats)
            if (accumulated >= needed) {
                break
            }
        }

        if (accumulated < needed) {
            lastError = IllegalStateException(
                "Not enough Lightning channel balance to move ${requiredOnchainSats} sats on-chain."
            )
            return null
        }

        val plan = LightningToOnchainPlan(
            requestedOnchainSats = requiredOnchainSats,
            currentOnchainSats = currentOnchain,
            neededTopupSats = needed,
            estimatedReleaseSats = accumulated,
            channelsToClose = selected.size,
        )
        return plan to selected
    }

    private fun totalUsableOutboundLiquiditySats(channels: List<org.lightningdevkit.ldknode.ChannelDetails>): Long {
        var total = 0L
        for (channel in channels) {
            if (!channel.isUsable || !channel.isChannelReady || !channel.isOutbound) {
                continue
            }
            val outboundMsat = minOf(channel.nextOutboundHtlcLimitMsat, channel.outboundCapacityMsat)
            val sats = msatsToSats(outboundMsat)
            if (sats <= 0L) continue
            total = if (Long.MAX_VALUE - total < sats) Long.MAX_VALUE else total + sats
        }
        return total
    }

    private fun totalUsableInboundLiquiditySats(channels: List<org.lightningdevkit.ldknode.ChannelDetails>): Long {
        var total = 0L
        for (channel in channels) {
            if (!channel.isUsable || !channel.isChannelReady) {
                continue
            }
            val inboundLimit = channel.inboundHtlcMaximumMsat ?: channel.inboundCapacityMsat
            val inboundMsat = minOf(channel.inboundCapacityMsat, inboundLimit)
            val sats = msatsToSats(inboundMsat)
            if (sats <= 0L) continue
            total = if (Long.MAX_VALUE - total < sats) Long.MAX_VALUE else total + sats
        }
        return total
    }

    private fun paymentMatchesHash(details: PaymentDetails, expectedHash: String): Boolean {
        val normalizedExpected = expectedHash.trim()
        if (normalizedExpected.isBlank()) return false
        return when (val kind = details.kind) {
            is PaymentKind.Bolt11 -> kind.hash.equals(normalizedExpected, ignoreCase = true)
            is PaymentKind.Bolt11Jit -> kind.hash.equals(normalizedExpected, ignoreCase = true)
            else -> false
        }
    }

    private fun suggestedJitFeeLimitMsat(amountMsat: ULong): ULong? {
        val msat = amountMsat.toLongSaturated()
        if (msat <= 0L) return null
        val proportional = msat / 10L // 10%
        val bounded = proportional.coerceAtLeast(1_000L).coerceAtMost(500_000L)
        return bounded.toULong()
    }

    private fun msatsToSats(msat: ULong): Long {
        val msatLong = msat.toLongSaturated()
        if (msatLong <= 0L) return 0L
        return msatLong / 1000L
    }

    private fun saturatingAdd(current: Long, delta: Long): Long {
        if (delta <= 0L) return current
        return if (Long.MAX_VALUE - current < delta) Long.MAX_VALUE else current + delta
    }

    private fun encodeGatewayPendingReference(
        userChannelId: String,
        nodeId: String,
        address: String,
    ): String {
        val normalizedUserChannelId = userChannelId.trim()
        val fallback = "$nodeId@$address"
        return if (normalizedUserChannelId.isBlank()) fallback else "$normalizedUserChannelId|$fallback"
    }

    private fun parseGatewayPendingUserChannelId(reference: String?): String? {
        val ref = reference?.trim().orEmpty()
        if (ref.isBlank() || !ref.contains('|')) return null
        return ref.substringBefore('|').trim().ifBlank { null }
    }

    private fun parseGatewayPendingNodeId(reference: String?): String? {
        val ref = reference?.trim().orEmpty()
        if (ref.isBlank()) return null
        val tail = if (ref.contains('|')) ref.substringAfter('|') else ref
        val nodeId = tail.substringBefore('@').trim()
        if (!nodeId.matches(Regex("^(02|03)[0-9a-fA-F]{64}$"))) return null
        return nodeId
    }

    private fun fetchMempoolSocketCandidates(
        network: String,
        nodeIds: List<String>,
        maxPerNode: Int,
        maxTotal: Int,
    ): List<Pair<String, String>> {
        if (nodeIds.isEmpty()) return emptyList()
        val out = linkedSetOf<Pair<String, String>>()
        for (nodeId in nodeIds) {
            val sockets = fetchNodeSocketsFromMempool(nodeId, network)
                .take(maxPerNode.coerceAtLeast(1))
            for (socket in sockets) {
                out.add(nodeId to socket)
                if (out.size >= maxTotal) {
                    return out.toList()
                }
            }
        }
        return out.toList()
    }

    private fun fetchPublicBootstrapCandidates(
        network: String,
        excludedNodeIds: Set<String>,
        maxTotal: Int,
    ): List<Pair<String, String>> {
        val out = linkedSetOf<Pair<String, String>>()
        val rankingNodeIds = fetchTopConnectivityNodesFromMempool(
            network = network,
            limit = GATEWAY_BOOTSTRAP_MAX_RANKING_NODES,
        )
        for (nodeId in rankingNodeIds) {
            if (nodeId in excludedNodeIds) continue
            val sockets = fetchNodeSocketsFromMempool(nodeId, network)
                .take(GATEWAY_BOOTSTRAP_MAX_ADDRS_PER_NODE)
            for (socket in sockets) {
                out.add(nodeId to socket)
                if (out.size >= maxTotal) {
                    return out.toList()
                }
            }
        }

        if (out.isEmpty()) {
            val fallbackPeers = builtInBootstrapPeersByNetwork[network].orEmpty()
            for (peer in fallbackPeers) {
                if (peer.nodeId in excludedNodeIds) continue
                val normalizedAddress = normalizeSocketAddress(peer.address) ?: continue
                out.add(peer.nodeId to normalizedAddress)
                if (out.size >= maxTotal) {
                    break
                }
            }
        }

        return out.toList()
    }

    private fun fetchTopConnectivityNodesFromMempool(network: String, limit: Int): List<String> {
        val rankingUrl = mempoolConnectivityRankingUrl(network) ?: return emptyList()
        val raw = httpGetBody(rankingUrl) ?: return emptyList()
        val arr = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(limit.coerceAtLeast(1))
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val nodeId = item.optString("publicKey")
                .ifBlank { item.optString("public_key") }
                .trim()
            if (!nodeId.matches(Regex("^(02|03)[0-9a-fA-F]{64}$"))) continue
            out.add(nodeId)
            if (out.size >= limit) break
        }
        return out
    }

    private fun fetchNodeSocketsFromMempool(nodeId: String, network: String): List<String> {
        if (!nodeId.matches(Regex("^(02|03)[0-9a-fA-F]{64}$"))) return emptyList()
        val base = mempoolNodeApiBase(network) ?: return emptyList()
        val raw = httpGetBody(base + nodeId) ?: return emptyList()
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return emptyList()
        val rawSockets = when (val value = obj.opt("sockets")) {
            is String -> value.split(',')
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    val socket = value.optString(i).trim()
                    if (socket.isNotBlank()) add(socket)
                }
            }
            else -> emptyList()
        }
        return rawSockets
            .mapNotNull { normalizeSocketAddress(it) }
            .distinct()
    }

    private fun mempoolNodeApiBase(network: String): String? {
        return when (network) {
            "bitcoin" -> MEMPOOL_MAINNET_NODE_API
            "testnet" -> MEMPOOL_TESTNET_NODE_API
            "signet" -> MEMPOOL_SIGNET_NODE_API
            else -> null
        }
    }

    private fun mempoolConnectivityRankingUrl(network: String): String? {
        return when (network) {
            "bitcoin" -> MEMPOOL_MAINNET_CONNECTIVITY_RANKING
            "testnet" -> MEMPOOL_TESTNET_CONNECTIVITY_RANKING
            "signet" -> MEMPOOL_SIGNET_CONNECTIVITY_RANKING
            else -> null
        }
    }

    private fun normalizeNetworkLabel(network: String?): String {
        return when (network?.trim()?.lowercase()) {
            "bitcoin", "mainnet", "btc", "" -> "bitcoin"
            "testnet", "test" -> "testnet"
            "signet" -> "signet"
            else -> "bitcoin"
        }
    }

    private fun normalizeSocketAddress(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null

        val hostPart = value.substringBeforeLast(':').trim().removePrefix("[").removeSuffix("]")
        val portPart = value.substringAfterLast(':').trim()
        val port = portPart.toIntOrNull() ?: return null
        if (hostPart.isBlank() || port !in 1..65535) return null
        val printableHost = if (hostPart.contains(':')) "[$hostPart]" else hostPart
        return "$printableHost:$port"
    }

    private fun httpGetBody(url: String): String? {
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            bootstrapHttpClient.newCall(req).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body?.string()?.trim().orEmpty().ifBlank { null }
            }
        }.getOrNull()
    }

    private fun httpGetBodyWithTimeout(url: String, timeoutMs: Long): String? {
        val timeout = timeoutMs.coerceAtLeast(500L)
        return runCatching {
            val req = Request.Builder().url(url).get().build()
            bootstrapHttpClient
                .newBuilder()
                .callTimeout(timeout, TimeUnit.MILLISECONDS)
                .connectTimeout(timeout, TimeUnit.MILLISECONDS)
                .readTimeout(timeout, TimeUnit.MILLISECONDS)
                .writeTimeout(timeout, TimeUnit.MILLISECONDS)
                .build()
                .newCall(req)
                .execute()
                .use { response ->
                    if (!response.isSuccessful) return null
                    response.body?.string()?.trim().orEmpty().ifBlank { null }
                }
        }.getOrNull()
    }

    private fun hasUsableFeeEstimates(esploraBaseUrl: String): Boolean {
        val base = esploraBaseUrl.trim().removeSuffix("/")
        if (base.isBlank()) return false
        val endpoint = "$base/fee-estimates"
        val body = httpGetBodyWithTimeout(endpoint, ESPLORA_PREFLIGHT_TIMEOUT_MS) ?: return false
        val parsed = runCatching { JSONObject(body) }.getOrNull() ?: return false
        if (parsed.length() == 0) return false

        val keys = parsed.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = parsed.optDouble(key, Double.NaN)
            if (!value.isNaN() && value >= 0.0) {
                return true
            }
        }
        return false
    }

    private fun orderCandidatesWithFeeEstimatePreflight(candidates: List<String>): List<String> {
        if (candidates.isEmpty() || candidates.size == 1) return candidates

        val healthy = ArrayList<String>(candidates.size)
        val unknownOrUnhealthy = ArrayList<String>(candidates.size)
        candidates.forEach { candidate ->
            val healthyCandidate = runCatching { hasUsableFeeEstimates(candidate) }.getOrDefault(false)
            Log.i(TAG, "Esplora preflight ${if (healthyCandidate) "OK" else "FAIL"}: $candidate")
            if (healthyCandidate) {
                healthy.add(candidate)
            } else {
                unknownOrUnhealthy.add(candidate)
            }
        }

        // Preserve all candidates. If probes fail due transient connectivity, keep fallbacks.
        return if (healthy.isNotEmpty()) healthy + unknownOrUnhealthy else candidates
    }

    fun ensureStarted(
        context: Context,
        federation: FederationEntry,
        callback: ((success: Boolean, error: String?) -> Unit)? = null,
    ) {
        val appContext = context.applicationContext
        appContextForRecovery = appContext
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
        appContextForRecovery = appContext
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
                val n = runningNodeOrNull(operation = "sync wallet", setError = false) ?: return@ensureBackgroundThread
                n.syncWallets()
                lastError = null
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
        return syncWalletsBlockingInternal(allowFeerateRecovery = true)
    }

    private fun syncWalletsBlockingInternal(allowFeerateRecovery: Boolean): Boolean {
        return try {
            val n = runningNodeOrNull(operation = "sync wallet") ?: return false
            n.syncWallets()
            resetFeerateFailureTracking()
            lastError = null
            true
        } catch (t: Throwable) {
            if (allowFeerateRecovery && t.isLikelyFeerateFailure()) {
                if (noteFeerateFailureAndShouldRecover() &&
                    recoverFromFeerateFailureBlocking(
                        operation = "sync wallet",
                        allowRunningNodeRebuild = true,
                    )
                ) {
                    return syncWalletsBlockingInternal(allowFeerateRecovery = false)
                }
            }
            Log.w(TAG, "syncWalletsBlocking() failed", t)
            lastError = t
            false
        }
    }

    @Throws(BuildException::class, NodeException::class)
    private fun startInternal(
        context: Context,
        federation: FederationEntry,
        forceRebuild: Boolean = false,
        preferredEsploraOverride: String? = null,
    ) {
        var sameFederationNode: Node? = null
        synchronized(lock) {
            if (isStarting) return

            // If we already have a node for this federation, just (re)start it.
            if (!forceRebuild && node != null && federationId == federation.id) {
                val existing = node
                if (existing != null && isNodeRunning(existing)) {
                    appContextForRecovery = context.applicationContext
                    federationSnapshotForRecovery = federation
                    lastError = null
                    return
                }
                sameFederationNode = existing
            } else {
                stopLocked()
            }

            isStarting = true
        }

        try {
            val existing = sameFederationNode
            if (existing != null) {
                val reuseOk = try {
                    existing.start()
                    true
                } catch (t: Throwable) {
                    // If the node reports "already running" semantics, treat it as success.
                    val runningNow = isNodeRunning(existing)
                    if (!runningNow) {
                        Log.w(TAG, "Existing node failed to restart; rebuilding node", t)
                    }
                    runningNow
                }

                if (reuseOk && isNodeRunning(existing)) {
                    synchronized(lock) {
                        node = existing
                        federationId = federation.id
                        appContextForRecovery = context.applicationContext
                        federationSnapshotForRecovery = federation
                        lastError = null
                    }
                    syncWallets()
                    return
                } else {
                    synchronized(lock) {
                        stopLocked()
                    }
                }
            }

            val network = parseNetwork(federation.network)
            val preferredEsplora = preferredEsploraOverride?.trim().orEmpty()
                .ifBlank { federation.esploraUrl?.trim().orEmpty() }
                .ifBlank { defaultEsploraUrl(network) }
            val esploraCandidates = candidateEsploraUrls(network, preferredEsplora)
            val preflightedCandidates = orderCandidatesWithFeeEstimatePreflight(esploraCandidates)
            Log.i(TAG, "Esplora startup candidates for ${federation.id}: ${preflightedCandidates.joinToString()}")
            var startError: Throwable? = null

            for ((index, esploraUrl) in preflightedCandidates.withIndex()) {
                val built = buildNode(context, federation, esploraUrlOverride = esploraUrl)
                try {
                    built.start()
                    if (!isNodeRunning(built)) {
                        throw IllegalStateException("Node started but is not running")
                    }

                    synchronized(lock) {
                        node = built
                        federationId = federation.id
                        appContextForRecovery = context.applicationContext
                        federationSnapshotForRecovery = federation.copy(esploraUrl = esploraUrl)
                        activeEsploraUrl = esploraUrl
                        lastError = null
                    }

                    // Run the first sync asynchronously to avoid blocking federation switches/UI refreshes.
                    syncWallets()
                    return
                } catch (t: Throwable) {
                    startError = t
                    runCatching { built.stop() }
                    runCatching { built.close() }

                    val shouldRetryWithFallback =
                        t.isLikelyFeerateFailure() && index < preflightedCandidates.lastIndex
                    if (!shouldRetryWithFallback) {
                        throw t
                    }
                    Log.w(TAG, "Node start failed with $esploraUrl, trying next Esplora fallback", t)
                }
            }

            if (startError != null) {
                throw startError
            }
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
        activeEsploraUrl = null
        activeLiquidityProviderId = null
        gatewayBootstrapPendingReference = null
        gatewayBootstrapPendingUntilMs = 0L
    }

    private fun runningNodeOrNull(operation: String, setError: Boolean = true): Node? {
        val current = node ?: run {
            if (setError) {
                lastError = IllegalStateException("Wallet node unavailable while trying to $operation")
            }
            return null
        }

        val running = isNodeRunning(current)
        if (!running) {
            if (setError) {
                lastError = IllegalStateException("Wallet node is not running while trying to $operation")
            }
            return null
        }

        return current
    }

    private fun isNodeRunning(candidate: Node): Boolean {
        return try {
            candidate.status().isRunning
        } catch (_: Throwable) {
            false
        }
    }

    private fun buildNode(
        context: Context,
        federation: FederationEntry,
        esploraUrlOverride: String? = null,
    ): Node {
        // ldk-node-android uses JNA internally; on some Android versions the JNA loader needs an
        // explicit hint (and/or extracted native libs) to find and load its native dispatch lib.
        // Do this here as a last line of defense, in addition to App.onCreate().
        configureJnaNativeLoading(context)

        val network = parseNetwork(federation.network)
        val esploraUrl = esploraUrlOverride?.trim().orEmpty()
            .ifBlank { federation.esploraUrl?.trim().orEmpty() }
            .ifBlank { defaultEsploraUrl(network) }
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

            // Optional LSPS1 bootstrap provider.
            // Selection order:
            // 1) Manual/auto provider from directory resolver
            // 2) Legacy provider data directly on the selected federation
            val selectedProvider = FederationDirectoryManager.resolveLiquidityProvider(context, federation)
            val lsps1NodeId = selectedProvider?.nodeId?.trim().orEmpty()
                .ifBlank { federation.lsps1NodeId?.trim().orEmpty() }
            val lsps1Address = selectedProvider?.address?.trim().orEmpty()
                .ifBlank { federation.lsps1Address?.trim().orEmpty() }
            val lsps1Token = selectedProvider?.token?.trim()?.takeIf { it.isNotBlank() }
                ?: federation.lsps1Token?.trim()?.takeIf { it.isNotBlank() }
            val providerId = selectedProvider?.id?.trim().orEmpty().ifBlank {
                if (lsps1NodeId.isNotBlank() && lsps1Address.isNotBlank()) "federation:${federation.id}" else ""
            }
            if (lsps1NodeId.isNotBlank() && lsps1Address.isNotBlank()) {
                builder.setLiquiditySourceLsps1(lsps1NodeId, lsps1Address, lsps1Token)
            }
            synchronized(lock) {
                activeLiquidityProviderId = providerId.ifBlank { null }
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

    private fun recoverFromFeerateFailureBlocking(
        operation: String,
        allowRunningNodeRebuild: Boolean = false,
    ): Boolean {
        // Default behavior keeps the existing stability-first stance.
        val existing = node
        if (!allowRunningNodeRebuild && existing != null && isNodeRunning(existing)) {
            return false
        }

        val context = appContextForRecovery ?: return false
        val federation = resolveFederationForRecovery(context) ?: return false
        val network = parseNetwork(federation.network)
        val preferred = federation.esploraUrl?.trim().orEmpty().ifBlank { defaultEsploraUrl(network) }
        val candidates = rotateCandidatesAfterActive(
            candidates = candidateEsploraUrls(network, preferred),
            active = activeEsploraUrl.orEmpty(),
        )
        if (candidates.isEmpty()) return false
        val preflightedCandidates = orderCandidatesWithFeeEstimatePreflight(candidates)
        Log.i(TAG, "Esplora recovery candidates during $operation: ${preflightedCandidates.joinToString()}")

        var lastRecoveryError: Throwable? = null
        for (candidate in preflightedCandidates) {
            val candidateFederation = federation.copy(esploraUrl = candidate)
            val recovered = try {
                startInternal(
                    context = context,
                    federation = candidateFederation,
                    forceRebuild = true,
                    preferredEsploraOverride = candidate,
                )
                waitForStartupToFinish(START_WAIT_TIMEOUT_MS) && isRunningForFederation(federation.id)
            } catch (t: Throwable) {
                lastRecoveryError = t
                Log.w(TAG, "Feerate recovery failed on $candidate during $operation", t)
                false
            }

            if (recovered) {
                resetFeerateFailureTracking()
                lastError = null
                Log.i(TAG, "Recovered from feerate failure during $operation via $candidate")
                return true
            }
        }

        if (lastRecoveryError != null) {
            lastError = lastRecoveryError
        }
        return false
    }

    private fun cacheBalanceSnapshot(balances: BalanceDetails) {
        cachedBalanceSnapshot = balances
        cachedBalanceSnapshotUpdatedAtMs = System.currentTimeMillis()
    }

    private fun getFreshBalanceSnapshotOrNull(): BalanceDetails? {
        val snapshot = cachedBalanceSnapshot ?: return null
        val ageMs = System.currentTimeMillis() - cachedBalanceSnapshotUpdatedAtMs
        return if (ageMs in 0..BALANCE_SNAPSHOT_MAX_AGE_MS) snapshot else null
    }

    private fun resetFeerateFailureTracking() {
        consecutiveFeerateFailures = 0
        lastFeerateFailureAtMs = 0L
        lastFeerateRecoveryAttemptAtMs = 0L
    }

    private fun noteFeerateFailureAndShouldRecover(): Boolean {
        val now = System.currentTimeMillis()
        val recentWindow = FEERATE_RECOVERY_FAILURE_WINDOW_MS
        val cooldownMs = FEERATE_RECOVERY_ATTEMPT_COOLDOWN_MS

        if (now - lastFeerateFailureAtMs > recentWindow) {
            consecutiveFeerateFailures = 0
        }
        lastFeerateFailureAtMs = now
        consecutiveFeerateFailures += 1

        if (consecutiveFeerateFailures < FEERATE_RECOVERY_MIN_FAILURES) {
            return false
        }
        if (now - lastFeerateRecoveryAttemptAtMs < cooldownMs) {
            return false
        }

        lastFeerateRecoveryAttemptAtMs = now
        return true
    }

    private fun resolveFederationForRecovery(context: Context): FederationEntry? {
        val currentId = federationId?.trim().orEmpty()
        if (currentId.isNotBlank()) {
            FederationDirectoryManager.getFederations(context)
                .firstOrNull { it.id == currentId }
                ?.let { return it }
        }
        val snapshot = federationSnapshotForRecovery
        if (snapshot != null && (currentId.isBlank() || snapshot.id == currentId)) {
            return snapshot
        }
        return null
    }

    private fun rotateCandidatesAfterActive(candidates: List<String>, active: String): List<String> {
        if (candidates.isEmpty()) return emptyList()
        val current = active.trim()
        if (current.isBlank() || candidates.size == 1) return candidates
        val index = candidates.indexOfFirst { it.equals(current, ignoreCase = true) }
        if (index < 0) return candidates
        return candidates.drop(index + 1) + candidates.take(index + 1)
    }

    private fun candidateEsploraUrls(network: Network, preferred: String): List<String> {
        val defaults = when (network) {
            Network.BITCOIN -> listOf(
                "https://blockstream.info/api",
                "https://mempool.space/api",
            )

            Network.TESTNET -> listOf(
                "https://blockstream.info/testnet/api",
                "https://mempool.space/testnet/api",
            )

            Network.SIGNET -> listOf(
                "https://blockstream.info/signet/api",
                "https://mempool.space/signet/api",
            )

            Network.REGTEST -> listOf(
                "http://127.0.0.1:3002/api",
            )
        }

        return (listOf(preferred.trim()) + defaults)
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
    }

    private fun Throwable.isLikelyFeerateFailure(): Boolean {
        var current: Throwable? = this
        var depth = 0
        while (current != null && depth < 6) {
            val className = current.javaClass.name.lowercase()
            val message = current.message?.lowercase().orEmpty()
            if (className.contains("feerate") ||
                message.containsFeerateSignal()
            ) {
                return true
            }
            current = current.cause
            depth++
        }
        return false
    }

    private fun String.containsFeerateSignal(): Boolean {
        val text = lowercase()
        if (text.contains("feerate") || text.contains("fee rate") || text.contains("fee rates")) {
            return true
        }
        val hasFeeWord = text.contains("fee") || text.contains("fees")
        val hasRateWord = text.contains("rate") || text.contains("rates")
        val hasEstimatorWord = text.contains("estimate") || text.contains("estimation") || text.contains("update")
        return hasFeeWord && (hasRateWord || hasEstimatorWord)
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
            val className = t.javaClass.name

            mapKnownWalletErrorSummary(className, msg)?.let { return it }

            if (t is RuntimeException || t is IllegalStateException || t is IllegalArgumentException) {
                if (msg.isNotBlank()) {
                    if (looksLikeRawExceptionClass(msg)) {
                        return mapKnownWalletErrorSummary(className = "", message = msg)
                            ?: "Wallet operation failed. Tap Sync and retry."
                    }
                    return msg
                }
            }

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

    private fun mapKnownWalletErrorSummary(className: String, message: String): String? {
        val msg = message.trim()
        if (msg.isBlank()) return null

        val classLower = className.lowercase()
        val msgLower = msg.lowercase()

        // Prefer actionable summaries over raw SDK exception names.
        if (classLower.contains("paymentsendingfailed") ||
            msgLower.contains("paymentsendingfailed") ||
            msgLower.contains("failed to send the given payment")
        ) {
            return "Lightning payment could not be routed. Check Lightning liquidity/channels and try again."
        }
        if (classLower.contains("insufficientfunds") ||
            msgLower.contains("insufficientfunds") ||
            (msgLower.contains("insufficient") && msgLower.contains("fund"))
        ) {
            return "Insufficient wallet balance."
        }
        if (classLower.contains("liquiditysourceunavailable") ||
            msgLower.contains("liquiditysourceunavailable") ||
            (msgLower.contains("liquidity source") && msgLower.contains("unavailable"))
        ) {
            return "Instant payments setup is missing for this wallet."
        }
        if (classLower.contains("liquidityrequestfailed") || msgLower.contains("liquidityrequestfailed")) {
            return "Automatic Lightning liquidity request failed."
        }
        if (classLower.contains("liquidityfeetoohigh") || msgLower.contains("liquidityfeetoohigh")) {
            return "Liquidity provider fee is higher than allowed."
        }
        if (classLower.contains("feerateestimationupdatefailed") ||
            classLower.contains("feerateestimationupdatetimeout") ||
            msgLower.contains("feerateestimationupdatefailed") ||
            msgLower.contains("feerateestimationupdatetimeout") ||
            (msgLower.contains("feerate") &&
                (msgLower.contains("update") || msgLower.contains("estimate"))) ||
            msgLower.contains("fee rate") ||
            msgLower.contains("fee rates")
        ) {
            return "Could not fetch current Bitcoin fee rates. Check connectivity, tap Sync, and retry."
        }
        if (classLower.contains("invalidfeerate") || msgLower.contains("invalidfeerate")) {
            return "Received an invalid Bitcoin fee estimate. Please retry in a moment."
        }

        val isNodeException = classLower.contains("ldknode.nodeexception") ||
            msgLower.contains("ldknode.nodeexception") ||
            (msgLower.contains("nodeexception") && msgLower.contains("lightningdevkit"))
        if (isNodeException) {
            return when {
                classLower.contains("invoicecreationfailed") || msgLower.contains("invoicecreationfailed") ->
                    "Could not create a Lightning invoice. Check liquidity setup and retry."
                classLower.contains("connectionfailed") || msgLower.contains("connectionfailed") ->
                    "Could not connect to the selected Lightning peer. Check network and retry."
                classLower.contains("channelcreationfailed") || msgLower.contains("channelcreationfailed") ->
                    "Could not open a Lightning channel for this operation."
                classLower.contains("walletoperationfailed") || msgLower.contains("walletoperationfailed") ->
                    "The Lightning wallet operation failed. Tap Sync and retry."
                classLower.contains("walletoperationtimeout") || msgLower.contains("walletoperationtimeout") ->
                    "The Lightning wallet operation timed out. Tap Sync and retry."
                classLower.contains("txsyncfailed") || msgLower.contains("txsyncfailed") ->
                    "Bitcoin chain sync failed. Check connectivity and retry."
                classLower.contains("txsynctimeout") || msgLower.contains("txsynctimeout") ->
                    "Bitcoin chain sync timed out. Check connectivity and retry."
                else -> "Lightning node rejected the request. Tap Sync and retry."
            }
        }

        return null
    }

    private fun looksLikeRawExceptionClass(message: String): Boolean {
        val text = message.trim()
        if (text.isBlank()) return false
        if (text.contains("org.lightningdevkit.ldknode.NodeException")) return true
        if (text.startsWith("java.lang.RuntimeException: org.lightningdevkit.ldknode.NodeException")) return true
        return text.matches(Regex("^[A-Za-z0-9_.$]+(?:Exception|Error)(:.*)?$"))
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
            val current = node ?: return false
            isNodeRunning(current)
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

    private fun ULong.toLongSaturated(): Long {
        return if (this > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else toLong()
    }

    private fun payExpectedLiquidityOrderPayment(node: Node, order: Lsps1OrderStatus): String? {
        val onchain = order.paymentOptions.onchain
        if (onchain != null && onchain.state == PaymentState.EXPECT_PAYMENT) {
            val amountSats = onchain.orderTotalSat.toLongSaturated()
            if (amountSats <= 0L) {
                throw IllegalStateException("Liquidity order returned an invalid on-chain amount.")
            }
            if (!WalletPolicy.isAmountWithinSingleTxLimit(amountSats)) {
                throw IllegalArgumentException("Liquidity order amount exceeds maximum transfer limit.")
            }
            val address = onchain.address.trim()
            if (address.isBlank()) {
                throw IllegalStateException("Liquidity order did not provide a valid on-chain address.")
            }
            return node.onchainPayment().sendToAddress(address, amountSats.toULong(), null)
        }

        val bolt11 = order.paymentOptions.bolt11
        if (bolt11 != null && bolt11.state == PaymentState.EXPECT_PAYMENT) {
            return node.bolt11Payment().send(bolt11.invoice, null)
        }

        return null
    }
}
