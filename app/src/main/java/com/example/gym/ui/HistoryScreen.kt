package com.example.gym.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.gym.LiftLogApp
import com.example.gym.data.LogEntryEntity
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Metric { WEIGHT, ONE_RM }

@Composable
fun HistoryScreen(setRowId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dao = (LocalContext.current.applicationContext as LiftLogApp).database.dao()
    val scope = rememberCoroutineScope()

    val history by remember(setRowId) { dao.observeSetRowHistory(setRowId) }
        .collectAsStateWithLifecycle(emptyList())
    val name by remember(setRowId) { dao.observeExerciseNameForSetRow(setRowId) }
        .collectAsStateWithLifecycle(null)
    val note by remember(setRowId) { dao.observeSetRow(setRowId).map { it?.note } }
        .collectAsStateWithLifecycle(null)

    var metric by remember { mutableStateOf(Metric.WEIGHT) }
    var draft by remember { mutableStateOf<LogEntryEntity?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        // Top bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onBack).padding(end = 12.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(name ?: "History", fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1)
                if (!note.isNullOrBlank()) {
                    Text(
                        note!!,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        HorizontalDivider()

        MetricToggle(metric) { metric = it }
        HistoryChart(history, metric)
        HorizontalDivider()

        // Editor lives OUTSIDE the scrolling list — nesting a vertical wheel inside a
        // vertical LazyColumn lets the list steal the drag, so the wheels wouldn't scroll.
        draft?.let { d ->
            EntryEditor(
                draft = d,
                onRepsSelected = { draft = draft?.copy(reps = it) },
                onWeightSelected = { draft = draft?.copy(weight = it) },
                onDateShift = { days -> draft = draft?.let { it.copy(date = it.date.plusDays(days)) } },
                onSave = {
                    scope.launch { dao.updateLogEntry(d) }
                    draft = null
                },
                onDelete = {
                    scope.launch { dao.deleteLogEntry(d.id) }
                    draft = null
                },
                onCancel = { draft = null },
            )
            HorizontalDivider()
        }

        Text(
            "History (${history.size}) — tap an entry to edit",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history, key = { it.id }) { entry ->
                EntryRow(entry, selected = draft?.id == entry.id) { draft = entry }
            }
        }
    }
}

@Composable
private fun MetricToggle(metric: Metric, onChange: (Metric) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToggleChip("Weight", metric == Metric.WEIGHT) { onChange(Metric.WEIGHT) }
        ToggleChip("Est. 1RM", metric == Metric.ONE_RM) { onChange(Metric.ONE_RM) }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.surfaceVariant
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
    ) {
        Text(
            label,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            color = if (selected) MaterialTheme.colorScheme.onPrimary
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun HistoryChart(history: List<LogEntryEntity>, metric: Metric) {
    // Points (oldest → newest) with a defined value for the chosen metric.
    val points = remember(history, metric) {
        history.sortedBy { it.date }.mapNotNull { e ->
            val v = when (metric) {
                Metric.WEIGHT -> e.weight
                Metric.ONE_RM -> epley(e.reps, e.weight)
            }
            if (v != null) ChartPoint(e.date, v, e.reps) else null
        }
    }

    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    Box(modifier = Modifier.fillMaxWidth().height(200.dp).padding(12.dp)) {
        if (points.isEmpty()) {
            Text(
                "No numeric data to plot",
                fontSize = 12.sp,
                color = labelColor,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        val labelPx = with(density) { 9.sp.toPx() }
        Canvas(modifier = Modifier.fillMaxSize()) {
            val left = 8f
            val right = size.width - 8f
            val top = 24f
            val bottom = size.height - 20f

            val minV = points.minOf { it.value }
            val maxV = points.maxOf { it.value }
            val vRange = (maxV - minV).takeIf { it > 0f } ?: 1f
            val minD = points.first().date.toEpochDay()
            val maxD = points.last().date.toEpochDay()
            val dRange = (maxD - minD).takeIf { it > 0 } ?: 1L

            fun x(d: Long) = if (points.size == 1) (left + right) / 2f
            else left + (d - minD).toFloat() / dRange * (right - left)
            fun y(v: Float) = bottom - (v - minV) / vRange * (bottom - top)

            // baseline
            drawLine(axisColor, Offset(left, bottom), Offset(right, bottom), strokeWidth = 2f)

            val offsets = points.map { Offset(x(it.date.toEpochDay()), y(it.value)) }
            for (i in 0 until offsets.size - 1) {
                drawLine(lineColor, offsets[i], offsets[i + 1], strokeWidth = 3f)
            }
            offsets.forEach { drawCircle(lineColor, radius = 6f, center = it) }

            // rep labels above each point
            drawIntoCanvas { canvas ->
                val paint = android.graphics.Paint().apply {
                    color = labelColor.toArgb()
                    textSize = labelPx
                    textAlign = android.graphics.Paint.Align.CENTER
                    isAntiAlias = true
                }
                points.forEachIndexed { i, p ->
                    val reps = p.reps?.let(::trimFloat) ?: "?"
                    canvas.nativeCanvas.drawText(
                        "${reps}r",
                        offsets[i].x,
                        offsets[i].y - 12f,
                        paint,
                    )
                }
            }
        }
        // Min/max value labels (top-left = max, bottom-left = min)
        Text(
            round1(points.maxOf { it.value }),
            fontSize = 9.sp,
            color = labelColor,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            round1(points.minOf { it.value }),
            fontSize = 9.sp,
            color = labelColor,
            modifier = Modifier.align(Alignment.BottomStart),
        )
    }
}

@Composable
private fun EntryRow(entry: LogEntryEntity, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatDate(entry.date), fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(
            formatValue(entry.reps, entry.weight),
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(1f),
        )
        val oneRm = epley(entry.reps, entry.weight)
        Text(
            if (oneRm != null) "1RM ${round1(oneRm)}" else "",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
}

@Composable
private fun EntryEditor(
    draft: LogEntryEntity,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
    onDateShift: (Long) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val repsValues = remember { wheelValues(60f) }
    val weightValues = remember { wheelValues(300f) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WheelPicker(
                items = repsValues,
                initialIndex = indexOfValue(repsValues, draft.reps),
                modifier = Modifier.width(56.dp),
                label = { it?.let(::trimFloat) ?: "—" },
                onSelected = { onRepsSelected(repsValues[it]) },
            )
            Text("×", fontSize = 13.sp)
            WheelPicker(
                items = weightValues,
                initialIndex = indexOfValue(weightValues, draft.weight),
                modifier = Modifier.width(64.dp),
                label = { it?.let(::trimFloat) ?: "BW" },
                onSelected = { onWeightSelected(weightValues[it]) },
            )
            Spacer(Modifier.weight(1f))
            // Date steppers
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepperButton("−1m") { onDateShift(-30) }
                    StepperButton("−1d") { onDateShift(-1) }
                    StepperButton("+1d") { onDateShift(1) }
                    StepperButton("+1m") { onDateShift(30) }
                }
                Text(formatDate(draft.date), fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "Delete",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.clickable(onClick = onDelete),
            )
            Spacer(Modifier.weight(1f))
            Text(
                "Cancel",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onCancel),
            )
            Text(
                "Save",
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable(onClick = onSave),
            )
        }
    }
    HorizontalDivider()
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

private data class ChartPoint(val date: LocalDate, val value: Float, val reps: Float?)

// android.graphics needs an ARGB int; bridge from Compose Color.
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
