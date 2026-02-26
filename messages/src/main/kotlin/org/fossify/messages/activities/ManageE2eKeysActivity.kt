package org.fossify.messages.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.Manifest
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.provider.ContactsContract
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.widget.ImageView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.io.ByteArrayOutputStream
import org.json.JSONObject
import org.fossify.commons.dialogs.ConfirmationDialog
import org.fossify.commons.dialogs.RadioGroupDialog
import org.fossify.commons.extensions.copyToClipboard
import org.fossify.commons.extensions.hasPermission
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.PERMISSION_READ_CONTACTS
import org.fossify.commons.helpers.PERMISSION_WRITE_CONTACTS
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.commons.models.RadioItem
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityManageE2eKeysBinding
import org.fossify.messages.helpers.CyberIdentityPayload
import org.fossify.messages.helpers.CyberIdentityQr
import org.fossify.messages.helpers.E2eManager
import org.fossify.messages.helpers.JSON_MIME_TYPE
import org.fossify.messages.helpers.MeshDiscoveryManager
import org.fossify.messages.helpers.TXT_MIME_TYPE
import org.fossify.messages.helpers.WalletContactHelper
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshIdentityStore
import org.fossify.mesh.MeshManager
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.rns.RnsNode
import org.fossify.mesh.MeshMode
import org.fossify.mesh.call.MeshCallQuality
import org.fossify.mesh.ble.MeshBleState
import org.fossify.mesh.wifidirect.MeshWifiDirectState
import org.fossify.mesh.wifiaware.MeshWifiAwareState

