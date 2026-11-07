package com.notlexi.nlfileeditor.game

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.notlexi.nlfileeditor.search.SafFileSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A folder the user granted through the system folder picker (persisted permission). */
data class GameFolder(val tree: Uri, val packageName: String?, val label: String, val path: String)

data class GameUiState(
    val checking: Boolean = true,
    val usageAccess: Boolean = false,
    /** The game found in the foreground / recently used (null if none, or if usage access is off). */
    val detected: GameInfo? = null,
    /** Other recently used user apps, offered when the real game isn't tagged as a game by its publisher. */
    val recentApps: List<GameInfo> = emptyList(),
    /** Folder currently used by Search / Changes. */
    val folder: GameFolder? = null,
    val notice: String? = null,
    val noticeIsError: Boolean = false
)

/**
 * Game detection + remembered folder access.
 *
 *  1. On launch (and whenever the app returns to the foreground) the foreground game is looked up
 *     with UsageStatsManager ([GameDetector]).
 *  2. A detected game uses its remembered folder grant if it still exists; otherwise the system folder
 *     picker opens ONCE at Android/data/<package>. Nothing is asked twice for the same game.
 *  3. No game detected: the picker opens at Android/data once per app start, so the user can choose a folder by hand.
 *  4. Whatever Android refuses is reported plainly; a folder is only "ready" after it has really been listed.
 *
 * Android 11+ restricts Android/data for ordinary apps (Android 13+ blocks it in the picker), so many
 * devices will answer with an error here. That is shown as is; it is never worked around.
 */
class GameModel(private val app: Application, private val scope: CoroutineScope) {
    private val prefs = app.getSharedPreferences("nl_game", Context.MODE_PRIVATE)
    private val cr get() = app.contentResolver

    private val _state = MutableStateFlow(GameUiState())
    val state: StateFlow<GameUiState> = _state.asStateFlow()

    /** Relative storage path the UI should open the folder picker at, e.g. "Android/data/com.x.y". */
    private val _pickRequests = MutableSharedFlow<String>(extraBufferCapacity = 2)
    val pickRequests: SharedFlow<String> = _pickRequests.asSharedFlow()

    private var pickFor: String? = null
    private val prompted = HashSet<String>()

    fun refresh(autoPrompt: Boolean = true) {
        scope.launch {
            _state.update { it.copy(checking = true) }
            val (access, found) = withContext(Dispatchers.IO) {
                val a = GameDetector.hasUsageAccess(app)
                val d = if (a) GameDetector.detect(app) else null to emptyList()
                a to d
            }
            val game = found.first
            val recents = found.second.filter { !it.isGame }.take(6)

            val remembered = withContext(Dispatchers.IO) {
                var found: GameFolder? = null
                if (game != null) {
                    found = savedFolder(game.packageName, game.label)
                    prefs.edit().putString(KEY_LAST_PKG, game.packageName).putString(KEY_LAST_LABEL, game.label).apply()
                } else {
                    // Nothing running: fall back to the last game, then to a folder chosen by hand.
                    found = lastGameFolder() ?: savedFolder(MANUAL, "Chosen folder")
                }
                found
            }
            _state.update {
                it.copy(checking = false, usageAccess = access, detected = game, recentApps = recents,
                    folder = remembered ?: it.folder?.takeIf { f -> game == null || f.packageName == game.packageName })
            }

            if (!autoPrompt) return@launch
            if (game != null && remembered == null) {
                if (prompted.add(game.packageName)) requestPick("Android/data/${game.packageName}", game.packageName)
            } else if (game == null && access && remembered == null && prompted.add("*")) {
                requestPick("Android/data", null)
            }
        }
    }

    /** Asks the UI to open the folder picker; the answer comes back through [onPicked]. */
    fun requestPick(relativePath: String, forPackage: String?) {
        pickFor = forPackage
        _pickRequests.tryEmit(relativePath)
    }

