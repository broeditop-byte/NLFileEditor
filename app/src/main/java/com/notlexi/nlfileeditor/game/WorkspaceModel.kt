package com.notlexi.nlfileeditor.game

import android.app.Application
import android.net.Uri
import com.notlexi.nlfileeditor.search.ChangeDetector
import com.notlexi.nlfileeditor.search.ChangeReport
import com.notlexi.nlfileeditor.search.FileSource
import com.notlexi.nlfileeditor.search.QueryMode
import com.notlexi.nlfileeditor.search.SafFileSource
import com.notlexi.nlfileeditor.search.SafNode
import com.notlexi.nlfileeditor.search.SearchEngine
import com.notlexi.nlfileeditor.search.SearchHit
import com.notlexi.nlfileeditor.search.SearchProgress
import com.notlexi.nlfileeditor.search.SearchQuery
import com.notlexi.nlfileeditor.search.SearchSummary
import com.notlexi.nlfileeditor.search.Snapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

class InspectState(val hit: SearchHit, val lines: List<Pair<Int, String>>?, val stale: Boolean)

data class WorkspaceUi(
    val tab: Int = 0,
    val mode: QueryMode = QueryMode.NUMBER,
    val query: String = "",
    val includeUnlabeled: Boolean = true,
    val searching: Boolean = false,
    val progress: SearchProgress? = null,
    val hits: List<SearchHit> = emptyList(),
    val summary: SearchSummary? = null,
    val inspect: InspectState? = null,
    val baseline: Snapshot? = null,
    val capturing: Boolean = false,
    val captureText: String = "",
    val report: ChangeReport? = null,
    val message: String? = null
)

/** State + jobs of the Search and Changes screens. Every scan runs on Dispatchers.IO and can be cancelled. */
class WorkspaceModel(private val app: Application, private val scope: CoroutineScope, private val folder: () -> GameFolder?) {
    private val _ui = MutableStateFlow(WorkspaceUi())
    val ui: StateFlow<WorkspaceUi> = _ui.asStateFlow()

    private val cancel = AtomicBoolean(false)
    private var running = false

    private fun source(): FileSource? = folder()?.let { SafFileSource(app.contentResolver, it.tree) }

    fun setTab(t: Int) = _ui.update { it.copy(tab = t) }
    fun setMode(m: QueryMode) = _ui.update { it.copy(mode = m, query = if (m == QueryMode.NUMBER) it.query.filter { c -> c.isDigit() || c in "-.,+" } else it.query) }
    fun setQuery(q: String) = _ui.update { it.copy(query = if (it.mode == QueryMode.NUMBER) q.filter { c -> c.isDigit() || c in "-.,+ " } else q) }
    fun setUnlabeled(v: Boolean) = _ui.update { it.copy(includeUnlabeled = v) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun cancelRunning() { cancel.set(true) }

    /** A folder change invalidates everything that was found in the old one. */
    fun reset() {
        cancel.set(true)
        _ui.update { WorkspaceUi(tab = it.tab, mode = it.mode, includeUnlabeled = it.includeUnlabeled) }
    }

    // ---------------------------------------------------------------------------------- search

    fun search() {
        if (running) return
        val src = source() ?: run { _ui.update { it.copy(message = "Choose a folder first.") }; return }
        val s = _ui.value
        val query = SearchQuery(s.query, s.mode, s.includeUnlabeled)
        cancel.set(false)
        running = true
        _ui.update { it.copy(searching = true, hits = emptyList(), summary = null, progress = null, message = null, inspect = null) }
        scope.launch {
            val acc = ArrayList<SearchHit>()
            val summary = withContext(Dispatchers.IO) {
                SearchEngine.search(
                    src, query,
                    cancelled = { cancel.get() || !isActive },
                    onProgress = { p -> val copy = acc.toList(); _ui.update { it.copy(progress = p, hits = copy) } },
                    onHit = { acc.add(it) }
                )
            }
            running = false
            _ui.update {
                it.copy(searching = false, hits = summary.hits, summary = summary, progress = null, message = summary.error)
            }
        }
    }

    // ---------------------------------------------------------------------------------- inspect / open

    fun inspect(hit: SearchHit) {
        val src = source() ?: return
        scope.launch {
            val (lines, stale) = withContext(Dispatchers.IO) {
                SearchEngine.context(src, hit) to SearchEngine.isStale(src, hit)
            }
            _ui.update { it.copy(inspect = InspectState(hit, lines, stale)) }
        }
    }

    fun closeInspect() = _ui.update { it.copy(inspect = null) }

    /**
     * The file's URI for the editor, or null (with a message) when the result is stale: a result that
     * no longer matches the file on disk is never opened as if it were current.
     */
    suspend fun openable(hit: SearchHit): Uri? {
        val src = source() ?: return null
        val stale = withContext(Dispatchers.IO) { SearchEngine.isStale(src, hit) }
        if (stale) {
            _ui.update { it.copy(message = "That file changed (or is gone) since the search. Tap Refresh to search again.") }
            return null
        }
        return (hit.node as? SafNode)?.uri
    }

    /** Document URI of a changed file, for "open in editor" (null for removed files). */
    fun uriOf(change: com.notlexi.nlfileeditor.search.FileChange): Uri? = (change.node as? SafNode)?.uri

    // ---------------------------------------------------------------------------------- changes

    fun takeBaseline() {
        capture { snap -> _ui.update { it.copy(baseline = snap, report = null, message = "Baseline saved: ${snap.files.size} readable files.") } }
    }

    fun compareNow() {
        val base = _ui.value.baseline ?: run { _ui.update { it.copy(message = "Take a baseline first, play the game, then compare.") }; return }
        capture { now ->
            val report = ChangeDetector.diff(base, now)
            _ui.update { it.copy(report = report, message = if (report.changes.isEmpty()) "No changes detected in the files this app can read." else null) }
        }
    }

    fun clearBaseline() = _ui.update { it.copy(baseline = null, report = null) }

    private fun capture(done: (Snapshot) -> Unit) {
        if (running) return
        val src = source() ?: run { _ui.update { it.copy(message = "Choose a folder first.") }; return }
        cancel.set(false)
        running = true
        _ui.update { it.copy(capturing = true, captureText = "Reading files…", message = null) }
        scope.launch {
            var last = 0L
            val snap = withContext(Dispatchers.IO) {
                ChangeDetector.capture(src, cancelled = { cancel.get() || !isActive }) { seen, cur ->
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - last > 120) { last = now; _ui.update { it.copy(captureText = "$seen files · $cur") } }
                }
            }
            running = false
            _ui.update { it.copy(capturing = false, captureText = "") }
            if (cancel.get()) _ui.update { it.copy(message = "Cancelled.") } else done(snap)
        }
    }
}
