package com.notlexi.nlfileeditor.hud

import android.net.Uri
import android.util.Log
import com.notlexi.nlfileeditor.util.TextCodec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.Charset

/** Largest file the HUD will load into memory (per snapshot). */
const val MAX_SNAPSHOT_BYTES = 8 * 1024 * 1024

/** Shown in the Detected Changes panel when both snapshots were read but no value differs. */
const val NO_CHANGES_MESSAGE =
    "No changes detected \u2014 try playing longer or performing an action that changes your coins/score."

/** One read of the target file, kept in memory exactly as it was on disk. */
class Snapshot private constructor(
    val bytes: ByteArray,
    /** Decoded text, or null when the file looks binary / cannot be decoded losslessly. */
    val text: String?,
    val charset: Charset,
    /** Byte-order mark that was in front of the text (UTF-16), kept so it can be written back. */
    private val bom: ByteArray,
    val lastModified: Long?,
    val takenAtMillis: Long
) {
    val isEmpty: Boolean get() = bytes.isEmpty()
    val isBinary: Boolean get() = text == null

    fun charsetLabel(): String = charset.displayName()

    fun encode(newText: String): ByteArray = bom + newText.toByteArray(charset)

    companion object {
        private val NO_BOM = ByteArray(0)

        fun from(bytes: ByteArray, lastModified: Long?): Snapshot {
            val now = System.currentTimeMillis()
            if (bytes.isEmpty()) return Snapshot(bytes, "", Charsets.UTF_8, NO_BOM, lastModified, now)

            val bom: ByteArray
            val charset: Charset
            val text: String
            val b0 = bytes[0]
            val b1: Byte = if (bytes.size > 1) bytes[1] else 0.toByte()
            if (bytes.size >= 2 && b0 == 0xFF.toByte() && b1 == 0xFE.toByte()) {
                bom = bytes.copyOfRange(0, 2)
                charset = Charsets.UTF_16LE
                text = String(bytes, 2, bytes.size - 2, charset)
            } else if (bytes.size >= 2 && b0 == 0xFE.toByte() && b1 == 0xFF.toByte()) {
                bom = bytes.copyOfRange(0, 2)
                charset = Charsets.UTF_16BE
                text = String(bytes, 2, bytes.size - 2, charset)
            } else {
                if (TextCodec.looksBinary(bytes)) return binary(bytes, lastModified, now)
                val decoded = TextCodec.decode(bytes)
                if (TextCodec.looksGarbled(decoded)) return binary(bytes, lastModified, now)
                bom = NO_BOM
                charset = decoded.charset
                text = decoded.text
            }
            // Safety net: only treat as text if writing it back reproduces the file byte for byte.
            if (!(bom + text.toByteArray(charset)).contentEquals(bytes)) return binary(bytes, lastModified, now)
            return Snapshot(bytes, text, charset, bom, lastModified, now)
        }

        private fun binary(bytes: ByteArray, lastModified: Long?, now: Long) =
            Snapshot(bytes, null, Charsets.ISO_8859_1, NO_BOM, lastModified, now)
    }
}

/** Explicit states: conflicting buttons are ignored while the engine is busy. */
enum class HudPhase { IDLE, SELECTING_FILE, CAPTURING, RECORDING, DIFFING, READY, PATCHING, ERROR }

data class HudUiState(
    /** True while a target URI is selected, even if it could not be opened (so it can still be cleared). */
    val hasTarget: Boolean = false,
    /** True once Snapshot A exists and Snapshot B does not: the 5s / 10s Record buttons may be used. */
    val armed: Boolean = false,
    /** True when A and B were compared as text and no value differs (drives the "No changes" panel text). */
    val noChanges: Boolean = false,
    val targetName: String? = null,
    val canWrite: Boolean = false,
    val phase: HudPhase = HudPhase.IDLE,
    val countdown: Int = 0,
    val totalSeconds: Int = 0,
    val hasSnapshotA: Boolean = false,
    val hasSnapshotB: Boolean = false,
    val encodingLabel: String? = null,
    val formatLabel: String? = null,
    /** Diff Mode: false = numbers only, true = numbers and True/False values. */
    val showAll: Boolean = false,
    val changes: List<ChangedValue> = emptyList(),
    val hiddenByMode: Int = 0,
    val ignoredTimeLike: Int = 0,
    val skippedAmbiguous: Int = 0,
    val truncated: Boolean = false,
    val selected: ChangedValue? = null,
    val pending: String? = null,
    val message: String? = null,
    val messageIsError: Boolean = false
) {
    val busy: Boolean
        get() = phase == HudPhase.CAPTURING || phase == HudPhase.RECORDING ||
            phase == HudPhase.DIFFING || phase == HudPhase.PATCHING
}

