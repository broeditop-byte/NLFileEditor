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
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.GradientButton
import com.notlexi.nlfileeditor.ui.theme.NLColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    isLoading: Boolean,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
    onUploadClick: () -> Unit
) {
    // --- slowly moving gradient background ---
    val bgTransition = rememberInfiniteTransition(label = "bg")
    val t by bgTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(14000, easing = LinearEasing), RepeatMode.Reverse),
        label = "bgT"
    )

    // --- entrance animations: title -> subtitle -> button ---
    val titleAlpha = remember { Animatable(0f) }
    val titleOffset = remember { Animatable(48f) }
    val subtitleAlpha = remember { Animatable(0f) }
    val buttonScale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { titleAlpha.animateTo(1f, tween(700)) }
        launch { titleOffset.animateTo(0f, tween(700, easing = FastOutSlowInEasing)) }
        launch { delay(450); subtitleAlpha.animateTo(1f, tween(600)) }
        launch {
            delay(900)
            buttonScale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }

    val bgColors = if (darkTheme)
        listOf(Color(0xFF070A18), Color(0xFF101A45), Color(0xFF1E1250), Color(0xFF070A18))
    else
        listOf(Color(0xFFF5F6FF), Color(0xFFE3EAFF), Color(0xFFEBE2FF), Color(0xFFF5F6FF))

    Box(
        modifier = Modifier
            .fillMaxSize()
            .drawBehind {
                val w = size.width
                val h = size.height
                drawRect(
                    brush = Brush.linearGradient(
                        colors = bgColors,
                        start = Offset(w * t * 0.6f, 0f),
                        end = Offset(w * (1f - t * 0.4f), h)
                    )
                )
                val glowA = Offset(w * (0.15f + 0.55f * t), h * 0.22f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(NLColors.Blue.copy(alpha = if (darkTheme) 0.30f else 0.18f), Color.Transparent),
                        center = glowA, radius = w * 0.85f
                    ),
                    radius = w * 0.85f, center = glowA
                )
                val glowB = Offset(w * (0.9f - 0.6f * t), h * 0.8f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(NLColors.Purple.copy(alpha = if (darkTheme) 0.28f else 0.16f), Color.Transparent),
                        center = glowB, radius = w * 0.9f
                    ),
                    radius = w * 0.9f, center = glowB
                )
            }
            .systemBarsPadding()
    ) {
        // Theme toggle (top right)
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
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
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
            Spacer(Modifier.height(56.dp))
            GradientButton(
                text = "Upload File",
                icon = Icons.Rounded.CloudUpload,
                onClick = onUploadClick,
                glow = true,
                modifier = Modifier.graphicsLayer {
                    scaleX = buttonScale.value
                    scaleY = buttonScale.value
                    alpha = buttonScale.value.coerceIn(0f, 1f)
                }
            )
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Works offline · only the numbers change",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                modifier = Modifier.graphicsLayer { alpha = subtitleAlpha.value }
            )
        }

        AnimatedVisibility(visible = isLoading, enter = fadeIn(tween(250)), exit = fadeOut(tween(250))) {
            LoadingOverlay()
        }
    }
}

/** Full-screen scrim with a spinning gradient ring while a file is being read and scanned. */
@Composable
private fun LoadingOverlay() {
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing)),
        label = "angle"
    )
    val pulse by spin.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "textPulse"
    )
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC050816))
            .pointerInput(Unit) { detectTapGestures { } }, // swallow touches while loading
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(Modifier.size(76.dp)) {
                val stroke = 8.dp.toPx()
                rotate(angle) {
                    drawArc(
                        brush = Brush.sweepGradient(listOf(Color.Transparent, NLColors.Blue, NLColors.Purple)),
                        startAngle = 0f,
                        sweepAngle = 300f,
                        useCenter = false,
                        topLeft = Offset(stroke / 2, stroke / 2),
                        size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                        style = Stroke(width = stroke, cap = StrokeCap.Round)
                    )
                }
            }
            Spacer(Modifier.height(20.dp))
            Text(
                text = "Reading & scanning file…",
                color = Color.White,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.graphicsLayer { alpha = pulse }
            )
        }
    }
}
