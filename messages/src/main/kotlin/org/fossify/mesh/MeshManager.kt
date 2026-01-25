package org.fossify.mesh

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

object MeshManager {
    fun sync(context: Context) {
        val appContext = context.applicationContext
        if (shouldRun(appContext)) {
            start(appContext)
        } else {
            stop(appContext)
        }
    }

    fun shouldRun(context: Context): Boolean {
        val config = MeshConfig.newInstance(context)
        return config.getMeshMode() != MeshMode.STANDARD_ONLY || config.meshRoutingEnabled
    }

    private fun start(context: Context) {
        val intent = Intent(context, MeshService::class.java).setAction(MeshService.ACTION_START)
        ContextCompat.startForegroundService(context, intent)
    }

    private fun stop(context: Context) {
        val intent = Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP)
        context.stopService(intent)
    }
}
