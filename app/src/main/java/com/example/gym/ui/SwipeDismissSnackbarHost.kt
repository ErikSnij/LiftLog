package com.example.gym.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * A [SnackbarHost] whose snackbar can be swiped away in any direction (down or sideways) — for
 * when it's sitting on top of something you need to reach right away, rather than waiting the
 * few seconds for it to time out on its own.
 */
@Composable
fun SwipeDismissSnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier) { data -> SwipeDismissSnackbar(data) }
}

@Composable
private fun SwipeDismissSnackbar(data: SnackbarData) {
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    // How far along its own size a swipe needs to travel before it counts as "dismiss" rather
    // than "snap back" — measured against the snackbar's actual size so it works the same
    // regardless of how long its message happens to be.
    var widthPx by remember { mutableStateOf(0f) }
    var heightPx by remember { mutableStateOf(0f) }

    Box(
        modifier = Modifier
            .onGloballyPositioned {
                widthPx = it.size.width.toFloat()
                heightPx = it.size.height.toFloat()
            }
            .offset { IntOffset(offsetX.value.roundToInt(), offsetY.value.roundToInt()) }
            .pointerInput(data) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch {
                            offsetX.snapTo(offsetX.value + dragAmount.x)
                            // Only downward, not up into the content above it — swiping up would
                            // read as "put it back", which isn't a gesture anyone expects here.
                            offsetY.snapTo((offsetY.value + dragAmount.y).coerceAtLeast(0f))
                        }
                    },
                    onDragEnd = {
                        val minWidthPx = with(density) { 48.dp.toPx() }
                        val minHeightPx = with(density) { 32.dp.toPx() }
                        val pastX = widthPx > 0f && abs(offsetX.value) > maxOf(widthPx * 0.4f, minWidthPx)
                        val pastY = heightPx > 0f && offsetY.value > maxOf(heightPx * 0.6f, minHeightPx)
                        if (pastX || pastY) {
                            data.dismiss()
                        } else {
                            scope.launch { offsetX.animateTo(0f) }
                            scope.launch { offsetY.animateTo(0f) }
                        }
                    },
                )
            },
    ) {
        Snackbar(snackbarData = data)
    }
}
