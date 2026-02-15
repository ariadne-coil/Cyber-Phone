package org.fossify.phone.wallet

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import org.fossify.commons.helpers.ensureBackgroundThread
import org.fossify.phone.extensions.config
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object ExchangeRateManager {
    // Simple, unauthenticated endpoint returning {"USD": <price>, ...}
    private const val PRICE_URL = "https://mempool.space/api/v1/prices"
    private const val STALE_AFTER_MS = 30L * 60L * 1000L

    private val client by lazy {
        OkHttpClient.Builder()
            .callTimeout(20, TimeUnit.SECONDS)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    fun getCachedUsdRate(context: Context): Double? {
        val rate = context.config.walletBtcUsdRate
        return if (rate > 0.0) rate else null
    }

    fun refreshIfStale(
        context: Context,
        force: Boolean = false,
        callback: ((success: Boolean) -> Unit)? = null,
    ) {
        val cfg = context.config
        val now = System.currentTimeMillis()
        val isStale = now - cfg.walletBtcUsdRateLastSyncMs > STALE_AFTER_MS
        if (!force && !isStale && cfg.walletBtcUsdRate > 0.0) {
            callback?.invoke(true)
            return
        }

        ensureBackgroundThread {
            val success = refreshBlocking(context)
            callback?.invoke(success)
        }
    }

    fun refreshBlocking(context: Context): Boolean {
        val cfg = context.config
        val now = System.currentTimeMillis()
        return try {
            val req = Request.Builder()
                .url(PRICE_URL)
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use false
                val obj = JSONObject(body)
                val usd = obj.optDouble("USD", 0.0)
                if (usd <= 0.0) return@use false

                cfg.walletBtcUsdRate = usd
                cfg.walletBtcUsdRateLastSyncMs = now
                true
            }
        } catch (_: IOException) {
            false
        } catch (_: Exception) {
            false
        }
    }
}
