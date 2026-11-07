package com.notlexi.nlfileeditor.model

/** Which parser produced the list of values. */
enum class FileFormat(val title: String) { JSON("JSON"), XML("XML"), TEXT("Text"), CODE("Code") }

/** Section name used for numbers found in source code (e.g. minified JavaScript). */
const val CODE_SECTION = "Code constants"

/** Message shown on the editor when the file turned out to be source code, not a save file. */
const val CODE_NOTICE =
    "This looks like app/SDK source code, not a save file — showing possible config constants only."

/**
 * True/False entries get ids starting here, so numeric ids (0 until 50 000) and boolean ids can
 * live together in the single "edited values" map without ever colliding.
 */
const val BOOL_ID_BASE = 1_000_000

/**
 * One editable number found in the file.
 * [start] (inclusive) and [end] (exclusive) are character positions in the original text,
 * so saving only has to swap that exact range.
 */
data class NumberEntry(
    val id: Int,
    val label: String,
    val section: String,
    val start: Int,
    val end: Int,
    val original: String
) {
    /** Number of digits after the decimal point in the original text. */
    val decimals: Int get() = original.substringAfter('.', "").length
}

/**
 * One True/False value found in the file. Same idea as [NumberEntry]: [start]/[end] is the exact
 * range of the word (True, false, TRUE...) in the original text.
 */
data class BooleanEntry(
    val id: Int,
    val label: String,
    val start: Int,
    val end: Int,
    val original: String
) {
    val originalValue: Boolean get() = original.equals("true", ignoreCase = true)

    /** The replacement text for [value], written in the same style as the original (True / true / TRUE). */
    fun textFor(value: Boolean): String {
        val word = if (value) "true" else "false"
        return when {
            original.length > 1 && original.none { it.isLowerCase() } -> word.uppercase()
            original.firstOrNull()?.isUpperCase() == true -> word.replaceFirstChar { it.uppercase() }
            else -> word
        }
    }
}

data class ParseResult(val entries: List<NumberEntry>, val format: FileFormat)

/** A file that has been read and scanned, kept in memory while the user edits. */
data class LoadedFile(
    val name: String,
    val mimeType: String,
    val text: String,
    val charsetName: String,
    val entries: List<NumberEntry>,
    val format: FileFormat
)

/** What the editor list shows for one number. */
data class EditItem(
    val entry: NumberEntry,
    val current: String,
    val error: String?,
    val changed: Boolean
)

/** What the True/False list shows for one boolean. */
data class BoolItem(
    val entry: BooleanEntry,
    val current: Boolean,
    val changed: Boolean
)

/** The two top-level sections of the editor. */
enum class EditorTab { VALUES, BOOLEANS }

/** How the search box filters the numeric list. */
enum class SearchMode { NAME, NUMBER }

data class EditorUiState(
    val fileName: String = "",
    val formatTitle: String = "",
    val isCode: Boolean = false,
    val tab: EditorTab = EditorTab.VALUES,
    val searchMode: SearchMode = SearchMode.NAME,
    // numbers
    val items: List<EditItem> = emptyList(),
    val query: String = "",
    val totalCount: Int = 0,
    // True/False
    val boolItems: List<BoolItem> = emptyList(),
    val boolQuery: String = "",
    val boolTotal: Int = 0,
    val boolLoading: Boolean = false,
    val boolScanned: Boolean = false,
    // shared
    val changedCount: Int = 0,
    val hasErrors: Boolean = false,
    val saving: Boolean = false
)

/** An installed app shown in the "Browse Apps" list. */
data class InstalledApp(val packageName: String, val label: String)

/** A possible save file found inside an app's data folder, with how many values it really holds. */
data class AppDataCandidate(
    val uriString: String,
    val name: String,
    /** Folder path inside the picked folder, e.g. "files/" or "shared_prefs/". */
    val folder: String,
    val size: Long,
    /** Detected editable numbers + True/False values (from reading the file content). */
    val valueCount: Int,
    /** True when only the first part of a big file was checked, so the real count is higher. */
    val truncated: Boolean,
    val score: Int,
    /** The most likely save file (shown with a "Best match" badge). */
    val recommended: Boolean
)

/** Everything the Browse Apps flow keeps while the user moves back and forth between its screens. */
data class BrowseState(
    /** null while the installed apps are still being listed. */
    val apps: List<InstalledApp>? = null,
    val selectedApp: InstalledApp? = null,
    /** Already-scanned files of [selectedApp]; kept so Back from the editor never re-scans. */
    val candidates: List<AppDataCandidate> = emptyList()
)

/** The file opened in the HTML Viewer. [text] is the content as it was on disk (the saved baseline). */
data class HtmlDoc(
    val name: String,
    val text: String,
    val charsetName: String,
    /** Changes every time a file is loaded, so the Code tab knows when to start over. */
    val loadId: Int = 0
)

/** Which screen the app is on. Loading / scanning is NOT a screen: see MainViewModel.busy. */
sealed interface AppPhase {
    data object Home : AppPhase
    /** Installed apps list ("Browse Apps"). */
    data object AppList : AppPhase
    /** Files of the chosen app, with detected-value counts. */
    data object AppFiles : AppPhase
    data class AppDataBlocked(val title: String, val message: String) : AppPhase
    data object HtmlViewer : AppPhase
    /** Search + Changes for the selected game folder. */
    data object Workspace : AppPhase
    /** NL TMX: the Light / Full chooser and the two terminals. */
    data object TmxHome : AppPhase
    data object TmxLight : AppPhase
    data object TmxFull : AppPhase
    data object Editor : AppPhase
    data class Saved(val savedName: String) : AppPhase
    data class Error(val title: String, val message: String) : AppPhase
}

/** One-shot events from the ViewModel to the UI. */
sealed interface UiEvent {
    data class LaunchSave(val suggestedName: String) : UiEvent
    data class Message(val text: String) : UiEvent
}
