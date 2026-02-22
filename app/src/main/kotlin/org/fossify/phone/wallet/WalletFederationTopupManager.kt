package org.fossify.phone.wallet

import android.content.Context
import org.fossify.phone.R
import org.lightningdevkit.ldknode.Bolt11Invoice
import org.lightningdevkit.ldknode.PaymentStatus
import java.text.NumberFormat
import java.util.Locale

object WalletFederationTopupManager {
    private const val BALANCE_SETTLE_TIMEOUT_MS = 90_000L
    private const val ONCHAIN_SETTLE_TIMEOUT_MS = 45_000L
    private const val BALANCE_POLL_INTERVAL_MS = 1_500L
    private const val SOURCE_BOOTSTRAP_PENDING_TTL_MS = 90L * 60L * 1000L

    private data class PendingBootstrapState(
        val reference: String?,
        val expiresAtMs: Long,
    )

    private val pendingBootstrapBySource = HashMap<String, PendingBootstrapState>()
    private val pendingBootstrapLock = Any()

    data class TopupQuote(
        val targetFederation: FederationEntry,
        val sourceFederation: FederationEntry,
        val invoiceAmountSats: Long,
        val currentFederationBalanceSats: Long,
        val mintAmountSats: Long,
        val estimatedFeeSats: Long,
        val routeHintNodeIds: List<String> = emptyList(),
    )

    data class TopupResult(
        val success: Boolean,
        val pending: Boolean = false,
        val errorMessage: String? = null,
        val reference: String? = null,
    )

    data class MainnetLightningRecoveryResult(
        val success: Boolean,
        val pending: Boolean = false,
        val errorMessage: String? = null,
        val reference: String? = null,
    )

    private fun sourceBootstrapKey(source: FederationEntry): String {
        return source.id.trim().lowercase(Locale.ROOT)
    }

    private fun getActivePendingBootstrap(sourceKey: String): PendingBootstrapState? {
        if (sourceKey.isBlank()) return null
        synchronized(pendingBootstrapLock) {
            val state = pendingBootstrapBySource[sourceKey] ?: return null
            if (System.currentTimeMillis() >= state.expiresAtMs) {
                pendingBootstrapBySource.remove(sourceKey)
                return null
            }
            return state
        }
    }

    private fun markPendingBootstrap(sourceKey: String, reference: String?) {
        if (sourceKey.isBlank()) return
        synchronized(pendingBootstrapLock) {
            pendingBootstrapBySource[sourceKey] = PendingBootstrapState(
                reference = reference?.trim()?.ifBlank { null },
                expiresAtMs = System.currentTimeMillis() + SOURCE_BOOTSTRAP_PENDING_TTL_MS,
            )
        }
    }

    private fun clearPendingBootstrap(sourceKey: String) {
        if (sourceKey.isBlank()) return
        synchronized(pendingBootstrapLock) {
            pendingBootstrapBySource.remove(sourceKey)
        }
    }

    fun isLikelyInsufficientBalance(error: String?): Boolean {
        val text = error?.trim().orEmpty().lowercase()
        if (text.isBlank()) return false
        return text.contains("insufficient") && (
            text.contains("balance") ||
                text.contains("funds") ||
                text.contains("liquidity")
            )
    }

    fun parseFixedInvoiceSats(text: String): Long? {
        val invoice = runCatching { Bolt11Invoice.fromStr(text.trim()) }.getOrNull() ?: return null
        val msat = runCatching { invoice.amountMilliSatoshis() }.getOrNull() ?: return null
        val sats = runCatching { (msat / 1000UL).toLong() }.getOrNull() ?: return null
        return sats.takeIf { it > 0L }
    }

    fun shouldAttemptMainnetLightningRecovery(
        requiredSats: Long?,
        errorMessage: String?,
    ): Boolean {
        val required = requiredSats?.takeIf { it > 0L }

        val text = errorMessage?.trim().orEmpty().lowercase(Locale.ROOT)
        val likelyRouteOrLiquidityFailure = text.contains("could not be routed") ||
            text.contains("failed to send the given payment") ||
            text.contains("paymentsendingfailed") ||
            text.contains("lightning payment failed before settlement") ||
            text.contains("liquidity source") ||
            text.contains("liquidity") ||
            isLikelyInsufficientBalance(text)
        if (likelyRouteOrLiquidityFailure) {
            return true
        }
        if (required == null) return false

        val outboundLiquidity = LdkWalletManager.getUsableOutboundLiquiditySats(setError = false)
        return outboundLiquidity != null && outboundLiquidity < required
    }

