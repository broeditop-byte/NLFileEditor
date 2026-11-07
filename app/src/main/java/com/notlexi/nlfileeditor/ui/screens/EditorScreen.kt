package com.notlexi.nlfileeditor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notlexi.nlfileeditor.model.BoolItem
import com.notlexi.nlfileeditor.model.CODE_NOTICE
import com.notlexi.nlfileeditor.model.CODE_SECTION
import com.notlexi.nlfileeditor.model.EditItem
import com.notlexi.nlfileeditor.model.EditorTab
import com.notlexi.nlfileeditor.model.EditorUiState
import com.notlexi.nlfileeditor.model.SearchMode
import com.notlexi.nlfileeditor.model.UiEvent
import com.notlexi.nlfileeditor.ui.GradientPill
import com.notlexi.nlfileeditor.ui.SlidingTabs
import com.notlexi.nlfileeditor.ui.bounceClick
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/** A row in the numbers list: either a section header or a value card. */
private sealed interface ListRow {
    data class Header(val title: String, val count: Int) : ListRow
    data class Item(val item: EditItem, val index: Int) : ListRow
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(vm: MainViewModel) {
    val state by vm.editor.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showDiscard by remember { mutableStateOf(false) }

    // System "Create document" dialog. The file name is suggested as originalname_edited.
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(vm.mimeType)
    ) { uri -> vm.onSaveLocation(uri) }

    LaunchedEffect(Unit) {
        vm.events.collect { event ->
            when (event) {
                is UiEvent.LaunchSave -> saveLauncher.launch(event.suggestedName)
                is UiEvent.Message -> snackbar.showSnackbar(event.text)
            }
        }
    }

    fun tryClose() {
        if (state.changedCount > 0) showDiscard = true else vm.closeFile()
    }
    BackHandler { tryClose() }

    // Group into sections (headers only appear if the file has more than one section).
    val rows = remember(state.items) {
        val sections = state.items.map { it.entry.section }.distinct()
        val out = ArrayList<ListRow>()
        var index = 0
        if (sections.size > 1 || sections.firstOrNull() == CODE_SECTION) {
            for (s in sections) {
                val group = state.items.filter { it.entry.section == s }
                out.add(ListRow.Header(s, group.size))
                group.forEach { out.add(ListRow.Item(it, index++)) }
            }
        } else {
            state.items.forEach { out.add(ListRow.Item(it, index++)) }
        }
        out as List<ListRow>
    }

    val onValuesTab = state.tab == EditorTab.VALUES
    val numberMode = state.searchMode == SearchMode.NUMBER

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            subtitleOf(state),
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { tryClose() }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (state.saving) {
                        CircularProgressIndicator(Modifier.size(24.dp).padding(end = 0.dp), strokeWidth = 3.dp)
                        Spacer(Modifier.width(16.dp))
                    } else {
                        GradientPill(
                            text = "Save",
                            icon = Icons.Rounded.Save,
                            enabled = state.changedCount > 0 && !state.hasErrors,
                            onClick = vm::requestSave
                        )
                        Spacer(Modifier.width(12.dp))
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background)
            )
        }
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
        ) {
            // ---- top-level tabs: numbers | True/False ----
            SlidingTabs(
                options = listOf("Values", "True/False"),
                selectedIndex = state.tab.ordinal,
                onSelect = { vm.selectTab(EditorTab.values()[it]) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // ---- notice for source-code files ----
            AnimatedVisibility(
                visible = state.isCode && onValuesTab,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                CodeNotice()
            }

            // ---- search (belongs to the open tab) ----
            OutlinedTextField(
                value = if (onValuesTab) state.query else state.boolQuery,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = {
                    Text(
                        when {
                            !onValuesTab -> "Search True/False names…"
                            numberMode -> "Find values equal to…"
                            else -> "Search values…"
                        }
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    val text = if (onValuesTab) state.query else state.boolQuery
                    AnimatedVisibility(text.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (onValuesTab && numberMode) KeyboardType.Decimal else KeyboardType.Text
                ),
                shape = MaterialTheme.shapes.extraLarge,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NLColors.Blue,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedContainerColor = MaterialTheme.colorScheme.surface
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            )

            // ---- Name | Number toggle (numbers tab only) ----
            AnimatedVisibility(
                visible = onValuesTab,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SlidingTabs(
                        options = listOf("Name", "Number"),
                        selectedIndex = state.searchMode.ordinal,
                        onSelect = { vm.setSearchMode(SearchMode.values()[it]) },
                        height = 34.dp,
                        modifier = Modifier.width(190.dp)
                    )
                }
            }

            // ---- count + reset all ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = countText(state),
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = vm::resetAll, enabled = state.changedCount > 0 || state.hasErrors) {
                    Icon(Icons.Rounded.RestartAlt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Reset all")
                }
            }

            // ---- content of the open tab ----
            Crossfade(
                targetState = state.tab,
                animationSpec = tween(250),
                label = "editorTab",
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { tab ->
                when (tab) {
                    EditorTab.VALUES -> ValuesList(state, rows, vm)
                    EditorTab.BOOLEANS -> BooleanList(state, vm)
                }
            }
        }
    }

    if (showDiscard) {
        AlertDialog(
            onDismissRequest = { showDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("You have unsaved changes. If you leave now they will be lost.") },
            confirmButton = {
                TextButton(onClick = { showDiscard = false; vm.closeFile() }) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscard = false }) { Text("Keep editing") }
            },
            shape = MaterialTheme.shapes.large
        )
    }
}

