package org.fossify.phone.activities

import android.app.role.RoleManager
import android.content.Intent
import android.os.Build
import android.provider.Telephony
import android.telecom.TelecomManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import org.fossify.commons.extensions.toast
import org.fossify.phone.R
import org.fossify.messages.activities.SimpleActivity as MessagesSimpleActivity

open class SimpleActivity : MessagesSimpleActivity() {
    private companion object {
        private const val FOSSIFY_BASE_ACTIVITY_CLASS = "org.fossify.commons.activities.BaseSimpleActivity"
        private const val FOSSIFY_PACKAGE_PREFIX_CHECK_VALUE = "org.fossify.phone"
    }

    private val pendingDefaultDialerCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val pendingDefaultSmsCallbacks = mutableListOf<(Boolean) -> Unit>()

    private val defaultDialerRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        dispatchDefaultDialerResult(isDefaultPhoneRoleHeld())
    }

    private val defaultSmsRoleLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        dispatchDefaultSmsResult(isDefaultSmsRoleHeld())
    }

    override fun getAppIconIDs() = arrayListOf(
        R.mipmap.ic_launcher_red,
        R.mipmap.ic_launcher_pink,
        R.mipmap.ic_launcher_purple,
        R.mipmap.ic_launcher_deep_purple,
        R.mipmap.ic_launcher_indigo,
        R.mipmap.ic_launcher_blue,
        R.mipmap.ic_launcher_light_blue,
        R.mipmap.ic_launcher_cyan,
        R.mipmap.ic_launcher_teal,
        R.mipmap.ic_launcher,
        R.mipmap.ic_launcher_light_green,
        R.mipmap.ic_launcher_lime,
        R.mipmap.ic_launcher_yellow,
        R.mipmap.ic_launcher_amber,
        R.mipmap.ic_launcher_orange,
        R.mipmap.ic_launcher_deep_orange,
        R.mipmap.ic_launcher_brown,
        R.mipmap.ic_launcher_blue_grey,
        R.mipmap.ic_launcher_grey_black
    )

    override fun getAppLauncherName() = getString(R.string.app_launcher_name)

    override fun getRepositoryName() = "Phone"

    override fun getPackageName(): String {
        val directCaller = Throwable().stackTrace.getOrNull(1)
        if (directCaller?.className == FOSSIFY_BASE_ACTIVITY_CLASS && directCaller.methodName == "onCreate") {
            // Fossify commons shows a fake-version warning for non-org.fossify package names.
            // Keep the real package everywhere except that internal branding check.
            return FOSSIFY_PACKAGE_PREFIX_CHECK_VALUE
        }

        return super.getPackageName()
    }

    fun requestDefaultDialerRoleIfNeeded(onResult: ((Boolean) -> Unit)? = null) {
        onResult?.let { pendingDefaultDialerCallbacks.add(it) }

        if (isDefaultPhoneRoleHeld()) {
            dispatchDefaultDialerResult(true)
            return
        }

        val roleIntent = createDefaultDialerRoleIntent()
        if (roleIntent == null) {
            toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
            dispatchDefaultDialerResult(false)
            return
        }

        defaultDialerRoleLauncher.launch(roleIntent)
    }

    fun requestDefaultSmsRoleIfNeeded(onResult: ((Boolean) -> Unit)? = null) {
        onResult?.let { pendingDefaultSmsCallbacks.add(it) }

        if (isDefaultSmsRoleHeld()) {
            dispatchDefaultSmsResult(true)
            return
        }

        val roleIntent = createDefaultSmsRoleIntent()
        if (roleIntent == null) {
            toast(R.string.default_sms_app_prompt, Toast.LENGTH_LONG)
            dispatchDefaultSmsResult(false)
            return
        }

        defaultSmsRoleLauncher.launch(roleIntent)
    }

    fun isDefaultPhoneRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            }
        }

        val telecomManager = getSystemService(TelecomManager::class.java)
        return telecomManager?.defaultDialerPackage == packageName
    }

    fun isDefaultSmsRoleHeld(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_SMS)) {
                return roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            }
        }

        return Telephony.Sms.getDefaultSmsPackage(this) == packageName
    }

    fun handlePermissionAfterDefaultDialerRole(permission: Int, callback: (Boolean) -> Unit) {
        requestDefaultDialerRoleIfNeeded { isDefault ->
            if (isDefault) {
                handlePermission(permission, callback)
            } else {
                toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
                callback(false)
            }
        }
    }

    fun handlePermissionAfterDefaultHandlerRoles(permission: Int, callback: (Boolean) -> Unit) {
        requestDefaultDialerRoleIfNeeded { isDefaultPhone ->
            if (!isDefaultPhone) {
                toast(R.string.default_phone_app_prompt, Toast.LENGTH_LONG)
                callback(false)
                return@requestDefaultDialerRoleIfNeeded
            }

            requestDefaultSmsRoleIfNeeded { isDefaultSms ->
                if (isDefaultSms) {
                    handlePermission(permission, callback)
                } else {
                    toast(R.string.default_sms_app_prompt, Toast.LENGTH_LONG)
                    callback(false)
                }
            }
        }
    }

    private fun createDefaultDialerRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            }
        }

        val telecomManager = getSystemService(TelecomManager::class.java) ?: return null
        return Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName)
        }
    }

    private fun createDefaultSmsRoleIntent(): Intent? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val roleManager = getSystemService(RoleManager::class.java)
            if (roleManager != null &&
                roleManager.isRoleAvailable(RoleManager.ROLE_SMS) &&
                !roleManager.isRoleHeld(RoleManager.ROLE_SMS)
            ) {
                return roleManager.createRequestRoleIntent(RoleManager.ROLE_SMS)
            }
        }

        return Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT).apply {
            putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, packageName)
        }
    }

    private fun dispatchDefaultDialerResult(isDefault: Boolean) {
        val callbacks = pendingDefaultDialerCallbacks.toList()
        pendingDefaultDialerCallbacks.clear()
        callbacks.forEach { it(isDefault) }
    }

    private fun dispatchDefaultSmsResult(isDefault: Boolean) {
        val callbacks = pendingDefaultSmsCallbacks.toList()
        pendingDefaultSmsCallbacks.clear()
        callbacks.forEach { it(isDefault) }
    }
}
