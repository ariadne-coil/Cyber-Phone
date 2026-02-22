package org.fossify.phone.helpers

import android.content.Context
import android.net.Uri
import android.telecom.PhoneAccountHandle
import android.telephony.PhoneNumberUtils
import android.telephony.TelephonyManager
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.fossify.commons.helpers.BaseConfig
import org.fossify.phone.extensions.getPhoneAccountHandleModel
import org.fossify.phone.extensions.arePhoneNumbersEquivalent
import org.fossify.phone.extensions.putPhoneAccountHandle
import org.fossify.phone.models.SpeedDial
import androidx.core.content.edit
import java.util.Locale

class Config(context: Context) : BaseConfig(context) {
    companion object {
        fun newInstance(context: Context) = Config(context)
    }

    private val regionHint: String by lazy {
        val telephonyManager = context.getSystemService(TelephonyManager::class.java)
        listOf(
            telephonyManager?.simCountryIso,
            telephonyManager?.networkCountryIso,
            Locale.getDefault().country
        )
            .firstOrNull { !it.isNullOrBlank() }
            ?.uppercase(Locale.US)
            .orEmpty()
    }

    fun getSpeedDialValues(): ArrayList<SpeedDial> {
        val speedDialType = object : TypeToken<List<SpeedDial>>() {}.type
        val speedDialValues = Gson().fromJson<ArrayList<SpeedDial>>(speedDial, speedDialType) ?: ArrayList(1)

        for (i in 1..9) {
            val speedDial = SpeedDial(i, "", "")
            if (speedDialValues.firstOrNull { it.id == i } == null) {
                speedDialValues.add(speedDial)
            }
        }

        return speedDialValues
    }

    fun saveCustomSIM(number: String, handle: PhoneAccountHandle) {
        prefs.edit().putPhoneAccountHandle(
            key = getKeyForCustomSIM(number),
            parcelable = handle
        ).apply()
    }

    fun getCustomSIM(number: String): PhoneAccountHandle? {
        val key = getKeyForCustomSIM(number)
        prefs.getPhoneAccountHandleModel(key, null)?.let {
            return it.toPhoneAccountHandle()
        }

        // fallback for old unstable keys. should be removed in future versions
        val migratedHandle = prefs.all.keys
            .filterIsInstance<String>()
            .filter { it.startsWith(REMEMBER_SIM_PREFIX) }
            .firstOrNull {
                arePhoneNumbersEquivalent(
                    it.removePrefix(REMEMBER_SIM_PREFIX),
                    normalizeCustomSIMNumber(number)
                )
            }?.let { legacyKey ->
                prefs.getPhoneAccountHandleModel(legacyKey, null)?.let {
                    val handle = it.toPhoneAccountHandle()
                    prefs.edit {
                        remove(legacyKey)
                        putPhoneAccountHandle(key, handle)
                    }
                    handle
                }
            }

        return migratedHandle
    }

    fun removeCustomSIM(number: String) {
        prefs.edit { remove(getKeyForCustomSIM(number)) }
    }

    private fun getKeyForCustomSIM(number: String): String {
        return REMEMBER_SIM_PREFIX + normalizeCustomSIMNumber(number)
    }

    private fun normalizeCustomSIMNumber(number: String): String {
        val decoded = Uri.decode(number).removePrefix("tel:")
        val formatted = PhoneNumberUtils.formatNumberToE164(decoded, regionHint)
        return formatted ?: PhoneNumberUtils.normalizeNumber(decoded)
    }

    var showTabs: Int
        get() = prefs.getInt(SHOW_TABS, ALL_TABS_MASK)
        set(showTabs) = prefs.edit { putInt(SHOW_TABS, showTabs) }

    var groupSubsequentCalls: Boolean
        get() = prefs.getBoolean(GROUP_SUBSEQUENT_CALLS, true)
        set(groupSubsequentCalls) = prefs.edit { putBoolean(GROUP_SUBSEQUENT_CALLS, groupSubsequentCalls) }

    var openDialPadAtLaunch: Boolean
        get() = prefs.getBoolean(OPEN_DIAL_PAD_AT_LAUNCH, false)
        set(openDialPad) = prefs.edit { putBoolean(OPEN_DIAL_PAD_AT_LAUNCH, openDialPad) }

    var disableProximitySensor: Boolean
        get() = prefs.getBoolean(DISABLE_PROXIMITY_SENSOR, false)
        set(disableProximitySensor) = prefs.edit { putBoolean(DISABLE_PROXIMITY_SENSOR, disableProximitySensor) }

    var disableSwipeToAnswer: Boolean
        get() = prefs.getBoolean(DISABLE_SWIPE_TO_ANSWER, false)
        set(disableSwipeToAnswer) = prefs.edit { putBoolean(DISABLE_SWIPE_TO_ANSWER, disableSwipeToAnswer) }

