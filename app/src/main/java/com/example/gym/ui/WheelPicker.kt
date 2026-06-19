package com.example.gym.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

/**
 * iOS-style vertical scroll-wheel picker. Shows [visibleCount] rows; the centered row is the
 * selection. Snaps to items and reports the settled index via [onSelected].
 */
@Composable
fun WheelPicker(
    items: List<Float?>,
    initialIndex: Int,
    modifier: Modifier = Modifier,
    visibleCount: Int = 3,
    label: (Float?) -> String,
    onSelected: (Int) -> Unit,
    onCenterClick: (() -> Unit)? = null,
) {
    val itemHeight = 32.dp
    val safeInitial = initialIndex.coerceIn(0, (items.size - 1).coerceAtLeast(0))
    val state = rememberLazyListState(initialFirstVisibleItemIndex = safeInitial)
    val fling = rememberSnapFlingBehavior(state)
    val scope = rememberCoroutineScope()

    // The item whose centre is closest to the viewport centre is the current selection.
    val centeredIndex by remember {
        derivedStateOf {
            val info = state.layoutInfo
            val viewportCentre = (info.viewportStartOffset + info.viewportEndOffset) / 2f
            info.visibleItemsInfo.minByOrNull { abs((it.offset + it.size / 2f) - viewportCentre) }
                ?.index ?: safeInitial
        }
    }

    // Report the selection once scrolling settles.
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collect { scrolling ->
            if (!scrolling) onSelected(centeredIndex)
        }
    }

    Box(modifier = modifier.height(itemHeight * visibleCount), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(itemHeight)
                .background(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    RoundedCornerShape(6.dp),
                ),
        )
        LazyColumn(
            state = state,
            flingBehavior = fling,
            // Top/bottom padding so the first and last items can reach the centre slot.
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                vertical = itemHeight * ((visibleCount - 1) / 2),
            ),
        ) {
            itemsIndexed(items) { index, value ->
                Box(
                    Modifier
                        .height(itemHeight)
                        .fillMaxWidth()
                        // Tap the centered item → type a value; tap another → scroll to it.
                        .clickable {
                            if (index == centeredIndex) onCenterClick?.invoke()
                            else scope.launch { state.animateScrollToItem(index) }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    val selected = index == centeredIndex
                    Text(
                        text = label(value),
                        fontSize = if (selected) 16.sp else 14.sp,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                        color = if (selected) MaterialTheme.colorScheme.onSurface
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Shared value domain for reps & weight wheels: a leading null (—/BW) then 0..[max] in 0.5 steps. */
fun wheelValues(max: Float): List<Float?> {
    val steps = (max / 0.5f).toInt()
    return buildList(steps + 2) {
        add(null)
        for (i in 0..steps) add(i * 0.5f)
    }
}

fun indexOfValue(values: List<Float?>, value: Float?): Int =
    values.indexOfFirst { it == value }.let { if (it >= 0) it else 0 }
