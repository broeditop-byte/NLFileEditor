package com.notlexi.nlfileeditor.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.notlexi.nlfileeditor.ui.theme.NLColors

/** Click that shrinks the element slightly while pressed (button press feedback). */
fun Modifier.bounceClick(enabled: Boolean = true, onClick: () -> Unit): Modifier = composed {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) 0.94f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "pressScale"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interactionSource = source, indication = null, enabled = enabled, onClick = onClick)
}

/**
 * Soft blue-purple halo behind the Upload button that breathes in and out.
 *
 * Why this does not stutter:
 *  - The infinite transition lives in THIS composable, which has no changing inputs, so
 *    recomposition of the screen (loading state, theme change...) can never restart it.
 *  - The pulse value is only read inside graphicsLayer { }, i.e. in the layer phase. Nothing is
 *    recomposed, re-measured or re-drawn per frame; the GPU just changes alpha/scale of a halo
 *    that was drawn once (drawWithCache).
 *  - ModulateAlpha avoids an off-screen buffer (which would clip the halo to the button bounds).
 */
@Composable
private fun PulsingGlow(modifier: Modifier, cornerRadius: Dp) {
    val transition = rememberInfiniteTransition(label = "buttonGlow")
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )
    Box(
        modifier = modifier
            .graphicsLayer {
                val p = pulse.value // read in the layer phase only
                alpha = 0.45f + 0.55f * p
                scaleX = 1f + 0.06f * p
                scaleY = 1f + 0.18f * p
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawWithCache {
                val steps = 6
                val spread = 26.dp.toPx()
                val radius = cornerRadius.toPx()
                val brushes = List(steps) { index ->
                    val a = 0.20f * (1f - (index + 1f) / (steps + 1f))
                    val grow = spread * (index + 1) / steps
                    Brush.horizontalGradient(
                        colors = listOf(NLColors.Blue.copy(alpha = a), NLColors.Purple.copy(alpha = a)),
                        startX = -grow,
                        endX = size.width + grow
                    )
                }
                onDrawBehind {
                    for (index in steps - 1 downTo 0) {
                        val grow = spread * (index + 1) / steps
                        drawRoundRect(
                            brush = brushes[index],
                            topLeft = Offset(-grow, -grow),
                            size = Size(size.width + 2 * grow, size.height + 2 * grow),
                            cornerRadius = CornerRadius(radius + grow)
                        )
                    }
                }
            }
    )
}

/** Large rounded gradient button. [glow] adds the soft pulsing halo behind it. */
@Composable
fun GradientButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 64.dp,
    glow: Boolean = false,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(height / 2)
    // propagateMinConstraints lets the button fill the width when the caller asks for it
    // (fillMaxWidth) and wrap its content otherwise.
    Box(
        modifier = modifier.height(height),
        contentAlignment = Alignment.Center,
        propagateMinConstraints = true
    ) {
        if (glow && enabled) PulsingGlow(Modifier.matchParentSize(), cornerRadius = height / 2)
        Box(
            modifier = Modifier
                .bounceClick(enabled, onClick)
                .clip(shape)
                .background(if (enabled) NLColors.gradient else SolidColor(MaterialTheme.colorScheme.outline))
                .padding(horizontal = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(12.dp))
                Text(text, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Compact gradient pill, used for the Save button in the top bar. */
@Composable
fun GradientPill(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier = modifier
            .height(40.dp)
            .bounceClick(enabled, onClick)
            .clip(RoundedCornerShape(20.dp))
            .background(if (enabled) NLColors.gradient else SolidColor(MaterialTheme.colorScheme.outline))
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        }
    }
}

/** Small circular "Info" button with a neon gradient ring (home screen corner). */
@Composable
fun InfoButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(48.dp)
            .bounceClick(onClick = onClick)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.55f))
            .border(1.5.dp, NLColors.gradient, CircleShape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Rounded.Info,
            contentDescription = "About NotLexi",
            tint = NLColors.Blue,
            modifier = Modifier.size(24.dp)
        )
    }
}
