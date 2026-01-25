package org.fossify.phone.blocking

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class YacbUpdateWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        YacbSiaManager.init(applicationContext)
        YacbSiaManager.updateSecondaryDb()
        return Result.success()
    }
}