    fun recoverMainnetLightningPaymentBlocking(
        context: Context,
        sourceFederation: FederationEntry,
        invoice: String,
        amountSats: Long?,
    ): MainnetLightningRecoveryResult {
        val requiredSats = parseFixedInvoiceSats(invoice) ?: amountSats?.takeIf { it > 0L }
        if (requiredSats != null && !WalletPolicy.isAmountWithinSingleTxLimit(requiredSats)) {
            val limit = NumberFormat.getIntegerInstance(Locale.getDefault())
                .format(WalletPolicy.MAX_SINGLE_TX_SATS)
            return MainnetLightningRecoveryResult(
                success = false,
                errorMessage = context.getString(R.string.wallet_amount_over_limit, limit),
            )
        }

        if (!LdkWalletManager.ensureStartedBlocking(context, sourceFederation)) {
            val startError = LdkWalletManager.getLastErrorMessage()
                ?: context.getString(R.string.wallet_unknown_error)
            return MainnetLightningRecoveryResult(success = false, errorMessage = startError)
        }

        // First, retry with fresh sync/state before opening new channels. Many route failures are
        // transient graph/peer-state issues and can clear without liquidity bootstrap.
        val preBootstrapRetry = retryMainnetLightningPayment(
            context = context,
            invoice = invoice,
            amountSats = amountSats,
            attempts = 2,
            syncBeforeAttempt = true,
        )
        if (preBootstrapRetry.success || preBootstrapRetry.pending) {
            return preBootstrapRetry
        }
        if (requiredSats == null) {
            val restarted = restartLdkNodeWithSync(
                context = context,
                sourceFederation = sourceFederation,
            )
            if (restarted) {
                val postRestartRetry = retryMainnetLightningPayment(
                    context = context,
                    invoice = invoice,
                    amountSats = amountSats,
                    attempts = 3,
                    syncBeforeAttempt = true,
                )
                if (postRestartRetry.success || postRestartRetry.pending) {
                    return postRestartRetry
                }
                if (isLikelyRouteOrLiquidityFailure(postRestartRetry.errorMessage)) {
                    val p2pRestarted = restartLdkNodeWithSync(
                        context = context,
                        sourceFederation = sourceFederation,
                        forceP2pGossip = true,
                    )
                    if (p2pRestarted) {
                        val p2pRetry = retryMainnetLightningPayment(
                            context = context,
                            invoice = invoice,
                            amountSats = amountSats,
                            attempts = 3,
                            syncBeforeAttempt = true,
                        )
                        if (p2pRetry.success || p2pRetry.pending) {
                            return p2pRetry
                        }
                        return MainnetLightningRecoveryResult(
                            success = false,
                            errorMessage = p2pRetry.errorMessage ?: context.getString(R.string.wallet_unknown_error),
                        )
                    }
                }
                return MainnetLightningRecoveryResult(
                    success = false,
                    errorMessage = postRestartRetry.errorMessage ?: context.getString(R.string.wallet_unknown_error),
                )
            }
            return MainnetLightningRecoveryResult(
                success = false,
                errorMessage = preBootstrapRetry.errorMessage ?: context.getString(R.string.wallet_unknown_error),
            )
        }

        val requiredWithFee = saturatingAdd(requiredSats, estimateRoutingFeeSats(requiredSats))
        val outboundLiquidity = LdkWalletManager.getUsableOutboundLiquiditySats(setError = false)
            ?.coerceAtLeast(0L)
            ?: 0L
        val hasLiquidityProvider = FederationDirectoryManager
            .resolveLiquidityProvider(context, sourceFederation) != null

        if (outboundLiquidity < requiredWithFee && hasLiquidityProvider) {
            val directBootstrap = LdkWalletManager.bootstrapOutboundLiquidityBlocking(
                context = context,
                requiredSats = requiredWithFee,
            )

            if (directBootstrap.success) {
                return retryMainnetLightningPayment(
                    context = context,
                    invoice = invoice,
                    amountSats = amountSats,
                    attempts = 3,
                    syncBeforeAttempt = true,
                )
            }
            if (directBootstrap.pending) {
                return MainnetLightningRecoveryResult(
                    success = false,
                    pending = true,
                    errorMessage = directBootstrap.errorMessage
                        ?: context.getString(R.string.wallet_exchange_advanced_pending),
                    reference = directBootstrap.fundingReference,
                )
            }
        }

        // Even with "enough" outbound sats, routing can still fail if the channel graph/peer set
        // is poor. Try opening to gateway hints to improve route diversity.
        val gatewayNodeHints = (sourceFederation.vettedGateways + extractInvoiceRouteHintNodeIds(invoice))
            .asSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
            .toList()
        val gatewayBootstrap = LdkWalletManager.bootstrapOutboundLiquidityViaGatewayHintsBlocking(
            context = context,
            gatewayNodeIds = gatewayNodeHints,
            requiredSats = requiredWithFee,
            network = sourceFederation.network,
            forceOpenOnRouteFailure = true,
        )

        if (gatewayBootstrap.success) {
            return retryMainnetLightningPayment(
                context = context,
                invoice = invoice,
                amountSats = amountSats,
                attempts = 3,
                syncBeforeAttempt = true,
            )
        }
        if (gatewayBootstrap.pending) {
            return MainnetLightningRecoveryResult(
                success = false,
                pending = true,
                errorMessage = gatewayBootstrap.errorMessage
                    ?: context.getString(R.string.wallet_exchange_advanced_pending),
                reference = gatewayBootstrap.fundingReference,
            )
        }

        if (outboundLiquidity < requiredWithFee && !hasLiquidityProvider) {
            val missingProvider = context.getString(R.string.wallet_instant_setup_message)
            return MainnetLightningRecoveryResult(
                success = false,
                errorMessage = missingProvider,
            )
        }

        val finalRetry = retryMainnetLightningPayment(
            context = context,
            invoice = invoice,
            amountSats = amountSats,
            attempts = 2,
            syncBeforeAttempt = true,
        )
        if (finalRetry.success || finalRetry.pending) {
            return finalRetry
        }
        var postRecoveryError = finalRetry.errorMessage
        if (isLikelyRouteOrLiquidityFailure(postRecoveryError)) {
            val restarted = restartLdkNodeWithSync(
                context = context,
                sourceFederation = sourceFederation,
            )
            if (restarted) {
                val postRestartRetry = retryMainnetLightningPayment(
                    context = context,
                    invoice = invoice,
                    amountSats = amountSats,
                    attempts = 2,
                    syncBeforeAttempt = true,
                )
                if (postRestartRetry.success || postRestartRetry.pending) {
                    return postRestartRetry
                }
                postRecoveryError = postRestartRetry.errorMessage
            }
            if (isLikelyRouteOrLiquidityFailure(postRecoveryError)) {
                val p2pRestarted = restartLdkNodeWithSync(
                    context = context,
                    sourceFederation = sourceFederation,
                    forceP2pGossip = true,
                )
                if (p2pRestarted) {
                    val p2pRetry = retryMainnetLightningPayment(
                        context = context,
                        invoice = invoice,
                        amountSats = amountSats,
                        attempts = 2,
                        syncBeforeAttempt = true,
                    )
                    if (p2pRetry.success || p2pRetry.pending) {
                        return p2pRetry
                    }
                    postRecoveryError = p2pRetry.errorMessage
                }
            }
        }

        val recoveryError = postRecoveryError
            ?: gatewayBootstrap.errorMessage
            ?: LdkWalletManager.getLastErrorMessage()
            ?: context.getString(R.string.wallet_unknown_error)
        return MainnetLightningRecoveryResult(success = false, errorMessage = recoveryError)
    }