private fun subtitleOf(state: EditorUiState): String {
    val parts = ArrayList<String>()
    if (state.totalCount > 0 || !state.boolScanned) parts.add("${state.totalCount} values")
    if (state.boolScanned) parts.add("${state.boolTotal} True/False")
    parts.add(state.formatTitle)
    if (state.changedCount > 0) parts.add("${state.changedCount} changed")
    return parts.joinToString(" · ")
}

private fun countText(state: EditorUiState): String = when (state.tab) {
    EditorTab.VALUES -> when {
        state.query.isBlank() -> "${state.items.size} values"
        state.searchMode == SearchMode.NUMBER -> "${state.items.size} equal ${state.query.trim()}"
        else -> "${state.items.size} of ${state.totalCount} match"
    }
    EditorTab.BOOLEANS -> when {
        state.boolLoading || !state.boolScanned -> "Scanning…"
        state.boolQuery.isBlank() -> "${state.boolItems.size} True/False values"
        else -> "${state.boolItems.size} of ${state.boolTotal} match"
    }
}

// ====================================================================== numbers tab

@Composable
private fun ValuesList(state: EditorUiState, rows: List<ListRow>, vm: MainViewModel) {
    if (state.items.isEmpty()) {
        val message = when {
            state.totalCount == 0 -> "No numeric values in this file"
            state.searchMode == SearchMode.NUMBER -> "No values equal “${state.query.trim()}”"
            else -> "No values match “${state.query}”"
        }
        EmptyMessage(message)
    } else {
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(
                items = rows,
                key = { row ->
                    when (row) {
                        is ListRow.Header -> "h_${row.title}"
                        is ListRow.Item -> "i_${row.item.entry.id}"
                    }
                },
                contentType = { row -> if (row is ListRow.Header) 0 else 1 }
            ) { row ->
                when (row) {
                    is ListRow.Header -> SectionHeader(row.title, row.count)
                    is ListRow.Item -> ValueCard(
                        item = row.item,
                        index = row.index,
                        onChange = { vm.onValueChange(row.item.entry.id, it) },
                        onStep = { vm.step(row.item.entry.id, it) },
                        onReset = { vm.reset(row.item.entry.id) }
                    )
                }
            }
        }
    }
}

// ====================================================================== True/False tab

@Composable
private fun BooleanList(state: EditorUiState, vm: MainViewModel) {
    when {
        state.boolLoading || !state.boolScanned -> BooleanLoading()
        state.boolItems.isEmpty() -> EmptyMessage(
            if (state.boolQuery.isBlank()) "No True/False values found in this file"
            else "No True/False values match “${state.boolQuery}”"
        )
        else -> LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            itemsIndexed(
                items = state.boolItems,
                key = { _, item -> "b_${item.entry.id}" }
            ) { index, item ->
                BooleanCard(
                    item = item,
                    index = index,
                    onToggle = { vm.setBoolean(item.entry.id, it) },
                    onReset = { vm.reset(item.entry.id) }
                )
            }
        }
    }
}

/** Shown while the on-demand True/False scan runs. */
@Composable
private fun BooleanLoading() {
    val pulse by rememberInfiniteTransition(label = "boolScan").animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "boolScanPulse"
    )
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(color = NLColors.Blue, strokeWidth = 4.dp, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(18.dp))
        Text(
            "Scanning for True/False values…",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 15.sp,
            modifier = Modifier.graphicsLayer { alpha = pulse }
        )
    }
}

