package com.notlexi.nlfileeditor.hud

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.theme.NLColors
import com.notlexi.nlfileeditor.ui.theme.NLFileEditorTheme

/** Outer padding (8dp) + header height (48dp): the part of the expanded card that drags the HUD. */
const val HUD_HEADER_DRAG_DP = 56

private val HudSurface = Color(0xF2121833)
private val HudBubbleFill = Color(0xE60A0E1F)
private val HudWarn = Color(0xFFFFC857)

/** Everything the overlay can ask the service to do. */
class HudActions(
    val onToggleExpanded: () -> Unit,
    val onClose: () -> Unit,
    val onSelectFile: () -> Unit,
    val onClearTarget: () -> Unit,
    val onStartGame: () -> Unit,
    val onSnapshot1: () -> Unit,
    val onSnapshot2: () -> Unit,
    val onRecord5: () -> Unit,
    val onRecord10: () -> Unit,
    val onToggleDiffMode: () -> Unit,
    val onSelectChange: (ChangedValue) -> Unit,
    val onEdit: () -> Unit,
    val onPatch: () -> Unit,
    val onCancel: () -> Unit
)

private const val OPEN_MS = 220
private const val CLOSE_MS = 150

@Composable
fun FloatingHudView(ui: HudUiState, expanded: Boolean, actions: HudActions) {
    NLFileEditorTheme(darkTheme = true) {
        HudStage(ui, expanded, actions)
    }
}

/**
 * Exactly ONE animation per open and per close, and never both layouts at once.
 *
 * v5/v6-beta used AnimatedContent, which keeps the old AND the new layout composed during the
 * transition. The overlay window is WRAP_CONTENT, so its size jumped to the union of both and the
 * service re-clamped / re-laid-out the window on every change: that was the "opens twice / closes
 * twice / wrong size" behaviour. Here the card is composed only while it is (or is becoming)
 * visible, and its open/close is a single [Animatable] read in the layer phase (graphicsLayer),
 * so the animation costs no recomposition and no re-measure. LaunchedEffect(expanded) cancels a
 * running animation when the user toggles again and continues from the current value.
 */
@Composable
private fun HudStage(ui: HudUiState, expanded: Boolean, actions: HudActions) {
    val progress = remember { Animatable(if (expanded) 1f else 0f) }
    var cardComposed by remember { mutableStateOf(expanded) }
    LaunchedEffect(expanded) {
        if (expanded) {
            cardComposed = true
            progress.animateTo(1f, tween(OPEN_MS, easing = FastOutSlowInEasing))
        } else {
            progress.animateTo(0f, tween(CLOSE_MS, easing = FastOutSlowInEasing))
            cardComposed = false
        }
    }
    if (cardComposed) {
        Box(
            Modifier.graphicsLayer {
                val p = progress.value
                alpha = p
                val sc = 0.92f + 0.08f * p
                scaleX = sc
                scaleY = sc
                // The card grows out of / shrinks back to the bubble's corner of the window.
                transformOrigin = TransformOrigin(0f, 0f)
            }
        ) { HudCard(ui, actions) }
    } else {
        val appear = remember { Animatable(0f) }
        LaunchedEffect(Unit) { appear.animateTo(1f, tween(120, easing = LinearEasing)) }
        Box(Modifier.graphicsLayer { alpha = appear.value }) { HudBubble(ui, actions.onToggleExpanded) }
    }
}

// ---------------------------------------------------------------------------------- collapsed

