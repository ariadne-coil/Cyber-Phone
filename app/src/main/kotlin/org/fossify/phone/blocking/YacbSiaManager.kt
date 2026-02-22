package org.fossify.phone.blocking

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.core.content.edit
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkManager
import org.fossify.commons.helpers.ensureBackgroundThread
import dummydomain.yetanothercallblocker.sia.Properties
import dummydomain.yetanothercallblocker.sia.SettingsImpl
import dummydomain.yetanothercallblocker.sia.Storage
import dummydomain.yetanothercallblocker.sia.model.SiaMetadata
import dummydomain.yetanothercallblocker.sia.model.database.AbstractDatabase
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabase
import dummydomain.yetanothercallblocker.sia.model.database.CommunityDatabaseItem
import dummydomain.yetanothercallblocker.sia.model.database.DbManager
import dummydomain.yetanothercallblocker.sia.model.database.FeaturedDatabase
import dummydomain.yetanothercallblocker.sia.network.DbDownloader
import dummydomain.yetanothercallblocker.sia.network.DbUpdateRequester
import dummydomain.yetanothercallblocker.sia.network.OkHttpClientFactory
import dummydomain.yetanothercallblocker.sia.network.WebService
import dummydomain.yetanothercallblocker.sia.utils.Utils
import okhttp3.OkHttpClient
import org.fossify.messages.extensions.config as messagesConfig
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.TimeUnit

object YacbSiaManager {
    private const val SIA_PROPERTIES = "sia_preferences"
    private const val SIA_PATH_PREFIX = "sia/"
    private const val SIA_SECONDARY_PATH_PREFIX = "sia-secondary/"
    private const val WORK_TAG = "yacbSiaAutoUpdate"
    private const val WORK_NAME = "yacbSiaAutoUpdateWork"

    @Volatile
    private var initialized = false

    private lateinit var communityDatabase: CommunityDatabase
    private lateinit var featuredDatabase: FeaturedDatabase
    private lateinit var siaMetadata: SiaMetadata
    private lateinit var dbManager: DbManager
    private var webService: WebService? = null
    private lateinit var appContext: Context

    fun init(context: Context) {
        if (initialized) {
            return
        }
        synchronized(this) {
            if (initialized) {
                return
            }
            try {
                val appContext = context.applicationContext
                this.appContext = appContext
                val storage = AndroidStorage(appContext)
                val properties = AndroidProperties(appContext, SIA_PROPERTIES)
                val settings = SettingsImpl(properties)

                val okHttpClientFactory = OkHttpClientFactory { OkHttpClient() }

                communityDatabase = CommunityDatabase(
                    storage,
                    AbstractDatabase.Source.ANY,
                    SIA_PATH_PREFIX,
                    SIA_SECONDARY_PATH_PREFIX,
                    settings
                )
                featuredDatabase = FeaturedDatabase(
                    storage,
                    AbstractDatabase.Source.ANY,
                    SIA_PATH_PREFIX
                )
                siaMetadata = SiaMetadata(storage, SIA_PATH_PREFIX, communityDatabase::isUsingInternal)

                val wsParameterProvider = object : WebService.DefaultWSParameterProvider() {
                    private var appId: String? = null
                    private var appIdTimestamp = 0L

                    override fun getAppId(): String {
                        val now = System.nanoTime()
                        val cached = appId
                        if (cached != null && now < appIdTimestamp + TimeUnit.MINUTES.toNanos(5)) {
                            return cached
                        }

                        val newId = Utils.generateAppId()
                        appId = newId
                        appIdTimestamp = now
                        return newId
                    }

                    override fun getAppVersion(): Int {
                        return siaMetadata.getSiaAppVersion()
                    }

                    override fun getOkHttpVersion(): String {
                        return siaMetadata.getSiaOkHttpVersion()
                    }

                    override fun getDbVersion(): Int {
                        return communityDatabase.getEffectiveDbVersion()
                    }

                    override fun getCountry(): SiaMetadata.Country {
                        return siaMetadata.getCountry("")
                    }
                }

                val webService = WebService(wsParameterProvider, okHttpClientFactory)
                this.webService = webService
                dbManager = DbManager(
                    storage,
                    SIA_PATH_PREFIX,
                    DbDownloader(okHttpClientFactory),
                    DbUpdateRequester(webService),
                    communityDatabase
                )

                initialized = true
                ensureCommunityDbAsync(appContext)
                updateAutoUpdate(appContext)
            } catch (t: Throwable) {
                initialized = false
                webService = null
                Log.e("YacbSiaManager", "Initialization failed, disabling YACB runtime", t)
            }
        }
    }

    fun disable(context: Context) {
        try {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        } catch (_: Exception) {
        }
        initialized = false
        webService = null
    }

    fun updateAutoUpdate(context: Context) {
        val appContext = context.applicationContext
        if (appContext.messagesConfig.yacbCommunityEnabled && appContext.messagesConfig.yacbAutoUpdate) {
            scheduleUpdates(appContext)
        } else {
            try {
                WorkManager.getInstance(appContext).cancelUniqueWork(WORK_NAME)
            } catch (_: Exception) {
            }
        }
    }

