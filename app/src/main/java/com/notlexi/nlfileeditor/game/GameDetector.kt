package com.notlexi.nlfileeditor.game

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings

data class GameInfo(val packageName: String, val label: String, val isGame: Boolean, val lastUsed: Long)

/**
 * Finds the game that is (or was just) in the foreground, with a supported Android API:
 * UsageStatsManager. Android only shares this after the user turns on "Usage access" for the app
 * (Settings > Apps > Special access), so the permission state is reported honestly and nothing is
 * guessed without it. No root, no accessibility service, no process inspection.
 *
 * "Game" means Android itself says so (ApplicationInfo.CATEGORY_GAME / FLAG_IS_GAME), or the
 * package is in the short list of well-known games below whose store category is often not declared.
 */
object GameDetector {
    /** Games that are popular with NL File Editor users but often not tagged as games by their publishers. */
    val KNOWN_GAMES = setOf(
        "com.mojang.minecraftpe", "com.mojang.minecraftedu", "com.roblox.client", "com.dts.freefireth",
        "com.dts.freefiremax", "com.tencent.ig", "com.pubg.imobile", "com.activision.callofduty.shooter",
        "com.mobile.legends", "com.supercell.clashofclans", "com.supercell.brawlstars", "com.kiloo.subwaysurf",
        "com.innersloth.spacemafia", "com.miHoYo.GenshinImpact", "com.garena.game.kgtw"
    )

    /** Where a game's data usually lives besides Android/data, for the folder-picker hint. */
    fun alternativeFolders(pkg: String): List<String> = when (pkg) {
        "com.mojang.minecraftpe", "com.mojang.minecraftedu" -> listOf("games/com.mojang", "Android/media/$pkg")
        else -> listOf("Android/media/$pkg")
    }

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
        val mode = try {
            if (Build.VERSION.SDK_INT >= 29) {
                ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            } else {
                @Suppress("DEPRECATION")
                ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
            }
        } catch (e: Exception) {
            return false
        }
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun usageAccessIntent(): Intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * The most recently used non-system app within [windowMillis] that is a game. [candidates] also
     * returns the other recently used user apps, so the UI can offer them if the game isn't tagged.
     */
    fun detect(context: Context, windowMillis: Long = 45 * 60_000L): Pair<GameInfo?, List<GameInfo>> {
        if (!hasUsageAccess(context)) return null to emptyList()
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null to emptyList()
        val now = System.currentTimeMillis()
        val events = try {
            usm.queryEvents(now - windowMillis, now)
        } catch (e: Exception) {
            return null to emptyList()
        } ?: return null to emptyList()

        val resumedType = if (Build.VERSION.SDK_INT >= 29) UsageEvents.Event.ACTIVITY_RESUMED else @Suppress("DEPRECATION") UsageEvents.Event.MOVE_TO_FOREGROUND
        val lastSeen = LinkedHashMap<String, Long>()
        val ev = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(ev)
            if (ev.eventType == resumedType) lastSeen[ev.packageName] = ev.timeStamp
        }

        val excluded = excludedPackages(context)
        val pm = context.packageManager
        val infos = lastSeen.entries
            .filter { it.key !in excluded }
            .sortedByDescending { it.value }
            .mapNotNull { (pkg, time) -> describe(pm, pkg, time) }
        return infos.firstOrNull { it.isGame } to infos
    }

    private fun excludedPackages(context: Context): Set<String> {
        val set = HashSet<String>()
        set.add(context.packageName)
        set.add("com.android.systemui")
        set.add("android")
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        try {
            for (r in context.packageManager.queryIntentActivities(home, 0)) r.activityInfo?.packageName?.let { set.add(it) }
        } catch (_: Exception) {
        }
        return set
    }

    private fun describe(pm: PackageManager, pkg: String, time: Long): GameInfo? {
        val ai = try { pm.getApplicationInfo(pkg, 0) } catch (e: Exception) { return null }
        val system = (ai.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        if (system) return null
        val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pkg }
        return GameInfo(pkg, label.ifBlank { pkg }, isGame(ai), time)
    }

    @Suppress("DEPRECATION")
    fun isGame(ai: ApplicationInfo): Boolean {
        if (ai.packageName in KNOWN_GAMES) return true
        if (Build.VERSION.SDK_INT >= 26 && ai.category == ApplicationInfo.CATEGORY_GAME) return true
        return (ai.flags and ApplicationInfo.FLAG_IS_GAME) != 0
    }
}
