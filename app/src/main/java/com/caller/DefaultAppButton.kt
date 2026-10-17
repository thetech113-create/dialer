package com.caller

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.sin

// Logo palette (see drawable/ic_launcher_phone_foreground.xml)
private val DialerBlue = Color(0xFF85C4FC)
private val DialerTeal = Color(0xFF28B9B8)
private val DialerInk = Color(0xFF141E28)

private val TrackHeight = 68.dp
private val KnobSize = 52.dp
private val KnobInset = 8.dp

/** How far (0..1 of the track) the knob must travel to count as a confirmed swipe. */
private const val CONFIRM_FRACTION = 0.85f

/**
 * "Set as default app" swipe-to-confirm (dialer, messages): gradient track in the app's logo
 * colors; drag the ringing icon knob to the right end to trigger [onClick]. Released early, the
 * knob springs back. Accessibility services can still activate it with a single action.
 */
@Composable
fun DefaultAppButton(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Call
) {
    val currentOnClick by rememberUpdatedState(onClick)
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val knobOffset = remember { Animatable(0f) }

    val transition = rememberInfiniteTransition(label = "defaultAppBtn")
    val shine by transition.animateFloat(
        initialValue = -0.6f,
        targetValue = 1.6f,
        animationSpec = infiniteRepeatable(tween(2800, easing = LinearEasing)),
        label = "defaultAppBtnShine"
    )
    val ring by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing)),
        label = "defaultAppBtnRing"
    )

    val shape = RoundedCornerShape(TrackHeight / 2)
    BoxWithConstraints(
        modifier = modifier
            .height(TrackHeight)
            .shadow(14.dp, shape, ambientColor = DialerTeal, spotColor = DialerTeal)
            .clip(shape)
            .background(Brush.linearGradient(listOf(DialerBlue, DialerTeal)))
            .drawWithContent {
                drawContent()
                val x = shine * size.width
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent),
                        start = Offset(x - size.width * 0.25f, 0f),
                        end = Offset(x, size.height)
                    )
                )
            }
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = "$title. Swipe right to confirm."
                // `this.` so it resolves to the semantics action, not the onClick parameter.
                this.onClick(label = title) {
                    currentOnClick()
                    true
                }
            }
    ) {
        val maxOffsetPx = with(density) { (maxWidth - KnobSize - KnobInset * 2).toPx() }.coerceAtLeast(1f)
        val progress = (knobOffset.value / maxOffsetPx).coerceIn(0f, 1f)
        // Knob "rings" only while resting; it holds still while being dragged.
        val wiggle = if (progress < 0.02f && ring < 0.3f) {
            sin(ring / 0.3f * 4f * 2f * PI.toFloat()) * 14f
        } else {
            0f
        }

        // Label + hint chevrons, fading out as the knob slides over them.
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = KnobSize + KnobInset * 2 + 6.dp, end = 18.dp)
                .graphicsLayer { alpha = 1f - progress },
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = DialerInk, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Text(text = subtitle, color = DialerInk.copy(alpha = 0.7f), fontSize = 12.sp, maxLines = 1)
            }
            SwipeHintChevrons(phase = ring)
        }

        // Draggable knob.
        Box(
            modifier = Modifier
                .padding(start = KnobInset)
                .align(Alignment.CenterStart)
                .offset { IntOffset(knobOffset.value.roundToInt(), 0) }
                .size(KnobSize)
                .drawBehind {
                    if (progress < 0.02f) {
                        drawCircle(
                            color = Color.White.copy(alpha = 0.55f * (1f - ring)),
                            radius = size.minDimension / 2f * (1f + 0.3f * ring),
                            style = Stroke(width = 2.dp.toPx())
                        )
                    }
                }
                .clip(CircleShape)
                .background(DialerInk)
                .draggable(
                    orientation = Orientation.Horizontal,
                    state = rememberDraggableState { delta ->
                        scope.launch { knobOffset.snapTo((knobOffset.value + delta).coerceIn(0f, maxOffsetPx)) }
                    },
                    onDragStopped = {
                        if (knobOffset.value >= maxOffsetPx * CONFIRM_FRACTION) {
                            knobOffset.animateTo(maxOffsetPx, tween(120))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            currentOnClick()
                        }
                        // Reset so the control is ready again (e.g. if the user cancels the system dialog).
                        knobOffset.animateTo(
                            0f,
                            spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow)
                        )
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = DialerBlue,
                modifier = Modifier
                    .size(24.dp)
                    .graphicsLayer { rotationZ = wiggle }
            )
        }
    }
}

/** Three right-pointing chevrons that light up in sequence to invite a swipe. */
@Composable
private fun SwipeHintChevrons(phase: Float) {
    Canvas(modifier = Modifier.size(width = 36.dp, height = 20.dp)) {
        val step = size.width / 3f
        for (i in 0 until 3) {
            // Brightest chevron travels left → right with the shared animation phase.
            val distance = kotlin.math.abs(phase * 3f - i - 0.5f).coerceAtMost(1.5f)
            val alpha = 1f - distance / 1.5f * 0.75f
            val left = i * step
            val chevron = Path().apply {
                moveTo(left + step * 0.25f, size.height * 0.2f)
                lineTo(left + step * 0.65f, size.height * 0.5f)
                lineTo(left + step * 0.25f, size.height * 0.8f)
            }
            drawPath(
                path = chevron,
                color = DialerInk.copy(alpha = alpha),
                style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            )
        }
    }
}
