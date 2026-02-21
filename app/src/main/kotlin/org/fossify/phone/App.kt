package org.fossify.phone

import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.fossify.commons.FossifyApp
import org.fossify.commons.extensions.baseConfig
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.mesh.MeshManager
import org.fossify.messages.extensions.config
import org.fossify.messages.helpers.MessagingCache
import org.fossify.phone.blocking.YacbSiaManager
import org.fossify.phone.mesh.voip.MeshVoipCallHandler
import org.fossify.phone.wallet.WalletDirectoryUpdateWorker
import org.fossify.phone.wallet.WalletExchangeRateUpdateWorker
import java.util.concurrent.TimeUnit

class App : FossifyApp() {
    override val isAppLockFeatureAvailable = true

    override fun onCreate() {
        super.onCreate()
        // Keep premium-gated customization features unlocked in this fork.
        baseConfig.hadThankYouInstalled = true
        configureWalletNativeLoading()
        if (config.yacbCommunityEnabled) {
            runCatching { YacbSiaManager.init(this) }.onFailure {
                // Never let optional YACB startup failures crash app launch.
                Log.e("App", "YACB initialization failed; continuing without community blocking", it)
            }
        }
        MeshVoipCallHandler.init(this)
        scheduleWalletDirectoryUpdates()
        if (hasPermission(PERMISSION_READ_CONTACTS)) {
            listOf(
                ContactsContract.Contacts.CONTENT_URI,
                ContactsContract.Data.CONTENT_URI,
                ContactsContract.DisplayPhoto.CONTENT_URI
            ).forEach {
                try {
                    contentResolver.registerContentObserver(it, true, contactsObserver)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun configureWalletNativeLoading() {
        // ldk-node-android uses JNA internally. On some Android builds, JNA fails to find/load its
        // native dispatch library unless we provide an explicit native library directory hint.
        try {
            val nativeDir = applicationInfo.nativeLibraryDir?.trim().orEmpty()
            if (nativeDir.isNotBlank()) {
                System.setProperty("jna.boot.library.path", nativeDir)
                System.setProperty("jna.library.path", nativeDir)
            }

            // Preload the native libs so failures appear early in logcat (and not as a vague
            // NoClassDefFoundError on first wallet use).
            runCatching { System.loadLibrary("jnidispatch") }.onFailure {
                Log.d("App", "Could not preload libjnidispatch", it)
            }
            runCatching { System.loadLibrary("ldk_node") }.onFailure {
                Log.d("App", "Could not preload libldk_node", it)
            }
        } catch (_: Throwable) {
        }
    }

    private fun scheduleWalletDirectoryUpdates() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequest.Builder(
            WalletDirectoryUpdateWorker::class.java,
            1,
            TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("wallet_directory_update", ExistingPeriodicWorkPolicy.UPDATE, request)

        // Keep the exchange rate reasonably fresh for fiat thresholds and UX.
        val rateRequest = PeriodicWorkRequest.Builder(
            WalletExchangeRateUpdateWorker::class.java,
            1,
            TimeUnit.HOURS
        )
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("wallet_exchange_rate_update", ExistingPeriodicWorkPolicy.UPDATE, rateRequest)
    }

    private val contactsObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean, uri: Uri?) {
            MessagingCache.namePhoto.evictAll()
            MessagingCache.participantsCache.evictAll()
        }
    }
}
