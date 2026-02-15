package org.fossify.phone.wallet

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class WalletEcashCancelWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val federationId = inputData.getString(KEY_FEDERATION_ID).orEmpty().trim()
        val operationId = inputData.getString(KEY_OPERATION_ID).orEmpty().trim()
        if (federationId.isBlank() || operationId.isBlank()) {
            return Result.failure()
        }

        val federation = FederationDirectoryManager.getFederations(applicationContext)
            .firstOrNull { it.id == federationId }
            ?: return if (runAttemptCount < 3) Result.retry() else Result.failure()

        val ok = FedimintWalletManager.tryCancelSpendBlocking(
            context = applicationContext,
            federation = federation,
            operationId = operationId,
        )
        return if (ok) Result.success() else if (runAttemptCount < 3) Result.retry() else Result.failure()
    }

    companion object {
        const val KEY_FEDERATION_ID = "federation_id"
        const val KEY_OPERATION_ID = "operation_id"
        const val TAG = "wallet_ecash_cancel"

        fun uniqueName(federationId: String, operationId: String): String {
            return "wallet_ecash_cancel_${federationId.trim()}_${operationId.trim()}"
        }
    }
}

