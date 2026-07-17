package de.pflueger.servotester.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** Usable arc: 270° with a 90° gap at the bottom (6 o'clock). */
private const val START_ANGLE = 135.0   // Compose degrees: 0° = 3 o'clock, CW
private const val SWEEP_ANGLE = 270.0

/**
 * Large circular jogdial for stepless PWM setting (Lastenheft §3.1).
 *
 * Dragging (or tapping) anywhere on the ring maps the finger angle onto the
 * [min]..[max] window and reports it via [onValueChange]. The value shown is
 * [value] (the live, ramped position) so the pointer tracks what the servo
 * actually does, not just the raw target.
 *
 * [center] is rendered in the middle — the app puts the ON/OFF toggle there.
 */
@Composable
fun Jogdial(
    value: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit = {},
) {
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    val progressColor = MaterialTheme.colorScheme.primary
    val knobColor = MaterialTheme.colorScheme.secondary
    val tickColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)

    val fraction = remember(value, min, max) {
        if (max <= min) 0f else ((value - min).toFloat() / (max - min)).coerceIn(0f, 1f)
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .pointerInput(min, max) {
                    detectDragGestures(
                        onDragStart = { pos -> reportFromTouch(pos, size.width, size.height, min, max, onValueChange) },
                        onDrag = { change, _ ->
                            change.consume()
                            reportFromTouch(change.position, size.width, size.height, min, max, onValueChange)
                        },
                    )
                }
                .pointerInput(min, max) {
                    detectTapGestures { pos ->
                        reportFromTouch(pos, size.width, size.height, min, max, onValueChange)
                    }
                }
        ) {
            val stroke = size.minDimension * 0.09f
            val radius = (size.minDimension - stroke) / 2f
            val topLeft = Offset(
                (size.width - radius * 2) / 2f,
                (size.height - radius * 2) / 2f,
            )
            val arcSize = Size(radius * 2, radius * 2)
            val center = Offset(size.width / 2f, size.height / 2f)

            // Track (full usable arc).
            drawArc(
                color = trackColor,
                startAngle = START_ANGLE.toFloat(),
                sweepAngle = SWEEP_ANGLE.toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Progress up to current value.
            drawArc(
                color = progressColor,
                startAngle = START_ANGLE.toFloat(),
                sweepAngle = (SWEEP_ANGLE * fraction).toFloat(),
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round),
            )
            // Tick marks every 10% of the arc.
            for (i in 0..10) {
                val a = Math.toRadians(START_ANGLE + SWEEP_ANGLE * i / 10.0)
                val rOuter = radius + stroke * 0.15f
                val rInner = radius - stroke * 0.6f
                drawLine(
                    color = tickColor,
                    start = Offset(center.x + (rOuter * cos(a)).toFloat(), center.y + (rOuter * sin(a)).toFloat()),
                    end = Offset(center.x + (rInner * cos(a)).toFloat(), center.y + (rInner * sin(a)).toFloat()),
                    strokeWidth = size.minDimension * 0.006f,
                )
            }
            // Knob at the current angle.
            val knobAngle = Math.toRadians(START_ANGLE + SWEEP_ANGLE * fraction)
            val knobCenter = Offset(
                center.x + (radius * cos(knobAngle)).toFloat(),
                center.y + (radius * sin(knobAngle)).toFloat(),
            )
            drawCircle(color = Color.White, radius = stroke * 0.75f, center = knobCenter)
            drawCircle(color = knobColor, radius = stroke * 0.55f, center = knobCenter)
        }

        center()
    }
}

/** Map a touch position on the dial to a value in [min]..[max] and report it. */
private fun reportFromTouch(
    pos: Offset,
    width: Int,
    height: Int,
    min: Int,
    max: Int,
    onValueChange: (Int) -> Unit,
) {
    val cx = width / 2f
    val cy = height / 2f
    var angle = Math.toDegrees(atan2((pos.y - cy).toDouble(), (pos.x - cx).toDouble()))
    if (angle < 0) angle += 360.0
    // Unwrap into [START, START+360).
    var a = angle
    if (a < START_ANGLE) a += 360.0
    val rel = a - START_ANGLE
    val fraction = when {
        rel <= SWEEP_ANGLE -> rel / SWEEP_ANGLE
        // Inside the bottom gap: clamp to whichever end is closer.
        rel < SWEEP_ANGLE + (360.0 - SWEEP_ANGLE) / 2.0 -> 1.0
        else -> 0.0
    }
    val value = (min + fraction * (max - min)).roundToInt().coerceIn(min, max)
    onValueChange(value)
}
