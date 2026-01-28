package org.fossify.messages.helpers

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.messages.extensions.config
import org.fossify.messages.R
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AiSpamModelManager {
    private const val MODEL_DIR = "ai_models"
    private const val MODEL_BASENAME = "spam_model"
    private const val UPDATE_INTERVAL_MS = 24 * 60 * 60 * 1000L

    fun getModelFile(context: Context): File? {
        val url = context.config.aiSpamModelUrl.trim()
        if (url.isEmpty()) {
            return null
        }
        val extension = url.substringAfterLast('.', "tflite").lowercase()
        val safeExt = if (extension in setOf("tflite", "task")) extension else "tflite"
        val dir = File(context.filesDir, MODEL_DIR)
        val file = File(dir, "$MODEL_BASENAME.$safeExt")
        return if (file.exists()) file else null
    }

    fun getModelStatusText(context: Context): String {
        val url = context.config.aiSpamModelUrl.trim()
        if (url.isEmpty()) {
            return context.getString(R.string.ai_spam_model_not_set)
        }
        return if (getModelFile(context) != null) {
            context.getString(R.string.ai_spam_model_ready)
        } else {
            context.getString(R.string.ai_spam_model_not_downloaded)
        }
    }

    fun ensureModelAvailable(context: Context) {
        maybeUpdateModel(context, force = false, callback = null)
    }

    fun requestModelUpdate(context: Context, callback: ((Boolean) -> Unit)?) {
        maybeUpdateModel(context, force = true, callback = callback)
    }

    fun resetModel(context: Context) {
        val dir = File(context.filesDir, MODEL_DIR)
        dir.listFiles()?.forEach { it.delete() }
        context.config.aiSpamModelEtag = ""
        context.config.aiSpamModelLastCheck = 0L
    }

    private fun maybeUpdateModel(
        context: Context,
        force: Boolean,
        callback: ((Boolean) -> Unit)?
    ) {
        val appContext = context.applicationContext
        val modelUrl = appContext.config.aiSpamModelUrl.trim()
        if (modelUrl.isEmpty()) {
            callback?.invoke(false)
            return
        }
        val now = System.currentTimeMillis()
        if (!force && now - appContext.config.aiSpamModelLastCheck < UPDATE_INTERVAL_MS) {
            callback?.invoke(false)
            return
        }
        appContext.config.aiSpamModelLastCheck = now
        ensureBackgroundThread {
            val updated = downloadModel(appContext, modelUrl, force)
            if (callback != null) {
                Handler(Looper.getMainLooper()).post { callback(updated) }
            }
        }
    }

    private fun downloadModel(context: Context, modelUrl: String, force: Boolean): Boolean {
        val extension = modelUrl.substringAfterLast('.', "tflite").lowercase()
        val safeExt = if (extension in setOf("tflite", "task")) extension else "tflite"
        val dir = File(context.filesDir, MODEL_DIR)
        if (!dir.exists()) {
            dir.mkdirs()
        }
        val target = File(dir, "$MODEL_BASENAME.$safeExt")
        val temp = File(dir, "$MODEL_BASENAME.$safeExt.tmp")

        val connection = (URL(modelUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            useCaches = false
            if (!force) {
                val etag = context.config.aiSpamModelEtag
                if (etag.isNotBlank()) {
                    setRequestProperty("If-None-Match", etag)
                }
            }
        }

        return try {
            val code = connection.responseCode
            if (code == HttpURLConnection.HTTP_NOT_MODIFIED) {
                return false
            }
            if (code !in 200..299) {
                return false
            }
            connection.inputStream.use { input ->
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            if (target.exists()) {
                target.delete()
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return false
            }
            val etag = connection.getHeaderField("ETag").orEmpty()
            if (etag.isNotBlank()) {
                context.config.aiSpamModelEtag = etag
            }
            true
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }
}
