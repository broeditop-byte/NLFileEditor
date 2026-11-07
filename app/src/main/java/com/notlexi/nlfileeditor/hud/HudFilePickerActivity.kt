package com.notlexi.nlfileeditor.hud

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Invisible helper that opens the system file picker (Storage Access Framework) on behalf of the
 * overlay service, which cannot launch a picker itself. The chosen file's permission is persisted.
 *
 * It can be opened while a file is already selected: the new file simply replaces the old one
 * (FileAccessManager.saveTarget bumps HudRuntime.targetEpoch, which resets Snapshot A/B and results).
 * Cancelling the picker leaves the current selection untouched.
 *
 * The picker is HINTED to start in Android/data (EXTRA_INITIAL_URI). The hint is only a request:
 * Android 11+ (and especially 13+) blocks third-party pickers from showing Android/data, and
 * some pickers ignore the hint, so the system may open at its default location instead.
 */
class HudFilePickerActivity : ComponentActivity() {

    private val picker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        val uri = data?.data
        if (result.resultCode != RESULT_OK || data == null || uri == null) {
            finish()
            return@registerForActivityResult
        }
        val files = FileAccessManager(this)
        val granted = data.flags and
            (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val persisted = try {
            files.saveTarget(uri, granted)
        } catch (e: Exception) {
            Log.e(TAG, "Could not save the selected file", e)
            Toast.makeText(this, "Could not use this file. Try another one.", Toast.LENGTH_LONG).show()
            finish()
            return@registerForActivityResult
        }
        lifecycleScope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { files.describe(uri) }.getOrNull() }
            val text = when {
                info == null -> "This file is not accessible."
                !info.canWrite -> "${info.name} selected (read-only: detection only)."
                !persisted -> "${info.name} selected (access lasts until the app restarts)."
                else -> "Target: ${info.name}"
            }
            Toast.makeText(this@HudFilePickerActivity, text, Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        HudRuntime.pickerOpen.value = true
        if (savedInstanceState == null) {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                )
                // Always attempt the hint; if the device ignores it nothing breaks.
                if (Build.VERSION.SDK_INT >= 26) {
                    putExtra(DocumentsContract.EXTRA_INITIAL_URI, androidDataUri())
                }
            }
            try {
                picker.launch(intent)
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(this, "No file picker is available on this device.", Toast.LENGTH_LONG).show()
                finish()
            } catch (e: SecurityException) {
                Toast.makeText(this, "The file picker could not be opened.", Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    override fun onDestroy() {
        HudRuntime.pickerOpen.value = false
        super.onDestroy()
    }

    /** Document URI of "Android/data" on the primary shared storage volume. */
    private fun androidDataUri(): Uri =
        DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:Android/data")

    private companion object {
        const val TAG = "NLHud"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
    }
}
