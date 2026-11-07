package com.notlexi.nlfileeditor.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.game.GameInfo
import com.notlexi.nlfileeditor.game.GameUiState
import com.notlexi.nlfileeditor.ui.GradientPill
import com.notlexi.nlfileeditor.ui.theme.NLColors

/**
 * Home-screen card that replaces "select a target file first": it shows the detected game (or why
 * none was found), the remembered folder, and the actions that work on it.
 */
@Composable
fun GameCard(
    state: GameUiState,
    onGrantUsage: () -> Unit,
    onChooseFolder: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenChanges: () -> Unit,
    onUseApp: (GameInfo) -> Unit,
    onForget: () -> Unit,
    onDismissNotice: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(22.dp)
    Column(
        modifier = modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .animateContentSize()
            .clip(shape)
            .background(scheme.surface.copy(alpha = 0.6f))
            .border(1.dp, NLColors.gradient, shape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        val game = state.detected
        when {
            state.checking && game == null && state.folder == null -> {
                Text("Checking for a running game…", fontSize = 14.sp, color = scheme.onSurfaceVariant)
                LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(4.dp)), color = NLColors.Blue)
            }
            !state.usageAccess -> {
                Text("Game detection is off", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Android only tells an app which game is running after you allow \"Usage access\" for NL File Editor. " +
                        "You can also just choose a folder yourself.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientPill(text = "Allow", icon = Icons.Rounded.Security, onClick = onGrantUsage)
                    if (state.folder == null) GradientPill(text = "Choose folder", icon = Icons.Rounded.FolderOpen, onClick = onChooseFolder)
                }
            }
            game != null -> {
                Text(game.label, fontWeight = FontWeight.Bold, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(game.packageName + "  ·  running / recently used", fontSize = 11.sp, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            else -> {
                Text("No supported game detected", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    "Open your game, then come back here. You can also choose its data folder by hand.",
                    fontSize = 12.sp, lineHeight = 17.sp, color = scheme.onSurfaceVariant
                )
            }
        }

        val folder = state.folder
        if (state.usageAccess || folder != null) {
            if (folder != null) {
                Text(
                    "Folder: " + folder.path + (folder.packageName?.let { "" } ?: ""),
                    fontSize = 12.sp, color = NLColors.Success, maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GradientPill(text = "Search", icon = Icons.Rounded.Search, onClick = onOpenSearch)
                    GradientPill(text = "Changes", icon = Icons.Rounded.CameraAlt, onClick = onOpenChanges)
                }
                TextButton(onClick = onForget) { Text("Forget this folder", fontSize = 12.sp) }
            } else if (!state.checking || game != null) {
                GradientPill(
                    text = if (game != null) "Open game folder" else "Choose game folder",
                    icon = Icons.Rounded.FolderOpen, onClick = onChooseFolder
                )
            }
        }

        if (state.recentApps.isNotEmpty() && game == null && state.usageAccess) {
            Text("Not listed as a game? Use a recent app:", fontSize = 11.sp, color = scheme.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (app in state.recentApps.take(3)) {
                    TextButton(onClick = { onUseApp(app) }) { Text(app.label, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }

        state.notice?.let {
            Spacer(Modifier.height(2.dp))
            Text(
                it, fontSize = 12.sp, lineHeight = 17.sp,
                color = if (state.noticeIsError) scheme.error else scheme.onSurfaceVariant,
                textAlign = TextAlign.Start
            )
            TextButton(onClick = onDismissNotice) { Text("Dismiss", fontSize = 12.sp) }
        }
    }
}
