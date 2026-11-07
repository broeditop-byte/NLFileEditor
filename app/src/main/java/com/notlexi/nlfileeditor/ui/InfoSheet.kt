package com.notlexi.nlfileeditor.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material.icons.rounded.SmartDisplay
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.theme.NLColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val URL_YOUTUBE = "https://youtube.com/@notlexi-gamer?si=hstcnZ1dsUQ-30Yx"
private const val URL_DISCORD = "https://discord.gg/HQDrtY5XU"
private const val URL_TELEGRAM = "https://t.me/NotLexi_Gamer"

private data class SocialLink(
    val platform: String,
    val label: String,
    val url: String,
    val icon: ImageVector,
    val tint: Color
)

private val socialLinks = listOf(
    SocialLink("YouTube", "Subscribe on YouTube", URL_YOUTUBE, Icons.Rounded.SmartDisplay, Color(0xFFFF4B4B)),
    SocialLink("Discord", "Join the Discord server", URL_DISCORD, Icons.Rounded.Forum, Color(0xFF7C86FF)),
    SocialLink("Telegram", "Follow on Telegram", URL_TELEGRAM, Icons.AutoMirrored.Rounded.Send, Color(0xFF3DB8F5))
)

/**
 * "About NotLexi" bottom sheet: slides up, drag down or tap the X to dismiss.
 * Links open in the browser / installed app through ACTION_VIEW (no INTERNET permission needed).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InfoSheet(onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme

    // Slide the sheet away first, then remove it from composition.
    fun close() {
        scope.launch { sheetState.hide() }.invokeOnCompletion {
            if (!sheetState.isVisible) onDismiss()
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        containerColor = scheme.surface,
        contentColor = scheme.onSurface,
        tonalElevation = 0.dp,
        dragHandle = { BottomSheetDefaults.DragHandle(color = scheme.outline) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(start = 24.dp, end = 24.dp, bottom = 24.dp)
        ) {
            // ---- header ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(NLColors.gradient),
                    contentAlignment = Alignment.Center
                ) {
                    Text("NL", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp)
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text("About NotLexi", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(
                        "Minecraft Bedrock mods · Android apps",
                        fontSize = 13.sp,
                        color = scheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { close() }) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = scheme.onSurfaceVariant)
                }
            }

            Spacer(Modifier.height(16.dp))
            Text(
                text = "NotLexi is a Minecraft Bedrock mod creator and app developer. " +
                    "NL File Editor is one of his tools: open a game or config file, change the numbers inside it, " +
                    "and save a clean copy. It works fully offline and leaves everything else in your file untouched.",
                fontSize = 15.sp,
                lineHeight = 22.sp,
                color = scheme.onSurfaceVariant
            )

            Spacer(Modifier.height(24.dp))
            Text(
                "FIND NOTLEXI ONLINE",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
                color = NLColors.Blue
            )
            Spacer(Modifier.height(10.dp))

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                socialLinks.forEachIndexed { index, link ->
                    SocialRow(link = link, index = index, onClick = { openLink(context, link.url) })
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                "NL File Editor · by NotLexi",
                fontSize = 12.sp,
                color = scheme.onSurfaceVariant.copy(alpha = 0.7f),
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun SocialRow(link: SocialLink, index: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme

    // Staggered slide/fade-in as the sheet appears.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(160L + index * 90L)
        enter.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 36f
            }
            .bounceClick(onClick = onClick)
            .clip(MaterialTheme.shapes.large)
            .background(scheme.surfaceVariant.copy(alpha = 0.55f))
            .border(BorderStroke(1.dp, scheme.outline.copy(alpha = 0.6f)), MaterialTheme.shapes.large)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(link.tint.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(link.icon, contentDescription = null, tint = link.tint, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(link.platform, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(link.label, fontSize = 13.sp, color = scheme.onSurfaceVariant)
        }
        Icon(
            Icons.AutoMirrored.Rounded.KeyboardArrowRight,
            contentDescription = null,
            tint = scheme.onSurfaceVariant
        )
    }
}

/** Opens [url] in the browser or the matching app; shows a toast if nothing can handle it. */
private fun openLink(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
    if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "No app found to open this link", Toast.LENGTH_SHORT).show()
    }
}
