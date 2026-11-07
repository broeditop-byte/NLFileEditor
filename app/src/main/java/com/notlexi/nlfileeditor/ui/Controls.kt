package com.notlexi.nlfileeditor.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.theme.NLColors
import kotlin.math.roundToInt

/**
 * Compact pill-shaped segmented control. A gradient "thumb" glides smoothly to the selected
 * option and the label colors cross-fade. Used for Values | True/False and Name | Number.
 */
@Composable
fun SlidingTabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 40.dp
) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(height / 2)
    val position by animateFloatAsState(
        targetValue = selectedIndex.toFloat(),
        animationSpec = tween(300, easing = FastOutSlowInEasing),
        label = "tabThumb"
    )

    Box(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline.copy(alpha = 0.6f), shape)
    ) {
        // The moving thumb.
        BoxWithConstraints(Modifier.matchParentSize().padding(3.dp)) {
            val segment = maxWidth / options.size
            Box(
                Modifier
                    .offset { IntOffset((segment.toPx() * position).roundToInt(), 0) }
                    .width(segment)
                    .fillMaxHeight()
                    .clip(shape)
                    .background(NLColors.gradient)
            )
        }
        // The labels (same padding as the thumb so both line up).
        Row(Modifier.matchParentSize().padding(3.dp)) {
            options.forEachIndexed { index, label ->
                val textColor by animateColorAsState(
                    targetValue = if (index == selectedIndex) Color.White else scheme.onSurfaceVariant,
                    animationSpec = tween(250),
                    label = "tabText"
                )
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(label, color = textColor, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Lighter sibling of [GradientButton]: translucent surface with a thin neon outline.
 * Used for secondary actions such as "Browse App Data".
 */
@Composable
fun SecondaryButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 52.dp
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .height(height)
            .bounceClick(onClick = onClick)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.2.dp, NLColors.gradient, shape)
            .padding(horizontal = 28.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            Icon(icon, contentDescription = null, tint = NLColors.Blue, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}