@Composable
private fun HudBubble(ui: HudUiState, onClick: () -> Unit) {
    val recording = ui.phase == HudPhase.RECORDING && ui.totalSeconds > 0
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(10.dp)) {
        Box(
            modifier = Modifier
                .size(56.dp)
                .drawBehind {
                    // Cheap static glow (no blurred shadow layer in the overlay window).
                    drawCircle(NLColors.Blue.copy(alpha = 0.22f), radius = size.minDimension / 2f + 5.dp.toPx())
                }
                .clip(CircleShape)
                .background(HudBubbleFill)
                .border(2.dp, NLColors.gradient, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (recording) {
                // One linear 1 s tween per tick = a continuously sweeping ring. The value is read in the
                // draw phase only, so the sweep never recomposes anything.
                val fraction = animateFloatAsState(
                    targetValue = ui.countdown / ui.totalSeconds.toFloat(),
                    animationSpec = tween(1000, easing = LinearEasing),
                    label = "hudRing"
                )
                Canvas(Modifier.fillMaxSize().padding(4.dp)) {
                    drawArc(
                        brush = NLColors.gradient,
                        startAngle = -90f,
                        sweepAngle = 360f * fraction.value,
                        useCenter = false,
                        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
                    )
                }
            }
            Text("NL", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.ExtraBold)
            if (!recording && ui.changes.isNotEmpty()) {
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 2.dp, end = 2.dp)
                        .size(16.dp)
                        .clip(CircleShape)
                        .background(NLColors.Success),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (ui.changes.size > 9) "9+" else ui.changes.size.toString(),
                        color = Color(0xFF0A0E1F), fontSize = 9.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        if (recording) {
            Spacer(Modifier.height(4.dp))
            Pill(text = "${ui.countdown}s", color = NLColors.Blue)
        } else if (ui.busy) {
            Spacer(Modifier.height(4.dp))
            Pill(text = "…", color = NLColors.Purple)
        }
    }
}

@Composable
private fun Pill(text: String, color: Color) {
    Box(
        Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(HudBubbleFill)
            .border(1.dp, color, RoundedCornerShape(10.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

// ---------------------------------------------------------------------------------- expanded

@Composable
private fun HudCard(ui: HudUiState, actions: HudActions) {
    val cfg = LocalConfiguration.current
    val cardWidth = minOf(304, cfg.screenWidthDp - 16).coerceAtLeast(220)
    val maxHeight = (cfg.screenHeightDp - 56).coerceAtLeast(240)
    val shape = RoundedCornerShape(22.dp)

    Column(
        Modifier
            .padding(8.dp)
            .width(cardWidth.dp)
            .heightIn(max = maxHeight.dp)
            .clip(shape)
            .background(HudSurface)
            .border(1.dp, NLColors.gradient, shape)
    ) {
        HudHeader(actions)
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            TargetSection(ui, actions)
            StatusSection(ui)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    (if (ui.armed) "\u2713 " else "") + "I Started The Game", actions.onStartGame,
                    enabled = !ui.busy && ui.hasTarget && ui.targetName != null, primary = true
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    (if (ui.hasSnapshotA) "\u2713 " else "") + "Snapshot 1",
                    actions.onSnapshot1, enabled = !ui.busy && ui.targetName != null
                )
                HudButton(
                    (if (ui.hasSnapshotB) "\u2713 " else "") + "Snapshot 2",
                    actions.onSnapshot2, enabled = !ui.busy && ui.hasSnapshotA
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton("5s Record", actions.onRecord5, enabled = !ui.busy && ui.armed, primary = true)
                HudButton("10s Record", actions.onRecord10, enabled = !ui.busy && ui.armed, primary = true)
            }
            if (ui.hasTarget && !ui.armed && !ui.busy) {
                Text(
                    "Step 1: tap \"I Started The Game\" (saves Snapshot A). Step 2: tap 5s or 10s Record and play.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 13.sp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                HudButton(
                    "Diff Mode: " + if (ui.showAll) "All" else "Numbers",
                    actions.onToggleDiffMode, enabled = !ui.busy
                )
                if (ui.busy && ui.phase != HudPhase.PATCHING) HudButton("Cancel", actions.onCancel)
            }
            ChangesSection(ui, actions)
            EditSection(ui, actions)
            Text(
                "Works only on files you can access. Games may ignore or overwrite edits until they reload the file.",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 10.sp, lineHeight = 13.sp
            )
        }
    }
}

@Composable
private fun HudHeader(actions: HudActions) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(NLColors.Success))
        Spacer(Modifier.width(8.dp))
        Text(
            "NL HUD", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        IconTap(onClick = actions.onToggleExpanded) {
            Icon(Icons.Rounded.Remove, contentDescription = "Collapse HUD", tint = NLColors.Blue)
        }
        IconTap(onClick = actions.onClose) {
            Icon(Icons.Rounded.Close, contentDescription = "Close HUD", tint = Color(0xFFFF6B7A))
        }
    }
}

@Composable
private fun IconTap(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(44.dp).clip(CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
private fun TargetSection(ui: HudUiState, actions: HudActions) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = ui.targetName ?: if (ui.hasTarget) "Selected file unavailable" else "No target file selected",
                color = if (ui.targetName == null) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (ui.hasTarget) {
                // Clears the selection and all snapshot state; works in every phase (also cancels a recording).
                Box(
                    Modifier.size(32.dp).clip(CircleShape).clickable(onClick = actions.onClearTarget),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Close, contentDescription = "Clear selection",
                        tint = Color(0xFFFF6B7A), modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
        val details = listOfNotNull(
            ui.formatLabel,
            ui.encodingLabel,
            if (ui.targetName != null && !ui.canWrite) "read-only" else null
        ).joinToString(" \u00B7 ")
        if (details.isNotEmpty()) {
            Text(details, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        }
        Row {
            HudButton(
                if (ui.hasTarget) "Change Target File" else "Select Target File",
                actions.onSelectFile, enabled = !ui.busy
            )
        }
    }
}

@Composable
private fun StatusSection(ui: HudUiState) {
    if (ui.phase == HudPhase.RECORDING) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color(0x333D7BFF)).padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("${ui.countdown}", color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.width(10.dp))
            Text("Snapshot A saved. Recording… play the game now.", color = Color.White, fontSize = 12.sp)
        }
        return
    }
    val text = ui.message ?: when (ui.phase) {
        HudPhase.SELECTING_FILE -> "Choosing a file…"
        HudPhase.CAPTURING -> "Reading file…"
        HudPhase.DIFFING -> "Comparing…"
        HudPhase.PATCHING -> "Writing…"
        else -> "Select a save file, then record a change."
    }
    Text(
        text = text,
        color = if (ui.messageIsError) Color(0xFFFF6B7A) else MaterialTheme.colorScheme.onSurfaceVariant,
        fontSize = 12.sp, lineHeight = 15.sp
    )
}

@Composable
private fun ChangesSection(ui: HudUiState, actions: HudActions) {
    Text(
        "Detected Changes (${ui.changes.size})", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold
    )
    if (ui.changes.isEmpty()) {
        val emptyText = if (ui.noChanges && ui.hasSnapshotB) NO_CHANGES_MESSAGE
        else "Nothing yet. Only values that really changed are listed."
        Text(
            emptyText,
            color = if (ui.noChanges && ui.hasSnapshotB) HudWarn else MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 11.sp, lineHeight = 14.sp
        )
    } else {
        val shown = remember(ui.changes) { ui.changes.take(200) }
        LazyColumn(
            Modifier.fillMaxWidth().heightIn(max = 150.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            items(shown, key = { "${it.kind}:${it.startIndex}" }) { change ->
                ChangeRow(change, selected = change == ui.selected, onClick = { actions.onSelectChange(change) })
            }
        }
    }
    val notes = buildList {
        if (ui.hiddenByMode > 0) add("${ui.hiddenByMode} True/False hidden (Diff Mode)")
        if (ui.ignoredTimeLike > 0) add("${ui.ignoredTimeLike} time-related hidden")
        if (ui.skippedAmbiguous > 0) add("${ui.skippedAmbiguous} uncertain skipped")
    }
    if (notes.isNotEmpty()) {
        Text(notes.joinToString(" · "), color = HudWarn, fontSize = 10.sp)
    }
}

@Composable
private fun ChangeRow(change: ChangedValue, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) Color(0x553D7BFF) else Color(0x22FFFFFF))
            .border(1.dp, if (selected) NLColors.Blue else Color.Transparent, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp)
    ) {
        Text(
            change.key, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Text(
            "${change.oldValue} \u2192 ${change.newValue}",
            color = NLColors.Success, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EditSection(ui: HudUiState, actions: HudActions) {
    val selected = ui.selected
    if (selected != null && ui.pending != null) {
        Text(
            "Pending: ${selected.newValue} \u2192 ${ui.pending}",
            color = HudWarn, fontSize = 12.sp, fontFamily = FontFamily.Monospace
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        HudButton("Edit Value", actions.onEdit, enabled = !ui.busy && selected != null)
        HudButton(
            "Save / Patch", actions.onPatch,
            enabled = !ui.busy && selected != null && ui.pending != null && ui.canWrite,
            primary = true
        )
    }
}

@Composable
private fun RowScope.HudButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    primary: Boolean = false
) {
    val shape = RoundedCornerShape(12.dp)
    val base = Modifier.weight(1f).height(38.dp).clip(shape)
    val styled = if (primary) base.background(NLColors.gradient, shape, alpha = if (enabled) 1f else 0.35f)
    else base.background(Color(0x22FFFFFF)).border(1.dp, Color(0x553D7BFF), shape)
    Box(
        modifier = styled.clickable(enabled = enabled, onClick = onClick).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text, color = if (enabled) Color.White else Color(0x77FFFFFF), fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center
        )
    }
}
