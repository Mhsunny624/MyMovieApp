package com.mymovieapp.app.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * A draggable scroll handle on the right edge. It appears while the list scrolls (or while you drag it)
 * and fades away afterwards. Works with any list: pass its item count, first visible index and a jump action.
 */
@Composable
fun FastScroller(
    itemCount: Int,
    firstVisible: () -> Int,
    scrolling: Boolean,
    onJump: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    if (itemCount < 12) return
    var dragFrac by remember { mutableStateOf<Float?>(null) }
    var show by remember { mutableStateOf(false) }
    val dragging = dragFrac != null
    LaunchedEffect(scrolling, dragging) {
        if (scrolling || dragging) show = true else { delay(1500); show = false }
    }
    val alpha by animateFloatAsState(if (show) 1f else 0f, label = "scroller")

    var trackH by remember { mutableIntStateOf(0) }
    val thumbH = with(LocalDensity.current) { 56.dp.roundToPx() }
    val range = (trackH - thumbH).coerceAtLeast(1)
    val natural = firstVisible().toFloat() / (itemCount - 1)
    val frac = (dragFrac ?: natural).coerceIn(0f, 1f)

    val onJumpNow by rememberUpdatedState(onJump)
    val countNow by rememberUpdatedState(itemCount)
    val fracNow by rememberUpdatedState(frac)

    Box(modifier.fillMaxHeight().width(28.dp).onSizeChanged { trackH = it.height }) {
        if (alpha > 0.01f) {
            Box(
                Modifier
                    .offset { IntOffset(0, (frac * range).roundToInt()) }
                    .size(28.dp, 56.dp)
                    .alpha(alpha)
                    .pointerInput(range) {
                        detectVerticalDragGestures(
                            onDragStart = { dragFrac = fracNow },
                            onVerticalDrag = { change, dy ->
                                change.consume()
                                val f = ((dragFrac ?: fracNow) + dy / range).coerceIn(0f, 1f)
                                dragFrac = f
                                onJumpNow((f * (countNow - 1)).roundToInt())
                            },
                            onDragEnd = { dragFrac = null },
                            onDragCancel = { dragFrac = null }
                        )
                    },
                contentAlignment = Alignment.CenterEnd
            ) {
                Box(
                    Modifier
                        .padding(end = 4.dp)
                        .size(10.dp, 48.dp)
                        .background(Color(0xCCF4F3F1), RoundedCornerShape(5.dp))
                )
            }
        }
    }
}