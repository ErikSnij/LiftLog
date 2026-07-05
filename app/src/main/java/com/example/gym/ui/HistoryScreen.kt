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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
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
import com.example.gym.data.BodyWeightEntity
import com.example.gym.data.LogEntryEntity
import kotlinx.coroutines.launch
import java.time.LocalDate

private enum class Metric { WEIGHT, REPS_AT_MAX, ONE_RM }

@Composable
fun HistoryScreen(exerciseId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dao = (LocalContext.current.applicationContext as LiftLogApp).database.dao()
    val scope = rememberCoroutineScope()

    val history by remember(exerciseId) { dao.observeExerciseHistory(exerciseId) }
        .collectAsStateWithLifecycle(emptyList())
    val name by remember(exerciseId) { dao.observeExerciseNameById(exerciseId) }
        .collectAsStateWithLifecycle(null)
    val firstSetRowId by remember(exerciseId) { dao.observeFirstSetRowId(exerciseId) }
        .collectAsStateWithLifecycle(null)
    val bodyWeights by remember { dao.observeBodyWeightHistory() }
        .collectAsStateWithLifecycle(emptyList())

    var metric by remember { mutableStateOf(Metric.REPS_AT_MAX) }
    var draft by remember { mutableStateOf<LogEntryEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    fun newDraft(): LogEntryEntity? {
        val srId = firstSetRowId ?: return null
        return LogEntryEntity(
            setRowId = srId,
            reps = history.firstOrNull()?.reps,
            weight = history.firstOrNull()?.weight,
            date = LocalDate.now(),
        )
    }

    Box(modifier = modifier.fillMaxSize().imePadding()) {
    Column(modifier = Modifier.fillMaxSize()) {
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
            Text(
                name ?: "History",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
        }
        HorizontalDivider()

        MetricToggle(metric) { metric = it }
        HistoryChart(history, metric, bodyWeights)
        HorizontalDivider()

        // Editor lives OUTSIDE the scrolling list — nesting a vertical wheel inside a
        // vertical LazyColumn lets the list steal the drag, so the wheels wouldn't scroll.
        draft?.let { d ->
            EntryEditor(
                draft = d,
                isNew = d.id == 0L,
                onRepsSelected = { draft = draft?.copy(reps = it) },
                onWeightSelected = { draft = draft?.copy(weight = it) },
                onDateShift = { days -> draft = draft?.let { it.copy(date = it.date.plusDays(days)) } },
                onYearShift = { years -> draft = draft?.let { it.copy(date = it.date.plusYears(years)) } },
                onSave = {
                    val isNew = d.id == 0L
                    scope.launch { if (isNew) dao.insertLogEntry(d) else dao.updateLogEntry(d) }
                    draft = null
                },
                onDelete = {
                    val deleted = d
                    draft = null
                    scope.launch {
                        dao.deleteLogEntry(deleted.id)
                        val res = snackbarHostState.showSnackbar(
                            message = "Entry deleted",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        // Re-insert with the original id so the chart/history restore exactly.
                        if (res == SnackbarResult.ActionPerformed) dao.insertLogEntry(deleted)
                    }
                },
                onCancel = { draft = null },
            )
            HorizontalDivider()
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "History (${history.size})",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            Text(
                "+ Add",
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { if (draft == null) newDraft()?.let { draft = it } }
                    .padding(horizontal = 10.dp, vertical = 4.dp),
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(history, key = { it.id }) { entry ->
                EntryRow(entry, selected = draft?.id == entry.id) { draft = entry }
            }
        }
    }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun MetricToggle(metric: Metric, onChange: (Metric) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        ToggleChip("Weight", metric == Metric.WEIGHT) { onChange(Metric.WEIGHT) }
        ToggleChip("Reps @ Max", metric == Metric.REPS_AT_MAX) { onChange(Metric.REPS_AT_MAX) }
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
private fun HistoryChart(history: List<LogEntryEntity>, metric: Metric, bodyWeights: List<BodyWeightEntity>) {
    val sortedBW = remember(bodyWeights) { bodyWeights.sortedBy { it.date } }
    val points = remember(history, metric, sortedBW) {
        fun resolvedWeight(e: LogEntryEntity) = e.weight ?: sortedBW.lastOrNull { it.date <= e.date }?.weight
        val maxWeightEver = if (metric == Metric.REPS_AT_MAX) history.mapNotNull(::resolvedWeight).maxOrNull() else null
        history.sortedBy { it.date }.mapNotNull { e ->
            val w = resolvedWeight(e)
            val v = when (metric) {
                Metric.WEIGHT -> w
                Metric.ONE_RM -> epley(e.reps, w)
                Metric.REPS_AT_MAX -> {
                    val e1rm = epley(e.reps, w)
                    if (e1rm != null && maxWeightEver != null) repsAtWeight(e1rm, maxWeightEver) else null
                }
            }
            if (v != null) ChartPoint(e.date, v, e.reps) else null
        }
    }

    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    if (points.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Text("No numeric data to plot", fontSize = 12.sp, color = labelColor)
        }
        return
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)) {
        val labelSp  = with(density) { 9.sp.toPx() }
        val dotR     = with(density) { 5.dp.toPx() }
        val leftMargin   = with(density) { 46.dp.toPx() }
        val rightMargin  = with(density) { 8.dp.toPx() }
        val topMargin    = with(density) { 18.dp.toPx() }   // room for rep labels
        val bottomMargin = with(density) { 22.dp.toPx() }   // room for date labels

        val cLeft   = leftMargin
        val cRight  = size.width - rightMargin
        val cTop    = topMargin
        val cBottom = size.height - bottomMargin
        val cW = cRight - cLeft
        val cH = cBottom - cTop

        val minV = points.minOf { it.value }
        val maxV = points.maxOf { it.value }
        val mean = (minV + maxV) / 2f
        val halfRange = maxOf(mean * 0.30f, (maxV - minV) / 2f * 1.15f).coerceAtLeast(1f)
        val yLo = (mean - halfRange).coerceAtLeast(0f)
        val yHi = mean + halfRange

        val hPad = with(density) { 12.dp.toPx() }
        val minD = points.first().date.toEpochDay()
        val maxD = points.last().date.toEpochDay()
        val dRange = (maxD - minD).coerceAtLeast(1L)

        fun xOf(d: Long) = if (points.size == 1) cLeft + cW / 2f
            else cLeft + hPad + (d - minD).toFloat() / dRange * (cW - 2 * hPad)
        fun yOf(v: Float) = cBottom - (v - yLo) / (yHi - yLo) * cH

        // Axis lines
        drawLine(axisColor, Offset(cLeft, cTop), Offset(cLeft, cBottom), strokeWidth = 1.5f)
        drawLine(axisColor, Offset(cLeft, cBottom), Offset(cRight, cBottom), strokeWidth = 1.5f)

        // Y grid + labels: min, mid, max of actual values
        val yLabelPaint = android.graphics.Paint().apply {
            textSize = labelSp; isAntiAlias = true; color = labelColor.toArgb()
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val gridInts = if (minV == maxV) listOf(Math.round(minV))
            else listOf(Math.round(yLo), Math.round((yLo + yHi) / 2f), Math.round(yHi)).distinct()
        drawIntoCanvas { canvas ->
            gridInts.forEach { v ->
                val gy = yOf(v.toFloat())
                drawLine(axisColor.copy(alpha = 0.35f), Offset(cLeft, gy), Offset(cRight, gy), strokeWidth = 1f)
                canvas.nativeCanvas.drawText(
                    "$v",
                    cLeft - with(density) { 5.dp.toPx() },
                    gy + labelSp * 0.35f,
                    yLabelPaint,
                )
            }
        }

        val offsets = points.map { Offset(xOf(it.date.toEpochDay()), yOf(it.value)) }

        // Lines between points
        for (i in 0 until offsets.size - 1) {
            drawLine(lineColor, offsets[i], offsets[i + 1], strokeWidth = 3f)
        }
        // Dots — filled white center so they stand out
        offsets.forEach {
            drawCircle(lineColor, radius = dotR, center = it)
            drawCircle(androidx.compose.ui.graphics.Color.White, radius = dotR * 0.45f, center = it)
        }

        drawIntoCanvas { canvas ->
            // Rep labels above each dot
            val repPaint = android.graphics.Paint().apply {
                textSize = labelSp; isAntiAlias = true; color = labelColor.toArgb()
                textAlign = android.graphics.Paint.Align.CENTER
            }
            points.forEachIndexed { i, p ->
                val reps = p.reps?.let(::trimFloat) ?: "?"
                canvas.nativeCanvas.drawText(
                    "${reps}r", offsets[i].x, offsets[i].y - dotR - with(density) { 3.dp.toPx() }, repPaint,
                )
            }

            // X date labels: all points if ≤ 5, otherwise first + last only
            val dateIndices = if (points.size <= 5) points.indices.toList() else listOf(0, points.size - 1)
            val datePaint = android.graphics.Paint().apply {
                textSize = labelSp; isAntiAlias = true; color = labelColor.toArgb()
            }
            dateIndices.forEach { i ->
                val ox = offsets[i].x
                datePaint.textAlign = when {
                    ox < cLeft + cW * 0.25f -> android.graphics.Paint.Align.LEFT
                    ox > cLeft + cW * 0.75f -> android.graphics.Paint.Align.RIGHT
                    else                     -> android.graphics.Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText(
                    formatMonthDay(points[i].date),
                    ox, cBottom + labelSp + with(density) { 3.dp.toPx() }, datePaint,
                )
            }
        }
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
    isNew: Boolean = false,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
    onDateShift: (Long) -> Unit,
    onYearShift: (Long) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val repsValues = remember { wheelValues(60f, step = 1f) }
    val weightValues = remember { wheelValues(300f) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            WheelOrTypeField(
                items = repsValues,
                current = draft.reps,
                width = 56.dp,
                blankLabel = "—",
                onSelected = onRepsSelected,
            )
            Text("×", fontSize = 13.sp)
            WheelOrTypeField(
                items = weightValues,
                current = draft.weight,
                width = 64.dp,
                blankLabel = "BW",
                onSelected = onWeightSelected,
            )
            Spacer(Modifier.weight(1f))
            // Date: label above, steppers below so thumb doesn't cover it
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatDate(draft.date), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    StepperButton("−1y") { onYearShift(-1) }
                    StepperButton("−1m") { onDateShift(-30) }
                    StepperButton("−1d") { onDateShift(-1) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 4.dp)) {
                    StepperButton("+1d") { onDateShift(1) }
                    StepperButton("+1m") { onDateShift(30) }
                    StepperButton("+1y") { onYearShift(1) }
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!isNew) {
                Text(
                    "Delete",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.clickable(onClick = onDelete),
                )
            }
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
