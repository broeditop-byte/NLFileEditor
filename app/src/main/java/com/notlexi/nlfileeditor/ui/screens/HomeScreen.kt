package com.notlexi.nlfileeditor.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.hud.FloatingHudDialog
import com.notlexi.nlfileeditor.ui.GradientButton
import com.notlexi.nlfileeditor.ui.InfoButton
import com.notlexi.nlfileeditor.ui.InfoSheet
import com.notlexi.nlfileeditor.ui.bounceClick
import com.notlexi.nlfileeditor.ui.theme.NLColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onUploadClick: () -> Unit,
    onBrowseAppsClick: () -> Unit,
    onHtmlViewerClick: () -> Unit,
    onSearchClick: (Int) -> Unit,
    onTmxClick: () -> Unit,
    gameState: com.notlexi.nlfileeditor.game.GameUiState,
    onGrantUsage: () -> Unit,
    onChooseFolder: () -> Unit,
    onUseApp: (com.notlexi.nlfileeditor.game.GameInfo) -> Unit,
    onForgetFolder: () -> Unit,
    onDismissNotice: () -> Unit
) {
    // The corner Info button opens the About panel.
    var showInfo by rememberSaveable { mutableStateOf(false) }
    var showHud by rememberSaveable { mutableStateOf(false) }

    // Entrance animations: title -> subtitle -> button (run once, never restarted by recomposition)
    val titleAlpha = remember { Animatable(0f) }
    val titleOffset = remember { Animatable(48f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val buttonScale = remember { Animatable(0f) }
    val secondaryScale = remember { Animatable(0f) }
    val barSlide = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { titleAlpha.animateTo(1f, tween(450)) }
        launch { titleOffset.animateTo(0f, tween(450, easing = FastOutSlowInEasing)) }
        launch { delay(150); subtitleAlpha.animateTo(1f, tween(400)) }
        launch {
            delay(250)
            buttonScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        launch {
            delay(350)
            secondaryScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
        launch {
            delay(400)
            barSlide.animateTo(1f, tween(550, easing = FastOutSlowInEasing))
        }
    }

    Box(Modifier.fillMaxSize()) {
        // Animated background lives in its own composable/layer so it never affects anything else.
        AnimatedBackdrop(darkTheme = darkTheme, modifier = Modifier.fillMaxSize())

        Box(Modifier.fillMaxSize().systemBarsPadding()) {
            // Info button (top-start) - absolutely positioned, does not touch the centered column
            InfoButton(
                onClick = { showInfo = true },
                modifier = Modifier.align(Alignment.TopStart).padding(12.dp)
            )

            // Theme toggle (top-end)
            IconButton(
                onClick = onToggleTheme,
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp)
            ) {
                Icon(
                    imageVector = if (darkTheme) Icons.Rounded.LightMode else Icons.Rounded.DarkMode,
                    contentDescription = "Switch theme",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .verticalScroll(rememberScrollState())
                    .padding(start = 24.dp, end = 24.dp, top = 56.dp, bottom = 110.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "NL File Editor",
                    textAlign = TextAlign.Center,
                    style = TextStyle(
                        brush = Brush.horizontalGradient(
                            listOf(
                                if (darkTheme) Color.White else Color(0xFF0A0E1F),
                                if (darkTheme) Color(0xFFC6B8FF) else NLColors.Purple
                            )
                        ),
                        fontSize = 40.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp
                    ),
                    modifier = Modifier.graphicsLayer {
                        alpha = titleAlpha.value
                        translationY = titleOffset.value
                    }
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "By NotLexi",
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Light,
                    letterSpacing = 3.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.value }
                )
                Spacer(Modifier.height(28.dp))
                GameCard(
                    state = gameState,
                    onGrantUsage = onGrantUsage,
                    onChooseFolder = onChooseFolder,
                    onOpenSearch = { onSearchClick(0) },
                    onOpenChanges = { onSearchClick(1) },
                    onUseApp = onUseApp,
                    onForget = onForgetFolder,
                    onDismissNotice = onDismissNotice,
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.value }
                )
                Spacer(Modifier.height(24.dp))
                GradientButton(
                    text = "Upload File",
                    icon = Icons.Rounded.CloudUpload,
                    onClick = onUploadClick,
                    glow = true,
                    modifier = Modifier
                        .widthIn(min = 260.dp)
                        .graphicsLayer {
                            scaleX = buttonScale.value
                            scaleY = buttonScale.value
                            alpha = buttonScale.value.coerceIn(0f, 1f)
                        }
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = "Works offline · only the values you edit change",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.value }
                )
            }

            // YouTube-style bottom action row: compact icon buttons with a short label underneath.
            BottomActionRow(
                onBrowseApps = onBrowseAppsClick,
                onHtmlViewer = onHtmlViewerClick,
                onFloatingHud = { showHud = true },
                onSearch = { onSearchClick(0) },
                onTmx = onTmxClick,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(horizontal = 24.dp, vertical = 16.dp)
                    .graphicsLayer {
                        alpha = barSlide.value
                        translationY = (1f - barSlide.value) * 60f
                    }
            )
        }
    }

    if (showInfo) {
        InfoSheet(onDismiss = { showInfo = false })
    }
    if (showHud) {
        FloatingHudDialog(onDismiss = { showHud = false })
    }
}

