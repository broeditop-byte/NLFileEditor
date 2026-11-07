package com.notlexi.nlfileeditor.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notlexi.nlfileeditor.game.GameModel
import com.notlexi.nlfileeditor.game.WorkspaceModel
import com.notlexi.nlfileeditor.model.AppDataCandidate
import com.notlexi.nlfileeditor.model.BrowseState
import com.notlexi.nlfileeditor.model.HtmlDoc
import com.notlexi.nlfileeditor.model.InstalledApp
import com.notlexi.nlfileeditor.model.AppPhase
import com.notlexi.nlfileeditor.model.BOOL_ID_BASE
import com.notlexi.nlfileeditor.model.BoolItem
import com.notlexi.nlfileeditor.model.BooleanEntry
import com.notlexi.nlfileeditor.model.CODE_NOTICE
import com.notlexi.nlfileeditor.model.EditItem
import com.notlexi.nlfileeditor.model.EditorTab
import com.notlexi.nlfileeditor.model.EditorUiState
import com.notlexi.nlfileeditor.model.FileFormat
import com.notlexi.nlfileeditor.model.LoadedFile
import com.notlexi.nlfileeditor.model.SearchMode
import com.notlexi.nlfileeditor.model.UiEvent
import com.notlexi.nlfileeditor.parser.NumberParser
import com.notlexi.nlfileeditor.parser.ValuePatcher
import com.notlexi.nlfileeditor.util.AppDataScanner
import com.notlexi.nlfileeditor.util.FileTooLargeException
import com.notlexi.nlfileeditor.util.FileUtils
import com.notlexi.nlfileeditor.util.InstalledApps
import com.notlexi.nlfileeditor.util.NumberUtils
import com.notlexi.nlfileeditor.util.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.math.BigDecimal
import java.nio.charset.Charset

/** Which editor section is open and what is typed in each search box. */
private data class SearchState(
    val tab: EditorTab = EditorTab.VALUES,
    val mode: SearchMode = SearchMode.NAME,
    val query: String = "",
    val boolQuery: String = ""
)

/** Result of the on-demand True/False scan. [entries] is null until the scan has finished. */
private data class BoolScan(
    val entries: List<BooleanEntry>? = null,
    val loading: Boolean = false
)

