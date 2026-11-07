package com.notlexi.nlfileeditor.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * System folder picker (Storage Access Framework, ACTION_OPEN_DOCUMENT_TREE).
 * The input is the package name of the app the user chose: the picker opens straight at
 * Android/data/<package> when the device allows it, so the user only has to tap "Use this folder".
 */
class PickAppDataFolder : ActivityResultContract<String, Uri?>() {

    override fun createIntent(context: Context, input: String): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val folder = if (input.isBlank()) "primary:Android/data" else "primary:Android/data/$input"
            val initial = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", folder)
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, initial)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
