package com.notlexi.nlfileeditor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notlexi.nlfileeditor.game.WorkspaceModel
import com.notlexi.nlfileeditor.search.ChangeKind
import com.notlexi.nlfileeditor.search.FileChange
import com.notlexi.nlfileeditor.search.HitKind
import com.notlexi.nlfileeditor.search.QueryMode
import com.notlexi.nlfileeditor.search.SearchHit
import com.notlexi.nlfileeditor.ui.GradientButton
import com.notlexi.nlfileeditor.ui.GradientPill
import com.notlexi.nlfileeditor.ui.SecondaryButton
import com.notlexi.nlfileeditor.ui.SlidingTabs
import com.notlexi.nlfileeditor.ui.bounceClick
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.util.formatFileSize
import com.notlexi.nlfileeditor.viewmodel.MainViewModel
import kotlinx.coroutines.launch

/**
 * Search + Changes for the selected game folder.
 *  - Search: real file search (file name / text / exact number) with live progress and cancel.
 *  - Changes: snapshot the folder, play the game, compare; shows the changed values with file and position.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceScreen(vm: MainViewModel) {
    val ws = vm.workspace
    val ui by ws.ui.collectAsStateWithLifecycle()
    val game by vm.game.state.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val folder = game.folder
    val scope = rememberCoroutineScope()

    BackHandler { if (ui.searching || ui.capturing) ws.cancelRunning() else vm.back() }

    Scaffold(
        containerColor = scheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Search & Changes", fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
                        if (folder != null) {
                            Text(
                                (folder.label.takeIf { folder.packageName != null } ?: "Folder") + " · " + folder.path,
                                fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = scheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { ws.cancelRunning(); vm.back() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { vm.game.requestPick(folder?.packageName?.let { "Android/data/$it" } ?: "Android/data", folder?.packageName) }) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Change folder", tint = NLColors.Blue)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = scheme.background)
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            if (folder == null) {
                NoFolder(
                    notice = game.notice,
                    onChoose = { vm.game.requestPick("Android/data", null) }
                )
            } else {
                SlidingTabs(
                    options = listOf("Search", "Changes"),
                    selectedIndex = ui.tab,
                    onSelect = ws::setTab,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                game.notice?.let {
                    Text(
                        it, fontSize = 12.sp, lineHeight = 16.sp,
                        color = if (game.noticeIsError) scheme.error else scheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                    )
                }
                if (ui.tab == 0) SearchTab(vm) else ChangesTab(vm)
            }
        }
    }

    ui.inspect?.let { st ->
        AlertDialog(
            onDismissRequest = ws::closeInspect,
            title = { Text(st.hit.node.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(st.hit.path, fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    if (st.stale) {
                        Spacer(Modifier.height(6.dp))
                        Text("This file changed since the search. Refresh the search for current results.", color = scheme.error, fontSize = 12.sp)
                    }
                    Spacer(Modifier.height(8.dp))
                    val lines = st.lines
                    if (lines == null) {
                        Text("The file can't be shown as text (binary, too large, or no longer readable).", fontSize = 13.sp)
                    } else {
                        for ((n, text) in lines) {
                            val hot = n == st.hit.line
                            Row(
                                Modifier.fillMaxWidth().background(if (hot) NLColors.Blue.copy(alpha = 0.22f) else androidx.compose.ui.graphics.Color.Transparent)
                            ) {
                                Text("$n", fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = scheme.onSurfaceVariant, modifier = Modifier.width(34.dp))
                                Text(text, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { val h = st.hit; ws.closeInspect(); scope.launch { ws.openable(h)?.let(vm::onFilePicked) } }) { Text("Open in editor") }
            },
            dismissButton = { TextButton(onClick = ws::closeInspect) { Text("Close") } }
        )
    }
}

@Composable
private fun NoFolder(notice: String?, onChoose: () -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Choose a folder to work with", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            "Pick the game's data folder (or any folder with its files) in the system folder picker. " +
                "Android decides which folders apps may open; whatever it refuses is reported here.",
            color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 14.sp, lineHeight = 20.sp
        )
        notice?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, color = MaterialTheme.colorScheme.error, fontSize = 13.sp, lineHeight = 18.sp)
        }
        Spacer(Modifier.height(24.dp))
        GradientButton(text = "Choose folder", icon = Icons.Rounded.FolderOpen, onClick = onChoose, glow = true)
    }
}

// ============================================================================ Search tab

@Composable
private fun SearchTab(vm: MainViewModel) {
    val ws = vm.workspace
    val ui by ws.ui.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val focus = LocalFocusManager.current
    val scope = rememberCoroutineScope()
    val modes = listOf(QueryMode.NUMBER, QueryMode.TEXT, QueryMode.NAME)

    fun go() {
        focus.clearFocus()
        ws.search()
    }

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SlidingTabs(
                options = listOf("Number", "Text", "File name"),
                selectedIndex = modes.indexOf(ui.mode),
                onSelect = { ws.setMode(modes[it]) },
                height = 36.dp
            )
            OutlinedTextField(
                value = ui.query,
                onValueChange = ws::setQuery,
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                label = {
                    Text(
                        when (ui.mode) {
                            QueryMode.NUMBER -> "Exact value, e.g. 1250"
                            QueryMode.TEXT -> "Text inside files"
                            QueryMode.NAME -> "File name (wildcards: * ?)"
                        }
                    )
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (ui.mode == QueryMode.NUMBER) KeyboardType.Decimal else KeyboardType.Text,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { go() }),
                shape = RoundedCornerShape(16.dp)
            )
            if (ui.mode == QueryMode.NUMBER) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ui.includeUnlabeled, onCheckedChange = ws::setUnlabeled)
                    Text(
                        "Also show numbers without a name (whole numbers only)",
                        fontSize = 12.sp, color = scheme.onSurfaceVariant
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.searching) {
                    GradientPill(text = "Cancel", icon = Icons.Rounded.Stop, onClick = ws::cancelRunning)
                } else {
                    GradientPill(text = "Search", icon = Icons.Rounded.Search, onClick = { go() }, enabled = ui.query.isNotBlank())
                    if (ui.summary != null) {
                        GradientPill(text = "Refresh", icon = Icons.Rounded.Refresh, onClick = { go() })
                    }
                }
            }
        }

        // progress + summary
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp).animateContentSize()) {
            if (ui.searching) {
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = NLColors.Blue)
                val p = ui.progress
                Text(
                    if (p == null) "Starting…" else "${p.filesSeen} files seen · ${p.filesRead} read · ${p.hits} found",
                    fontSize = 12.sp, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp)
                )
                if (p != null && p.current.isNotEmpty()) {
                    Text(p.current, fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ui.summary?.let { s ->
                if (!ui.searching) {
                    val parts = ArrayList<String>()
                    parts.add("${s.hits.size} result" + (if (s.hits.size == 1) "" else "s") + (if (s.truncated) "+" else ""))
                    parts.add("${s.filesSeen} files searched in ${s.millis} ms")
                    if (s.cancelled) parts.add("cancelled")
                    Text(parts.joinToString(" · "), fontSize = 12.sp, color = scheme.onSurfaceVariant)
                    val skipped = ArrayList<String>()
                    if (s.skippedBinary > 0) skipped.add("${s.skippedBinary} binary")
                    if (s.skippedLarge > 0) skipped.add("${s.skippedLarge} over 8 MB")
                    if (s.unreadable > 0) skipped.add("${s.unreadable} unreadable")
                    if (s.unreadableFolders > 0) skipped.add("${s.unreadableFolders} folders Android would not list")
                    if (skipped.isNotEmpty() && s.query.mode != QueryMode.NAME) {
                        Text("Not searched: " + skipped.joinToString(", "), fontSize = 11.sp, color = scheme.onSurfaceVariant)
                    }
                    if (s.truncated) Text("Showing the first results only. Narrow the search.", fontSize = 11.sp, color = NLColors.Blue)
                }
            }
            ui.message?.let { Text(it, fontSize = 12.sp, color = scheme.error, modifier = Modifier.padding(top = 4.dp)) }
        }

        if (ui.summary != null && ui.hits.isEmpty() && !ui.searching && ui.summary?.error == null) {
            Text(
                "No matches. Only files this app can read as text were searched" +
                    (if (ui.mode == QueryMode.NUMBER) ", and a number only matches when the whole value is equal." else "."),
                color = scheme.onSurfaceVariant, fontSize = 13.sp, lineHeight = 18.sp,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
            )
        }

        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(ui.hits, key = { it.key }) { hit ->
                HitCard(
                    hit,
                    onInspect = { ws.inspect(hit) },
                    onOpen = { scope.launch { ws.openable(hit)?.let(vm::onFilePicked) } }
                )
            }
        }
    }
}

@Composable
private fun HitCard(hit: SearchHit, onInspect: () -> Unit, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.6f), shape)
            .padding(12.dp)
    ) {
        Text(hit.path, fontSize = 12.sp, color = scheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(4.dp))
        when (hit.kind) {
            HitKind.FILE_NAME -> Text(hit.matched, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            HitKind.NAMED_VALUE -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.label ?: "", fontWeight = FontWeight.SemiBold, fontSize = 15.sp, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  =  ", color = scheme.onSurfaceVariant)
                Text(hit.matched, color = NLColors.Success, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
            }
            HitKind.UNLABELED_NUMBER -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(hit.matched, color = NLColors.Success, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                Text("   no name · line ${hit.line}", fontSize = 12.sp, color = scheme.onSurfaceVariant)
            }
            HitKind.TEXT -> Text("line ${hit.line}, col ${hit.column + 1}", fontSize = 12.sp, color = NLColors.Blue)
        }
        if (hit.kind != HitKind.FILE_NAME) {
            Spacer(Modifier.height(4.dp))
            Text(
                hit.snippet, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 16.sp,
                maxLines = 3, overflow = TextOverflow.Ellipsis, color = scheme.onSurface.copy(alpha = 0.85f)
            )
        } else {
            Text(formatFileSize(hit.node.size), fontSize = 12.sp, color = scheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            if (hit.kind != HitKind.FILE_NAME) TextButton(onClick = onInspect) { Text("Inspect") }
            if (!hit.node.isDir) TextButton(onClick = onOpen) { Text("Open in editor") }
        }
    }
}

// ============================================================================ Changes tab

@Composable
private fun ChangesTab(vm: MainViewModel) {
    val ws = vm.workspace
    val ui by ws.ui.collectAsStateWithLifecycle()
    val scheme = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "1. Take a baseline.  2. Play the game (earn or spend something).  3. Come back and compare. " +
                    "Works for files this app can read: text formats show exact named values; small binary files show changed numbers by position. " +
                    "Encrypted, compressed or server-side data cannot be read.",
                fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant
            )
            ui.baseline?.let {
                Text(
                    "Baseline: ${it.files.size} readable files (${it.unsupported} not readable) of ${it.filesSeen} seen.",
                    fontSize = 12.sp, color = NLColors.Success
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (ui.capturing) {
                    GradientPill(text = "Cancel", icon = Icons.Rounded.Stop, onClick = ws::cancelRunning)
                } else {
                    GradientPill(text = "Baseline", icon = Icons.Rounded.CameraAlt, onClick = ws::takeBaseline)
                    GradientPill(text = "Compare", icon = Icons.Rounded.Refresh, onClick = ws::compareNow, enabled = ui.baseline != null)
                    if (ui.baseline != null) TextButton(onClick = ws::clearBaseline) { Text("Clear") }
                }
            }
            AnimatedVisibility(visible = ui.capturing, enter = fadeIn(), exit = fadeOut()) {
                Column {
                    LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = NLColors.Blue)
                    Text(ui.captureText, fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            ui.message?.let { Text(it, fontSize = 12.sp, color = scheme.onSurfaceVariant) }
        }

        val report = ui.report
        if (report != null) {
            Text(
                "${report.changes.size} changed file(s) · ${report.valueChangeCount} changed value(s)",
                fontWeight = FontWeight.SemiBold, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
            )
            LazyColumn(
                Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(report.changes, key = { it.path }) { change ->
                    ChangeCard(change, onOpen = { ws.uriOf(change)?.let(vm::onFilePicked) })
                }
            }
        }
    }
}

@Composable
private fun ChangeCard(change: FileChange, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(scheme.surface).border(1.dp, scheme.outline.copy(alpha = 0.6f), shape).padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (change.kind) { ChangeKind.ADDED -> "NEW"; ChangeKind.REMOVED -> "REMOVED"; ChangeKind.MODIFIED -> "CHANGED" },
                fontSize = 10.sp, fontWeight = FontWeight.Bold, color = NLColors.Blue,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(NLColors.Blue.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(change.path, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        val sizeText = listOfNotNull(change.sizeBefore?.let { formatFileSize(it) }, change.sizeAfter?.let { formatFileSize(it) }).joinToString(" → ")
        if (sizeText.isNotEmpty()) Text(sizeText, fontSize = 11.sp, color = scheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        for (v in change.valueChanges.take(40)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(v.key, fontSize = 13.sp, modifier = Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("  ${v.before ?: "∅"} → ${v.after ?: "∅"}", fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = NLColors.Success)
                v.numericDelta?.let { d ->
                    val txt = d.stripTrailingZeros().toPlainString()
                    Text("  (" + (if (d.signum() > 0) "+" else "") + txt + ")", fontSize = 11.sp, color = scheme.onSurfaceVariant)
                }
            }
        }
        if (change.valueChanges.size > 40) Text("+ ${change.valueChanges.size - 40} more", fontSize = 11.sp, color = scheme.onSurfaceVariant)
        for (b in change.binaryChanges.take(20)) {
            Text(
                "offset 0x" + b.offset.toString(16) + ":  " + b.before + " → " + b.after + "  (32-bit, little-endian)",
                fontFamily = FontFamily.Monospace, fontSize = 12.sp, color = NLColors.Success
            )
        }
        if (change.binaryChanges.size > 20) Text("+ ${change.binaryChanges.size - 20} more positions", fontSize = 11.sp, color = scheme.onSurfaceVariant)
        if (change.opaque && change.kind == ChangeKind.MODIFIED) {
            Text(
                "Content changed, but its values can't be read (encrypted, compressed, large, or only formatting changed).",
                fontSize = 12.sp, color = scheme.onSurfaceVariant
            )
        }
        if (change.node != null && change.kind != ChangeKind.REMOVED) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onOpen) { Text("Open in editor") }
            }
        }
    }
}
