package org.fossify.phone.blocking

import android.content.Context
import android.util.Log
import androidx.work.Worker
import androidx.work.WorkerParameters

class YacbUpdateWorker(
    context: Context,
    params: WorkerParameters
) : Worker(context, params) {
    override fun doWork(): Result {
        return runCatching {
            YacbSiaManager.init(applicationContext)
            YacbSiaManager.updateSecondaryDb()
            Result.success()
        }.getOrElse {
            Log.e("YacbUpdateWorker", "YACB update worker failed", it)
            if (it is LinkageError || it is VerifyError) {
                Result.failure()
            } else {
                Result.retry()
            }
        }
    }
}
