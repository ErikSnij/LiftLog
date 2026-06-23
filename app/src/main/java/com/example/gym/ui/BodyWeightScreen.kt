package com.example.gym.ui

import androidx.compose.foundation.Canvas
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.gym.LiftLogApp
import com.example.gym.data.BodyWeightEntity
import kotlinx.coroutines.launch
import java.time.LocalDate

@Composable
fun BodyWeightScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val dao = (LocalContext.current.applicationContext as LiftLogApp).database.dao()
    val scope = rememberCoroutineScope()

    val history by remember { dao.observeBodyWeightHistory() }
        .collectAsStateWithLifecycle(emptyList())

    // draft: the entry currently being edited/created; null = no active edit
    var draft by remember { mutableStateOf<BodyWeightEntity?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    // Default draft: today's date, last recorded weight (or blank)
    fun newDraft() = BodyWeightEntity(
        weight = history.firstOrNull()?.weight ?: 0f,
        date = LocalDate.now(),
    )

    Box(modifier = modifier.fillMaxSize()) {
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
                Text("Body Weight", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(Modifier.weight(1f))
                Text(
                    "+ Log",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { draft = newDraft() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
            HorizontalDivider()

            BwChart(history)
            HorizontalDivider()

            draft?.let { d ->
                BwEditor(
                    draft = d,
                    isNew = d.id == 0L,
                    onWeightSelected = { draft = d.copy(weight = it ?: 0f) },
                    onDateShift = { days -> draft = d.copy(date = d.date.plusDays(days)) },
                    onSave = {
                        val toSave = d
                        draft = null
                        scope.launch { dao.upsertBodyWeight(toSave) }
                    },
                    onDelete = {
                        val toDelete = d
                        draft = null
                        scope.launch {
                            dao.deleteBodyWeight(toDelete.id)
                            val res = snackbarHostState.showSnackbar(
                                message = "Entry deleted",
                                actionLabel = "Undo",
                                duration = SnackbarDuration.Short,
                            )
                            if (res == SnackbarResult.ActionPerformed) dao.upsertBodyWeight(toDelete)
                        }
                    },
                    onCancel = { draft = null },
                )
                HorizontalDivider()
            }

            Text(
                "History (${history.size}) — tap to edit",
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp, top = 6.dp, bottom = 2.dp),
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(history, key = { it.id }) { entry ->
                    BwEntryRow(
                        entry = entry,
                        selected = draft?.id == entry.id,
                        onClick = { draft = entry },
                    )
                }
            }
        }
        SnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun BwChart(history: List<BodyWeightEntity>) {
    val sorted = remember(history) { history.sortedBy { it.date } }

    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current

    if (sorted.isEmpty()) {
        Box(modifier = Modifier.fillMaxWidth().height(180.dp), contentAlignment = Alignment.Center) {
            Text("No data yet", fontSize = 12.sp, color = labelColor)
        }
        return
    }

    Canvas(modifier = Modifier.fillMaxWidth().height(190.dp).padding(top = 8.dp, bottom = 4.dp, start = 8.dp, end = 8.dp)) {
        val labelSp     = with(density) { 9.sp.toPx() }
        val dotR        = with(density) { 5.dp.toPx() }
        val leftMargin  = with(density) { 46.dp.toPx() }
        val rightMargin = with(density) { 8.dp.toPx() }
        val topMargin   = with(density) { 10.dp.toPx() }
        val bottomMargin = with(density) { 22.dp.toPx() }

        val cLeft   = leftMargin
        val cRight  = size.width - rightMargin
        val cTop    = topMargin
        val cBottom = size.height - bottomMargin
        val cW = cRight - cLeft
        val cH = cBottom - cTop

        val minV = sorted.minOf { it.weight }
        val maxV = sorted.maxOf { it.weight }
        val vPad = (maxV - minV).coerceAtLeast(1f) * 0.15f
        val yLo = minV - vPad
        val yHi = maxV + vPad

        val hPad = with(density) { 12.dp.toPx() }
        val minD = sorted.first().date.toEpochDay()
        val maxD = sorted.last().date.toEpochDay()
        val dRange = (maxD - minD).coerceAtLeast(1L)

        fun xOf(d: Long) = if (sorted.size == 1) cLeft + cW / 2f
            else cLeft + hPad + (d - minD).toFloat() / dRange * (cW - 2 * hPad)
        fun yOf(v: Float) = cBottom - (v - yLo) / (yHi - yLo) * cH

        drawLine(axisColor, Offset(cLeft, cTop), Offset(cLeft, cBottom), strokeWidth = 1.5f)
        drawLine(axisColor, Offset(cLeft, cBottom), Offset(cRight, cBottom), strokeWidth = 1.5f)

        val yLabelPaint = android.graphics.Paint().apply {
            textSize = labelSp; isAntiAlias = true; color = labelColor.toArgb()
            textAlign = android.graphics.Paint.Align.RIGHT
        }
        val gridInts = if (minV == maxV) listOf(Math.round(minV))
            else listOf(Math.round(minV), Math.round((minV + maxV) / 2f), Math.round(maxV)).distinct()
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

        val offsets = sorted.map { Offset(xOf(it.date.toEpochDay()), yOf(it.weight)) }

        for (i in 0 until offsets.size - 1) {
            drawLine(lineColor, offsets[i], offsets[i + 1], strokeWidth = 3f)
        }
        offsets.forEach {
            drawCircle(lineColor, radius = dotR, center = it)
            drawCircle(Color.White, radius = dotR * 0.45f, center = it)
        }

        drawIntoCanvas { canvas ->
            val dateIndices = if (sorted.size <= 5) sorted.indices.toList() else listOf(0, sorted.size - 1)
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
                    formatMonthDay(sorted[i].date),
                    ox, cBottom + labelSp + with(density) { 3.dp.toPx() }, datePaint,
                )
            }
        }
    }
}

@Composable
private fun BwEditor(
    draft: BodyWeightEntity,
    isNew: Boolean,
    onWeightSelected: (Float?) -> Unit,
    onDateShift: (Long) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val weightValues = remember { wheelValues(300f) }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Weight:", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            WheelOrTypeField(
                items = weightValues,
                current = draft.weight.takeIf { it > 0f },
                width = 72.dp,
                blankLabel = "—",
                onSelected = onWeightSelected,
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(formatDate(draft.date), fontSize = 12.sp, modifier = Modifier.padding(bottom = 4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    StepperButton("−1m") { onDateShift(-30) }
                    StepperButton("−1d") { onDateShift(-1) }
                    StepperButton("+1d") { onDateShift(1) }
                    StepperButton("+1m") { onDateShift(30) }
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
}

@Composable
private fun BwEntryRow(entry: BodyWeightEntity, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
                else Modifier
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(formatDate(entry.date), fontSize = 13.sp, modifier = Modifier.width(110.dp))
        Text(
            "${trimFloat(entry.weight)} kg",
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
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

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
