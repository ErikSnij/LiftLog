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
import kotlinx.coroutines.flow.drop
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

    // Report the selection whenever centeredIndex itself changes, rather than watching
    // isScrollInProgress for a settle: snapshotFlow only guarantees delivering the latest value,
    // not every intermediate one, so a quick, short scroll (settle happening within a couple of
    // frames — e.g. nudging reps from 7 to 9) can have its true→false transition collapse away
    // entirely before the collector next resumes, silently dropping the report even though the
    // wheel visibly (and correctly) settled — the picker looks right but the pending value never
    // updates, so confirming later saves the old number. centeredIndex has no such gap: it's a
    // live layout-derived value, and once scrolling truly stops it stays put, so the collector is
    // guaranteed to eventually observe that final value even if some earlier ones were coalesced.
    // `drop(1)` skips only the very first (mount-time) emission — without it, simply opening the
    // picker for a value that isn't an exact grid step (e.g. a manually-typed custom weight) would
    // report the coerced starting index right away, silently overwriting that value.
    LaunchedEffect(state) {
        snapshotFlow { centeredIndex }.drop(1).collect { index -> onSelected(index) }
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

/** Value domain for a wheel: a leading null (—/BW) then 0..[max] in [step] increments. */
fun wheelValues(max: Float, step: Float = 0.5f): List<Float?> {
    val steps = (max / step).toInt()
    return buildList(steps + 2) {
        add(null)
        for (i in 0..steps) add(i * step)
    }
}

/**
 * Index of [value] in [values], or — for a non-null value that isn't an exact grid step (e.g. a
 * custom weight from a machine on a different increment than this wheel) — the index of the
 * closest one, so the picker opens centered near the real value instead of snapping to the
 * leading null/BW slot.
 */
fun indexOfValue(values: List<Float?>, value: Float?): Int {
    val exact = values.indexOfFirst { it == value }
    if (exact >= 0) return exact
    if (value == null) return 0
    return values.indices
        .filter { values[it] != null }
        .minByOrNull { kotlin.math.abs(values[it]!! - value) }
        ?: 0
}