class ManageE2eKeysActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityManageE2eKeysBinding::inflate)
    private var pendingIdentity: CyberIdentityPayload? = null

    private companion object {
        const val MAX_E2E_BACKUP_IMPORT_BYTES = 256 * 1024
        const val PREFS_NAME = "Prefs"
        const val WALLET_LIQUIDITY_PROVIDER_MODE = "wallet_liquidity_provider_mode"
        const val WALLET_LIQUIDITY_PROVIDER_ID = "wallet_liquidity_provider_id"
        const val WALLET_DIRECTORY_JSON = "wallet_directory_json"
        const val WALLET_LIQUIDITY_CUSTOM_NAME = "wallet_liquidity_custom_name"
        const val WALLET_LIQUIDITY_CUSTOM_ADDRESS = "wallet_liquidity_custom_address"
        const val CUSTOM_PROVIDER_ID = "custom-provider"
        const val MODE_MANUAL = "manual"
    }

    private val createDocument =
        registerForActivityResult(ActivityResultContracts.CreateDocument(TXT_MIME_TYPE)) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            try {
                val data = E2eManager.buildBackupData(this)
                contentResolver.openOutputStream(uri)?.use { stream ->
                    stream.write(data.toByteArray(Charsets.UTF_8))
                }
                toast(R.string.e2e_backup_successful)
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private val openDocument =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri == null) {
                return@registerForActivityResult
            }
            try {
                val content = readTextFromUriLimited(uri, MAX_E2E_BACKUP_IMPORT_BYTES).orEmpty()
                if (E2eManager.importBackupData(this, content)) {
                    refreshProfile()
                    toast(R.string.e2e_import_successful)
                } else {
                    toast(R.string.e2e_import_failed)
                }
            } catch (e: Exception) {
                showErrorToast(e)
            }
        }

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                scanQrCode()
            } else {
                toast(R.string.profile_mesh_scan_denied)
            }
        }

    private val requestWifiDirectPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val meshConfig = MeshConfig.newInstance(this)
                meshConfig.meshWifiDirectEnabled = true
                binding.profileMeshWifidirect.isChecked = true
                MeshManager.sync(this)
            } else {
                binding.profileMeshWifidirect.isChecked = false
                toast(R.string.profile_mesh_scan_denied)
            }
        }

    private val requestBlePermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
            val granted = results.values.all { it }
            if (granted) {
                val meshConfig = MeshConfig.newInstance(this)
                meshConfig.meshBleEnabled = true
                binding.profileMeshBle.isChecked = true
                MeshManager.sync(this)
            } else {
                binding.profileMeshBle.isChecked = false
                toast(R.string.profile_mesh_scan_denied)
            }
        }

    private val requestWifiAwarePermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                val meshConfig = MeshConfig.newInstance(this)
                meshConfig.meshWifiAwareEnabled = true
                binding.profileMeshWifiAware.isChecked = true
                MeshManager.sync(this)
            } else {
                binding.profileMeshWifiAware.isChecked = false
                toast(R.string.profile_mesh_scan_denied)
            }
        }

    private val scanMeshQr =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val raw = result.data
                ?.getStringExtra(MeshQrScanActivity.EXTRA_QR_PAYLOAD)
                ?.trim()
                .orEmpty()
                .ifBlank {
                    result.data
                        ?.getStringExtra(MeshQrScanActivity.EXTRA_MESH_ADDRESS)
                        ?.trim()
                        .orEmpty()
                }
            if (raw.isBlank()) return@registerForActivityResult
            val parsed = CyberIdentityQr.parse(raw)
            if (parsed == null) {
                toast(R.string.profile_mesh_invalid)
                return@registerForActivityResult
            }
            pendingIdentity = parsed
            showIdentitySaveDialog()
        }

    private val pickContact =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != Activity.RESULT_OK) return@registerForActivityResult
            val contactUri = result.data?.data ?: return@registerForActivityResult
            val contactId = contentResolver.query(
                contactUri,
                arrayOf(ContactsContract.Contacts._ID),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else null
            } ?: return@registerForActivityResult

            val rawId = resolveRawContactId(contactId) ?: return@registerForActivityResult
            val identity = pendingIdentity ?: return@registerForActivityResult
            if (!hasPermission(PERMISSION_WRITE_CONTACTS)) {
                handlePermission(PERMISSION_WRITE_CONTACTS) { granted ->
                    if (granted) {
                        saveIdentityToRawContact(rawId, identity)
                        toast(R.string.profile_mesh_saved)
                        pendingIdentity = null
                    }
                }
                return@registerForActivityResult
            }
            saveIdentityToRawContact(rawId, identity)
            toast(R.string.profile_mesh_saved)
            pendingIdentity = null
        }

    private val createContact =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                toast(R.string.profile_mesh_saved)
            }
            pendingIdentity = null
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)
        setupTopAppBar(binding.manageE2eKeysAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.manageE2eKeysHolder)
        refreshProfile()
        setupActions()
        setupMeshSettings()
        handleIncomingIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        refreshProfile()
        updateLiquidityProviderSummary()
        updateMeshStatus(MeshConfig.newInstance(this))
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun refreshProfile() {
        val detectedPhone = getProfilePhoneNumber()
        binding.profileContactValue.text = getProfileDisplayName(detectedPhone) ?: getString(R.string.profile_unknown)
        binding.profilePhoneValue.text = detectedPhone ?: getString(R.string.profile_unknown)
        binding.profileMeshAddressValue.text = MeshDiscoveryManager.getLocalMeshAddress(this)
            ?: getString(R.string.profile_unknown)
        binding.manageE2eKeysPublicValue.text = E2eManager.getPublicKeyBase64(this)
        binding.manageE2eKeysPrivateValue.text = E2eManager.getPrivateKeyBase64(this)
    }

    private fun setupActions() {
        binding.manageE2eKeysPublicValue.setOnClickListener {
            copyToClipboard(E2eManager.getPublicKeyBase64(this))
        }
        binding.manageE2eKeysPrivateValue.setOnClickListener {
            copyToClipboard(E2eManager.getPrivateKeyBase64(this))
        }
        binding.profileMeshAddressValue.setOnClickListener {
            val address = MeshDiscoveryManager.getLocalMeshAddress(this) ?: return@setOnClickListener
            copyToClipboard(address)
        }
        binding.profileMeshAnnounceHolder.setOnClickListener {
            RnsNode.announceAll()
            toast(R.string.profile_mesh_announced)
        }
        binding.profileShowQrHolder.setOnClickListener {
            showQrCode()
        }
        binding.profileScanQrHolder.setOnClickListener {
            scanQrCode()
        }
        binding.profileRotateMeshHolder.setOnClickListener {
            ConfirmationDialog(this, getString(R.string.profile_rotate_mesh)) {
                MeshIdentityStore.rotateIdentity(this)
                MeshManager.restart(this)
                refreshProfile()
                toast(R.string.profile_mesh_rotated)
            }
        }
        binding.manageE2eKeysBackupHolder.setOnClickListener {
            createDocument.launch("cyber_phone_e2e_keys.txt")
        }
        binding.manageE2eKeysImportHolder.setOnClickListener {
            openDocument.launch(arrayOf(TXT_MIME_TYPE, JSON_MIME_TYPE, "text/plain"))
        }
        binding.manageE2eKeysRegenerateHolder.setOnClickListener {
            ConfirmationDialog(this, getString(R.string.e2e_regenerate_confirmation)) {
                E2eManager.regenerateKeyPair(this)
                refreshProfile()
                toast(R.string.e2e_keys_regenerated)
            }
        }
    }

    private fun readTextFromUriLimited(uri: android.net.Uri, maxBytes: Int): String? {
        return runCatching {
            contentResolver.openInputStream(uri)?.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8 * 1024)
                var total = 0
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > maxBytes) {
                        return null
                    }
                    output.write(buffer, 0, read)
                }
                output.toString(Charsets.UTF_8.name())
            }
        }.getOrNull()
    }

    private fun setupMeshSettings() = binding.apply {
        val meshConfig = MeshConfig.newInstance(this@ManageE2eKeysActivity)
        profileMeshModeValue.text = getMeshModeLabel(meshConfig.getMeshMode())
        profileMeshModeHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(MeshMode.STANDARD_ONLY.id, getString(R.string.mesh_mode_standard)),
                RadioItem(MeshMode.MESH_WITH_FALLBACK.id, getString(R.string.mesh_mode_fallback)),
                RadioItem(MeshMode.MESH_ONLY.id, getString(R.string.mesh_mode_mesh_only))
            )
            RadioGroupDialog(this@ManageE2eKeysActivity, items, meshConfig.meshMode) {
                val previousMode = meshConfig.getMeshMode()
                meshConfig.meshMode = it as Int
                val updatedMode = meshConfig.getMeshMode()
                if (previousMode == MeshMode.STANDARD_ONLY && updatedMode != MeshMode.STANDARD_ONLY) {
                    meshConfig.meshRoutingEnabled = true
                    meshConfig.meshWifiDirectEnabled = true
                    profileMeshRouting.isChecked = true
                    profileMeshWifidirect.isChecked = true
                }
                profileMeshModeValue.text = getMeshModeLabel(meshConfig.getMeshMode())
                updateMeshRoutingUi(meshConfig)
                updateMeshStatus(meshConfig)
                MeshManager.sync(this@ManageE2eKeysActivity)
            }
        }

        profileMeshLiquidityProviderValue.text = getLiquidityProviderSummary()
        profileMeshLiquidityProviderHolder.setOnClickListener {
            val intent = Intent().setClassName(
                packageName,
                "org.fossify.phone.activities.WalletLiquidityProviderSettingsActivity"
            )
            runCatching {
                startActivity(intent)
            }.onFailure {
                toast(R.string.profile_mesh_liquidity_settings_unavailable)
            }
        }

        profileMeshCallQualityValue.text = getMeshCallQualityLabel(meshConfig.meshCallQuality)
        profileMeshCallQualityHolder.setOnClickListener {
            val items = arrayListOf(
                RadioItem(MeshCallQuality.LOW.id, getString(R.string.mesh_call_quality_low)),
                RadioItem(MeshCallQuality.HIGH.id, getString(R.string.mesh_call_quality_high))
            )
            RadioGroupDialog(this@ManageE2eKeysActivity, items, meshConfig.meshCallQuality) {
                meshConfig.meshCallQuality = it as Int
                profileMeshCallQualityValue.text = getMeshCallQualityLabel(meshConfig.meshCallQuality)
            }
        }

        profileMeshRouting.isChecked = meshConfig.meshRoutingEnabled
        profileMeshRoutingHolder.setOnClickListener {
            profileMeshRouting.toggle()
            val routingEnabled = profileMeshRouting.isChecked
            meshConfig.meshRoutingEnabled = routingEnabled
            if (routingEnabled && meshConfig.getMeshMode() == MeshMode.STANDARD_ONLY) {
                meshConfig.meshMode = MeshMode.MESH_WITH_FALLBACK.id
                profileMeshModeValue.text = getMeshModeLabel(meshConfig.getMeshMode())
            }
            updateMeshRoutingUi(meshConfig)
            updateMeshStatus(meshConfig)
            MeshManager.sync(this@ManageE2eKeysActivity)
        }

        profileMeshWifidirect.isChecked = meshConfig.meshWifiDirectEnabled
        profileMeshWifidirectHolder.setOnClickListener {
            profileMeshWifidirect.toggle()
            val enabled = profileMeshWifidirect.isChecked
            if (enabled) {
                val permission = getWifiDirectPermission()
                if (ContextCompat.checkSelfPermission(this@ManageE2eKeysActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                    requestWifiDirectPermission.launch(permission)
                    return@setOnClickListener
                }
            }
            meshConfig.meshWifiDirectEnabled = enabled
            MeshManager.sync(this@ManageE2eKeysActivity)
        }

        profileMeshBle.isChecked = meshConfig.meshBleEnabled
        profileMeshBleHolder.setOnClickListener {
            profileMeshBle.toggle()
            val enabled = profileMeshBle.isChecked
            if (enabled) {
                val permissions = getBlePermissions()
                val missing = permissions.any {
                    ContextCompat.checkSelfPermission(this@ManageE2eKeysActivity, it) != PackageManager.PERMISSION_GRANTED
                }
                if (missing) {
                    requestBlePermissions.launch(permissions.toTypedArray())
                    return@setOnClickListener
                }
            }
            meshConfig.meshBleEnabled = enabled
            MeshManager.sync(this@ManageE2eKeysActivity)
        }

        val hasWifiAware = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        profileMeshWifiAwareHolder.visibility = if (hasWifiAware) android.view.View.VISIBLE else android.view.View.GONE
        if (hasWifiAware) {
            profileMeshWifiAware.isChecked = meshConfig.meshWifiAwareEnabled
            profileMeshWifiAwareHolder.setOnClickListener {
                profileMeshWifiAware.toggle()
                val enabled = profileMeshWifiAware.isChecked
                if (enabled) {
                    val permission = getWifiAwarePermission()
                    if (ContextCompat.checkSelfPermission(this@ManageE2eKeysActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                        requestWifiAwarePermission.launch(permission)
                        return@setOnClickListener
                    }
                }
                meshConfig.meshWifiAwareEnabled = enabled
                MeshManager.sync(this@ManageE2eKeysActivity)
            }
        }

        updateMeshRoutingUi(meshConfig)
        updateMeshStatus(meshConfig)
    }

    private fun updateMeshRoutingUi(meshConfig: MeshConfig) = binding.apply {
        val isMeshEnabled = meshConfig.getMeshMode() != MeshMode.STANDARD_ONLY
        profileMeshRouting.isEnabled = isMeshEnabled
        profileMeshRoutingHolder.isEnabled = isMeshEnabled
        profileMeshCallQualityHolder.isEnabled = isMeshEnabled
        if (!isMeshEnabled && meshConfig.meshRoutingEnabled) {
            meshConfig.meshRoutingEnabled = false
            profileMeshRouting.isChecked = false
        }
    }

    private fun updateMeshStatus(meshConfig: MeshConfig) = binding.apply {
        // If the user enabled mesh, but the runtime isn't running (e.g. service start denied),
        // attempt to kick it while we are in the foreground.
        if (meshConfig.getMeshMode() != MeshMode.STANDARD_ONLY && !RnsNode.isRunning()) {
            MeshManager.sync(this@ManageE2eKeysActivity)
        }

        val running = if (RnsNode.isRunning()) getString(R.string.profile_running) else getString(R.string.profile_stopped)
        val ifaceNames = RnsNode.getInterfaceNames().joinToString().ifBlank { getString(R.string.profile_unknown) }
        val neighbors = RnsNode.getDirectNeighborCount()
        val routingStatus = if (meshConfig.meshRoutingEnabled && RnsNode.hasRecentRoutingActivity()) {
            getString(R.string.mesh_routing_in_use)
        } else {
            getString(R.string.mesh_routing_idle)
        }
        val rawCount = RnsNode.getRawPacketReceivedCount()
        val rawSent = RnsNode.getRawPacketSentCount()
        val announceCount = RnsNode.getAnnounceReceivedCount()
        val lastPacketMs = RnsNode.getLastPacketReceivedMs()
        val lastSentMs = RnsNode.getLastPacketSentMs()
        val lastPacketText = if (lastPacketMs > 0L) {
            android.text.format.DateUtils.getRelativeTimeSpanString(lastPacketMs).toString()
        } else {
            getString(R.string.profile_unknown)
        }
        val lastSentText = if (lastSentMs > 0L) {
            android.text.format.DateUtils.getRelativeTimeSpanString(lastSentMs).toString()
        } else {
            getString(R.string.profile_unknown)
        }
        val status = getString(R.string.mesh_service_status, neighbors, routingStatus)
        val diagnostics = getString(R.string.mesh_diagnostics, rawCount, announceCount, lastPacketText) +
            "\nTX: $rawSent • Last TX: $lastSentText"
        val wifiStatus = buildWifiDirectStatus()
        val awareStatus = buildWifiAwareStatus()
        val bleStatus = buildBleStatus()
        profileMeshStatusValue.text = "$status\n$diagnostics\nRuntime: $running\nInterfaces: $ifaceNames\n${buildUdpStatus()}\n$bleStatus\n$wifiStatus\n$awareStatus"
    }

    private fun buildBleStatus(): String {
        val enabled = if (MeshBleState.isBluetoothEnabled()) "On" else "Off"
        val active = if (MeshBleState.isActive()) "Active" else "Inactive"
        val connections = MeshBleState.getConnections()
        val rx = MeshBleState.getLastRxMs()
        val tx = MeshBleState.getLastTxMs()
        val rxText = if (rx > 0L) android.text.format.DateUtils.getRelativeTimeSpanString(rx).toString() else getString(R.string.profile_unknown)
        val txText = if (tx > 0L) android.text.format.DateUtils.getRelativeTimeSpanString(tx).toString() else getString(R.string.profile_unknown)
        return "BLE: $enabled • $active • Connections: $connections • RX: $rxText • TX: $txText"
    }

    private fun buildUdpStatus(): String {
        return try {
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager
            val dhcp = wifi.dhcpInfo
            if (dhcp == null || dhcp.ipAddress == 0) {
                "UDP: " + getString(R.string.profile_unknown)
            } else {
                val ipAddr = java.net.InetAddress.getByAddress(
                    java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(dhcp.ipAddress).array()
                )
                val ip = ipAddr.hostAddress

                val iface = try {
                    java.net.NetworkInterface.getByInetAddress(ipAddr)
                } catch (_: Exception) {
                    null
                }

                val ifaceAddr = iface?.interfaceAddresses?.firstOrNull { it.address is java.net.Inet4Address && it.address == ipAddr }
                val broadcastFromIface = ifaceAddr?.broadcast?.hostAddress
                val broadcast = when {
                    dhcp.netmask != 0 -> {
                        val maskAddr = java.net.InetAddress.getByAddress(
                            java.nio.ByteBuffer.allocate(4).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(dhcp.netmask).array()
                        )
                        val ipInt = java.nio.ByteBuffer.wrap(ipAddr.address).int
                        val maskInt = java.nio.ByteBuffer.wrap(maskAddr.address).int
                        val broadcastInt = ipInt or maskInt.inv()
                        java.net.InetAddress.getByAddress(java.nio.ByteBuffer.allocate(4).putInt(broadcastInt).array()).hostAddress
                    }
                    !broadcastFromIface.isNullOrBlank() -> broadcastFromIface
                    else -> "255.255.255.255"
                }

                val peers = RnsNode.getUdpPeerCount()
                "UDP: $ip -> $broadcast:4242 • Peers: $peers"
            }
        } catch (_: Exception) {
            "UDP: " + getString(R.string.profile_unknown)
        }
    }

    private fun getMeshModeLabel(mode: MeshMode): String {
        return when (mode) {
            MeshMode.STANDARD_ONLY -> getString(R.string.mesh_mode_standard)
            MeshMode.MESH_WITH_FALLBACK -> getString(R.string.mesh_mode_fallback)
            MeshMode.MESH_ONLY -> getString(R.string.mesh_mode_mesh_only)
        }
    }

    private fun getMeshCallQualityLabel(value: Int): String {
        return when (MeshCallQuality.fromId(value)) {
            MeshCallQuality.LOW -> getString(R.string.mesh_call_quality_low)
            MeshCallQuality.HIGH -> getString(R.string.mesh_call_quality_high)
        }
    }

    private fun getWifiDirectPermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private fun getWifiAwarePermission(): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.NEARBY_WIFI_DEVICES
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
    }

    private fun getBlePermissions(): List<String> {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
    }

    private fun buildWifiDirectStatus(): String {
        val ssid = MeshWifiDirectState.getSsid()
        val passphrase = MeshWifiDirectState.getPassphrase()
        val isOwner = MeshWifiDirectState.isGroupOwner()
        val status = if (!ssid.isNullOrBlank()) {
            val role = if (isOwner) "Owner" else "Client"
            if (!passphrase.isNullOrBlank()) {
                "$ssid ($role) • $passphrase"
            } else {
                "$ssid ($role)"
            }
        } else {
            getString(R.string.mesh_wifidirect_inactive)
        }
        return getString(R.string.mesh_wifidirect_status, status)
    }

    private fun buildWifiAwareStatus(): String {
        val supported = packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_AWARE)
        if (!supported) {
            return getString(R.string.mesh_wifiaware_status, getString(R.string.mesh_wifiaware_unsupported))
        }
        val status = if (MeshWifiAwareState.isActive()) {
            getString(R.string.mesh_wifiaware_active)
        } else {
            getString(R.string.mesh_wifiaware_inactive)
        }
        val peers = MeshWifiAwareState.getPeers()
        val rx = MeshWifiAwareState.getLastRxMs()
        val tx = MeshWifiAwareState.getLastTxMs()
        val rxText = if (rx > 0L) android.text.format.DateUtils.getRelativeTimeSpanString(rx).toString() else getString(R.string.profile_unknown)
        val txText = if (tx > 0L) android.text.format.DateUtils.getRelativeTimeSpanString(tx).toString() else getString(R.string.profile_unknown)
        return getString(R.string.mesh_wifiaware_status, "$status • Peers: $peers • RX: $rxText • TX: $txText")
    }

    private fun showQrCode() {
        val meshUri = MeshDiscoveryManager.getLocalMeshAddress(this) ?: return
        val content = CyberIdentityQr.buildVCard(
            displayName = getProfileDisplayName(),
            phoneNumber = getProfilePhoneNumber(),
            meshUri = meshUri,
            e2ePublicKeyBase64 = E2eManager.getPublicKeyBase64(this),
            walletOnchainAddress = getLocalWalletOnchainAddress(),
            walletLightningDestination = getLocalWalletLightningDestination(),
        )
        val size = resources.getDimensionPixelSize(R.dimen.profile_qr_size)
        val bitmap = createQrBitmap(content, size) ?: return
        val pad = (16 * resources.displayMetrics.density).toInt()
        val imageView = ImageView(this).apply {
            setImageBitmap(bitmap)
            adjustViewBounds = true
        }
        val textView = android.widget.TextView(this).apply {
            text = content
            setTextIsSelectable(true)
            setPadding(0, pad, 0, 0)
        }
        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(
                pad,
                pad,
                pad,
                pad
            )
            addView(imageView)
            addView(textView)
        }
        val dialog = android.app.AlertDialog.Builder(this)
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .create()
        dialog.show()
    }

    private fun scanQrCode() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
            return
        }
        scanMeshQr.launch(Intent(this, MeshQrScanActivity::class.java))
    }

    private fun showIdentitySaveDialog() {
        val identity = pendingIdentity ?: return
        val message = buildString {
            identity.meshAddress?.let { appendLine("Mesh: $it") }
            identity.e2ePublicKeyBase64?.let { appendLine("E2E: ${it.take(16)}...") }
            identity.walletOnchainAddress?.let { appendLine("BTC: $it") }
            identity.walletLightningDestination?.let { appendLine("LN: ${it.take(16)}...") }
        }.trim().ifBlank { identity.raw }
        android.app.AlertDialog.Builder(this)
            .setTitle(R.string.profile_mesh_save_title)
            .setMessage(message)
            .setPositiveButton(R.string.profile_mesh_select_contact) { _, _ ->
                requestContactForIdentity()
            }
            .setNeutralButton(R.string.profile_mesh_add_contact) { _, _ ->
                createNewContactWithIdentity(identity)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ ->
                pendingIdentity = null
            }
            .show()
    }

    private fun createNewContactWithIdentity(identity: CyberIdentityPayload) {
        Intent().apply {
            action = Intent.ACTION_INSERT
            data = ContactsContract.Contacts.CONTENT_URI
            identity.meshAddress?.let { MeshContactHelper.addMeshPhoneInsertExtras(this, it) }
            identity.e2ePublicKeyBase64?.let { E2eManager.addE2ePublicKeyInsertExtras(this, it) }
            WalletContactHelper.addWalletInsertExtras(
                intent = this,
                onchainDestination = identity.walletOnchainAddress?.trim().orEmpty().ifBlank { "bitcoin:" },
                lightningDestination = identity.walletLightningDestination?.trim().orEmpty().ifBlank { "lightning:" }
            )
            createContact.launch(this)
        }
    }

    private fun requestContactForIdentity() {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) {
            handlePermission(PERMISSION_READ_CONTACTS) { granted ->
                if (granted) {
                    launchContactPicker()
                }
            }
            return
        }
        launchContactPicker()
    }

    private fun launchContactPicker() {
        val intent = Intent(Intent.ACTION_PICK, ContactsContract.Contacts.CONTENT_URI)
        pickContact.launch(intent)
    }

    private fun resolveRawContactId(contactId: Long): Long? {
        return contentResolver.query(
            ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(ContactsContract.RawContacts._ID),
            "${ContactsContract.RawContacts.CONTACT_ID}=? AND ${ContactsContract.RawContacts.DELETED}=0",
            arrayOf(contactId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        }
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        val textExtra = intent.getStringExtra(Intent.EXTRA_TEXT)
        val dataString = intent.dataString
        val payload = listOfNotNull(textExtra, dataString).firstOrNull().orEmpty()
        if (payload.isBlank()) return
        val parsed = CyberIdentityQr.parse(payload) ?: return
        pendingIdentity = parsed
        showIdentitySaveDialog()
    }

    private fun saveIdentityToRawContact(rawId: Long, identity: CyberIdentityPayload) {
        identity.meshAddress?.let { MeshContactHelper.upsertMeshAddressForRawContact(this, rawId, it) }
        identity.e2ePublicKeyBase64?.let { E2eManager.storeContactPublicKeyForRawContact(this, rawId, it) }
        identity.walletOnchainAddress?.let { WalletContactHelper.upsertWalletOnchainDestination(this, rawId, it) }
        identity.walletLightningDestination?.let { WalletContactHelper.upsertWalletLightningDestination(this, rawId, it) }
    }

    private fun updateLiquidityProviderSummary() {
        binding.profileMeshLiquidityProviderValue.text = getLiquidityProviderSummary()
    }

    private fun getLiquidityProviderSummary(): String {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val mode = prefs.getString(WALLET_LIQUIDITY_PROVIDER_MODE, "auto")
            ?.trim()
            ?.lowercase()
            .orEmpty()
        if (mode != MODE_MANUAL) {
            return getString(R.string.profile_mesh_liquidity_auto)
        }

        val providerId = prefs.getString(WALLET_LIQUIDITY_PROVIDER_ID, "")
            ?.trim()
            .orEmpty()
        if (providerId.isBlank()) {
            return getString(R.string.profile_mesh_liquidity_manual_unset)
        }

        if (providerId == CUSTOM_PROVIDER_ID) {
            val customName = prefs.getString(WALLET_LIQUIDITY_CUSTOM_NAME, "")?.trim().orEmpty()
            val customAddress = prefs.getString(WALLET_LIQUIDITY_CUSTOM_ADDRESS, "")?.trim().orEmpty()
            val display = when {
                customName.isNotBlank() -> customName
                customAddress.isNotBlank() -> customAddress
                else -> getString(R.string.profile_mesh_liquidity_custom)
            }
            return getString(R.string.profile_mesh_liquidity_manual_value, display)
        }

        val providerName = resolveLiquidityProviderName(prefs.getString(WALLET_DIRECTORY_JSON, "").orEmpty(), providerId)
        return getString(
            R.string.profile_mesh_liquidity_manual_value,
            providerName ?: providerId
        )
    }

    private fun resolveLiquidityProviderName(directoryJson: String, providerId: String): String? {
        val text = directoryJson.trim()
        if (text.isBlank()) return null

        // Preferred schema.
        runCatching {
            val root = JSONObject(text)
            val providers = root.optJSONArray("liquidity_providers")
            if (providers != null) {
                for (i in 0 until providers.length()) {
                    val obj = providers.optJSONObject(i) ?: continue
                    val id = obj.optString("id").orEmpty().trim()
                    if (id == providerId) {
                        return obj.optString("name").orEmpty().trim().ifBlank { null }
                    }
                }
            }

            // Derived provider ids are "federation:<id>".
            if (providerId.startsWith("federation:", ignoreCase = true)) {
                val federationId = providerId.substringAfter("federation:", "").trim()
                val federations = root.optJSONArray("federations")
                if (federations != null) {
                    for (i in 0 until federations.length()) {
                        val obj = federations.optJSONObject(i) ?: continue
                        val id = obj.optString("id").orEmpty().trim()
                        if (id == federationId) {
                            return obj.optString("name").orEmpty().trim().ifBlank { null }
                        }
                    }
                }
            }
        }

        // Legacy/simple schema: raw array of federation entries.
        runCatching {
            if (providerId.startsWith("federation:", ignoreCase = true)) {
                val federationId = providerId.substringAfter("federation:", "").trim()
                val array = org.json.JSONArray(text)
                for (i in 0 until array.length()) {
                    val obj = array.optJSONObject(i) ?: continue
                    val id = obj.optString("id").orEmpty().trim()
                    if (id == federationId) {
                        return obj.optString("name").orEmpty().trim().ifBlank { null }
                    }
                }
            }
        }

        return null
    }

    private fun getLocalWalletOnchainAddress(): String? {
        // Stored by the Wallet feature in the shared BaseConfig prefs ("Prefs").
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString("wallet_selected_federation_id", "")?.trim().orEmpty()
        val scoped = if (selectedId.isNotBlank()) {
            prefs.getString("wallet_last_onchain_address_$selectedId", "")?.trim().orEmpty()
        } else {
            ""
        }
        if (scoped.isNotBlank()) return scoped
        return prefs.getString("wallet_last_onchain_address", "")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun getLocalWalletLastInvoice(): String? {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val selectedId = prefs.getString("wallet_selected_federation_id", "")?.trim().orEmpty()
        val scoped = if (selectedId.isNotBlank()) {
            prefs.getString("wallet_last_invoice_$selectedId", "")?.trim().orEmpty()
        } else {
            ""
        }
        if (scoped.isNotBlank()) return scoped
        return prefs.getString("wallet_last_invoice", "")?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun getLocalWalletLightningDestination(): String? {
        val fromProfile = getProfileCustomWalletDestination("Lightning")
        if (!fromProfile.isNullOrBlank()) return fromProfile
        return getLocalWalletLastInvoice()
    }

    private fun getProfileCustomWalletDestination(label: String): String? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        val profileId = contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        } ?: return null

        return contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=? AND " +
                "${ContactsContract.CommonDataKinds.Phone.TYPE}=? AND " +
                "${ContactsContract.CommonDataKinds.Phone.LABEL}=?",
            arrayOf(
                profileId.toString(),
                ContactsContract.CommonDataKinds.Phone.TYPE_CUSTOM.toString(),
                label
            ),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun getProfileDisplayName(phoneHint: String? = null): String? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        val profileName = contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile.DISPLAY_NAME_PRIMARY),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
        if (!profileName.isNullOrBlank()) {
            return profileName
        }

        val number = phoneHint?.trim().orEmpty().ifBlank { getProfilePhoneNumber().orEmpty() }
        if (number.isBlank()) return null

        val lookupUri = Uri.withAppendedPath(
            ContactsContract.PhoneLookup.CONTENT_FILTER_URI,
            Uri.encode(number)
        )
        return contentResolver.query(
            lookupUri,
            arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    private fun getProfilePhoneNumber(): String? {
        val profilePhone = getProfilePhoneNumberFromContact()
        if (!profilePhone.isNullOrBlank()) {
            return profilePhone
        }
        return getProfilePhoneNumberFromSubscriptions()
    }

    private fun getProfilePhoneNumberFromContact(): String? {
        if (!hasPermission(PERMISSION_READ_CONTACTS)) return null
        val profileId = contentResolver.query(
            ContactsContract.Profile.CONTENT_URI,
            arrayOf(ContactsContract.Profile._ID),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else null
        } ?: return null

        return contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID}=?",
            arrayOf(profileId.toString()),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }?.trim()?.takeIf { it.isNotBlank() }
    }

    @SuppressLint("MissingPermission")
    private fun getProfilePhoneNumberFromSubscriptions(): String? {
        val canReadLineNumber =
            hasAndroidPermission(Manifest.permission.READ_PHONE_STATE) ||
                hasAndroidPermission(Manifest.permission.READ_PHONE_NUMBERS) ||
                hasAndroidPermission(Manifest.permission.READ_SMS)
        if (!canReadLineNumber) return null

        val subscriptionManager = getSystemService(SubscriptionManager::class.java)
        if (subscriptionManager != null) {
            val subs = runCatching { subscriptionManager.activeSubscriptionInfoList }.getOrNull().orEmpty()
            for (sub in subs) {
                val number = runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        subscriptionManager.getPhoneNumber(sub.subscriptionId)
                    } else {
                        sub.number
                    }
                }.getOrNull()?.trim().orEmpty()
                if (number.isNotBlank()) {
                    return number
                }
            }
        }

        val telephonyManager = getSystemService(TelephonyManager::class.java)
        val line1 = runCatching { telephonyManager?.line1Number }.getOrNull()?.trim().orEmpty()
        return line1.takeIf { it.isNotBlank() }
    }

    private fun hasAndroidPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    private fun createQrBitmap(content: String, size: Int): Bitmap? {
        return try {
            val matrix: BitMatrix = MultiFormatWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (matrix[x, y]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
                }
            }
            Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        } catch (_: Exception) {
            null
        }
    }
}
