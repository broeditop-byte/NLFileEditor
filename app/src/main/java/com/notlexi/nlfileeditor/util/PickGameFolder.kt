package com.notlexi.nlfileeditor.util

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.activity.result.contract.ActivityResultContract

/**
 * System folder picker (ACTION_OPEN_DOCUMENT_TREE) opened at a relative location of the primary
 * storage, e.g. "Android/data/com.mojang.minecraftpe". The location is only a hint: Android 11+
 * refuses Android/data for third-party pickers on most devices and Android 13+ everywhere, in which
 * case the picker opens somewhere else (or says the folder can't be used) and the app reports it.
 * Read AND write access are requested and persisted so edited files can be written back.
 */
class PickGameFolder : ActivityResultContract<String, Uri?>() {
    override fun createIntent(context: Context, input: String): Intent {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:" + input.trim('/'))
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, uri)
        }
        return intent
    }

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}
