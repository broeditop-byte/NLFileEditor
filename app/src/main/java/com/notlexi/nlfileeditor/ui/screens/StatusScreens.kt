package com.notlexi.nlfileeditor.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.GradientButton
import com.notlexi.nlfileeditor.ui.theme.NLColors

/** Friendly error screen: unreadable file, binary file, no numbers found. */
@Composable
fun ErrorScreen(title: String, message: String, onTryAgain: () -> Unit) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)) }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Box(
                Modifier
                    .size(96.dp)
                    .graphicsLayer { scaleX = appear.value; scaleY = appear.value; alpha = appear.value.coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(52.dp))
            }
            Spacer(Modifier.height(24.dp))
            Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, fontSize = 15.sp)
            Spacer(Modifier.height(32.dp))
            GradientButton(
                text = "Try another file",
                icon = Icons.Rounded.FolderOpen,
                onClick = onTryAgain,
                height = 56.dp
            )
        }
    }
}

/** Shown after the edited file was written: animated check mark, Share, Keep editing, Done. */
@Composable
fun SuccessScreen(
    savedName: String,
    onShare: (Context) -> Unit,
    onKeepEditing: () -> Unit,
    onDone: () -> Unit
) {
    val context = LocalContext.current
    val progress = remember { Animatable(0f) }
    val content = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
        content.animateTo(1f, tween(400))
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).systemBarsPadding()) {
        Column(
            Modifier.align(Alignment.Center).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val ringColor = NLColors.Success
            Canvas(Modifier.size(120.dp)) {
                val stroke = 8.dp.toPx()
                val p = progress.value
                // 1) circle draws itself during the first half
                val circleP = (p * 2f).coerceIn(0f, 1f)
                drawArc(
                    color = ringColor,
                    startAngle = -90f,
                    sweepAngle = 360f * circleP,
                    useCenter = false,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = androidx.compose.ui.geometry.Size(size.width - stroke, size.height - stroke),
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                // 2) check mark draws itself during the second half
                val checkP = ((p - 0.5f) * 2f).coerceIn(0f, 1f)
                if (checkP > 0f) {
                    val a = Offset(size.width * 0.28f, size.height * 0.52f)
                    val b = Offset(size.width * 0.44f, size.height * 0.68f)
                    val c = Offset(size.width * 0.74f, size.height * 0.36f)
                    val firstLen = 0.35f
                    val firstP = (checkP / firstLen).coerceIn(0f, 1f)
                    val secondP = ((checkP - firstLen) / (1f - firstLen)).coerceIn(0f, 1f)
                    drawLine(ringColor, a, a + (b - a) * firstP, strokeWidth = stroke, cap = StrokeCap.Round)
                    if (secondP > 0f) {
                        drawLine(ringColor, b, b + (c - b) * secondP, strokeWidth = stroke, cap = StrokeCap.Round)
                    }
                }
            }
            Spacer(Modifier.height(28.dp))
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.graphicsLayer {
                    alpha = content.value
                    translationY = (1f - content.value) * 30f
                }
            ) {
                Text("File saved!", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(8.dp))
                Text(
                    savedName,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    "Your original file was not touched.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Spacer(Modifier.height(36.dp))
                GradientButton(
                    text = "Share",
                    icon = Icons.Rounded.Share,
                    onClick = { onShare(context) },
                    height = 56.dp,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onKeepEditing,
                    shape = MaterialTheme.shapes.extraLarge,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                    modifier = Modifier.fillMaxWidth().height(52.dp)
                ) { Text("Keep editing") }
                TextButton(onClick = onDone, modifier = Modifier.padding(top = 4.dp)) { Text("Done") }
            }
        }
    }
}

/** Opens the Android share sheet for the saved file. */
fun shareSavedFile(context: Context, uri: Uri?, mimeType: String) {
    if (uri == null) return
    val send = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(send, "Share edited file"))
}
