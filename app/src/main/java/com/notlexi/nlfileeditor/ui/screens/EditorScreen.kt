package com.notlexi.nlfileeditor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.notlexi.nlfileeditor.model.EditItem
import com.notlexi.nlfileeditor.model.UiEvent
import com.notlexi.nlfileeditor.ui.GradientPill
import com.notlexi.nlfileeditor.ui.bounceClick
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.viewmodel.MainViewModel
import kotlinx.coroutines.delay

/** A row in the list: either a section header or a value card. */
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
        if (sections.size > 1) {
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

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
                        Text(
                            "${state.totalCount} values · ${state.formatTitle}" +
                                if (state.changedCount > 0) " · ${state.changedCount} changed" else "",
                            fontSize = 12.sp,
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
            // ---- search ----
            OutlinedTextField(
                value = state.query,
                onValueChange = vm::setQuery,
                singleLine = true,
                placeholder = { Text("Search values…") },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
                trailingIcon = {
                    AnimatedVisibility(state.query.isNotEmpty(), enter = fadeIn(), exit = fadeOut()) {
                        IconButton(onClick = { vm.setQuery("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear search")
                        }
                    }
                },
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

            // ---- reset all row ----
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (state.query.isBlank()) "${state.items.size} values"
                    else "${state.items.size} of ${state.totalCount} match",
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

            if (state.items.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text("No values match “${state.query}”", color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(
                    state = rememberLazyListState(),
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
    // Staggered entrance: the first cards slide/fade in one after another.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(12) * 45).toLong())
        enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }

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
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, onClick: () -> Unit) {
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