    fun ensureCommunityDbAsync(context: Context, onFinished: ((Boolean) -> Unit)? = null) {
        if (!initialized) {
            init(context)
        }
        if (!initialized || !::communityDatabase.isInitialized || !::dbManager.isInitialized) {
            onFinished?.invoke(false)
            return
        }
        ensureBackgroundThread {
            val wasReady = isCommunityDbReady()
            ensureMainDatabase()
            val isReady = isCommunityDbReady()
            val appContext = context.applicationContext
            if (!wasReady && isReady) {
                appContext.messagesConfig.yacbLastRefresh = System.currentTimeMillis()
            }
            onFinished?.invoke(isReady)
        }
    }

    fun isCommunityDbReady(): Boolean {
        return initialized && ::communityDatabase.isInitialized && communityDatabase.isOperational
    }

    fun getRating(number: String): Rating? {
        if (!initialized || !communityDatabase.isOperational) {
            return null
        }

        val communityItem = communityDatabase.getDbItemByNumber(number) ?: return null
        if (!communityItem.hasRatings()) {
            return null
        }

        return calculateRating(communityItem)
    }

    @Keep
    fun submitRating(number: String, rating: Rating) {
        if (!initialized) {
            return
        }
        val ws = webService ?: return
        val candidates = listOf(
            "submitRating",
            "sendRating",
            "postRating",
            "rateNumber",
            "submitCommunityRating",
            "sendCommunityRating"
        )
        val methods = ws.javaClass.methods.filter { it.name in candidates && it.parameterTypes.size == 2 }
        if (methods.isEmpty()) {
            return
        }
        val ratingInt = when (rating) {
            Rating.POSITIVE -> 1
            Rating.NEGATIVE -> -1
            Rating.NEUTRAL -> 0
        }

        for (method in methods) {
            val params = method.parameterTypes
            if (params[0] == String::class.java) {
                val ratingParam = params[1]
                try {
                    when {
                        ratingParam == Int::class.javaPrimitiveType || ratingParam == Int::class.javaObjectType ->
                            method.invoke(ws, number, ratingInt)

                        ratingParam == String::class.java ->
                            method.invoke(ws, number, rating.name)

                        ratingParam.isEnum -> {
                            val enumClass = ratingParam.asSubclass(Enum::class.java)
                            val enumValue = java.lang.Enum.valueOf(enumClass, rating.name)
                            method.invoke(ws, number, enumValue)
                        }
                    }
                    return
                } catch (_: Exception) {
                }
            }
        }
    }

    fun getFeaturedName(number: String): String? {
        if (!initialized || !featuredDatabase.isOperational) {
            return null
        }

        return featuredDatabase.getDbItemByNumber(number)?.name
    }

    @Keep
    fun getRatingCounts(number: String): IntArray? {
        if (!initialized || !communityDatabase.isOperational) {
            return null
        }
        val communityItem = communityDatabase.getDbItemByNumber(number) ?: return null
        if (!communityItem.hasRatings()) {
            return null
        }
        return intArrayOf(
            communityItem.negativeRatingsCount,
            communityItem.positiveRatingsCount,
            communityItem.neutralRatingsCount
        )
    }

    fun updateSecondaryDb() {
        if (!initialized) {
            return
        }
        try {
            dbManager.updateSecondaryDb()
            communityDatabase.reload()
            featuredDatabase.reload()
            siaMetadata.reload()
        } catch (e: Throwable) {
            Log.w("YacbSiaManager", "Secondary DB update failed", e)
        }
    }

    private fun ensureMainDatabase() {
        if (communityDatabase.isOperational) {
            return
        }

        try {
            val downloaded = dbManager.downloadMainDb()
            if (downloaded) {
                communityDatabase.reload()
                featuredDatabase.reload()
                siaMetadata.reload()
                if (::appContext.isInitialized) {
                    appContext.messagesConfig.yacbLastRefresh = System.currentTimeMillis()
                }
            }
        } catch (e: Throwable) {
            Log.w("YacbSiaManager", "Main DB download failed", e)
        }
    }

    private fun scheduleUpdates(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequest.Builder(
            YacbUpdateWorker::class.java,
            1,
            TimeUnit.DAYS
        )
            .addTag(WORK_TAG)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
    }

    private fun calculateRating(item: CommunityDatabaseItem): Rating {
        val negative = item.negativeRatingsCount
        val positive = item.positiveRatingsCount
        val neutral = item.neutralRatingsCount
        return when {
            negative > positive + neutral -> Rating.NEGATIVE
            positive > neutral + negative -> Rating.POSITIVE
            else -> Rating.NEUTRAL
        }
    }

    enum class Rating {
        POSITIVE,
        NEUTRAL,
        NEGATIVE
    }

    private class AndroidProperties(context: Context, name: String) : Properties {
        private val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)

        override fun getInt(key: String, defaultValue: Int): Int {
            return prefs.getInt(key, defaultValue)
        }

        override fun setInt(key: String, value: Int) {
            prefs.edit { putInt(key, value) }
        }
    }

    private class AndroidStorage(private val context: Context) : Storage {
        override fun getDataDirPath(): String {
            return context.filesDir.absolutePath + "/"
        }

        override fun getCacheDirPath(): String {
            return context.cacheDir.absolutePath + "/"
        }

        @Throws(IOException::class)
        override fun openFile(fileName: String, internal: Boolean): InputStream {
            return if (internal) {
                context.assets.open(fileName)
            } else {
                FileInputStream(getDataDirPath() + fileName)
            }
        }
    }
}
