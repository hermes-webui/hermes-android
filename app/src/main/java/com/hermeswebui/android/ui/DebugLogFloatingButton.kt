package com.hermeswebui.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Draggable floating "Save log" button used in debug builds so testers can
 * one-tap share the latest captured Hermes debug log without navigating to
 * Settings. The button position is kept clamped inside the parent bounds and
 * remembered for the lifetime of the composition.
 *
 * Tap → [onTap] (e.g. share the latest log file).
 * Long-press / drag → repositions the button.
 */
@Composable
fun DebugLogFloatingOverlay(
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val buttonSizeDp = 56.dp
        val edgePaddingDp = 8.dp

        val maxXPx = with(density) { (maxWidth - buttonSizeDp - edgePaddingDp).toPx() }
        val maxYPx = with(density) { (maxHeight - buttonSizeDp - edgePaddingDp).toPx() }
        val edgePaddingPx = with(density) { edgePaddingDp.toPx() }

        // Default position: bottom-right corner, above system gesture area.
        val defaultX = maxXPx
        val defaultY = with(density) { (maxHeight - buttonSizeDp - 96.dp).toPx() }

        var offsetX by remember { mutableStateOf(defaultX) }
        var offsetY by remember { mutableStateOf(defaultY) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .size(buttonSizeDp)
                .shadow(8.dp, RoundedCornerShape(50))
                .clip(RoundedCornerShape(50))
                .background(MaterialTheme.colorScheme.errorContainer)
                .semantics { contentDescription = "Save Hermes debug log (drag to move)" }
                .pointerInput(maxXPx, maxYPx) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX = (offsetX + dragAmount.x).coerceIn(edgePaddingPx, maxXPx)
                        offsetY = (offsetY + dragAmount.y).coerceIn(edgePaddingPx, maxYPx)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onTap() })
                },
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.BugReport,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Anchored hint label below the button so the tester knows what it does
        // even if the icon is unfamiliar. Kept small + non-interactive.
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (offsetX - 24).roundToInt().coerceAtLeast(0),
                        (offsetY + with(density) { buttonSizeDp.toPx() } + 2f).roundToInt()
                    )
                }
                .background(Color(0x99000000), RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = "Save log",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}







