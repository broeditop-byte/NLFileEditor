package com.notlexi.nlfileeditor.viewmodel

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.notlexi.nlfileeditor.model.AppPhase
import com.notlexi.nlfileeditor.model.EditItem
import com.notlexi.nlfileeditor.model.EditorUiState
import com.notlexi.nlfileeditor.model.LoadedFile
import com.notlexi.nlfileeditor.model.UiEvent
import com.notlexi.nlfileeditor.parser.NumberParser
import com.notlexi.nlfileeditor.parser.ValuePatcher
import com.notlexi.nlfileeditor.util.FileTooLargeException
import com.notlexi.nlfileeditor.util.FileUtils
import com.notlexi.nlfileeditor.util.NumberUtils
import com.notlexi.nlfileeditor.util.TextCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.charset.Charset

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

    // ---- Screen / phase ----
    private val _phase = MutableStateFlow<AppPhase>(AppPhase.Home)
    val phase: StateFlow<AppPhase> = _phase.asStateFlow()

    private val _events = MutableSharedFlow<UiEvent>(extraBufferCapacity = 8)
    val events: SharedFlow<UiEvent> = _events.asSharedFlow()

    // ---- File + edits ----
    private val loaded = MutableStateFlow<LoadedFile?>(null)
    private val edits = MutableStateFlow<Map<Int, String>>(emptyMap())
    private val query = MutableStateFlow("")
    private val saving = MutableStateFlow(false)

    private var pendingBytes: ByteArray? = null
    var savedUri: Uri? = null
        private set

    /** MIME type of the loaded file, used for the save dialog and sharing. */
    val mimeType: String
        get() = loaded.value?.mimeType ?: "application/octet-stream"

    val editor: StateFlow<EditorUiState> =
        combine(loaded, edits, query, saving) { file, e, q, s -> buildState(file, e, q, s) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), EditorUiState())

    // ------------------------------------------------------------------ loading

    fun onFilePicked(uri: Uri) {
        if (_phase.value == AppPhase.Loading) return
        _phase.value = AppPhase.Loading
        viewModelScope.launch {
            val started = System.currentTimeMillis()
            val outcome = withContext(Dispatchers.IO) { readAndParse(uri) }
            // Keep the loading animation visible for a moment so it never just flashes.
            val elapsed = System.currentTimeMillis() - started
            if (elapsed < 700) delay(700 - elapsed)

            when (outcome) {
                is Outcome.Ok -> {
                    loaded.value = outcome.file
                    edits.value = emptyMap()
                    query.value = ""
                    _phase.value = AppPhase.Editor
                }
                is Outcome.Fail -> _phase.value = AppPhase.Error(outcome.title, outcome.message)
            }
        }
    }

    private sealed interface Outcome {
        class Ok(val file: LoadedFile) : Outcome
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
            val result = NumberParser.parse(decoded.text)
            if (result.entries.isEmpty()) {
                return Outcome.Fail("No numbers found", "I couldn't find any named numbers in this file. Try a different file.")
            }
            Outcome.Ok(
                LoadedFile(
                    name = name,
                    mimeType = mime,
                    text = decoded.text,
                    charsetName = decoded.charset.name(),
                    entries = result.entries,
                    format = result.format
                )
            )
        } catch (e: FileTooLargeException) {
            Outcome.Fail("File too large", "This file is bigger than 30 MB, which is too large to edit safely.")
        } catch (e: OutOfMemoryError) {
            Outcome.Fail("Not enough memory", "This file is too large for your device to open.")
        } catch (e: Exception) {
            Outcome.Fail("Couldn't open the file", e.message ?: "The file could not be read.")
        }
    }

    // ------------------------------------------------------------------ editing

    fun setQuery(q: String) {
        query.value = q
    }

    fun onValueChange(id: Int, text: String) {
        edits.value = edits.value + (id to text)
    }

    fun step(id: Int, delta: Int) {
        val entry = loaded.value?.entries?.getOrNull(id) ?: return
        val current = edits.value[id] ?: entry.original
        edits.value = edits.value + (id to NumberUtils.step(current, entry, delta))
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
        val state = buildState(file, edits.value, query.value, false)
        when {
            state.hasErrors -> { _events.tryEmit(UiEvent.Message("Fix the highlighted values first")); return }
            state.changedCount == 0 -> { _events.tryEmit(UiEvent.Message("Nothing has changed yet")); return }
        }
        val snapshot = edits.value
        viewModelScope.launch {
            try {
                pendingBytes = withContext(Dispatchers.Default) {
                    val patched = ValuePatcher.apply(file.text, file.entries, snapshot)
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
                _phase.value = AppPhase.Saved(name)
            } catch (e: Exception) {
                _events.emit(UiEvent.Message("Save failed: ${e.message ?: "unknown error"}"))
            } finally {
                saving.value = false
            }
        }
    }

    // ------------------------------------------------------------------ navigation helpers

    fun keepEditing() {
        _phase.value = AppPhase.Editor
    }

    /** Leaves the editor and forgets the file. */
    fun closeFile() {
        loaded.value = null
        edits.value = emptyMap()
        query.value = ""
        pendingBytes = null
        savedUri = null
        _phase.value = AppPhase.Home
    }

    fun dismissError() {
        _phase.value = AppPhase.Home
    }

    // ------------------------------------------------------------------ state building

    private fun buildState(
        file: LoadedFile?,
        edits: Map<Int, String>,
        query: String,
        saving: Boolean
    ): EditorUiState {
        if (file == null) return EditorUiState()
        val q = query.trim().lowercase()
        var changed = 0
        var errors = false
        val items = ArrayList<EditItem>()
        for (entry in file.entries) {
            val current = edits[entry.id] ?: entry.original
            val error = if (edits.containsKey(entry.id)) NumberUtils.validate(current) else null
            val isChanged = edits.containsKey(entry.id) && current.trim() != entry.original
            if (error != null) errors = true
            if (isChanged && error == null) changed++
            val matches = q.isEmpty() ||
                entry.label.lowercase().contains(q) ||
                entry.section.lowercase().contains(q)
            if (matches) items.add(EditItem(entry, current, error, isChanged))
        }
        return EditorUiState(
            fileName = file.name,
            formatTitle = file.format.title,
            items = items,
            query = query,
            totalCount = file.entries.size,
            changedCount = changed,
            hasErrors = errors,
            saving = saving
        )
    }
}