private sealed interface PatchResult {
    data class Success(val written: String) : PatchResult
    data class Failure(val message: String) : PatchResult
}

/**
 * Takes the two snapshots, runs the diff and applies a patch. Owned by the HUD service; every
 * public function is called on the main thread and returns immediately (work runs in [scope],
 * file access on Dispatchers.IO, parsing on Dispatchers.Default).
 */
class SnapshotEngine(
    private val files: FileAccessManager,
    private val scope: CoroutineScope
) {
    private val _state = MutableStateFlow(HudUiState())
    val state: StateFlow<HudUiState> = _state.asStateFlow()

    private var target: Uri? = null
    private var snapA: Snapshot? = null
    private var snapB: Snapshot? = null
    private var allChanges: List<ChangedValue> = emptyList()
    private var ignoredTimeLike = 0
    private var skippedAmbiguous = 0
    private var truncated = false
    private var formatLabel: String? = null
    private var noChanges = false
    private var job: Job? = null

    // ------------------------------------------------------------------ target / housekeeping

    fun onTargetChanged(uri: Uri?) {
        job?.cancel()
        job = null
        target = uri
        snapA = null
        snapB = null
        resetResults()
        commit {
            HudUiState(
                showAll = it.showAll,
                targetName = if (uri == null) null else "…",
                message = if (uri == null) "No target file selected. Tap Select Target File." else "Target selected."
            )
        }
        if (uri == null) return
        scope.launch {
            val info = withContext(Dispatchers.IO) { runCatching { files.describe(uri) }.getOrNull() }
            if (target != uri) return@launch
            if (info == null || !info.canRead) {
                commit {
                    it.copy(
                        targetName = null,
                        phase = HudPhase.ERROR,
                        message = "The selected file is not accessible. Tap X to clear it, then select it again.",
                        messageIsError = true
                    )
                }
                return@launch
            }
            commit {
                it.copy(
                    targetName = info.name,
                    canWrite = info.canWrite,
                    message = if (info.canWrite) "Target ready: ${info.name}"
                    else "${info.name} is read-only: detection works, patching will not.",
                    messageIsError = false
                )
            }
        }
    }

    fun setSelectingFile(open: Boolean) {
        _state.update {
            when {
                open && !it.busy -> it.copy(phase = HudPhase.SELECTING_FILE)
                !open && it.phase == HudPhase.SELECTING_FILE ->
                    it.copy(phase = if (allChanges.isNotEmpty()) HudPhase.READY else HudPhase.IDLE)
                else -> it
            }
        }
    }

    fun postMessage(text: String, isError: Boolean) {
        commit { it.copy(message = text, messageIsError = isError) }
    }

    fun shutdown() {
        job?.cancel()
        job = null
    }

    // ------------------------------------------------------------------ snapshots

    /** "Take Snapshot 1": capture A (clears any previous B and results). */
    fun snapshot1() = captureA("Snapshot 1", "Do something in the game, then take Snapshot 2.")

    /**
     * "I Started The Game": arms the engine. Takes Snapshot A right now by re-reading the file
     * through its URI (a brand-new read, nothing cached). Record is only possible after this.
     */
    fun startGame() = captureA("Snapshot A", "Now tap 5s Record or 10s Record and play.")

    private fun captureA(label: String, nextStep: String) {
        if (state.value.busy) return
        val uri = needTarget() ?: return
        snapA = null
        snapB = null
        resetResults()
        commit {
            it.copy(
                phase = HudPhase.CAPTURING, totalSeconds = 0, countdown = 0,
                message = "Reading $label…", messageIsError = false, selected = null, pending = null
            )
        }
        launchOp {
            val snap = capture(uri)
            snapA = snap
            commit {
                it.copy(phase = HudPhase.IDLE, message = describeSnapshot(label, snap, nextStep), messageIsError = false)
            }
        }
    }

    /** "Take Snapshot 2": capture B and compare it with A. */
    fun snapshot2() {
        if (state.value.busy) return
        val uri = needTarget() ?: return
        if (snapA == null) {
            postMessage("Take Snapshot 1 first.", true)
            return
        }
        snapB = null
        resetResults()
        commit {
            it.copy(
                phase = HudPhase.CAPTURING, message = "Reading snapshot 2…", messageIsError = false,
                selected = null, pending = null
            )
        }
        launchOp {
            snapB = capture(uri)
            runDiff()
        }
    }

    /**
     * 5s / 10s Record. Requires Snapshot A (taken by "I Started The Game"). Counts down while the
     * user plays, then re-reads the SAME file as Snapshot B and runs the diff. Overlapping
     * sessions are refused.
     */
    fun record(seconds: Int) {
        if (state.value.busy) return
        val uri = needTarget() ?: return
        if (snapA == null) {
            postMessage("Tap \"I Started The Game\" first. It saves Snapshot A, then choose 5s or 10s.", true)
            return
        }
        if (snapB != null) {
            postMessage("Tap \"I Started The Game\" again to start a new recording.", true)
            return
        }
        resetResults()
        commit {
            it.copy(
                phase = HudPhase.RECORDING, totalSeconds = seconds, countdown = seconds,
                message = "Recording. Do your action in the game now.", messageIsError = false,
                selected = null, pending = null
            )
        }
        launchOp {
            RecordingTimer.countdown(seconds).collect { left ->
                commit { it.copy(countdown = left) }
            }
            commit { it.copy(phase = HudPhase.CAPTURING, countdown = 0, message = "Reading Snapshot B…") }
            snapB = capture(uri) // fresh read of the same URI
            runDiff()
        }
    }

    fun cancel() {
        if (!state.value.busy) return
        job?.cancel()
        job = null
        commit {
            it.copy(
                phase = if (allChanges.isNotEmpty()) HudPhase.READY else HudPhase.IDLE,
                countdown = 0, message = if (snapA != null && snapB == null) "Cancelled. Snapshot A is still saved: tap 5s or 10s Record to try again." else "Cancelled.",
                messageIsError = false
            )
        }
    }

    // ------------------------------------------------------------------ diff mode / selection / editing

    fun toggleDiffMode() {
        commit {
            it.copy(
                showAll = !it.showAll,
                message = if (!it.showAll) "Diff Mode: numbers and True/False" else "Diff Mode: numbers only",
                messageIsError = false
            )
        }
    }

    fun select(change: ChangedValue) {
        commit {
            if (it.busy) it
            else if (it.selected == change) it.copy(selected = null, pending = null)
            else it.copy(selected = change, pending = null)
        }
    }

    /** Called with the value typed in the edit dialog. Does not touch the file yet. */
    fun setPending(raw: String) {
        val change = state.value.selected
        if (change == null) {
            postMessage("Select a detected value first.", true)
            return
        }
        val error = ChangedValueEditor.validate(change, raw)
        if (error != null) {
            postMessage(error, true)
            return
        }
        val value = ChangedValueEditor.normalize(change, raw)
        commit {
            it.copy(
                pending = value,
                message = "${change.key}: ${change.newValue} → $value. Press Save / Patch to write it.",
                messageIsError = false
            )
        }
    }

    // ------------------------------------------------------------------ patching

    fun patch() {
        val s = state.value
        if (s.busy) return
        val change = s.selected
        val raw = s.pending
        if (change == null) {
            postMessage("Select a detected value first.", true)
            return
        }
        if (raw == null) {
            postMessage("Tap Edit Value and enter the new value first.", true)
            return
        }
        val uri = needTarget() ?: return
        val base = snapB
        if (base == null) {
            postMessage("No Snapshot 2 to patch against. Take new snapshots.", true)
            return
        }
        commit { it.copy(phase = HudPhase.PATCHING, message = "Writing…", messageIsError = false) }
        launchOp {
            val name = state.value.targetName ?: "file"
            when (val result = withContext(Dispatchers.IO) { doPatch(uri, name, change, raw, base) }) {
                is PatchResult.Failure -> failOp(result.message)
                is PatchResult.Success -> {
                    val fresh = captureOrNull(uri)
                    snapA = fresh
                    snapB = null
                    resetResults()
                    commit {
                        it.copy(
                            phase = HudPhase.IDLE, selected = null, pending = null,
                            message = "Patched ${change.key}: ${change.newValue} → ${result.written}. " +
                                "A fresh Snapshot 1 was taken. The game may ignore or overwrite this until it reloads the file.",
                            messageIsError = false
                        )
                    }
                }
            }
        }
    }

    /** Runs on Dispatchers.IO. Backs up, writes, verifies and rolls back when anything goes wrong. */
    private fun doPatch(uri: Uri, name: String, change: ChangedValue, raw: String, base: Snapshot): PatchResult {
        val validation = ChangedValueEditor.validate(change, raw)
        if (validation != null) return PatchResult.Failure(validation)
        if (!files.canWrite(uri)) {
            return PatchResult.Failure("This file is read-only for the app. Select it again and allow write access. Nothing was changed.")
        }

        val currentBytes = files.readBytes(uri, MAX_SNAPSHOT_BYTES)
        val current = Snapshot.from(currentBytes, null)
        val currentText = current.text
        if (current.isBinary || currentText == null) {
            return PatchResult.Failure("This file's format is not supported for patching (binary or unreadable). Nothing was changed.")
        }
        val located = ChangedValueEditor.locate(currentText, change, currentBytes.contentEquals(base.bytes))
            ?: return PatchResult.Failure("The file changed since the snapshot and the value could not be located safely. Take new snapshots and try again. Nothing was changed.")

        val replacement = ChangedValueEditor.normalize(change, raw)
        val newBytes = current.encode(ChangedValueEditor.replaceRange(currentText, located, replacement))

        try {
            files.backup(name, currentBytes)
        } catch (e: IOException) {
            return PatchResult.Failure("Could not create a backup, so nothing was changed.")
        }

        try {
            files.writeBytes(uri, newBytes)
        } catch (e: TargetAccessException) {
            return PatchResult.Failure(e.message ?: "Write was refused. Nothing was changed.")
        } catch (e: IOException) {
            val restored = restore(uri, currentBytes)
            return PatchResult.Failure(
                "Write failed (${e.message ?: "I/O error"}). " +
                    if (restored) "The original file was restored." else "Restoring the original failed; a backup is in the app's storage."
            )
        }

        val verified = try {
            files.readBytes(uri, MAX_SNAPSHOT_BYTES).contentEquals(newBytes)
        } catch (e: IOException) {
            false
        }
        if (!verified) {
            val restored = restore(uri, currentBytes)
            return PatchResult.Failure(
                "The file did not contain the expected data after writing. " +
                    if (restored) "The original file was restored." else "Restoring the original failed; a backup is in the app's storage."
            )
        }
        return PatchResult.Success(replacement)
    }

    private fun restore(uri: Uri, original: ByteArray): Boolean =
        try {
            files.writeBytes(uri, original)
            true
        } catch (e: IOException) {
            false
        }

    // ------------------------------------------------------------------ internals

    private fun needTarget(): Uri? {
        val uri = target
        if (uri == null) postMessage("Select a target file first.", true)
        return uri
    }

    private suspend fun capture(uri: Uri): Snapshot = withContext(Dispatchers.IO) {
        val bytes = files.readBytes(uri, MAX_SNAPSHOT_BYTES)
        Snapshot.from(bytes, files.lastModified(uri))
    }

    private suspend fun captureOrNull(uri: Uri): Snapshot? =
        try {
            capture(uri)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    private suspend fun runDiff() {
        val a = snapA ?: return
        val b = snapB ?: return
        commit { it.copy(phase = HudPhase.DIFFING, countdown = 0, message = "Comparing snapshots…", messageIsError = false) }

        if (a.bytes.contentEquals(b.bytes)) {
            noChanges = true
            finishDiff("$NO_CHANGES_MESSAGE The game may not write to this file until it saves.")
            return
        }
        if (a.isBinary || b.isBinary) {
            finishDiff("The file changed, but it looks binary or encrypted, so individual values can't be identified.")
            return
        }
        if (a.isEmpty || b.isEmpty) {
            finishDiff("The file was empty in one of the snapshots, so there is nothing to compare.")
            return
        }

        val result = withContext(Dispatchers.Default) { SnapshotDiff.compare(a.text.orEmpty(), b.text.orEmpty()) }
        allChanges = result.changes
        ignoredTimeLike = result.ignoredTimeLike
        skippedAmbiguous = result.skippedAmbiguous
        truncated = result.truncated
        formatLabel = result.format?.title
        noChanges = result.totalFound == 0

        val msg = buildString {
            if (result.changes.isEmpty()) {
                append(NO_CHANGES_MESSAGE)
                append(" The file changed, but no individual number differs")
                if (result.ignoredTimeLike > 0) append(" (only time-related ones, which are hidden)")
                append(".")
            } else {
                append("${result.totalFound} changed value(s) found.")
                if (result.ignoredTimeLike > 0) append(" ${result.ignoredTimeLike} time-related hidden.")
            }
            if (result.skippedAmbiguous > 0) append(" ${result.skippedAmbiguous} uncertain group(s) skipped.")
            if (result.usedFallback && result.changes.isNotEmpty()) append(" Labels are unknown for this format.")
            if (result.truncated) append(" Showing the first ${result.changes.size}.")
        }
        commit { it.copy(phase = HudPhase.READY, message = msg, messageIsError = false, selected = null, pending = null) }
    }

    private fun finishDiff(message: String) {
        commit { it.copy(phase = HudPhase.READY, message = message, messageIsError = false, selected = null, pending = null) }
    }

    private fun launchOp(block: suspend () -> Unit) {
        job = scope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SnapshotTooLargeException) {
                failOp("This file is larger than the HUD limit of ${MAX_SNAPSHOT_BYTES / (1024 * 1024)} MB, so it was not loaded.")
            } catch (e: TargetAccessException) {
                failOp(e.message ?: "The file could not be accessed.")
            } catch (e: IOException) {
                failOp("File error: ${e.message ?: e.javaClass.simpleName}")
            } catch (e: OutOfMemoryError) {
                failOp("Not enough memory to load this file safely.")
            } catch (e: Exception) {
                Log.e(TAG, "HUD operation failed", e)
                failOp("Unexpected error: ${e.javaClass.simpleName}")
            }
        }
    }

    private fun failOp(message: String) {
        commit { it.copy(phase = HudPhase.ERROR, countdown = 0, message = message, messageIsError = true) }
    }

    private fun resetResults() {
        allChanges = emptyList()
        ignoredTimeLike = 0
        skippedAmbiguous = 0
        truncated = false
        formatLabel = null
        noChanges = false
    }

    private fun visibleChanges(showAll: Boolean): List<ChangedValue> =
        if (showAll) allChanges else allChanges.filter { it.kind == ValueKind.NUMBER }

    /** Applies [transform], then refreshes every field derived from the engine's private data. */
    private fun commit(transform: (HudUiState) -> HudUiState) {
        _state.update { old ->
            val s = transform(old)
            val visible = visibleChanges(s.showAll)
            val sel = s.selected?.takeIf { it in visible }
            s.copy(
                changes = visible,
                hiddenByMode = allChanges.size - visible.size,
                ignoredTimeLike = ignoredTimeLike,
                skippedAmbiguous = skippedAmbiguous,
                truncated = truncated,
                formatLabel = formatLabel,
                selected = sel,
                pending = if (sel == null) null else s.pending,
                hasTarget = target != null,
                noChanges = noChanges,
                armed = snapA != null && snapB == null,
                hasSnapshotA = snapA != null,
                hasSnapshotB = snapB != null,
                encodingLabel = snapA?.charsetLabel()
            )
        }
    }

    private fun describeSnapshot(label: String, snap: Snapshot, nextStep: String): String = when {
        snap.isEmpty -> "$label saved, but the file is empty."
        snap.isBinary -> "$label saved, but this file looks binary or encrypted, so values can't be detected."
        else -> "$label saved (${formatSize(snap.bytes.size)}, ${snap.charsetLabel()}). $nextStep"
    }

    private fun formatSize(bytes: Int): String =
        if (bytes < 1024) "$bytes B" else if (bytes < 1024 * 1024) "${bytes / 1024} KB" else "%.1f MB".format(bytes / 1048576.0)

    private companion object {
        const val TAG = "NLHud"
    }
}