@Composable
private fun BooleanCard(
    item: BoolItem,
    index: Int,
    onToggle: (Boolean) -> Unit,
    onReset: () -> Unit
) {
    val enter = rememberEntrance(index)
    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (item.changed) NLColors.Blue.copy(alpha = 0.14f) else scheme.surface,
        animationSpec = tween(300),
        label = "boolCardColor"
    )
    val border by animateColorAsState(
        targetValue = if (item.changed) NLColors.Blue else scheme.outline.copy(alpha = 0.5f),
        animationSpec = tween(300),
        label = "boolCardBorder"
    )
    val valueColor by animateColorAsState(
        targetValue = if (item.current) NLColors.Success else scheme.onSurfaceVariant,
        animationSpec = tween(250),
        label = "boolValueColor"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(if (item.changed) 1.5.dp else 1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 40f
            }
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = item.entry.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Original: ${item.entry.original}",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            AnimatedVisibility(item.changed, enter = fadeIn(), exit = fadeOut()) {
                IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.Undo, contentDescription = "Reset this value", modifier = Modifier.size(20.dp), tint = NLColors.Blue)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = if (item.current) "True" else "False",
                color = valueColor,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(48.dp)
            )
            Spacer(Modifier.width(8.dp))
            Switch(
                checked = item.current,
                onCheckedChange = onToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = NLColors.Blue,
                    uncheckedThumbColor = scheme.onSurfaceVariant,
                    uncheckedTrackColor = scheme.surfaceVariant,
                    uncheckedBorderColor = scheme.outline
                )
            )
        }
    }
}

// ====================================================================== shared pieces

@Composable
private fun EmptyMessage(text: String) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Calm info card used when the file turned out to be source code rather than a save file. */
@Composable
private fun CodeNotice() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = NLColors.Blue.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, NLColors.Blue.copy(alpha = 0.6f)),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.Top) {
            Icon(Icons.Rounded.Info, contentDescription = null, tint = NLColors.Blue, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(CODE_NOTICE, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/** Staggered entrance: the first cards slide/fade in one after another. */
@Composable
private fun rememberEntrance(index: Int): Animatable<Float, androidx.compose.animation.core.AnimationVector1D> {
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(12) * 45).toLong())
        enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    return enter
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title.uppercase(),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.2.sp,
            color = NLColors.Blue,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false)
        )
        Spacer(Modifier.width(8.dp))
        Text("$count", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ValueCard(
    item: EditItem,
    index: Int,
    onChange: (String) -> Unit,
    onStep: (Int) -> Unit,
    onReset: () -> Unit
) {
    val enter = rememberEntrance(index)

    val scheme = MaterialTheme.colorScheme
    val container by animateColorAsState(
        targetValue = if (item.changed) NLColors.Blue.copy(alpha = 0.14f) else scheme.surface,
        animationSpec = tween(300),
        label = "cardColor"
    )
    val border by animateColorAsState(
        targetValue = when {
            item.error != null -> scheme.error
            item.changed -> NLColors.Blue
            else -> scheme.outline.copy(alpha = 0.5f)
        },
        animationSpec = tween(300),
        label = "cardBorder"
    )

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = container),
        border = BorderStroke(if (item.changed || item.error != null) 1.5.dp else 1.dp, border),
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 40f
            }
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.entry.label,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 16.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                AnimatedVisibility(item.changed || item.error != null, enter = fadeIn(), exit = fadeOut()) {
                    IconButton(onClick = onReset, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Undo, contentDescription = "Reset this value", modifier = Modifier.size(20.dp), tint = NLColors.Blue)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Original: ${item.entry.original}",
                    fontSize = 12.sp,
                    color = scheme.onSurfaceVariant.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                StepButton(Icons.Rounded.Remove, "Decrease") { onStep(-1) }
                OutlinedTextField(
                    value = item.current,
                    onValueChange = { v -> onChange(v.filter { it.isDigit() || it == '-' || it == '.' }) },
                    singleLine = true,
                    isError = item.error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    textStyle = LocalTextStyle.current.copy(
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    shape = MaterialTheme.shapes.medium,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = NLColors.Blue,
                        unfocusedBorderColor = scheme.outline,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent
                    ),
                    modifier = Modifier.width(120.dp)
                )
                StepButton(Icons.Rounded.Add, "Increase") { onStep(1) }
            }
            AnimatedVisibility(
                visible = item.error != null,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Text(
                    text = item.error.orEmpty(),
                    color = scheme.error,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
        }
    }
}

@Composable
private fun StepButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Icon(
        imageVector = icon,
        contentDescription = description,
        tint = NLColors.Blue,
        modifier = Modifier
            .padding(horizontal = 4.dp)
            .size(36.dp)
            .bounceClick(onClick = onClick)
            .padding(6.dp)
    )
}