/** Translucent, neon-outlined bar holding the two tool shortcuts. */
@Composable
private fun BottomActionRow(
    onBrowseApps: () -> Unit,
    onHtmlViewer: () -> Unit,
    onFloatingHud: () -> Unit,
    onSearch: () -> Unit,
    onTmx: () -> Unit,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(28.dp)
    Row(
        modifier = modifier
            .widthIn(max = 440.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f), shape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ActionIconButton(icon = Icons.Rounded.Apps, label = "Apps", onClick = onBrowseApps)
        ActionIconButton(icon = Icons.Rounded.Search, label = "Search", onClick = onSearch)
        ActionIconButton(icon = Icons.Rounded.Code, label = "HTML", onClick = onHtmlViewer)
        ActionIconButton(icon = Icons.Rounded.Bolt, label = "HUD", onClick = onFloatingHud)
        ActionIconButton(icon = Icons.Rounded.Terminal, label = "NL TMX", onClick = onTmx)
    }
}

/** Round neon-ringed icon with a short label under it. */
@Composable
private fun ActionIconButton(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .bounceClick(onClick = onClick)
            .widthIn(min = 62.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.6f))
                .border(1.5.dp, NLColors.gradient, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = NLColors.Blue, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Slowly drifting gradient background.
 * The gradients are built once (drawWithCache); each frame only moves two soft blobs by
 * translating the canvas, and the animated value is read in the draw phase only, so this never
 * triggers recomposition.
 */
@Composable
private fun AnimatedBackdrop(darkTheme: Boolean, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "backdrop")
    val t = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "backdropT"
    )
    Box(
        modifier = modifier.drawWithCache {
            val w = size.width
            val h = size.height
            val colors = if (darkTheme)
                listOf(Color(0xFF070A18), Color(0xFF101A45), Color(0xFF1E1250), Color(0xFF070A18))
            else
                listOf(Color(0xFFF5F6FF), Color(0xFFE3EAFF), Color(0xFFEBE2FF), Color(0xFFF5F6FF))
            val base = Brush.linearGradient(colors, start = Offset(0f, 0f), end = Offset(w, h))
            val radius = w * 0.9f
            val blue = Brush.radialGradient(
                colors = listOf(NLColors.Blue.copy(alpha = if (darkTheme) 0.30f else 0.18f), Color.Transparent),
                center = Offset.Zero,
                radius = radius
            )
            val purple = Brush.radialGradient(
                colors = listOf(NLColors.Purple.copy(alpha = if (darkTheme) 0.28f else 0.16f), Color.Transparent),
                center = Offset.Zero,
                radius = radius
            )
            onDrawBehind {
                drawRect(base)
                val p = t.value // draw-phase read
                translate(left = w * (0.15f + 0.55f * p), top = h * 0.22f) {
                    drawCircle(brush = blue, radius = radius, center = Offset.Zero)
                }
                translate(left = w * (0.9f - 0.6f * p), top = h * 0.8f) {
                    drawCircle(brush = purple, radius = radius, center = Offset.Zero)
                }
            }
        }
    )
}
