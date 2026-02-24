package org.fossify.phone.mesh.voip

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.app.KeyguardManager
import android.media.AudioManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.ImageView
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.os.postDelayed
import org.fossify.commons.extensions.adjustAlpha
import org.fossify.commons.extensions.applyColorFilter
import org.fossify.commons.extensions.beGone
import org.fossify.commons.extensions.beVisible
import org.fossify.commons.extensions.getFormattedDuration
import org.fossify.commons.extensions.getContrastColor
import org.fossify.commons.extensions.getProperBackgroundColor
import org.fossify.commons.extensions.getProperPrimaryColor
import org.fossify.commons.extensions.getProperTextColor
import org.fossify.commons.extensions.isRTLLayout
import org.fossify.commons.extensions.onGlobalLayout
import org.fossify.commons.extensions.performHapticFeedback
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.MINUTE_SECONDS
import org.fossify.mesh.MeshConfig
import org.fossify.mesh.MeshManager
import org.fossify.mesh.MeshMode
import org.fossify.mesh.MeshContactHelper
import org.fossify.mesh.call.MeshCallQuality
import org.fossify.mesh.call.MeshCallRouter
import org.fossify.mesh.lxmf.LxmfAddress
import org.fossify.phone.R
import org.fossify.phone.activities.SimpleActivity
import org.fossify.phone.databinding.ActivityCallBinding
import org.fossify.phone.extensions.startCallIntent
import org.fossify.phone.mesh.MeshAudioEngine
import kotlin.math.max
import kotlin.math.min

/**
 * In-app VoIP mesh calling UI. This intentionally bypasses Telecom entirely.
 *
 * We reuse the same call UI layout as PSTN calls, but drive it with MeshCallRouter + MeshAudioEngine.
 */
class MeshVoipCallActivity : SimpleActivity(), MeshCallRouter.Listener {
    private val binding by viewBinding(ActivityCallBinding::inflate)

    private enum class MeshAudioRoute { EARPIECE, BLUETOOTH, SPEAKER }

    private var sessionId: ByteArray? = null
    private var remoteDeliveryHash: ByteArray? = null
    private var quality: MeshCallQuality = MeshCallQuality.LOW
    private var audioEngine: MeshAudioEngine? = null
    private var audioSeq = 0
    private var isCallActive = false
    private var isIncoming = false
    private var allowFallback = false
    private var fallbackNumber: String? = null
    private var displayName: String? = null
    private var meshAddress: String? = null
    private var ringtone: Ringtone? = null
    private var stopSwipeAnimation = false
    private var dragDownX = 0f
    private var isMicrophoneOff = false
    private var proximityWakeLock: PowerManager.WakeLock? = null
    private var lastRemotePacketMs: Long = 0L
    private var lastRemoteAudioSeq: Int = -1
    private var audioRoute: MeshAudioRoute = MeshAudioRoute.EARPIECE
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var outgoingSession: MeshCallRouter.MeshCallSession? = null
    private val inviteRetryHandler = Handler(Looper.getMainLooper())
    private var inviteRetryCount = 0
    private var micPermissionRequestInFlight = false
    private var pendingMicPermissionAction: (() -> Unit)? = null

    private val callDurationHandler = Handler(Looper.getMainLooper())
    private var callDurationSeconds = 0
    private val callWatchdogHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        addLockScreenFlags()
        setupUiSkeleton()

        isIncoming = intent.getBooleanExtra(EXTRA_INCOMING, false)
        allowFallback = intent.getBooleanExtra(EXTRA_ALLOW_FALLBACK, false)
        fallbackNumber = intent.getStringExtra(EXTRA_FALLBACK_NUMBER)
        displayName = intent.getStringExtra(EXTRA_DISPLAY_NAME)
        meshAddress = intent.getStringExtra(EXTRA_MESH_ADDRESS)?.let { LxmfAddress.normalize(it) }