/** Holds all app state: which screen we are on, the loaded file and the user's edits. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences("nl_prefs", Context.MODE_PRIVATE)

    // ---- Theme (dark is the default) ----
    private val _darkTheme = MutableStateFlow(prefs.getBoolean("dark", true))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    fun toggleTheme() {
        val next = !_darkTheme.value
        _darkTheme.value = next
        prefs.edit().putBoolean("dark", next).apply()
    }

    // ---- Game detection + remembered folder, and the Search / Changes workspace ----
    val game = GameModel(app, viewModelScope)
    val tmxFull = com.notlexi.nlfileeditor.tmx.TmxFullModel(app, viewModelScope)
    val workspace = WorkspaceModel(app, viewModelScope) { game.state.value.folder }

    init {
        // A different folder means every earlier result / baseline belongs to somewhere else: drop them.
        viewModelScope.launch {
            game.state.map { it.folder?.tree }.distinctUntilChanged().drop(1).collect { workspace.reset() }
        }
        game.refresh()
    }

    fun openWorkspace(tab: Int = 0) {
        workspace.setTab(tab)
        if (_phase.value != AppPhase.Workspace) go(AppPhase.Workspace)
    }

    fun openTmx() = go(AppPhase.TmxHome)
    fun openTmxLight() = go(AppPhase.TmxLight)
    fun openTmxFull() = go(AppPhase.TmxFull)

    // ---- Screen / phase ----
    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Home)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    /**
     * The screens below the current one. Home -> Browse Apps -> files -> editor is a real stack:
     * [back] always returns to the previous screen and everything it needs (the scanned file list,
     * the installed apps) stays in memory.
     */
    private val backStack = ArrayList<AppPhase>()

    /** Text of the full-screen "working…" overlay, or null when nothing is running. */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    private fun go(next: AppPhase, replaceCurrent: Boolean = false) {
        if (!replaceCurrent) backStack.add(_phase.value)
        _phase.value = next
    }

    /** One step back (system Back button and the arrow in every top bar). */
    fun back() {
        when (_phase.value) {
            is AppPhase.Editor -> closeFile()
            is AppPhase.HtmlViewer -> closeHtml()
            else -> pop()
        }
    }

    private fun pop() {
        val previous = if (backStack.isEmpty()) AppPhase.Home else backStack.removeAt(backStack.size - 1)
        _phase.value = previous
        if (previous == AppPhase.Home) {
            // Left the Browse Apps flow: forget the chosen app and its files (the app list stays cached).
            _browse.update { it.copy(selectedApp = null, candidates = emptyList()) }
        }
    }

    // ---- Browse Apps ----
    private val _browse = MutableStateFlow(BrowseState())
    val browse: StateFlow<BrowseState> = _browse.asStateFlow()

    // ---- File + edits ----
    private val loaded = MutableStateFlow<LoadedFile?>(null)

    /** Numeric edits (id = entry index) AND True/False edits (id >= BOOL_ID_BASE) live together here. */
    private val edits = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val search = MutableStateFlow(SearchState())
    private val boolScan = MutableStateFlow(BoolScan())
    private val saving = MutableStateFlow(false)

    private var pendingBytes: ByteArray? = null
    var savedUri: Uri? = null
        private set

    /** MIME type of the loaded file, used for the save dialog and sharing. */
    val mimeType: String
        get() = loaded.value?.mimeType ?: "application/octet-stream"

    val editor: StateFlow<EditorUiState> =
        combine(loaded, edits, search, boolScan, saving) { file, e, s, b, sv -> buildState(file, e, s, b, sv) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    // ------------------------------------------------------------------ loading

    fun onFilePicked(uri: Uri) {
        if (_busy.value != null) return
        _busy.value = "Reading & scanning file…"
        viewModelScope.launch {
            // v6: no artificial minimum delay (v5 padded every open to 700 ms, which felt like lag).
            val outcome = withContext(Dispatchers.IO) { readAndParse(uri) }

            when (outcome) {
                is Outcome.Ok -> {
                    loaded.value = outcome.file
                    edits.value = emptyMap()
                    // A file with only True/False values opens straight on that tab.
                    val onlyBooleans = outcome.file.entries.isEmpty()
                    search.value = SearchState(tab = if (onlyBooleans) EditorTab.BOOLEANS else EditorTab.VALUES)
                    boolScan.value = BoolScan(entries = outcome.booleans, loading = false)
                    // Opened from the "can't open folder" screen? That screen is replaced, not kept.
                    go(AppPhase.Editor, replaceCurrent = _phase.value is AppPhase.AppDataBlocked)
                }
                is Outcome.Fail -> go(AppPhase.Error(outcome.title, outcome.message))
            }
            _busy.value = null
        }
    }

    private sealed interface Outcome {
        /** [booleans] is only filled when the scan was already needed to open the file. */
        class Ok(val file: LoadedFile, val booleans: List<BooleanEntry>?) : Outcome
        class Fail(val title: String, val message: String) : Outcome
    }

    private fun readAndParse(uri: Uri): Outcome {
        val cr = getApplication<Application>().contentResolver
        return try {
            val name = FileUtils.displayName(cr, uri)
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val bytes = FileUtils.readBytes(cr, uri)

            if (bytes.isEmpty()) {
                return Outcome.Fail("Empty file", "This file has no content, so there is nothing to edit.")
            }
            if (TextCodec.looksBinary(bytes)) {
                return Outcome.Fail(
                    "Can't read this file",
                    "This looks like a binary file (not text). NL File Editor works with text-based files such as JSON, XML, config and save files."
                )
            }
            val decoded = TextCodec.decode(bytes)
            if (TextCodec.looksGarbled(decoded)) {
                return Outcome.Fail(
                    "Can't read this file",
                    "This file is mostly unreadable symbols, so it is probably binary or compressed. NL File Editor works with text-based files such as JSON, XML, config and save files."
                )
            }
            val result = NumberParser.parse(decoded.text)
            var prescannedBooleans: List<BooleanEntry>? = null
            if (result.entries.isEmpty()) {
                if (result.format == FileFormat.CODE) {
                    return Outcome.Fail(
                        "No editable values found",
                        "$CODE_NOTICE I couldn't find any plain constants in it."
                    )
                }
                // No numbers: the file may still hold True/False values.
                val bools = NumberParser.parseBooleans(decoded.text)
                if (bools.isEmpty()) {
                    return Outcome.Fail(
                        "No values found",
                        "I couldn't find any named numbers or True/False values in this file. Try a different file."
                    )
                }
                prescannedBooleans = bools
            }
            Outcome.Ok(
                LoadedFile(
                    name = name,
                    mimeType = mime,
                    text = decoded.text,
                    charsetName = decoded.charset.name(),
                    entries = result.entries,
                    format = result.format
                ),
                prescannedBooleans
            )
        } catch (e: FileTooLargeException) {
            Outcome.Fail("File too large", "This file is bigger than 30 MB, which is too large to edit safely.")
        } catch (e: OutOfMemoryError) {
            Outcome.Fail("Not enough memory", "This file is too large for your device to open.")
        } catch (e: Exception) {
            Outcome.Fail("Couldn't open the file", e.message ?: "The file could not be read.")
        }
    }

    // ------------------------------------------------------------------ browse apps

    /** Home -> "Browse Apps": shows the installed apps (listed once, then cached). */
    fun openBrowseApps() {
        go(AppPhase.AppList)
        if (_browse.value.apps == null) {
            viewModelScope.launch {
                val apps = withContext(Dispatchers.Default) { InstalledApps.load(getApplication()) }
                _browse.update { it.copy(apps = apps) }
            }
        }
    }

    /** The user tapped an app; the UI now opens the folder picker for it. */
    fun selectApp(app: InstalledApp) {
        _browse.update { it.copy(selectedApp = app, candidates = emptyList()) }
    }

    /** Called with the folder the user chose in the system folder picker. */
    fun onAppDataFolderPicked(tree: Uri) {
        if (_busy.value != null) return
        val appLabel = _browse.value.selectedApp?.label ?: "that app"
        _busy.value = "Looking inside $appLabel…"
        viewModelScope.launch {
            val cr = getApplication<Application>().contentResolver
            val result = withContext(Dispatchers.IO) {
                try {
                    var lastTick = 0L
                    AppDataScanner.scan(cr, tree) { done, total ->
                        // At most ~8 overlay updates per second; every update recomposes the overlay.
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastTick >= 120 || done >= total) {
                            lastTick = now
                            _busy.value = "Checking files… $done / $total"
                        }
                    }
                } catch (e: Exception) {
                    AppDataScanner.Result.Inaccessible
                }
            }
            when (result) {
                is AppDataScanner.Result.Found -> {
                    // Kept in state: Back from the editor returns to this list without re-scanning.
                    _browse.update { it.copy(candidates = result.candidates) }
                    go(AppPhase.AppFiles)
                }
                AppDataScanner.Result.Inaccessible -> go(
                    AppPhase.AppDataBlocked(
                        "Can't open this folder",
                        "Android doesn't let apps look inside $appLabel's data folder on this version of Android " +
                            "or on this device, or the folder is empty.\n\n" +
                            "You can still edit its files: copy the file out with a file manager, then choose it with “Upload File”."
                    )
                )
                AppDataScanner.Result.NoEditableData -> go(
                    AppPhase.AppDataBlocked(
                        "No editable data found",
                        "I looked through $appLabel's folder and opened its files, but none of them contain numbers " +
                            "or True/False values that can be edited.\n\n" +
                            "The game may keep its save somewhere Android doesn't share, or in a binary file. " +
                            "You can still pick a file yourself with “Upload File”."
                    )
                )
            }
            _busy.value = null
        }
    }

    /** The device has no folder picker we can open. */
    fun onBrowseUnavailable() {
        go(
            AppPhase.AppDataBlocked(
                "Can't open the folder picker",
                "This device doesn't offer a way to browse app folders from here.\n\n" +
                    "You can still edit a file: copy it out with a file manager, then choose it with “Upload File”."
            )
        )
    }

    /** The user tapped a file in the list. */
    fun onCandidateChosen(candidate: AppDataCandidate) {
        onFilePicked(Uri.parse(candidate.uriString))
    }

    // ------------------------------------------------------------------ editor tabs + search

    /** Switches between the numbers list and the True/False list. The True/False scan runs on first open. */
    fun selectTab(tab: EditorTab) {
        search.value = search.value.copy(tab = tab)
        if (tab == EditorTab.BOOLEANS) ensureBooleansScanned()
    }

    /** Name / Number search. Switching clears the search box. */
    fun setSearchMode(mode: SearchMode) {
        val s = search.value
        if (s.mode == mode) return
        search.value = s.copy(mode = mode, query = "")
    }

    /** Sets the text of the search box that belongs to the open tab. */
    fun setQuery(q: String) {
        val s = search.value
        search.value = when (s.tab) {
            EditorTab.VALUES -> s.copy(
                query = if (s.mode == SearchMode.NUMBER) q.filter { it.isDigit() || it == '-' || it == '.' } else q
            )
            EditorTab.BOOLEANS -> s.copy(boolQuery = q)
        }
    }

    private fun ensureBooleansScanned() {
        val file = loaded.value ?: return
        val current = boolScan.value
        if (current.entries != null || current.loading) return
        boolScan.value = BoolScan(entries = null, loading = true)
        viewModelScope.launch {
            val found = withContext(Dispatchers.Default) {
                try {
                    NumberParser.parseBooleans(file.text)
                } catch (e: Exception) {
                    emptyList()
                }
            }
            if (loaded.value === file) boolScan.value = BoolScan(entries = found, loading = false)
        }
    }

    // ------------------------------------------------------------------ editing

    fun onValueChange(id: Int, text: String) {
        edits.value = edits.value + (id to text)
    }

    fun step(id: Int, delta: Int) {
        val entry = loaded.value?.entries?.getOrNull(id) ?: return
        val current = edits.value[id] ?: entry.original
        edits.value = edits.value + (id to NumberUtils.step(current, entry, delta))
    }

    /** Flips a True/False value. The edit is stored as text (True/False) in the same map as the numbers. */
    fun setBoolean(id: Int, value: Boolean) {
        val entry = boolScan.value.entries?.getOrNull(id - BOOL_ID_BASE)?.takeIf { it.id == id } ?: return
        val text = entry.textFor(value)
        edits.value = if (text == entry.original) edits.value - id else edits.value + (id to text)
    }

    fun reset(id: Int) {
        edits.value = edits.value - id
    }

    fun resetAll() {
        edits.value = emptyMap()
    }

    // ------------------------------------------------------------------ saving

    fun requestSave() {
        val file = loaded.value ?: return
        val booleans = boolScan.value.entries.orEmpty()
        val state = buildState(file, edits.value, search.value, boolScan.value, false)
        when {
            state.hasErrors -> { _events.tryEmit(UiEvent.Message("Fix the highlighted values first")); return }
            state.changedCount == 0 -> { _events.tryEmit(UiEvent.Message("Nothing has changed yet")); return }
        }
        val snapshot = edits.value
        viewModelScope.launch {
            try {
                pendingBytes = withContext(Dispatchers.Default) {
                    val patched = ValuePatcher.apply(file.text, file.entries, snapshot, booleans)
                    patched.toByteArray(Charset.forName(file.charsetName))
                }
                _events.emit(UiEvent.LaunchSave(FileUtils.editedName(file.name)))
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Couldn't build the file: ${e.message}"))
            }
        }
    }

    /** Called with the location the user chose in the "Create document" dialog (null = cancelled). */
    fun onSaveLocation(uri: Uri?) {
        val bytes = pendingBytes
        if (uri == null || bytes == null) return
        saving.value = true
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                withContext(Dispatchers.IO) { FileUtils.writeBytes(cr, uri, bytes) }
                savedUri = uri
                val name = withContext(Dispatchers.IO) { FileUtils.displayName(cr, uri) }
                go(AppPhase.Saved(name))
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Save failed: ${e.message ?: "unknown error"}"))
            } finally {
                saving.value = false
            }
        }
    }

    // ------------------------------------------------------------------ navigation helpers

    /** From the "Saved" screen back to the editor. */
    fun keepEditing() {
        pop()
    }

    /** Leaves the editor, forgets the file and returns to where it was opened from (file list or Home). */
    fun closeFile() {
        loaded.value = null
        edits.value = emptyMap()
        search.value = SearchState()
        boolScan.value = BoolScan()
        pendingBytes = null
        savedUri = null
        // Editor (and the Saved screen above it) are popped; the screen below is shown again.
        while (_phase.value is AppPhase.Editor || _phase.value is AppPhase.Saved) pop()
    }

    /** Back from an error / "can't open folder" screen. */
    fun dismissError() {
        pop()
    }

    // ------------------------------------------------------------------ HTML viewer

    private val _html = MutableStateFlow<HtmlDoc?>(null)
    /** The file open in the HTML Viewer (its saved content); null until the user uploads one. */
    val htmlDoc: StateFlow<HtmlDoc?> = _html.asStateFlow()

    /** What is currently typed in the Code tab. A plain field: the text box owns the live text. */
    var htmlDraft: String = ""
        private set

    private var htmlLoads = 0

    private val _htmlDirty = MutableStateFlow(false)
    val htmlDirty: StateFlow<Boolean> = _htmlDirty.asStateFlow()

    private val _htmlSaving = MutableStateFlow(false)
    val htmlSaving: StateFlow<Boolean> = _htmlSaving.asStateFlow()

    private val _htmlSaveRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    /** Emits the suggested file name when the system "Create document" dialog should open. */
    val htmlSaveRequests: SharedFlow<String> = _htmlSaveRequests.asSharedFlow()

    // ---- HTML resource folder (lets a page find its images / CSS / fonts next to it) ----
    private val _htmlTree = MutableStateFlow(loadHtmlTree())
    val htmlTree: StateFlow<Uri?> = _htmlTree.asStateFlow()

    private fun loadHtmlTree(): Uri? {
        val saved = prefs.getString("html_tree", null) ?: return null
        val uri = runCatching { Uri.parse(saved) }.getOrNull() ?: return null
        val held = getApplication<Application>().contentResolver.persistedUriPermissions
            .any { it.uri == uri && it.isReadPermission }
        return if (held) uri else null
    }

    /** Remembers the folder the user granted for the HTML Viewer (null forgets it). */
    fun setHtmlTree(uri: Uri?) {
        val cr = getApplication<Application>().contentResolver
        val old = _htmlTree.value
        if (old != null && old != uri) {
            runCatching { cr.releasePersistableUriPermission(old, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        if (uri != null) {
            runCatching { cr.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        }
        prefs.edit().apply { if (uri == null) remove("html_tree") else putString("html_tree", uri.toString()) }.apply()
        _htmlTree.value = uri
    }

    fun openHtmlViewer() {
        go(AppPhase.HtmlViewer)
    }

    fun onHtmlPicked(uri: Uri) {
        if (_busy.value != null) return
        _busy.value = "Reading HTML…"
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) { readHtml(uri) }
            when (outcome) {
                is HtmlOutcome.Ok -> {
                    _html.value = outcome.doc.copy(loadId = ++htmlLoads)
                    htmlDraft = outcome.doc.text
                    _htmlDirty.value = false
                }
                is HtmlOutcome.Fail -> _events.emit(UiEvent.Message(outcome.message))
            }
            _busy.value = null
        }
    }

    private sealed interface HtmlOutcome {
        class Ok(val doc: HtmlDoc) : HtmlOutcome
        class Fail(val message: String) : HtmlOutcome
    }

    private fun readHtml(uri: Uri): HtmlOutcome {
        val cr = getApplication<Application>().contentResolver
        return try {
            val name = FileUtils.displayName(cr, uri)
            val bytes = FileUtils.readBytes(cr, uri)
            when {
                bytes.isEmpty() -> HtmlOutcome.Fail("That file is empty")
                bytes.size > MAX_HTML_BYTES -> HtmlOutcome.Fail("That file is too large for the HTML Viewer (limit 2 MB)")
                TextCodec.looksBinary(bytes) -> HtmlOutcome.Fail("That doesn't look like an HTML file")
                else -> {
                    val decoded = TextCodec.decode(bytes)
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val looksHtml = ext in setOf("html", "htm", "xhtml") || decoded.text.contains('<')
                    if (!looksHtml) {
                        HtmlOutcome.Fail("That doesn't look like an HTML file")
                    } else {
                        HtmlOutcome.Ok(HtmlDoc(name, decoded.text, decoded.charset.name()))
                    }
                }
            }
        } catch (e: FileTooLargeException) {
            HtmlOutcome.Fail("That file is too large to open")
        } catch (e: OutOfMemoryError) {
            HtmlOutcome.Fail("Not enough memory to open that file")
        } catch (e: Exception) {
            HtmlOutcome.Fail(e.message ?: "The file could not be read")
        }
    }

    /** Called on every change in the Code tab. */
    fun onHtmlEdited(text: String) {
        htmlDraft = text
        _htmlDirty.value = text != _html.value?.text
    }

    /** Save button of the Code tab: opens the same "Create document" dialog the editor uses. */
    fun requestHtmlSave() {
        val doc = _html.value ?: return
        if (!_htmlDirty.value) {
            _events.tryEmit(UiEvent.Message("Nothing has changed yet"))
            return
        }
        _htmlSaveRequests.tryEmit(FileUtils.editedName(doc.name))
    }

    /** Called with the location chosen in the "Create document" dialog (null = cancelled). */
    fun onHtmlSaveLocation(uri: Uri?) {
        val doc = _html.value ?: return
        if (uri == null) return
        val text = htmlDraft
        _htmlSaving.value = true
        viewModelScope.launch {
            try {
                val cr = getApplication<Application>().contentResolver
                val name = withContext(Dispatchers.IO) {
                    // A Latin-1 file can't hold other characters: write UTF-8 in that case.
                    val charset = if (doc.charsetName == Charsets.ISO_8859_1.name() && text.any { it.code > 255 }) {
                        Charsets.UTF_8
                    } else {
                        Charset.forName(doc.charsetName)
                    }
                    FileUtils.writeBytes(cr, uri, text.toByteArray(charset))
                    FileUtils.displayName(cr, uri)
                }
                _html.value = doc.copy(text = text)
                _htmlDirty.value = false
                _events.emit(UiEvent.Message("Saved $name"))
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Save failed: ${e.message ?: "unknown error"}"))
            } finally {
                _htmlSaving.value = false
            }
        }
    }

    /** Leaves the HTML Viewer and forgets the file. */
    fun closeHtml() {
        _html.value = null
        htmlDraft = ""
        _htmlDirty.value = false
        pop()
    }

    // ------------------------------------------------------------------ state building

    private fun buildState(
        file: LoadedFile?,
        edits: Map<Int, String>,
        search: SearchState,
        bools: BoolScan,
        saving: Boolean
    ): EditorUiState {
        if (file == null) return EditorUiState()

        // ---- numbers ----
        val q = search.query.trim()
        val qLower = q.lowercase()
        val numberMode = search.mode == SearchMode.NUMBER
        val target: BigDecimal? = if (numberMode && q.isNotEmpty()) q.toBigDecimalOrNull() else null

        var changed = 0
        var errors = false
        val items = ArrayList<EditItem>()
        for (entry in file.entries) {
            val current = edits[entry.id] ?: entry.original
            val error = if (edits.containsKey(entry.id)) NumberUtils.validate(current) else null
            val isChanged = edits.containsKey(entry.id) && current.trim() != entry.original
            if (error != null) errors = true
            if (isChanged && error == null) changed++
            val matches = when {
                q.isEmpty() -> true
                // Number mode: the CURRENT value must equal the typed number (300 also matches 300.0).
                numberMode -> {
                    val c = current.trim()
                    c == q || (target != null && c.toBigDecimalOrNull()?.compareTo(target) == 0)
                }
                else -> entry.label.lowercase().contains(qLower) || entry.section.lowercase().contains(qLower)
            }
            if (matches) items.add(EditItem(entry, current, error, isChanged))
        }

        // ---- True/False ----
        val boolEntries = bools.entries.orEmpty()
        val bq = search.boolQuery.trim().lowercase()
        var boolChanged = 0
        val boolItems = ArrayList<BoolItem>()
        for (b in boolEntries) {
            val text = edits[b.id] ?: b.original
            val isChanged = edits.containsKey(b.id) && text != b.original
            if (isChanged) boolChanged++
            if (bq.isEmpty() || b.label.lowercase().contains(bq)) {
                boolItems.add(BoolItem(b, text.equals("true", ignoreCase = true), isChanged))
            }
        }

        return EditorUiState(
            fileName = file.name,
            formatTitle = file.format.title,
            isCode = file.format == FileFormat.CODE,
            tab = search.tab,
            searchMode = search.mode,
            items = items,
            query = search.query,
            totalCount = file.entries.size,
            boolItems = boolItems,
            boolQuery = search.boolQuery,
            boolTotal = boolEntries.size,
            boolLoading = bools.loading,
            boolScanned = bools.entries != null,
            changedCount = changed + boolChanged,
            hasErrors = errors,
            saving = saving
        )
    }

    private companion object {
        const val MAX_HTML_BYTES = 2 * 1024 * 1024
    }
}
