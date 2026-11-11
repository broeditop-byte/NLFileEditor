package com.notlexi.nlfileeditor.model

/** Which parser produced the list of values. */
enum class FileFormat(val title: String) { JSON("JSON"), XML("XML"), TEXT("Text") }

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

/** What the editor list shows for one value. */
data class EditItem(
    val entry: NumberEntry,
    val current: String,
    val error: String?,
    val changed: Boolean
)

data class EditorUiState(
    val fileName: String = "",
    val formatTitle: String = "",
    val items: List<EditItem> = emptyList(),
    val query: String = "",
    val totalCount: Int = 0,
    val changedCount: Int = 0,
    val hasErrors: Boolean = false,
    val saving: Boolean = false
)

/** Which screen the app is on. */
sealed interface AppPhase {
    data object Home : AppPhase
    data object Loading : AppPhase
    data object Editor : AppPhase
    data class Saved(val savedName: String) : AppPhase
    data class Error(val title: String, val message: String) : AppPhase
}

/** One-shot events from the ViewModel to the UI. */
sealed interface UiEvent {
    data class LaunchSave(val suggestedName: String) : UiEvent
    data class Message(val text: String) : UiEvent
}