    var wasOverlaySnackbarConfirmed: Boolean
        get() = prefs.getBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, false)
        set(wasOverlaySnackbarConfirmed) = prefs.edit { putBoolean(WAS_OVERLAY_SNACKBAR_CONFIRMED, wasOverlaySnackbarConfirmed) }

    var dialpadVibration: Boolean
        get() = prefs.getBoolean(DIALPAD_VIBRATION, true)
        set(dialpadVibration) = prefs.edit { putBoolean(DIALPAD_VIBRATION, dialpadVibration) }

    var hideDialpadNumbers: Boolean
        get() = prefs.getBoolean(HIDE_DIALPAD_NUMBERS, false)
        set(hideDialpadNumbers) = prefs.edit { putBoolean(HIDE_DIALPAD_NUMBERS, hideDialpadNumbers) }

    var dialpadBeeps: Boolean
        get() = prefs.getBoolean(DIALPAD_BEEPS, true)
        set(dialpadBeeps) = prefs.edit { putBoolean(DIALPAD_BEEPS, dialpadBeeps) }

    var alwaysShowFullscreen: Boolean
        get() = prefs.getBoolean(ALWAYS_SHOW_FULLSCREEN, false)
        set(alwaysShowFullscreen) = prefs.edit { putBoolean(ALWAYS_SHOW_FULLSCREEN, alwaysShowFullscreen) }

    var blockNegativeRatings: Boolean
        get() = prefs.getBoolean(BLOCK_NEGATIVE_RATINGS, false)
        set(value) = prefs.edit { putBoolean(BLOCK_NEGATIVE_RATINGS, value) }

    var showBlockedCallNotifications: Boolean
        get() = prefs.getBoolean(SHOW_BLOCKED_CALL_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(SHOW_BLOCKED_CALL_NOTIFICATIONS, value) }

    var showCallRatingNotifications: Boolean
        get() = prefs.getBoolean(SHOW_CALL_RATING_NOTIFICATIONS, false)
        set(value) = prefs.edit { putBoolean(SHOW_CALL_RATING_NOTIFICATIONS, value) }

    var walletSelectedFederationId: String
        get() = prefs.getString(WALLET_SELECTED_FEDERATION_ID, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_SELECTED_FEDERATION_ID, value) }

    var walletDirectoryJson: String
        get() = prefs.getString(WALLET_DIRECTORY_JSON, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_DIRECTORY_JSON, value) }

    var walletDirectoryLastSyncMs: Long
        get() = prefs.getLong(WALLET_DIRECTORY_LAST_SYNC_MS, 0L)
        set(value) = prefs.edit { putLong(WALLET_DIRECTORY_LAST_SYNC_MS, value) }

    var walletDirectoryLastHash: String
        get() = prefs.getString(WALLET_DIRECTORY_LAST_HASH, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_DIRECTORY_LAST_HASH, value) }

    var walletDirectoryLastUpdatedAtMs: Long
        get() = prefs.getLong(WALLET_DIRECTORY_LAST_UPDATED_AT_MS, 0L)
        set(value) = prefs.edit { putLong(WALLET_DIRECTORY_LAST_UPDATED_AT_MS, value) }

    // Stored as raw IEEE 754 bits so we can keep it in SharedPreferences reliably.
    var walletBtcUsdRate: Double
        get() = java.lang.Double.longBitsToDouble(prefs.getLong(WALLET_BTC_USD_RATE, 0L))
        set(value) = prefs.edit { putLong(WALLET_BTC_USD_RATE, java.lang.Double.doubleToLongBits(value)) }

    var walletBtcUsdRateLastSyncMs: Long
        get() = prefs.getLong(WALLET_BTC_USD_RATE_LAST_SYNC_MS, 0L)
        set(value) = prefs.edit { putLong(WALLET_BTC_USD_RATE_LAST_SYNC_MS, value) }

    var walletLastInvoice: String
        get() = prefs.getString(WALLET_LAST_INVOICE, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LAST_INVOICE, value) }

    var walletLastInvoiceCreatedMs: Long
        get() = prefs.getLong(WALLET_LAST_INVOICE_CREATED_MS, 0L)
        set(value) = prefs.edit { putLong(WALLET_LAST_INVOICE_CREATED_MS, value) }

    var walletLastOnchainAddress: String
        get() = prefs.getString(WALLET_LAST_ONCHAIN_ADDRESS, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LAST_ONCHAIN_ADDRESS, value) }

    var walletLastOnchainAddressCreatedMs: Long
        get() = prefs.getLong(WALLET_LAST_ONCHAIN_ADDRESS_CREATED_MS, 0L)
        set(value) = prefs.edit { putLong(WALLET_LAST_ONCHAIN_ADDRESS_CREATED_MS, value) }

    var walletLiquidityProviderMode: String
        get() = prefs.getString(WALLET_LIQUIDITY_PROVIDER_MODE, "auto") ?: "auto"
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_PROVIDER_MODE, value.trim().ifBlank { "auto" }) }

    var walletLiquidityProviderId: String
        get() = prefs.getString(WALLET_LIQUIDITY_PROVIDER_ID, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_PROVIDER_ID, value.trim()) }

    var walletLiquidityProviderStatsJson: String
        get() = prefs.getString(WALLET_LIQUIDITY_PROVIDER_STATS_JSON, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_PROVIDER_STATS_JSON, value) }

    var walletLiquidityCustomName: String
        get() = prefs.getString(WALLET_LIQUIDITY_CUSTOM_NAME, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_CUSTOM_NAME, value.trim()) }

    var walletLiquidityCustomNetwork: String
        get() = prefs.getString(WALLET_LIQUIDITY_CUSTOM_NETWORK, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_CUSTOM_NETWORK, value.trim()) }

    var walletLiquidityCustomNodeId: String
        get() = prefs.getString(WALLET_LIQUIDITY_CUSTOM_NODE_ID, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_CUSTOM_NODE_ID, value.trim()) }

    var walletLiquidityCustomAddress: String
        get() = prefs.getString(WALLET_LIQUIDITY_CUSTOM_ADDRESS, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_CUSTOM_ADDRESS, value.trim()) }

    var walletLiquidityCustomToken: String
        get() = prefs.getString(WALLET_LIQUIDITY_CUSTOM_TOKEN, "") ?: ""
        set(value) = prefs.edit { putString(WALLET_LIQUIDITY_CUSTOM_TOKEN, value.trim()) }

    // Wallet receive cache should be federation-specific, otherwise switching between mainnet/testnet
    // will show invalid addresses/invoices. Keep the legacy global keys for migration/backward compat.
    private fun walletKey(prefix: String, federationId: String): String {
        val id = federationId.trim().ifBlank { "default" }
        return "${prefix}_$id"
    }

    fun getWalletLastInvoiceForFederation(federationId: String): String {
        val key = walletKey(WALLET_LAST_INVOICE, federationId)
        val scoped = prefs.getString(key, "")?.trim().orEmpty()
        if (scoped.isNotBlank()) return scoped

        // One-time migration for the currently selected federation only.
        val legacy = walletLastInvoice.trim()
        if (legacy.isNotBlank() && federationId == walletSelectedFederationId) {
            setWalletLastInvoiceForFederation(federationId, legacy)
            return legacy
        }
        return ""
    }

    fun setWalletLastInvoiceForFederation(federationId: String, invoice: String) {
        val key = walletKey(WALLET_LAST_INVOICE, federationId)
        prefs.edit { putString(key, invoice.trim()) }
        // Keep legacy in sync for older code paths.
        walletLastInvoice = invoice.trim()
    }

    fun getWalletLastInvoiceCreatedMsForFederation(federationId: String): Long {
        val key = walletKey(WALLET_LAST_INVOICE_CREATED_MS, federationId)
        val scoped = prefs.getLong(key, 0L)
        if (scoped > 0L) return scoped

        val legacy = walletLastInvoiceCreatedMs
        if (legacy > 0L && federationId == walletSelectedFederationId) {
            setWalletLastInvoiceCreatedMsForFederation(federationId, legacy)
            return legacy
        }
        return 0L
    }

    fun setWalletLastInvoiceCreatedMsForFederation(federationId: String, createdMs: Long) {
        val key = walletKey(WALLET_LAST_INVOICE_CREATED_MS, federationId)
        prefs.edit { putLong(key, createdMs) }
        walletLastInvoiceCreatedMs = createdMs
    }

    fun getWalletLastOnchainAddressForFederation(federationId: String): String {
        val key = walletKey(WALLET_LAST_ONCHAIN_ADDRESS, federationId)
        val scoped = prefs.getString(key, "")?.trim().orEmpty()
        if (scoped.isNotBlank()) return scoped

        val legacy = walletLastOnchainAddress.trim()
        if (legacy.isNotBlank() && federationId == walletSelectedFederationId) {
            setWalletLastOnchainAddressForFederation(federationId, legacy)
            return legacy
        }
        return ""
    }

    fun setWalletLastOnchainAddressForFederation(federationId: String, address: String) {
        val key = walletKey(WALLET_LAST_ONCHAIN_ADDRESS, federationId)
        prefs.edit { putString(key, address.trim()) }
        walletLastOnchainAddress = address.trim()
    }

    fun getWalletLastOnchainAddressCreatedMsForFederation(federationId: String): Long {
        val key = walletKey(WALLET_LAST_ONCHAIN_ADDRESS_CREATED_MS, federationId)
        val scoped = prefs.getLong(key, 0L)
        if (scoped > 0L) return scoped

        val legacy = walletLastOnchainAddressCreatedMs
        if (legacy > 0L && federationId == walletSelectedFederationId) {
            setWalletLastOnchainAddressCreatedMsForFederation(federationId, legacy)
            return legacy
        }
        return 0L
    }

    fun setWalletLastOnchainAddressCreatedMsForFederation(federationId: String, createdMs: Long) {
        val key = walletKey(WALLET_LAST_ONCHAIN_ADDRESS_CREATED_MS, federationId)
        prefs.edit { putLong(key, createdMs) }
        walletLastOnchainAddressCreatedMs = createdMs
    }
}