    /** The user chose a recent app as "the game" (apps without a games tag, e.g. Minecraft forks). */
    fun useApp(info: GameInfo) {
        scope.launch {
            val folder = withContext(Dispatchers.IO) {
                prefs.edit().putString(KEY_LAST_PKG, info.packageName).putString(KEY_LAST_LABEL, info.label).apply()
                savedFolder(info.packageName, info.label)
            }
            _state.update { it.copy(detected = info, folder = folder, notice = null) }
            if (folder == null) requestPick("Android/data/${info.packageName}", info.packageName)
        }
    }

    fun onPicked(uri: Uri?) {
        val pkg = pickFor
        if (uri == null) {
            _state.update { it.copy(notice = "No folder was selected.", noticeIsError = false) }
            return
        }
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                val kids = SafFileSource(cr, uri).children(null)
                if (kids == null) "Android would not let NL File Editor read that folder."
                else if (kids.isEmpty()) "That folder is empty, so there is nothing to work with."
                else null
            }
            if (ok != null) {
                val alt = if (pkg != null) GameDetector.alternativeFolders(pkg).joinToString(" or ") else null
                _state.update {
                    it.copy(
                        notice = ok + (if (alt != null) " Try $alt instead, or copy the file out with a file manager and use Upload File." else ""),
                        noticeIsError = true
                    )
                }
                return@launch
            }
            val key = pkg ?: MANUAL
            val label = if (pkg != null) (_state.value.detected?.takeIf { it.packageName == pkg }?.label ?: pkg) else "Chosen folder"
            withContext(Dispatchers.IO) {
                persist(uri)
                prefs.edit().putString(TREE_PREFIX + key, uri.toString()).apply()
            }
            _state.update {
                it.copy(folder = folderOf(uri, pkg, label), notice = "Folder ready: ${displayPath(uri)}", noticeIsError = false)
            }
        }
    }

    /** Forgets the remembered folder (and releases its permission) for the current game / manual choice. */
    fun forgetFolder() {
        val f = _state.value.folder ?: return
        val key = f.packageName ?: MANUAL
        prefs.edit().remove(TREE_PREFIX + key).apply()
        try {
            cr.releasePersistableUriPermission(f.tree, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (_: Exception) {
        }
        _state.update { it.copy(folder = null, notice = "Folder forgotten.", noticeIsError = false) }
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---------------------------------------------------------------------------------- storage

    private fun lastGameFolder(): GameFolder? {
        val pkg = prefs.getString(KEY_LAST_PKG, null) ?: return null
        return savedFolder(pkg, prefs.getString(KEY_LAST_LABEL, null) ?: pkg)
    }

    /** The remembered folder of [key], only if Android still holds the permission for it. */
    private fun savedFolder(key: String, label: String): GameFolder? {
        val s = prefs.getString(TREE_PREFIX + key, null) ?: return null
        val uri = runCatching { Uri.parse(s) }.getOrNull() ?: return null
        val held = cr.persistedUriPermissions.any { it.uri == uri && it.isReadPermission }
        if (!held) return null
        return folderOf(uri, if (key == MANUAL) null else key, label)
    }

    private fun persist(uri: Uri) {
        try {
            cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        } catch (e: SecurityException) {
            try {
                cr.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: SecurityException) {
                // The provider can't persist: the grant lasts until the app is closed.
            }
        }
    }

    private fun folderOf(uri: Uri, pkg: String?, label: String) = GameFolder(uri, pkg, label, displayPath(uri))

    private fun displayPath(uri: Uri): String = try {
        DocumentsContract.getTreeDocumentId(uri).substringAfter(':')
    } catch (e: Exception) {
        uri.toString()
    }

    private companion object {
        const val MANUAL = "_manual"
        const val TREE_PREFIX = "tree_"
        const val KEY_LAST_PKG = "last_pkg"
        const val KEY_LAST_LABEL = "last_label"
    }
}