    private fun restartLdkNodeWithSync(
        context: Context,
        sourceFederation: FederationEntry,
        forceP2pGossip: Boolean = false,
    ): Boolean {
        val restarted = LdkWalletManager.restartForRouteRecoveryBlocking(
            context = context,
            federation = sourceFederation,
            forceP2pGossip = forceP2pGossip,
        )
        if (!restarted) return false
        return true
    }

    private fun isLikelyRouteOrLiquidityFailure(errorMessage: String?): Boolean {
        val text = errorMessage?.trim().orEmpty().lowercase(Locale.ROOT)
        if (text.isBlank()) return false
        return text.contains("could not be routed") ||
            text.contains("payment could not be routed") ||
            text.contains("failed to send the given payment") ||
            text.contains("paymentsendingfailed") ||
            text.contains("liquidity") ||
            text.contains("route") ||
            text.contains("channel")
    }

    private fun retryMainnetLightningPayment(
        context: Context,
        invoice: String,
        amountSats: Long?,
        attempts: Int,
        syncBeforeAttempt: Boolean,
    ): MainnetLightningRecoveryResult {
        val maxAttempts = attempts.coerceAtLeast(1)
        var latestError: String? = null
        var lastPaymentId: String? = null

        for (attempt in 1..maxAttempts) {
            if (syncBeforeAttempt || attempt > 1) {
                runCatching { LdkWalletManager.syncWalletsBlocking() }
            }

            val paymentId = LdkWalletManager.payBolt11Invoice(invoice, amountSats)
            if (!paymentId.isNullOrBlank()) {
                lastPaymentId = paymentId
                when (LdkWalletManager.awaitOutgoingLightningPaymentStatusBlocking(paymentId)) {
                    PaymentStatus.SUCCEEDED -> {
                        return MainnetLightningRecoveryResult(
                            success = true,
                            reference = paymentId,
                        )
                    }

                    PaymentStatus.PENDING -> {
                        return MainnetLightningRecoveryResult(
                            success = false,
                            pending = true,
                            errorMessage = context.getString(R.string.wallet_send_pending),
                            reference = paymentId,
                        )
                    }

                    PaymentStatus.FAILED,
                    null,
                    -> {
                        latestError = LdkWalletManager.getLastErrorMessage()
                    }
                }
            } else {
                latestError = LdkWalletManager.getLastErrorMessage()
            }

            if (attempt < maxAttempts && isLikelyRouteOrLiquidityFailure(latestError)) {
                try {
                    Thread.sleep((300L * attempt).coerceAtMost(1_200L))
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                continue
            }
            break
        }

        return MainnetLightningRecoveryResult(
            success = false,
            errorMessage = latestError ?: context.getString(R.string.wallet_unknown_error),
            reference = lastPaymentId,
        )
    }

    fun buildTopupQuote(
        context: Context,
        targetFederation: FederationEntry,
        invoice: String,
        currentFederationBalanceSats: Long? = null,
        assumeZeroOnUnknownBalance: Boolean = true,
    ): TopupQuote? {
        val invoiceAmount = parseFixedInvoiceSats(invoice) ?: return null
        val routeHintNodeIds = extractInvoiceRouteHintNodeIds(invoice)
        val lookedUpBalance = currentFederationBalanceSats
            ?: FedimintWalletManager.getBalanceSatsBlocking(context, targetFederation)
        val current = when {
            lookedUpBalance != null -> lookedUpBalance
            assumeZeroOnUnknownBalance -> 0L
            else -> return null
        }
        val shortfall = (invoiceAmount - current).coerceAtLeast(0L)
        if (shortfall <= 0L) return null

        val source = findMainnetSourceFederation(
            context = context,
            targetFederationId = targetFederation.id,
            targetNetwork = targetFederation.network,
        ) ?: return null

        return TopupQuote(
            targetFederation = targetFederation,
            sourceFederation = source,
            invoiceAmountSats = invoiceAmount,
            currentFederationBalanceSats = current.coerceAtLeast(0L),
            mintAmountSats = shortfall,
            estimatedFeeSats = estimateRoutingFeeSats(shortfall),
            routeHintNodeIds = routeHintNodeIds,
        )
    }

    fun findMainnetSourceFederation(
        context: Context,
        targetFederationId: String,
        targetNetwork: String? = null,
    ): FederationEntry? {
        val excludedId = targetFederationId.trim()
        val entries = FederationDirectoryManager.getFederations(context)
            .filterNot { FederationDirectoryManager.isFedimintFederation(it) }
            .filterNot { it.id == excludedId }
            .filter {
                val network = normalizeNetwork(it.network)
                val target = normalizeNetwork(targetNetwork)
                target.isBlank() || network.isBlank() || network == target
            }
        if (entries.isEmpty()) return null

        val normalizedTargetNetwork = normalizeNetwork(targetNetwork)
        val prioritized = entries.sortedWith(
            compareByDescending<FederationEntry> {
                normalizeNetwork(it.network) == normalizedTargetNetwork && normalizedTargetNetwork.isNotBlank()
            }
                .thenByDescending { isMainnetLikeNetwork(it.network) }
                .thenBy { it.id.lowercase() }
        )

        var fallbackMainnet: FederationEntry? = prioritized.firstOrNull {
            isMainnetLikeNetwork(it.network)
        } ?: prioritized.firstOrNull {
            it.id.contains("mainnet", ignoreCase = true)
        } ?: prioritized.firstOrNull()

        // Prefer a source wallet that already has at least some Lightning liquidity.
        for (candidate in prioritized) {
            if (!LdkWalletManager.ensureStartedBlocking(context, candidate)) {
                continue
            }
            val lightning = LdkWalletManager.listBalances()
                ?.totalLightningBalanceSats
                ?.toString()
                ?.toLongOrNull()
                ?: 0L
            if (lightning > 0L) {
                return candidate
            }
            if (fallbackMainnet == null) {
                fallbackMainnet = candidate
            }
        }

        return fallbackMainnet
    }

    fun topupFromMainnetBlocking(
        context: Context,
        quote: TopupQuote,
    ): TopupResult {
        if (!FedimintWalletManager.ensureStartedBlocking(context, quote.targetFederation)) {
            return TopupResult(
                success = false,
                errorMessage = FedimintWalletManager.getLastErrorMessage()
                    ?: "Could not start target federation wallet"
            )
        }

        val sourceCandidates = buildSourceCandidates(context, quote)
        if (sourceCandidates.isEmpty()) {
            return TopupResult(
                success = false,
                errorMessage = context.getString(R.string.wallet_federation_topup_unavailable),
            )
        }

        val requiredLightningSats = quote.mintAmountSats + quote.estimatedFeeSats
        val gatewayNodeHints = (quote.targetFederation.vettedGateways + quote.routeHintNodeIds)
            .asSequence()
            .map { it.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
            .toList()
        val amountFormat = NumberFormat.getIntegerInstance(Locale.getDefault())
        val errors = LinkedHashSet<String>()
        var pendingResult: TopupResult? = null
        var topupInvoice: String? = null

        fun ensureTopupInvoice(): String? {
            if (!topupInvoice.isNullOrBlank()) return topupInvoice
            val invoice = FedimintWalletManager.createBolt11InvoiceBlocking(
                context = context,
                federation = quote.targetFederation,
                amountSats = quote.mintAmountSats,
                memo = "Federation top-up",
                expirySeconds = 15 * 60,
            )
            topupInvoice = invoice
            return invoice
        }

        fun tryPayTopupInvoiceNow(sourceKey: String): TopupResult? {
            val invoice = ensureTopupInvoice()
            if (invoice.isNullOrBlank()) {
                errors.add(
                    FedimintWalletManager.getLastErrorMessage()
                        ?: "Could not create federation top-up invoice"
                )
                return null
            }

            val paid = LdkWalletManager.payBolt11Invoice(invoice)
            if (!paid.isNullOrBlank()) {
                clearPendingBootstrap(sourceKey)
                if (waitForFederationBalance(
                        context = context,
                        federation = quote.targetFederation,
                        minRequiredSats = quote.invoiceAmountSats,
                        timeoutMs = BALANCE_SETTLE_TIMEOUT_MS,
                    )
                ) {
                    return TopupResult(success = true, reference = paid)
                }

                return TopupResult(
                    success = false,
                    pending = true,
                    errorMessage = context.getString(R.string.wallet_federation_topup_balance_pending),
                    reference = paid,
                )
            }

            val payError = LdkWalletManager.getLastErrorMessage().orEmpty().trim()
            if (payError.isNotBlank() && !isLikelyInsufficientBalance(payError)) {
                errors.add(payError)
            }
            return null
        }

        for (source in sourceCandidates) {
            if (!LdkWalletManager.ensureStartedBlocking(context, source)) {
                errors.add(
                    context.getString(
                        R.string.wallet_federation_topup_source_start_failed,
                        source.name,
                    )
                )
                continue
            }

            var sourceBalances = LdkWalletManager.listBalances()
            var sourceLightningSats = sourceBalances?.totalLightningBalanceSats
                ?.toString()
                ?.toLongOrNull()
                ?: 0L
            var sourceOnchainSats = sourceBalances?.spendableOnchainBalanceSats
                ?.toString()
                ?.toLongOrNull()
                ?: 0L
            val sourceKey = sourceBootstrapKey(source)

            // Always attempt the invoice first; this avoids false negatives from stale balance heuristics.
            val directPay = tryPayTopupInvoiceNow(sourceKey)
            if (directPay != null) {
                return directPay
            }

            val activePendingBootstrap = getActivePendingBootstrap(sourceKey)
            if (activePendingBootstrap != null && sourceLightningSats < requiredLightningSats) {
                LdkWalletManager.syncWalletsBlocking()
                sourceBalances = LdkWalletManager.listBalances()
                sourceLightningSats = sourceBalances?.totalLightningBalanceSats
                    ?.toString()
                    ?.toLongOrNull()
                    ?: sourceLightningSats
                sourceOnchainSats = sourceBalances?.spendableOnchainBalanceSats
                    ?.toString()
                    ?.toLongOrNull()
                    ?: sourceOnchainSats

                val paidWhilePending = tryPayTopupInvoiceNow(sourceKey)
                if (paidWhilePending != null) {
                    return paidWhilePending
                }

                if (sourceLightningSats < requiredLightningSats) {
                    val pendingText = buildString {
                        append("Automatic liquidity channel bootstrap is still pending")
                        activePendingBootstrap.reference?.takeIf { it.isNotBlank() }?.let {
                            append(" (")
                            append(it)
                            append(")")
                        }
                        append(". Wait for confirmations and retry.")
                    }
                    pendingResult = pendingResult ?: TopupResult(
                        success = false,
                        pending = true,
                        errorMessage = pendingText,
                        reference = activePendingBootstrap.reference,
                    )
                } else {
                    clearPendingBootstrap(sourceKey)
                }
            }

            if (sourceLightningSats >= requiredLightningSats) {
                val paid = tryPayTopupInvoiceNow(sourceKey)
                if (paid != null) {
                    return paid
                }
                continue
            }

            val hasLiquidityProvider = FederationDirectoryManager
                .resolveLiquidityProvider(context, source) != null

            if (sourceLightningSats < requiredLightningSats && sourceOnchainSats > 0L && hasLiquidityProvider) {
                val bootstrap = LdkWalletManager.bootstrapOutboundLiquidityBlocking(
                    context = context,
                    requiredSats = requiredLightningSats,
                )
                if (bootstrap.success) {
                    clearPendingBootstrap(sourceKey)
                    sourceBalances = LdkWalletManager.listBalances()
                    sourceLightningSats = sourceBalances?.totalLightningBalanceSats
                        ?.toString()
                        ?.toLongOrNull()
                        ?: sourceLightningSats
                    sourceOnchainSats = sourceBalances?.spendableOnchainBalanceSats
                        ?.toString()
                        ?.toLongOrNull()
                        ?: sourceOnchainSats

                    val paid = tryPayTopupInvoiceNow(sourceKey)
                    if (paid != null) {
                        return paid
                    }
                } else if (bootstrap.pending) {
                    markPendingBootstrap(sourceKey, bootstrap.fundingReference)
                    val pending = TopupResult(
                        success = false,
                        pending = true,
                        errorMessage = context.getString(
                            R.string.wallet_federation_topup_liquidity_pending,
                            source.name,
                        ),
                        reference = bootstrap.fundingReference,
                    )
                    val canTryOnchainFallback = sourceOnchainSats >= quote.mintAmountSats &&
                        quote.targetFederation.onchainDepositsDisabled != true
                    if (!canTryOnchainFallback) {
                        return pending
                    }
                    pendingResult = pendingResult ?: pending
                } else {
                    clearPendingBootstrap(sourceKey)
                    val details = bootstrap.errorMessage
                        ?: LdkWalletManager.getLastErrorMessage()
                        ?: context.getString(R.string.wallet_unknown_error)
                    errors.add(
                        context.getString(
                            R.string.wallet_federation_topup_liquidity_bootstrap_failed,
                            source.name,
                            details,
                        )
                    )
                }
            }

            if (sourceLightningSats < requiredLightningSats && sourceOnchainSats > 0L) {
                val gatewayBootstrap = LdkWalletManager.bootstrapOutboundLiquidityViaGatewayHintsBlocking(
                    context = context,
                    gatewayNodeIds = gatewayNodeHints,
                    requiredSats = requiredLightningSats,
                    network = source.network,
                )
                if (gatewayBootstrap.success) {
                    clearPendingBootstrap(sourceKey)
                    sourceBalances = LdkWalletManager.listBalances()
                    sourceLightningSats = sourceBalances?.totalLightningBalanceSats
                        ?.toString()
                        ?.toLongOrNull()
                        ?: sourceLightningSats
                    sourceOnchainSats = sourceBalances?.spendableOnchainBalanceSats
                        ?.toString()
                        ?.toLongOrNull()
                        ?: sourceOnchainSats
                    val paid = tryPayTopupInvoiceNow(sourceKey)
                    if (paid != null) {
                        return paid
                    }
                } else if (gatewayBootstrap.pending) {
                    sourceBalances = LdkWalletManager.listBalances()
                    sourceLightningSats = sourceBalances?.totalLightningBalanceSats
                        ?.toString()
                        ?.toLongOrNull()
                        ?: sourceLightningSats
                    sourceOnchainSats = sourceBalances?.spendableOnchainBalanceSats
                        ?.toString()
                        ?.toLongOrNull()
                        ?: sourceOnchainSats

                    val paid = tryPayTopupInvoiceNow(sourceKey)
                    if (paid != null) {
                        return paid
                    } else {
                        markPendingBootstrap(sourceKey, gatewayBootstrap.fundingReference)
                        val pending = TopupResult(
                            success = false,
                            pending = true,
                            errorMessage = gatewayBootstrap.errorMessage ?: context.getString(
                                R.string.wallet_federation_topup_gateway_pending,
                                source.name,
                            ),
                            reference = gatewayBootstrap.fundingReference,
                        )
                        val canTryOnchainFallback = sourceOnchainSats >= quote.mintAmountSats &&
                            quote.targetFederation.onchainDepositsDisabled != true
                        if (!canTryOnchainFallback) {
                            return pending
                        }
                        pendingResult = pendingResult ?: pending
                    }
                } else {
                    clearPendingBootstrap(sourceKey)
                    val details = gatewayBootstrap.errorMessage
                        ?: LdkWalletManager.getLastErrorMessage()
                        ?: context.getString(R.string.wallet_unknown_error)
                    errors.add(
                        context.getString(
                            R.string.wallet_federation_topup_gateway_bootstrap_failed,
                            source.name,
                            details,
                        )
                    )
                }
            }

            if (sourceOnchainSats >= quote.mintAmountSats && quote.targetFederation.onchainDepositsDisabled != true) {
                val depositAddress = FedimintWalletManager.createOnchainDepositAddressBlocking(
                    context = context,
                    federation = quote.targetFederation,
                )
                if (depositAddress.isNullOrBlank()) {
                    val depositError = FedimintWalletManager.getLastErrorMessage().orEmpty()
                    errors.add(
                        if (isOnchainDepositUnsupported(depositError)) {
                            context.getString(R.string.wallet_federation_topup_onchain_disabled)
                        } else {
                            depositError.ifBlank {
                                context.getString(R.string.wallet_federation_topup_onchain_address_failed)
                            }
                        }
                    )
                    continue
                }

                val txId = LdkWalletManager.sendOnchain(depositAddress, quote.mintAmountSats)
                if (txId.isNullOrBlank()) {
                    errors.add(
                        LdkWalletManager.getLastErrorMessage()
                            ?: context.getString(R.string.wallet_federation_topup_onchain_send_failed, source.name)
                    )
                    continue
                }

                if (waitForFederationBalance(
                        context = context,
                        federation = quote.targetFederation,
                        minRequiredSats = quote.invoiceAmountSats,
                        timeoutMs = ONCHAIN_SETTLE_TIMEOUT_MS,
                    )
                ) {
                    clearPendingBootstrap(sourceKey)
                    return TopupResult(success = true, reference = txId)
                }

                pendingResult = pendingResult ?: TopupResult(
                    success = false,
                    pending = true,
                    errorMessage = context.getString(
                        R.string.wallet_federation_topup_onchain_pending,
                        source.name,
                        txId,
                    ),
                    reference = txId,
                )
                continue
            }

            if (sourceOnchainSats >= quote.mintAmountSats && quote.targetFederation.onchainDepositsDisabled == true) {
                errors.add(context.getString(R.string.wallet_federation_topup_onchain_disabled))
            }

            errors.add(
                context.getString(
                    R.string.wallet_federation_topup_lightning_insufficient,
                    source.name,
                    amountFormat.format(requiredLightningSats),
                    amountFormat.format(sourceLightningSats),
                    amountFormat.format(sourceOnchainSats),
                )
            )
        }

        pendingResult?.let { return it }

        val summary = errors
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinct()
            .take(2)
            .joinToString(separator = " | ")
        return TopupResult(
            success = false,
            errorMessage = summary.ifBlank {
                context.getString(R.string.wallet_federation_topup_unavailable)
            }
        )
    }

    private fun extractInvoiceRouteHintNodeIds(invoiceText: String): List<String> {
        val invoice = runCatching { Bolt11Invoice.fromStr(invoiceText.trim()) }.getOrNull() ?: return emptyList()
        val routeHints = runCatching { invoice.routeHints() }.getOrNull().orEmpty()
        return routeHints
            .flatten()
            .map { it.srcNodeId.trim() }
            .filter { it.matches(Regex("^(02|03)[0-9a-fA-F]{64}$")) }
            .distinct()
    }

    private fun buildSourceCandidates(
        context: Context,
        quote: TopupQuote,
    ): List<FederationEntry> {
        val excludedId = quote.targetFederation.id.trim()
        val normalizedTargetNetwork = normalizeNetwork(quote.targetFederation.network)
        val discovered = FederationDirectoryManager.getFederations(context)
            .asSequence()
            .filterNot { FederationDirectoryManager.isFedimintFederation(it) }
            .filterNot { it.id == excludedId }
            .filter {
                val network = normalizeNetwork(it.network)
                normalizedTargetNetwork.isBlank() || network.isBlank() || network == normalizedTargetNetwork
            }
            .sortedWith(
                compareByDescending<FederationEntry> {
                    normalizeNetwork(it.network) == normalizedTargetNetwork && normalizedTargetNetwork.isNotBlank()
                }
                    .thenByDescending { isMainnetLikeNetwork(it.network) }
                    .thenBy { it.id.lowercase() }
            )
            .toList()

        val deduped = LinkedHashMap<String, FederationEntry>()
        if (quote.sourceFederation.id.isNotBlank() && quote.sourceFederation.id != excludedId) {
            deduped[quote.sourceFederation.id] = quote.sourceFederation
        }
        for (entry in discovered) {
            val id = entry.id.trim()
            if (id.isBlank()) continue
            if (!deduped.containsKey(id)) {
                deduped[id] = entry
            }
        }
        return deduped.values.toList()
    }

    private fun waitForFederationBalance(
        context: Context,
        federation: FederationEntry,
        minRequiredSats: Long,
        timeoutMs: Long = BALANCE_SETTLE_TIMEOUT_MS,
    ): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(BALANCE_POLL_INTERVAL_MS)
        while (System.currentTimeMillis() < deadline) {
            val balance = FedimintWalletManager.getBalanceSatsBlocking(context, federation)
            if (balance != null && balance >= minRequiredSats) {
                return true
            }
            try {
                Thread.sleep(BALANCE_POLL_INTERVAL_MS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    private fun estimateRoutingFeeSats(amountSats: Long): Long {
        if (amountSats <= 0L) return 0L
        val proportional = amountSats / 200L // ~0.5%
        return proportional.coerceAtLeast(10L).coerceAtMost(5_000L)
    }

    private fun saturatingAdd(left: Long, right: Long): Long {
        if (right <= 0L) return left
        return if (left > Long.MAX_VALUE - right) Long.MAX_VALUE else left + right
    }

    private fun isMainnetLikeNetwork(network: String?): Boolean {
        val normalized = normalizeNetwork(network)
        return normalized == "bitcoin"
    }

    private fun normalizeNetwork(network: String?): String {
        return when (network?.trim()?.lowercase()) {
            "bitcoin", "mainnet", "btc" -> "bitcoin"
            "testnet", "test" -> "testnet"
            "signet" -> "signet"
            "regtest" -> "regtest"
            else -> network?.trim()?.lowercase().orEmpty()
        }
    }

    private fun isOnchainDepositUnsupported(error: String?): Boolean {
        val text = error?.trim().orEmpty().lowercase()
        if (text.isBlank()) return false
        return text.contains("does not expose on-chain deposit support") ||
            text.contains("module not found") && (text.contains("wallet") || text.contains("onchain")) ||
            text.contains("no compatible wallet rpc methods")
    }
}
