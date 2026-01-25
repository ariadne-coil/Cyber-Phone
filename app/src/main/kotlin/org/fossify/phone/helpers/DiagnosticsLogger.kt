package org.fossify.phone.helpers

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticsLogger {
    private const val TAG = "DiagnosticsLogger"
    private const val FILE_NAME = "call_ui_diagnostics.log"
    private const val MAX_BYTES = 256 * 1024

    fun log(context: Context, message: String) {
        try {
            val file = File(context.filesDir, FILE_NAME)
            if (file.exists() && file.length() > MAX_BYTES) {
                file.delete()
            }

            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
            val line = "$timestamp $message\n"
            FileOutputStream(file, true).use { out ->
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write diagnostics", e)
        }
    }

    fun getLogFile(context: Context): File {
        return File(context.filesDir, FILE_NAME)
    }
}
