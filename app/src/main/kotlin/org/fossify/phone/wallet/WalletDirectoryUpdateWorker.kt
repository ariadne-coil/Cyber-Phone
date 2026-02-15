package org.fossify.phone.wallet

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class WalletDirectoryUpdateWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        val ok = FederationDirectoryManager.refreshBlocking(applicationContext)
        return if (ok) Result.success() else Result.retry()
    }
}

