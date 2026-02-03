package org.fossify.mesh

import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

object MeshManager {
    private const val TAG = "MeshManager"

    fun sync(context: Context) {
        val appContext = context.applicationContext
        if (shouldRun(appContext)) {
            start(appContext)
        } else {
            stop(appContext)
        }
    }

    fun restart(context: Context) {
        val appContext = context.applicationContext
        stop(appContext)
        if (shouldRun(appContext)) {
            start(appContext)
        }
    }

    fun shouldRun(context: Context): Boolean {
        val config = MeshConfig.newInstance(context)
        return config.getMeshMode() != MeshMode.STANDARD_ONLY || config.meshRoutingEnabled
    }

    private fun start(context: Context) {
        val intent = Intent(context, MeshService::class.java).setAction(MeshService.ACTION_START)
        try {
            ContextCompat.startForegroundService(context, intent)
        } catch (e: Exception) {
            if (isForegroundStartNotAllowed(e)) {
                Log.w(TAG, "Foreground service start not allowed", e)
            } else {
                throw e
            }
        }
    }

    private fun isForegroundStartNotAllowed(e: Exception): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return false
        return e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
    }

    private fun stop(context: Context) {
        val intent = Intent(context, MeshService::class.java).setAction(MeshService.ACTION_STOP)
        context.stopService(intent)
    }
}