        if (isIncoming) {
            sessionId = intent.getByteArrayExtra(EXTRA_SESSION_ID)
            remoteDeliveryHash = intent.getByteArrayExtra(EXTRA_REMOTE_DELIVERY_HASH)
            val qualityId = intent.getIntExtra(EXTRA_CALL_QUALITY, MeshCallQuality.LOW.id)
            quality = MeshCallQuality.fromId(qualityId)

            if (sessionId == null || remoteDeliveryHash == null) {
                toast(R.string.unknown_error_occurred)
                finish()
                return
            }
            val addr = LxmfAddress.encode(remoteDeliveryHash!!)
            if (meshAddress.isNullOrBlank()) {
                meshAddress = addr
            }
            renderCallerInfo(meshAddress!!)
            showIncomingUi()
            startRingtone()
        } else {
            if (meshAddress.isNullOrBlank()) {
                toast(R.string.unknown_error_occurred)
                finish()
                return
            }
            val hash = LxmfAddress.decode(meshAddress!!)
            if (hash == null) {
                toast(R.string.mesh_invalid_address)
                finish()
                return
            }
            remoteDeliveryHash = hash
            renderCallerInfo(meshAddress!!)
            startOutgoingCall()
        }
    }

    override fun onStart() {
        super.onStart()
        MeshCallRouter.addListener(this)
    }

    override fun onResume() {
        super.onResume()
        syncAudioButtonStateFromSystem()
    }

    override fun onStop() {
        MeshCallRouter.removeListener(this)
        super.onStop()
    }

    override fun onDestroy() {
        stopRingtone()
        stopAudio()
        disableProximitySensor()
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callWatchdogHandler.removeCallbacks(callWatchdogTask)
        inviteRetryHandler.removeCallbacks(inviteRetryTask)
        super.onDestroy()
    }

    private fun setupUiSkeleton() = binding.apply {
        updateTextColors(callHolder)

        // Mesh calls do not support SIM selection / conferencing / PSTN dialpad.
        callSimId.beGone()
        callSimImage.beGone()
        dialpadWrapper.beGone()
        callDialpad.beGone()
        callToggleHold.beGone()
        callAdd.beGone()
        callSwap.beGone()
        callMerge.beGone()
        callManage.beGone()
        controlsSingleCall.beGone()
        controlsTwoCalls.beGone()

        // Basic ongoing controls only.
        callToggleMicrophone.beVisible()
        callToggleSpeaker.beVisible()
        val bgColor = getProperBackgroundColor()
        val inactiveColor = getInactiveButtonColor()
        arrayOf(callToggleMicrophone, callToggleSpeaker).forEach {
            it.applyColorFilter(bgColor.getContrastColor())
            it.background.applyColorFilter(inactiveColor)
        }

        // Incoming swipe UI.
        handleSwipe()
        // Make the "accept/decline" icons tappable too (swipe is still the primary interaction).
        callAccept.apply {
            isClickable = true
            setOnClickListener { answer() }
        }
        callDecline.apply {
            isClickable = true
            setOnClickListener { hangup() }
        }

        callEnd.setOnClickListener {
            hangup()
        }
        callToggleMicrophone.setOnClickListener {
            toggleMute()
        }
        callToggleSpeaker.setOnClickListener {
            toggleSpeaker()
        }

        callToggleMicrophone.setOnLongClickListener {
            toast(callToggleMicrophone.contentDescription?.toString().orEmpty())
            true
        }
        callToggleSpeaker.setOnLongClickListener {
            toast(callToggleSpeaker.contentDescription?.toString().orEmpty())
            true
        }

        // Make the ring UI visible only when needed.
        incomingCallHolder.beGone()
        ongoingCallHolder.beGone()
        callEnd.beGone()

        isMicrophoneOff = try {
            (getSystemService(Context.AUDIO_SERVICE) as AudioManager).isMicrophoneMute
        } catch (_: Exception) {
            false
        }
        updateMicrophoneButton()
        updateAudioRouteUi()
    }

    private fun showIncomingUi() = binding.apply {
        callStatusLabel.text = getString(R.string.is_calling)
        incomingCallHolder.beVisible()
        ongoingCallHolder.beGone()
        callEnd.beGone()
    }

    private fun showOngoingUi(statusText: String? = null) = binding.apply {
        incomingCallHolder.beGone()
        ongoingCallHolder.beVisible()
        callEnd.beVisible()
        if (!statusText.isNullOrBlank()) {
            callStatusLabel.text = statusText
        }
    }

    private fun renderCallerInfo(meshAddr: String) = binding.apply {
        val (nameFromContacts, photoUri) = MeshContactHelper.getContactNameAndPhotoForMeshAddress(this@MeshVoipCallActivity, meshAddr)
        val resolvedName = displayName?.takeIf { it.isNotBlank() } ?: nameFromContacts ?: meshAddr
        callerNameLabel.text = resolvedName
        callerNumber.text = meshAddr
        callerLocation.beGone()
        callerReputation.beGone()

        // We intentionally keep caller avatar styling consistent with PSTN calls.
            callerAvatar.apply {
                setImageResource(R.drawable.ic_person_vector)
                background = AppCompatResources.getDrawable(
                    this@MeshVoipCallActivity,
                    R.drawable.circle_background
                )
                val tint = getProperTextColor()
                drawable?.mutate()?.setTint(tint)
            }
    }

    private fun startOutgoingCall() {
        val hash = remoteDeliveryHash ?: return
        val meshMode = MeshConfig.newInstance(this).getMeshMode()
        if (meshMode == MeshMode.STANDARD_ONLY) {
            toast(R.string.mesh_disabled)
            finish()
            return
        }

        // Ensure the mesh backend is running before we probe/send invites.
        MeshManager.ensureRunning(this)
        // Ensure our call destination exists even if the mesh service is still starting.
        MeshCallRouter.start(this)

        showOngoingUi(getString(R.string.dialing))

        val preferredQuality = MeshCallQuality.fromId(MeshConfig.newInstance(this).meshCallQuality)
        MeshCallRouter.probe(
            context = this,
            remoteDeliveryHash = hash,
            preferredQuality = preferredQuality,
            timeoutMs = 6000L
        ) { result ->
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                val destination = result.remoteDestination
                if (result.success && destination != null) {
                    quality = result.quality
                    val session = MeshCallRouter.createOutgoingSession(
                        remoteDeliveryHash = hash,
                        remoteCallHash = result.remoteCallHash,
                        remoteDestination = destination,
                        quality = result.quality
                    )
                    sessionId = session.sessionId
                    outgoingSession = session
                    MeshCallRouter.sendInvite(session)
                    startInviteRetries()
                    binding.callStatusLabel.text = getString(R.string.dialing)
                    enableProximitySensor()
                } else {
                    // Mesh-only => fail. Mesh-with-fallback => fall back to PSTN if we have a number.
                    if (allowFallback && !fallbackNumber.isNullOrBlank() && meshMode == MeshMode.MESH_WITH_FALLBACK) {
                        startCallIntent(fallbackNumber!!, forceSimSelector = false)
                    } else {
                        toast(R.string.mesh_delivery_failed)
                    }
                    finish()
                }
            }
        }
    }

    private fun answer() {
        if (isCallActive) return
        ensureMicrophonePermission {
            val id = sessionId ?: return@ensureMicrophonePermission
            stopRingtone()
            MeshCallRouter.sendAccept(id)
            // Accept is small and can still be lost. Send it a few times to improve call setup reliability.
            inviteRetryHandler.postDelayed(200L) {
                if (!isDestroyed && !isFinishing && sessionId?.contentEquals(id) == true) {
                    MeshCallRouter.sendAccept(id)
                }
            }
            inviteRetryHandler.postDelayed(800L) {
                if (!isDestroyed && !isFinishing && sessionId?.contentEquals(id) == true) {
                    MeshCallRouter.sendAccept(id)
                }
            }
            if (!startAudio()) {
                MeshCallRouter.sendEnd(id)
                toast(R.string.mesh_call_audio_start_failed)
                finish()
                return@ensureMicrophonePermission
            }
            isCallActive = true
            lastRemotePacketMs = SystemClock.elapsedRealtime()
            stopInviteRetries()
            showOngoingUi(null)
            startCallDuration()
            startCallWatchdog()
            enableProximitySensor()
        }
    }

    private fun hangup() {
        val id = sessionId
        stopRingtone()
        stopInviteRetries()
        stopAudio()
        callWatchdogHandler.removeCallbacks(callWatchdogTask)
        if (id != null) {
            if (isIncoming && !isCallActive) {
                MeshCallRouter.sendDecline(id)
            } else {
                MeshCallRouter.sendEnd(id)
            }
        }
        finish()
    }

    private fun startAudio(): Boolean {
        if (audioEngine != null) return true
        val id = sessionId ?: return false
        val engine = MeshAudioEngine(quality) { frame ->
            val seq = audioSeq++
            MeshCallRouter.sendAudioFrame(id, seq, frame)
        }
        audioEngine = engine
        setAudioModeForCall()
        startAudioRouting()
        val started = try {
            engine.start()
        } catch (_: Exception) {
            false
        }
        if (!started) {
            stopAudio()
            return false
        }
        return true
    }

    private fun stopAudio() {
        audioEngine?.stop()
        audioEngine = null
        stopAudioRouting()
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_NORMAL
        } catch (_: Exception) {
        }
    }

    private fun setAudioModeForCall() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        } catch (_: Exception) {
        }
    }

    private fun toggleMute() = binding.apply {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            isMicrophoneOff = !audioManager.isMicrophoneMute
            audioManager.isMicrophoneMute = isMicrophoneOff
            updateMicrophoneButton()
        } catch (_: Exception) {
        }
    }

    private fun toggleSpeaker() = binding.apply {
        val audioManager = try {
            getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } catch (_: Exception) {
            return@apply
        }

        val bluetoothAvailable = isBluetoothRouteAvailable(audioManager)
        when (audioRoute) {
            MeshAudioRoute.SPEAKER -> {
                // Speaker off -> prefer bluetooth headset if available, else fall back to earpiece.
                if (bluetoothAvailable) {
                    setRouteBluetooth(audioManager)
                } else {
                    setRouteEarpiece(audioManager)
                }
            }
            MeshAudioRoute.BLUETOOTH, MeshAudioRoute.EARPIECE -> {
                setRouteSpeaker(audioManager)
            }
        }
        updateAudioRouteUi()
    }

    private fun startAudioRouting() {
        val audioManager = try {
            getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } catch (_: Exception) {
            return
        }

        // Auto-prefer bluetooth headsets for VoIP calls when available.
        if (audioRoute == MeshAudioRoute.EARPIECE && isBluetoothRouteAvailable(audioManager)) {
            setRouteBluetooth(audioManager)
        }
        updateAudioRouteUi()

        if (audioDeviceCallback == null) {
            audioDeviceCallback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                    // A headset might have connected mid-call.
                    if (audioRoute == MeshAudioRoute.EARPIECE && isBluetoothRouteAvailable(audioManager)) {
                        setRouteBluetooth(audioManager)
                    }
                    updateAudioRouteUi()
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                    // If our active bluetooth route disappeared, fall back cleanly.
                    if (audioRoute == MeshAudioRoute.BLUETOOTH && !isBluetoothRouteAvailable(audioManager)) {
                        setRouteEarpiece(audioManager)
                    }
                    updateAudioRouteUi()
                }
            }
            try {
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
            } catch (_: Exception) {
            }
        }
    }

    private fun stopAudioRouting() {
        val audioManager = try {
            getSystemService(Context.AUDIO_SERVICE) as AudioManager
        } catch (_: Exception) {
            return
        }

        try {
            audioDeviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) }
        } catch (_: Exception) {
        }
        audioDeviceCallback = null

        // Always stop bluetooth routing when leaving the VoIP call.
        stopBluetooth(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {
            }
        }
        try {
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        audioRoute = MeshAudioRoute.EARPIECE
        updateAudioRouteUi()
    }

    private fun isBluetoothRouteAvailable(audioManager: AudioManager): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                audioManager.availableCommunicationDevices.any { isBluetoothCommDeviceType(it.type) }
            } else {
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { isBluetoothCommDeviceType(it.type) }
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isBluetoothCommDeviceType(type: Int): Boolean {
        return type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_HEADSET) ||
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && type == AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }

    private fun setRouteBluetooth(audioManager: AudioManager) {
        try {
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }

        var set = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val device = audioManager.availableCommunicationDevices.firstOrNull { isBluetoothCommDeviceType(it.type) }
                if (device != null) {
                    set = audioManager.setCommunicationDevice(device)
                }
            } catch (_: Exception) {
            }
        }

        if (!set) {
            // Pre-S (or S+ fallback): best-effort SCO.
            startBluetooth(audioManager)
        }
        audioRoute = MeshAudioRoute.BLUETOOTH
    }

    private fun setRouteSpeaker(audioManager: AudioManager) {
        // Speaker implies no bluetooth comm routing.
        stopBluetooth(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {
            }
        }
        try {
            audioManager.isSpeakerphoneOn = true
        } catch (_: Exception) {
        }
        audioRoute = MeshAudioRoute.SPEAKER
    }

    private fun setRouteEarpiece(audioManager: AudioManager) {
        stopBluetooth(audioManager)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                audioManager.clearCommunicationDevice()
            } catch (_: Exception) {
            }
        }
        try {
            audioManager.isSpeakerphoneOn = false
        } catch (_: Exception) {
        }
        audioRoute = MeshAudioRoute.EARPIECE
    }

    private fun startBluetooth(audioManager: AudioManager) {
        try {
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
        } catch (_: Exception) {
        }
    }

    private fun stopBluetooth(audioManager: AudioManager) {
        try {
            audioManager.isBluetoothScoOn = false
        } catch (_: Exception) {
        }
        try {
            audioManager.stopBluetoothSco()
        } catch (_: Exception) {
        }
    }

    private fun updateAudioRouteUi() = binding.apply {
        val iconRes = when (audioRoute) {
            MeshAudioRoute.SPEAKER -> R.drawable.ic_volume_up_vector
            MeshAudioRoute.BLUETOOTH -> R.drawable.ic_bluetooth_audio_vector
            MeshAudioRoute.EARPIECE -> R.drawable.ic_volume_down_vector
        }
        callToggleSpeaker.setImageResource(iconRes)
        toggleButtonColor(callToggleSpeaker, enabled = audioRoute != MeshAudioRoute.EARPIECE)
        callToggleSpeaker.contentDescription = getString(
            if (audioRoute == MeshAudioRoute.SPEAKER) R.string.turn_speaker_off else R.string.turn_speaker_on
        )
    }

    private fun startRingtone() {
        try {
            val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            ringtone = RingtoneManager.getRingtone(this, uri).also { it.play() }
        } catch (_: Exception) {
        }
    }

    private fun stopRingtone() {
        try {
            ringtone?.stop()
        } catch (_: Exception) {
        }
        ringtone = null
    }

    override fun onIncomingInvite(session: MeshCallRouter.MeshCallSession) {
        // Ignore; this activity is launched explicitly for a given session.
    }

    override fun onCallAccepted(sessionId: ByteArray) {
        val current = this.sessionId ?: return
        if (!current.contentEquals(sessionId)) return
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            setCallActiveIfNeeded()
        }
    }

    override fun onCallDeclined(sessionId: ByteArray) {
        val current = this.sessionId ?: return
        if (!current.contentEquals(sessionId)) return
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            stopAudio()
            callWatchdogHandler.removeCallbacks(callWatchdogTask)
            toast(R.string.mesh_call_declined)
            finish()
        }
    }

    override fun onCallEnded(sessionId: ByteArray) {
        val current = this.sessionId ?: return
        if (!current.contentEquals(sessionId)) return
        runOnUiThread {
            if (isDestroyed || isFinishing) return@runOnUiThread
            stopAudio()
            callWatchdogHandler.removeCallbacks(callWatchdogTask)
            finish()
        }
    }

    override fun onAudioFrame(sessionId: ByteArray, sequence: Int, payload: ByteArray) {
        val current = this.sessionId ?: return
        if (!current.contentEquals(sessionId)) return
        // Deduplicate/ignore out-of-order frames. We may receive duplicates if multiple mesh interfaces
        // deliver the same packet.
        if (sequence <= lastRemoteAudioSeq) return
        lastRemoteAudioSeq = sequence
        lastRemotePacketMs = SystemClock.elapsedRealtime()

        // If we start receiving audio but missed the ACCEPT control packet, treat audio as an implicit
        // accept. This improves call setup reliability on lossy links.
        if (!isCallActive && !isIncoming) {
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                setCallActiveIfNeeded()
            }
        }

        audioEngine?.enqueueFrame(payload)
    }

    private fun setCallActiveIfNeeded() {
        if (isCallActive) return
        ensureMicrophonePermission {
            if (isDestroyed || isFinishing || isCallActive) return@ensureMicrophonePermission
            if (!startAudio()) {
                toast(R.string.mesh_call_audio_start_failed)
                hangup()
                return@ensureMicrophonePermission
            }
            isCallActive = true
            lastRemotePacketMs = SystemClock.elapsedRealtime()
            stopInviteRetries()
            showOngoingUi(null)
            startCallDuration()
            startCallWatchdog()
            enableProximitySensor()
        }
    }

    private fun startInviteRetries() {
        inviteRetryHandler.removeCallbacks(inviteRetryTask)
        inviteRetryCount = 0
        inviteRetryHandler.postDelayed(inviteRetryTask, 1500L)
    }

    private fun stopInviteRetries() {
        inviteRetryHandler.removeCallbacks(inviteRetryTask)
        outgoingSession = null
        inviteRetryCount = 0
    }

    private val inviteRetryTask = object : Runnable {
        override fun run() {
            if (isDestroyed || isFinishing) return
            if (isIncoming || isCallActive) return
            val session = outgoingSession ?: return
            inviteRetryCount++
            if (inviteRetryCount >= 15) {
                // ~30s without connect/decline.
                toast(R.string.mesh_delivery_failed)
                hangup()
                return
            }
            try {
                MeshCallRouter.sendInvite(session)
            } catch (_: Exception) {
            }
            inviteRetryHandler.postDelayed(this, 2000L)
        }
    }

    private fun startCallWatchdog() {
        callWatchdogHandler.removeCallbacks(callWatchdogTask)
        callWatchdogHandler.postDelayed(callWatchdogTask, 1000L)
    }

    private val callWatchdogTask = object : Runnable {
        override fun run() {
            if (!isCallActive) return
            val last = lastRemotePacketMs
            if (last > 0L) {
                val idle = SystemClock.elapsedRealtime() - last
                if (idle > 4_000L) {
                    // Remote likely hung up or the network died. Avoid getting stuck in-call.
                    stopAudio()
                    finish()
                    return
                }
            }
            callWatchdogHandler.postDelayed(this, 1000L)
        }
    }

    private fun startCallDuration() {
        callDurationSeconds = 0
        callDurationHandler.removeCallbacks(updateCallDurationTask)
        callDurationHandler.post(updateCallDurationTask)
    }

    private val updateCallDurationTask = object : Runnable {
        override fun run() {
            if (!isCallActive) return
            callDurationSeconds++
            binding.callStatusLabel.text = callDurationSeconds.getFormattedDuration()
            callDurationHandler.postDelayed(this, 1000)
        }
    }

    // --- Swipe-to-answer UI (ported from CallActivity, but calls mesh answer/hangup) ---

    @SuppressLint("ClickableViewAccessibility")
    private fun handleSwipe() = binding.apply {
        var minDragX = 0f
        var maxDragX = 0f
        var initialDraggableX = 0f
        var initialLeftArrowX = 0f
        var initialRightArrowX = 0f
        var initialLeftArrowScaleX = 0f
        var initialLeftArrowScaleY = 0f
        var initialRightArrowScaleX = 0f
        var initialRightArrowScaleY = 0f
        var leftArrowTranslation = 0f
        var rightArrowTranslation = 0f

        val isRtl = isRTLLayout
        callAccept.onGlobalLayout {
            minDragX = if (isRtl) callAccept.left.toFloat() else callDecline.left.toFloat()
            maxDragX = if (isRtl) callDecline.left.toFloat() else callAccept.left.toFloat()

            initialDraggableX = callDraggable.left.toFloat()
            initialLeftArrowX = callLeftArrow.x
            initialRightArrowX = callRightArrow.x
            initialLeftArrowScaleX = callLeftArrow.scaleX
            initialLeftArrowScaleY = callLeftArrow.scaleY
            initialRightArrowScaleX = callRightArrow.scaleX
            initialRightArrowScaleY = callRightArrow.scaleY
            leftArrowTranslation = if (isRtl) callAccept.x else -callDecline.x
            rightArrowTranslation = if (isRtl) -callAccept.x else callDecline.x

            callLeftArrow.applyColorFilter(getColor(org.fossify.commons.R.color.md_red_400))
            callRightArrow.applyColorFilter(getColor(org.fossify.commons.R.color.md_green_400))

            startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
            startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
        }

        // Keep parity with the normal CallActivity styling.
        try {
            callDraggable.drawable?.mutate()?.setTint(getProperTextColor())
            callDraggableBackground.drawable?.mutate()?.setTint(getProperTextColor())
        } catch (_: Exception) {
        }

        var lock = false
        callDraggable.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragDownX = event.x
                    callDraggableBackground.animate().alpha(0f)
                    stopSwipeAnimation = true
                    callLeftArrow.animate().alpha(0f)
                    callRightArrow.animate().alpha(0f)
                    lock = false
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragDownX = 0f
                    callDraggable.animate().x(initialDraggableX).withEndAction {
                        callDraggableBackground.animate().alpha(0.2f)
                    }
                    callDraggable.setImageDrawable(
                        AppCompatResources.getDrawable(
                            this@MeshVoipCallActivity,
                            R.drawable.ic_phone_down_vector
                        )
                    )
                    callDraggable.drawable?.mutate()?.setTint(getProperTextColor())
                    callLeftArrow.animate().alpha(1f)
                    callRightArrow.animate().alpha(1f)
                    stopSwipeAnimation = false
                    startArrowAnimation(callLeftArrow, initialLeftArrowX, initialLeftArrowScaleX, initialLeftArrowScaleY, leftArrowTranslation)
                    startArrowAnimation(callRightArrow, initialRightArrowX, initialRightArrowScaleX, initialRightArrowScaleY, rightArrowTranslation)
                }

                MotionEvent.ACTION_MOVE -> {
                    callDraggable.x = min(maxDragX, max(minDragX, event.rawX - dragDownX))
                    when {
                        callDraggable.x >= maxDragX - 50f -> {
                            if (!lock) {
                                lock = true
                                callDraggable.performHapticFeedback()
                                if (isRtl) {
                                    hangup()
                                } else {
                                    answer()
                                }
                            }
                        }

                        callDraggable.x <= minDragX + 50f -> {
                            if (!lock) {
                                lock = true
                                callDraggable.performHapticFeedback()
                                if (isRtl) {
                                    answer()
                                } else {
                                    hangup()
                                }
                            }
                        }

                        callDraggable.x > initialDraggableX -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_down_red_vector
                            } else {
                                R.drawable.ic_phone_green_vector
                            }
                            callDraggable.setImageDrawable(
                                AppCompatResources.getDrawable(
                                    this@MeshVoipCallActivity,
                                    drawableRes
                                )
                            )
                        }

                        else -> {
                            lock = false
                            val drawableRes = if (isRtl) {
                                R.drawable.ic_phone_green_vector
                            } else {
                                R.drawable.ic_phone_down_red_vector
                            }
                            callDraggable.setImageDrawable(
                                AppCompatResources.getDrawable(
                                    this@MeshVoipCallActivity,
                                    drawableRes
                                )
                            )
                        }
                    }
                }
            }
            true
        }
    }

    private fun startArrowAnimation(arrow: ImageView, initialX: Float, initialScaleX: Float, initialScaleY: Float, translation: Float) {
        arrow.apply {
            alpha = 1f
            x = initialX
            scaleX = initialScaleX
            scaleY = initialScaleY
            animate()
                .alpha(0f)
                .translationX(translation)
                .scaleXBy(-0.5f)
                .scaleYBy(-0.5f)
                .setDuration(1000)
                .withEndAction {
                    if (!stopSwipeAnimation) {
                        startArrowAnimation(this, initialX, initialScaleX, initialScaleY, translation)
                    }
                }
        }
    }

    // --- Lock screen + proximity ---

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun addLockScreenFlags() {
        // Mirror the behavior of the normal CallActivity so the incoming UI is interactive on
        // modern Android versions (the deprecated flags alone can show, but not accept input).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        (getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).requestDismissKeyguard(this, null)

        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            val wakeLock = powerManager.newWakeLock(PowerManager.FULL_WAKE_LOCK, "org.fossify.phone:mesh_full_wake_lock")
            wakeLock.acquire(5_000L)
        } catch (_: Exception) {
        }
    }

    private fun enableProximitySensor() {
        if (proximityWakeLock == null || proximityWakeLock?.isHeld == false) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                proximityWakeLock =
                    powerManager.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "org.fossify.phone:mesh_wake_lock")
                proximityWakeLock?.acquire(60 * MINUTE_SECONDS * 1000L)
            } catch (_: Exception) {
            }
        }
    }

    private fun disableProximitySensor() {
        try {
            if (proximityWakeLock?.isHeld == true) {
                proximityWakeLock?.release()
            }
        } catch (_: Exception) {
        }
    }

    private fun syncAudioButtonStateFromSystem() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            isMicrophoneOff = audioManager.isMicrophoneMute
        } catch (_: Exception) {
            // Keep previous state if AudioManager is unavailable.
        }
        updateMicrophoneButton()
    }

    private fun updateMicrophoneButton() = binding.apply {
        toggleButtonColor(callToggleMicrophone, enabled = isMicrophoneOff)
        callToggleMicrophone.contentDescription = getString(
            if (isMicrophoneOff) R.string.turn_microphone_on else R.string.turn_microphone_off
        )
    }

    private fun getActiveButtonColor() = getProperPrimaryColor()

    private fun getInactiveButtonColor() = getProperTextColor().adjustAlpha(0.10f)

    private fun ensureMicrophonePermission(onGranted: () -> Unit) {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            onGranted()
            return
        }
        pendingMicPermissionAction = onGranted
        if (micPermissionRequestInFlight) return
        micPermissionRequestInFlight = true
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        micPermissionRequestInFlight = false
        val action = pendingMicPermissionAction
        pendingMicPermissionAction = null
        val granted = grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED
        if (granted) {
            action?.invoke()
        } else {
            toast(R.string.mesh_call_microphone_permission_required)
            hangup()
        }
    }

    private fun toggleButtonColor(view: ImageView, enabled: Boolean) {
        if (enabled) {
            val color = getActiveButtonColor()
            view.background.applyColorFilter(color)
            view.applyColorFilter(color.getContrastColor())
        } else {
            view.background.applyColorFilter(getInactiveButtonColor())
            view.applyColorFilter(getProperBackgroundColor().getContrastColor())
        }
    }

    companion object {
        private const val EXTRA_INCOMING = "mesh_voip_incoming"
        private const val EXTRA_SESSION_ID = "mesh_voip_session_id"
        private const val EXTRA_REMOTE_DELIVERY_HASH = "mesh_voip_remote_delivery_hash"
        private const val EXTRA_CALL_QUALITY = "mesh_voip_call_quality"
        private const val EXTRA_MESH_ADDRESS = "mesh_voip_mesh_address"
        private const val EXTRA_DISPLAY_NAME = "mesh_voip_display_name"
        private const val EXTRA_FALLBACK_NUMBER = "mesh_voip_fallback_number"
        private const val EXTRA_ALLOW_FALLBACK = "mesh_voip_allow_fallback"
        private const val REQUEST_RECORD_AUDIO = 1083

        fun startOutgoing(
            context: Context,
            meshAddress: String,
            displayName: String?,
            fallbackNumber: String?,
            allowFallback: Boolean
        ) {
            val intent = Intent(context, MeshVoipCallActivity::class.java).apply {
                putExtra(EXTRA_INCOMING, false)
                putExtra(EXTRA_MESH_ADDRESS, meshAddress)
                putExtra(EXTRA_DISPLAY_NAME, displayName)
                putExtra(EXTRA_FALLBACK_NUMBER, fallbackNumber)
                putExtra(EXTRA_ALLOW_FALLBACK, allowFallback)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }

        fun startIncoming(
            context: Context,
            sessionId: ByteArray,
            remoteDeliveryHash: ByteArray,
            qualityId: Int,
            meshAddress: String?
        ) {
            val intent = Intent(context, MeshVoipCallActivity::class.java).apply {
                putExtra(EXTRA_INCOMING, true)
                putExtra(EXTRA_SESSION_ID, sessionId)
                putExtra(EXTRA_REMOTE_DELIVERY_HASH, remoteDeliveryHash)
                putExtra(EXTRA_CALL_QUALITY, qualityId)
                putExtra(EXTRA_MESH_ADDRESS, meshAddress)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}
