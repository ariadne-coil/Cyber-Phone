package org.fossify.messages.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import org.fossify.commons.extensions.showErrorToast
import org.fossify.commons.extensions.toast
import org.fossify.commons.extensions.updateTextColors
import org.fossify.commons.extensions.viewBinding
import org.fossify.commons.helpers.NavigationIcon
import org.fossify.messages.R
import org.fossify.messages.databinding.ActivityMeshQrScanBinding
import org.fossify.messages.helpers.MeshDiscoveryManager

class MeshQrScanActivity : SimpleActivity() {
    private val binding by viewBinding(ActivityMeshQrScanBinding::inflate)
    private var handled = false
    private var lastInvalidToastMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(binding.root)

        setupTopAppBar(binding.meshQrScanAppbar, NavigationIcon.Arrow)
        updateTextColors(binding.meshQrScanHolder)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        binding.meshQrBarcodeView.statusView?.text = ""
        binding.meshQrBarcodeView.statusView?.alpha = 0f

        // Decode only QR codes to reduce false positives and speed up scanning.
        binding.meshQrBarcodeView.barcodeView.decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
        binding.meshQrBarcodeView.decodeContinuous(object : BarcodeCallback {
            override fun barcodeResult(result: BarcodeResult?) {
                if (handled) return
                val raw = result?.text?.trim().orEmpty()
                if (raw.isBlank()) return

                val meshAddress = MeshDiscoveryManager.extractMeshAddress(raw)
                if (meshAddress.isNullOrBlank()) {
                    // Keep scanning; avoid spamming toasts if we repeatedly scan a non-mesh QR.
                    val now = System.currentTimeMillis()
                    if (now - lastInvalidToastMs > 2_000L) {
                        lastInvalidToastMs = now
                        toast(R.string.profile_mesh_invalid)
                    }
                    return
                }

                handled = true
                val data = Intent().putExtra(EXTRA_MESH_ADDRESS, meshAddress)
                setResult(Activity.RESULT_OK, data)
                finish()
            }

            override fun possibleResultPoints(resultPoints: MutableList<com.google.zxing.ResultPoint>?) = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        try {
            binding.meshQrBarcodeView.resume()
        } catch (e: Exception) {
            showErrorToast(e)
        }
    }

    override fun onPause() {
        try {
            binding.meshQrBarcodeView.pause()
        } catch (_: Exception) {
        }
        super.onPause()
    }

    companion object {
        const val EXTRA_MESH_ADDRESS = "org.fossify.messages.extra.MESH_ADDRESS"
    }
}
