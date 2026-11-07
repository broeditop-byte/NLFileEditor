package com.notlexi.nlfileeditor.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.notlexi.nlfileeditor.model.InstalledApp

/** Lists the launchable apps installed on the phone (needs only the <queries> entry in the manifest). */
object InstalledApps {

    fun load(context: Context): List<InstalledApp> {
        val pm = context.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = try {
            pm.queryIntentActivities(launcher, 0)
        } catch (e: Exception) {
            emptyList()
        }
        val seen = HashSet<String>()
        val out = ArrayList<InstalledApp>()
        for (info in infos) {
            val ai = info.activityInfo?.applicationInfo ?: continue
            val pkg = ai.packageName
            if (pkg == context.packageName || !seen.add(pkg)) continue
            val isSystem = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
            if (isSystem) continue // games and tools installed by the user only
            val label = try {
                info.loadLabel(pm).toString()
            } catch (e: Exception) {
                pkg
            }
            out.add(InstalledApp(packageName = pkg, label = label.ifBlank { pkg }))
        }
        return out.sortedBy { it.label.lowercase() }
    }

    /** App icon as a small bitmap, or null if it can't be loaded. */
    fun icon(context: Context, packageName: String, sizePx: Int = 96): Bitmap? = try {
        context.packageManager.getApplicationIcon(packageName).toBitmap(sizePx, sizePx)
    } catch (e: Exception) {
        null
    }
}
