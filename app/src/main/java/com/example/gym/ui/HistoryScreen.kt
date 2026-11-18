package com.example.gym.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.gym.LiftLogApp
import com.example.gym.data.BodyWeightEntity
import com.example.gym.data.LogEntryEntity
import com.example.gym.data.SyncQueueEntity
import com.example.gym.data.WeightConfig
import com.example.gym.data.weightConfig
import com.example.gym.sync.TrainHubSyncWorker
import kotlinx.coroutines.launch
import java.time.LocalDate
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

private enum class Metric(val label: String) {
    WEIGHT("Weight"),
    REPS_AT_MAX("Reps @ Max"),
    ONE_RM("Est. 1RM"),
}

@Composable
fun HistoryScreen(exerciseId: Long, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val dao = (context.applicationContext as LiftLogApp).database.dao()
    val scope = rememberCoroutineScope()

    val history by remember(exerciseId) { dao.observeExerciseHistory(exerciseId) }
        .collectAsStateWithLifecycle(emptyList())
    val name by remember(exerciseId) { dao.observeExerciseNameById(exerciseId) }
        .collectAsStateWithLifecycle(null)
    val firstSetRowId by remember(exerciseId) { dao.observeFirstSetRowId(exerciseId) }
        .collectAsStateWithLifecycle(null)
    val bodyWeights by remember { dao.observeBodyWeightHistory() }
        .collectAsStateWithLifecycle(emptyList())
    val exercise by remember(exerciseId) { dao.observeExercise(exerciseId) }
        .collectAsStateWithLifecycle(null)
    val weightConfig = remember(exercise) { exercise?.weightConfig() ?: WeightConfig() }

    var metric by remember { mutableStateOf(Metric.REPS_AT_MAX) }
    var draft by remember { mutableStateOf<LogEntryEntity?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    // Add-on weighting being adjusted (the panel is open while non-null); the chart follows it live.
    var weighting by remember(exerciseId) { mutableStateOf<Float?>(null) }
    var showBulkExtra by remember { mutableStateOf(false) }
    var showBulkEdit by remember { mutableStateOf(false) }
    // Multi-select: non-empty means the list is in selection mode (tap toggles, no single editor).
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    val selecting = selectedIds.isNotEmpty()
    val selected = history.filter { it.id in selectedIds }
    val snackbarHostState = remember { SnackbarHostState() }
    // Back leaves selection mode first, before it leaves the screen.
    BackHandler(enabled = selecting) { selectedIds = emptySet() }
    // Entries that vanish (deleted, undone) shouldn't linger in the selection.
    LaunchedEffect(history) {
        val live = history.mapTo(HashSet()) { it.id }
        if (selectedIds.any { it !in live }) selectedIds = selectedIds.filterTo(HashSet()) { it in live }
    }

    /** Applies [transform] to every selected entry, re-queues the affected days, and offers Undo. */
    fun editSelected(message: String, transform: (LogEntryEntity) -> LogEntryEntity) {
        val before = selected
        if (before.isEmpty()) return
        val after = before.map(transform)
        scope.launch {
            after.forEach { dao.updateLogEntry(it) }
            if (after.any { it.extraKg != null }) dao.setAddOnWeight(exerciseId, true)
            (before.map { it.date } + after.map { it.date }).distinct()
                .forEach { dao.enqueueDateForSync(SyncQueueEntity(date = it)) }
            TrainHubSyncWorker.requestImmediateSync(context)
            val res = snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            if (res == SnackbarResult.ActionPerformed) {
                before.forEach { dao.updateLogEntry(it) }
                (before.map { it.date } + after.map { it.date }).distinct()
                    .forEach { dao.enqueueDateForSync(SyncQueueEntity(date = it)) }
                TrainHubSyncWorker.requestImmediateSync(context)
            }
        }
    }

    fun deleteSelected() {
        val doomed = selected
        if (doomed.isEmpty()) return
        selectedIds = emptySet()
        scope.launch {
            doomed.forEach { dao.deleteLogEntry(it.id) }
            doomed.map { it.date }.distinct().forEach { dao.enqueueDateForSync(SyncQueueEntity(date = it)) }
            TrainHubSyncWorker.requestImmediateSync(context)
            val res = snackbarHostState.showSnackbar(
                message = if (doomed.size == 1) "Entry deleted" else "${doomed.size} entries deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short,
            )
            // Re-insert with the original ids so the chart/history restore exactly.
            if (res == SnackbarResult.ActionPerformed) {
                doomed.forEach { dao.insertLogEntry(it) }
                doomed.map { it.date }.distinct().forEach { dao.enqueueDateForSync(SyncQueueEntity(date = it)) }
                TrainHubSyncWorker.requestImmediateSync(context)
            }
        }
    }

    fun newDraft(): LogEntryEntity? {
        val srId = firstSetRowId ?: return null
        return LogEntryEntity(
            setRowId = srId,
            reps = history.firstOrNull()?.reps,
            weight = history.firstOrNull()?.weight,
            date = LocalDate.now(),
            extraKg = history.firstOrNull()?.extraKg,
        )
    }

    // Chart data for the chosen metric, shared by the hero and the chart. A tapped/dragged point
    // is shown in the hero; otherwise the latest session is.
    val sortedBW = remember(bodyWeights) { bodyWeights.sortedBy { it.date } }
    val savedFactor = exercise?.addOnFactor ?: 1f
    val addOnFactor = weighting ?: savedFactor
    val hasAddOn = history.any { it.extraKg != null }
    val chart = remember(history, metric, sortedBW, addOnFactor) { chartData(history, metric, sortedBW, addOnFactor) }
    var pickedPoint by remember(exerciseId, metric) { mutableStateOf<Int?>(null) }
    val shownPoint = pickedPoint?.takeIf { it in chart.points.indices } ?: chart.points.lastIndex.takeIf { it >= 0 }
    val haptics = LocalHapticFeedback.current

    // Material's default text style gives every line a 24sp box (1.7x a 14sp row's font), which is
    // what made this screen's rows and cards so tall. A line height proportional to each font keeps
    // the rounded look at a much tighter pitch.
    ProvideTextStyle(LocalTextStyle.current.copy(lineHeight = 1.3.em)) {
    Box(modifier = modifier.fillMaxSize().imePadding()) {
    Column(modifier = Modifier.fillMaxSize()) {
        // Top bar, matching the app's other screens.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(onClick = onBack)
                    .padding(horizontal = 4.dp, vertical = 6.dp)
                    .padding(end = 8.dp),
            )
            Text(
                name ?: "History",
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            // Rarely-needed actions live behind this, out of the way.
            Box {
                Text(
                    "⋯",
                    fontSize = 20.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { menuOpen = true }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Add-on weight for all entries…") },
                        onClick = { menuOpen = false; showBulkExtra = true },
                    )
                    if (hasAddOn) {
                        DropdownMenuItem(
                            text = { Text("Add-on weighting…") },
                            onClick = {
                                menuOpen = false
                                draft = null
                                selectedIds = emptySet()
                                weighting = savedFactor
                            },
                        )
                    }
                }
            }
        }

        HistoryHero(
            chart = chart,
            index = shownPoint,
            metric = metric,
            note = if (hasAddOn && addOnFactor != 1f) "add-on ×${trimFloat(addOnFactor)}" else null,
        )

        // Chart card with the metric switch underneath (as Progress has its range switch).
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
        ) {
            Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                if (chart.points.isEmpty()) {
                    Box(Modifier.fillMaxWidth().height(120.dp), contentAlignment = Alignment.Center) {
                        Text("No sets with reps and weight yet", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                } else {
                    SessionChart(
                        points = chart.points,
                        color = trendColor(chart.changeScore(chart.points.lastIndex)),
                        picked = pickedPoint?.takeIf { it in chart.points.indices },
                        onPick = { i ->
                            if (i != null && i != pickedPoint) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            pickedPoint = i
                        },
                        modifier = Modifier.fillMaxWidth().height(150.dp).padding(horizontal = 12.dp),
                    )
                }
                Spacer(Modifier.height(6.dp))
                SegmentedSelector(
                    labels = Metric.entries.map { it.label },
                    selectedIndex = metric.ordinal,
                    onSelect = { metric = Metric.entries[it] },
                    verticalPadding = 5.dp,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        // Editor lives OUTSIDE the scrolling list — nesting a vertical wheel inside a
        // vertical LazyColumn lets the list steal the drag, so the wheels wouldn't scroll.
        weighting?.let { w ->
            AddOnWeightingPanel(
                factor = w,
                onChange = { weighting = it },
                onSave = {
                    weighting = null
                    scope.launch { dao.setAddOnFactor(exerciseId, w) }
                },
                onCancel = { weighting = null },
            )
        }

        if (weighting == null) draft?.let { d ->
            EntryEditor(
                draft = d,
                isNew = d.id == 0L,
                weightConfig = weightConfig,
                addOnEnabled = exercise?.addOnWeight == true,
                onRepsSelected = { draft = draft?.copy(reps = it) },
                onWeightSelected = { draft = draft?.copy(weight = it) },
                onExtraSelected = { draft = draft?.copy(extraKg = it) },
                onDateShift = { days -> draft = draft?.let { it.copy(date = it.date.plusDays(days)) } },
                onYearShift = { years -> draft = draft?.let { it.copy(date = it.date.plusYears(years)) } },
                onSave = {
                    val isNew = d.id == 0L
                    // The entry may have been date-shifted — re-queue whichever day it used to
                    // belong to as well, so that day's (now smaller) session gets re-delivered too.
                    val previousDate = if (!isNew) history.find { it.id == d.id }?.date else null
                    scope.launch {
                        if (isNew) dao.insertLogEntry(d) else dao.updateLogEntry(d)
                        // Using an add-on at all means this exercise tracks one.
                        if (d.extraKg != null) dao.setAddOnWeight(exerciseId, true)
                        dao.enqueueDateForSync(SyncQueueEntity(date = d.date))
                        if (previousDate != null && previousDate != d.date) {
                            dao.enqueueDateForSync(SyncQueueEntity(date = previousDate))
                        }
                        TrainHubSyncWorker.requestImmediateSync(context)
                    }
                    draft = null
                },
                onDelete = {
                    val deleted = d
                    draft = null
                    scope.launch {
                        dao.deleteLogEntry(deleted.id)
                        dao.enqueueDateForSync(SyncQueueEntity(date = deleted.date))
                        TrainHubSyncWorker.requestImmediateSync(context)
                        val res = snackbarHostState.showSnackbar(
                            message = "Entry deleted",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        // Re-insert with the original id so the chart/history restore exactly.
                        if (res == SnackbarResult.ActionPerformed) {
                            dao.insertLogEntry(deleted)
                            dao.enqueueDateForSync(SyncQueueEntity(date = deleted.date))
                            TrainHubSyncWorker.requestImmediateSync(context)
                        }
                    }
                },
                onCancel = { draft = null },
            )
        }

        if (selecting) {
            SelectionBar(
                count = selected.size,
                allSelected = selected.size == history.size,
                onSelectAll = { selectedIds = history.mapTo(HashSet()) { it.id } },
                onEdit = { showBulkEdit = true },
                onDelete = ::deleteSelected,
                onClear = { selectedIds = emptySet() },
            )
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "HISTORY (${history.size})",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.3.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Select",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable(enabled = history.isNotEmpty()) {
                            draft = null
                            selectedIds = setOf(history.first().id)
                        }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
                Text(
                    "+ Add",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { if (draft == null) newDraft()?.let { draft = it } }
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                )
            }
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(bottom = 12.dp),
        ) {
            itemsIndexed(history, key = { _, it -> it.id }) { i, entry ->
                EntryRow(
                    entry = entry,
                    isFirst = i == 0,
                    isLast = i == history.lastIndex,
                    selected = if (selecting) entry.id in selectedIds else draft?.id == entry.id,
                    selecting = selecting,
                    addOnFactor = addOnFactor,
                    onClick = {
                        if (weighting != null) {
                            // The weighting panel is open: finish (Save / Cancel) that first.
                        } else if (selecting) {
                            selectedIds = if (entry.id in selectedIds) selectedIds - entry.id else selectedIds + entry.id
                        } else {
                            draft = entry
                        }
                    },
                    onLongClick = {
                        // Holding an entry starts selecting with it already ticked.
                        if (weighting == null) {
                            draft = null
                            selectedIds = selectedIds + entry.id
                        }
                    },
                )
            }
        }
    }
        SwipeDismissSnackbarHost(snackbarHostState, modifier = Modifier.align(Alignment.BottomCenter))

        if (showBulkEdit && selecting) {
            BulkEditDialog(
                count = selected.size,
                onDismiss = { showBulkEdit = false },
                onApply = { change ->
                    showBulkEdit = false
                    // A row is meant to have one entry per day (the tree's "log today" updates that
                    // one), so refuse a shift that would stack two entries on the same row and day.
                    if (change.shiftDays != 0) {
                        val taken = history.filter { it.id !in selectedIds }.mapTo(HashSet()) { it.setRowId to it.date }
                        val moved = selected.map { it.setRowId to it.date.plusDays(change.shiftDays.toLong()) }
                        if (moved.any { it in taken } || moved.toSet().size < moved.size) {
                            scope.launch {
                                snackbarHostState.showSnackbar(
                                    message = "Not applied: a shifted entry would land on a day that already has one",
                                    duration = SnackbarDuration.Short,
                                )
                            }
                            return@BulkEditDialog
                        }
                    }
                    val n = selected.size
                    editSelected("Edited $n ${if (n == 1) "entry" else "entries"}") { e ->
                        e.copy(
                            reps = change.reps ?: e.reps,
                            weight = change.weight ?: e.weight,
                            extraKg = if (change.clearExtra) null else change.extraKg ?: e.extraKg,
                            date = e.date.plusDays(change.shiftDays.toLong()),
                        )
                    }
                },
            )
        }

        if (showBulkExtra) {
            BulkExtraDialog(
                initial = history.firstOrNull()?.extraKg,
                onDismiss = { showBulkExtra = false },
                onApply = { extra ->
                    showBulkExtra = false
                    val before = history
                    scope.launch {
                        dao.setExtraForExercise(exerciseId, extra)
                        if (extra != null) dao.setAddOnWeight(exerciseId, true)
                        before.map { it.date }.distinct().forEach { dao.enqueueDateForSync(SyncQueueEntity(date = it)) }
                        TrainHubSyncWorker.requestImmediateSync(context)
                        val res = snackbarHostState.showSnackbar(
                            message = if (extra == null) "Add-on cleared on ${before.size} entries"
                            else "Add-on set on ${before.size} entries",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        )
                        if (res == SnackbarResult.ActionPerformed) {
                            before.forEach { dao.updateLogEntry(it) }
                            before.map { it.date }.distinct().forEach { dao.enqueueDateForSync(SyncQueueEntity(date = it)) }
                            TrainHubSyncWorker.requestImmediateSync(context)
                        }
                    }
                },
            )
        }
    }
    }
}

@Composable
private fun BulkExtraDialog(initial: Float?, onDismiss: () -> Unit, onApply: (Float?) -> Unit) {
    var text by remember { mutableStateOf(initial?.let(::trimFloat) ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add-on weight") },
        text = {
            Column {
                Text(
                    "Weight added by hand on top of the stack or bar. This replaces the add-on on " +
                        "every entry of this exercise; edit a single entry afterwards to change just that one.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' } },
                    label = { Text("kg") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApply(text.toFloatOrNull()) }, enabled = text.toFloatOrNull() != null) {
                Text("Apply to all")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { onApply(null) }) { Text("Clear all") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** One session on the chart: its date, the metric's value, and the set as logged. */
private data class ChartPoint(
    val date: LocalDate,
    val value: Float,
    /** Compact, for the label above the dot: "9×86", "10×BW+5". */
    val label: String,
    /** Spaced, for the hero caption: "9 × 86 +5". */
    val set: String,
)

/** The chart's points for one metric, plus what the hero needs to describe them. */
private class ChartData(val metric: Metric, val points: List<ChartPoint>, val maxWeight: Float?) {
    /**
     * How far point [i] is from the first session, scaled for trendColor's ±1 threshold: a % for
     * weight and 1RM, tenths of a rep for Reps @ Max (so a 0.1-rep change already counts).
     */
    fun changeScore(i: Int): Float {
        val first = points.firstOrNull()?.value ?: return 0f
        val v = points.getOrNull(i)?.value ?: return 0f
        return when (metric) {
            Metric.REPS_AT_MAX -> (v - first) * 10f
            else -> if (first > 0f) (v / first - 1f) * 100f else 0f
        }
    }

    /** The change from the first session to point [i], as the hero's pill shows it. */
    fun deltaText(i: Int): String {
        val first = points.first().value
        val v = points[i].value
        return when (metric) {
            Metric.REPS_AT_MAX -> {
                val d = Math.round((v - first) * 10f) / 10f
                when {
                    d > 0f -> "+${trimFloat(d)} reps"
                    d < 0f -> "\u2212${trimFloat(-d)} reps"
                    else -> "0 reps"
                }
            }
            else -> signedPct(if (first > 0f) (v / first - 1f) * 100f else 0f, decimals = 1)
        }
    }
}

private fun chartData(
    history: List<LogEntryEntity>,
    metric: Metric,
    sortedBW: List<BodyWeightEntity>,
    addOnFactor: Float,
): ChartData {
    // The load actually lifted: pin/bar (or body weight) plus any hand-added extra.
    fun resolvedWeight(e: LogEntryEntity): Float? {
        val base = e.weight ?: sortedBW.lastOrNull { it.date <= e.date }?.weight
        // The add-on counts as many times its kilos as the exercise's add-on weighting says.
        return effectiveWeight(base, e.extraKg, addOnFactor)
    }
    val maxWeightEver = if (metric == Metric.REPS_AT_MAX) history.mapNotNull(::resolvedWeight).maxOrNull() else null
    val points = history.sortedBy { it.date }.mapNotNull { e ->
        val w = resolvedWeight(e)
        val v = when (metric) {
            Metric.WEIGHT -> w
            Metric.ONE_RM -> epley(e.reps, w)
            Metric.REPS_AT_MAX -> {
                val e1rm = epley(e.reps, w)
                if (e1rm != null && maxWeightEver != null) repsAtWeight(e1rm, maxWeightEver) else null
            }
        } ?: return@mapNotNull null
        val extra = e.extraKg?.let { "+${trimFloat(it)}" } ?: ""
        ChartPoint(
            date = e.date,
            value = v,
            label = "${e.reps?.let(::trimFloat) ?: "?"}×${e.weight?.let(::trimFloat) ?: "BW"}$extra",
            set = formatValue(e.reps, e.weight) + if (extra.isEmpty()) "" else " $extra",
        )
    }
    return ChartData(metric, points, maxWeightEver)
}

/**
 * The session shown big at the top: the latest one, or the one picked on the chart. Its value in
 * the chosen metric, the change since the first session, and the set itself.
 */
@Composable
private fun HistoryHero(chart: ChartData, index: Int?, metric: Metric, note: String?) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 2.dp)) {
        if (index == null) {
            Text("—", fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = muted)
            Text("Nothing to chart yet", fontSize = 13.sp, color = muted)
        } else {
            val p = chart.points[index]
            val unit = if (metric == Metric.REPS_AT_MAX) "reps" else "kg"
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    buildAnnotatedString {
                        append(round1(p.value))
                        withStyle(SpanStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = muted)) {
                            append(" $unit")
                        }
                    },
                    fontSize = 30.sp,
                    lineHeight = 34.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                if (index > 0) {
                    Spacer(Modifier.width(12.dp))
                    ChangePill(chart.deltaText(index), trendColor(chart.changeScore(index), muted))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "since ${formatDate(chart.points.first().date)}",
                        fontSize = 12.sp,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val what = when (metric) {
                Metric.WEIGHT -> "Weight"
                Metric.ONE_RM -> "Est. 1RM"
                Metric.REPS_AT_MAX -> "Reps at ${chart.maxWeight?.let(::trimFloat) ?: "max"} kg"
            }
            Text(
                listOfNotNull(what, formatDate(p.date), p.set, note).joinToString(" · "),
                fontSize = 13.sp,
                color = muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A tidy axis step near [raw]: 1, 2, 2.5 or 5 times a power of ten. */
private fun niceAxisStep(raw: Float): Float {
    if (raw <= 0f) return 1f
    val magnitude = 10.0.pow(floor(log10(raw.toDouble()))).toFloat()
    val f = raw / magnitude
    val nice = when {
        f <= 1f -> 1f
        f <= 2f -> 2f
        f <= 2.5f -> 2.5f
        f <= 5f -> 5f
        else -> 10f
    }
    return nice * magnitude
}

/**
 * Every session as an exact point at its real date, styled like the Progress chart: a smooth line
 * over a soft fade, gridlines labelled on the right, faint calendar ticks so the gaps between
 * sessions can be read. Each dot carries its set ("9×86"); labels that would overlap are skipped,
 * latest first. Tap a point to show it in the hero (tap again to clear), or drag across to scrub.
 */
@Composable
private fun SessionChart(
    points: List<ChartPoint>,
    color: Color,
    picked: Int?,
    onPick: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val n = points.size
    val reveal = remember(points) { Animatable(0f) }
    LaunchedEffect(points) { reveal.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guideColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    val density = LocalDensity.current
    val axisLabelPx = with(density) { 10.sp.toPx() }
    val setLabelPx = with(density) { 9.5.sp.toPx() }
    val rightGutter: Dp = 40.dp
    val topGutter: Dp = 18.dp
    val bottomGutter: Dp = 18.dp
    val sidePad: Dp = 10.dp
    val currentPicked by rememberUpdatedState(picked)
    val currentOnPick by rememberUpdatedState(onPick)

    // Y range: the data with some air (never a microscope on tiny wiggles), snapped to gridlines.
    val values = points.map { it.value }
    val lo = values.min()
    val hi = values.max()
    val mid = (lo + hi) / 2
    val span = maxOf(hi - lo, abs(mid) * 0.12f, 1f)
    val rawLo = (mid - span * 0.65f).let { if (lo >= 0f) it.coerceAtLeast(0f) else it }
    val rawHi = mid + span * 0.65f
    val step = niceAxisStep((rawHi - rawLo) / 3f)
    val yLo = floor(rawLo / step) * step
    val yHi = ceil(rawHi / step) * step

    val firstDate = points.first().date
    val lastDate = points.last().date
    val totalDays = ChronoUnit.DAYS.between(firstDate, lastDate).toFloat()
    val ticks = remember(points) { dateTicks(firstDate, lastDate) }

    // Same x mapping for drawing and for touch.
    fun xOfDate(d: LocalDate, width: Float, gutter: Float, pad: Float): Float {
        val w = width - gutter
        return if (totalDays <= 0f) w / 2 else pad + (w - 2 * pad) * ChronoUnit.DAYS.between(firstDate, d) / totalDays
    }
    fun nearest(x: Float, width: Float, gutter: Float, pad: Float): Int =
        (0 until n).minByOrNull { abs(xOfDate(points[it].date, width, gutter, pad) - x) } ?: 0

    Canvas(
        modifier = modifier
            .pointerInput(points) {
                val gutter = rightGutter.toPx()
                val pad = sidePad.toPx()
                detectTapGestures { o ->
                    val i = nearest(o.x, size.width.toFloat(), gutter, pad)
                    currentOnPick(if (currentPicked == i) null else i)
                }
            }
            .pointerInput(points) {
                val gutter = rightGutter.toPx()
                val pad = sidePad.toPx()
                detectHorizontalDragGestures(
                    onDragStart = { o -> currentOnPick(nearest(o.x, size.width.toFloat(), gutter, pad)) },
                    onDragEnd = { currentOnPick(null) },
                    onDragCancel = { currentOnPick(null) },
                ) { change, _ ->
                    change.consume()
                    currentOnPick(nearest(change.position.x, size.width.toFloat(), gutter, pad))
                }
            },
    ) {
        val gutter = rightGutter.toPx()
        val pad = sidePad.toPx()
        val right = size.width - gutter
        val top = topGutter.toPx()
        val bottom = size.height - bottomGutter.toPx()
        fun xOf(i: Int) = xOfDate(points[i].date, size.width, gutter, pad)
        fun yOf(v: Float) = bottom - (v - yLo) / (yHi - yLo) * (bottom - top)

        val axisPaint = android.graphics.Paint().apply {
            textSize = axisLabelPx
            isAntiAlias = true
            this.color = labelColor.toArgb()
        }

        // Horizontal gridlines, labelled on the right.
        var g = yLo
        while (g <= yHi + step * 0.001f) {
            val y = yOf(g)
            drawLine(gridColor.copy(alpha = 0.35f), Offset(0f, y), Offset(right, y), 1.dp.toPx())
            drawIntoCanvas {
                it.nativeCanvas.drawText(trimFloat(Math.round(g * 10f) / 10f), right + 8.dp.toPx(), y + axisLabelPx / 3, axisPaint)
            }
            g += step
        }

        // Calendar ticks: a faint vertical line each, labels underneath (or first/last when too short).
        val tickPaint = android.graphics.Paint(axisPaint).apply { textAlign = android.graphics.Paint.Align.CENTER }
        val dateY = size.height - 4.dp.toPx()
        val tickLabels = if (ticks.isNotEmpty()) ticks.map { it.date to it.label }
        else listOf(firstDate to formatMonthDay(firstDate), lastDate to formatMonthDay(lastDate)).distinct()
        for ((date, label) in tickLabels) {
            val x = xOfDate(date, size.width, gutter, pad)
            if (ticks.isNotEmpty()) drawLine(gridColor.copy(alpha = 0.18f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            val half = tickPaint.measureText(label) / 2
            drawIntoCanvas { it.nativeCanvas.drawText(label, x.coerceIn(half, right - half), dateY, tickPaint) }
        }

        // The line through every session: smooth, but never overshooting a real value.
        fun Path.trace() {
            for (i in 0 until n) {
                val x = xOf(i)
                val y = yOf(points[i].value)
                if (i == 0) moveTo(x, y) else {
                    val px = xOf(i - 1)
                    val py = yOf(points[i - 1].value)
                    val cx = (px + x) / 2
                    cubicTo(cx, py, cx, y, x, y)
                }
            }
        }
        clipRect(right = right * reveal.value + 8.dp.toPx()) {
            if (n > 1) {
                val fill = Path().apply {
                    trace()
                    lineTo(xOf(n - 1), bottom)
                    lineTo(xOf(0), bottom)
                    close()
                }
                drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.24f), color.copy(alpha = 0f)), startY = top, endY = bottom))
                drawPath(Path().apply { trace() }, color, style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            // Every session is a real point.
            for (i in 0 until n) {
                val c = Offset(xOf(i), yOf(points[i].value))
                drawCircle(backgroundColor, radius = 4.8.dp.toPx(), center = c)
                drawCircle(color, radius = 3.3.dp.toPx(), center = c)
            }
        }

        // Set labels above the dots, latest (and picked) first; skip any that would overlap.
        if (reveal.value >= 0.999f) {
            val labelPaint = android.graphics.Paint(axisPaint).apply {
                textSize = setLabelPx
                textAlign = android.graphics.Paint.Align.CENTER
            }
            val order = buildList {
                currentPicked?.let { add(it) }
                for (i in n - 1 downTo 0) if (i != currentPicked) add(i)
            }
            val taken = ArrayList<ClosedFloatingPointRange<Float>>()
            val gap = 4.dp.toPx()
            for (i in order) {
                val text = points[i].label
                val half = labelPaint.measureText(text) / 2
                val x = xOf(i).coerceIn(half, right - half)
                val span = (x - half - gap)..(x + half + gap)
                if (taken.any { it.start < span.endInclusive && span.start < it.endInclusive }) continue
                taken += span
                val y = yOf(points[i].value) - 9.dp.toPx()
                labelPaint.color = (if (i == picked) guideColor else labelColor).toArgb()
                drawIntoCanvas { it.nativeCanvas.drawText(text, x, y, labelPaint) }
            }
        }

        // The picked session: a hairline and a haloed dot.
        val s = picked
        if (s != null && s in 0 until n) {
            val c = Offset(xOf(s), yOf(points[s].value))
            drawLine(guideColor.copy(alpha = 0.35f), Offset(c.x, top), Offset(c.x, bottom), 1.dp.toPx())
            drawCircle(color.copy(alpha = 0.22f), radius = 10.dp.toPx(), center = c)
            drawCircle(backgroundColor, radius = 5.5.dp.toPx(), center = c)
            drawCircle(color, radius = 4.dp.toPx(), center = c)
        }
    }
}

/** Header shown instead of "History (n)" while entries are being multi-selected. */
@Composable
private fun SelectionBar(
    count: Int,
    allSelected: Boolean,
    onSelectAll: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClear: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 2.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f))
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClear) { Text("✕") }
        Text("$count selected", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        if (!allSelected) TextButton(onClick = onSelectAll) { Text("All") }
        TextButton(onClick = onEdit) { Text("Edit") }
        TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
    }
}

/** What a bulk edit changes; null / false / 0 mean "leave as is". */
private data class BulkChange(
    val reps: Float?,
    val weight: Float?,
    val extraKg: Float?,
    val clearExtra: Boolean,
    val shiftDays: Int,
)

@Composable
private fun BulkEditDialog(count: Int, onDismiss: () -> Unit, onApply: (BulkChange) -> Unit) {
    var reps by remember { mutableStateOf("") }
    var weight by remember { mutableStateOf("") }
    var extra by remember { mutableStateOf("") }
    var clearExtra by remember { mutableStateOf(false) }
    var shift by remember { mutableStateOf("") }

    fun decimal(s: String) = s.replace(',', '.').filter { it.isDigit() || it == '.' }
    val change = BulkChange(
        reps = reps.toFloatOrNull(),
        weight = weight.toFloatOrNull(),
        extraKg = if (clearExtra) null else extra.toFloatOrNull(),
        clearExtra = clearExtra,
        shiftDays = shift.toIntOrNull() ?: 0,
    )
    val anyChange = change.reps != null || change.weight != null || change.extraKg != null ||
        change.clearExtra || change.shiftDays != 0

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit $count ${if (count == 1) "entry" else "entries"}") },
        text = {
            Column {
                Text(
                    "Only filled-in fields change; leave a field empty to keep each entry's own value.",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = reps,
                        onValueChange = { reps = decimal(it) },
                        label = { Text("Reps") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = weight,
                        onValueChange = { weight = decimal(it) },
                        label = { Text("Weight kg") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = extra,
                    onValueChange = { extra = decimal(it); if (it.isNotEmpty()) clearExtra = false },
                    label = { Text("Add-on kg") },
                    singleLine = true,
                    enabled = !clearExtra,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { clearExtra = !clearExtra; if (clearExtra) extra = "" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = clearExtra, onCheckedChange = { clearExtra = it; if (it) extra = "" })
                    Text("Remove add-on", fontSize = 13.sp)
                }
                Spacer(Modifier.height(4.dp))
                OutlinedTextField(
                    value = shift,
                    onValueChange = { v -> shift = v.filterIndexed { i, c -> c.isDigit() || (i == 0 && c == '-') } },
                    label = { Text("Shift dates by days (e.g. -1)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onApply(change) }, enabled = anyChange) { Text("Apply") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * One entry, drawn as a slice of a single rounded card (the first and last rows carry its
 * corners), with the same layout as Progress's "Recent sets". Tap to edit, hold to start selecting.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun EntryRow(
    entry: LogEntryEntity,
    isFirst: Boolean,
    isLast: Boolean,
    selected: Boolean,
    selecting: Boolean,
    addOnFactor: Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val corner = 20.dp
    val shape = RoundedCornerShape(
        topStart = if (isFirst) corner else 0.dp,
        topEnd = if (isFirst) corner else 0.dp,
        bottomStart = if (isLast) corner else 0.dp,
        bottomEnd = if (isLast) corner else 0.dp,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (selected) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)) else Modifier)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (selecting) {
                SelectMark(selected)
                Spacer(Modifier.width(12.dp))
            }
            Text(
                formatDate(entry.date),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(96.dp),
            )
            Text(
                formatValue(entry.reps, entry.weight) + (entry.extraKg?.let { " +${trimFloat(it)}" } ?: ""),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
            )
            val oneRm = epley(entry.reps, effectiveWeight(entry.weight, entry.extraKg, addOnFactor))
            if (oneRm != null) {
                Text("1RM ${round1(oneRm)}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (!isLast) {
            HorizontalDivider(
                modifier = Modifier.padding(start = 16.dp),
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            )
        }
    }
}

/** Round tick for selection mode: an outline ring, or a filled circle with a check when chosen. */
@Composable
private fun SelectMark(selected: Boolean) {
    val primary = MaterialTheme.colorScheme.primary
    val ring = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val check = MaterialTheme.colorScheme.onPrimary
    Canvas(Modifier.size(20.dp)) {
        val r = size.minDimension / 2
        if (selected) {
            drawCircle(primary, radius = r)
            val tick = Path().apply {
                moveTo(size.width * 0.28f, size.height * 0.52f)
                lineTo(size.width * 0.44f, size.height * 0.68f)
                lineTo(size.width * 0.74f, size.height * 0.36f)
            }
            drawPath(tick, check, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        } else {
            drawCircle(ring, radius = r - 0.8.dp.toPx(), style = Stroke(width = 1.6.dp.toPx()))
        }
    }
}

/**
 * Sets how much 1 kg of add-on counts compared with 1 kg on the stack, for this exercise's charts,
 * estimated 1RM and progress (logged sets are never changed). The chart above redraws live while
 * sliding, so the factor can be tuned until the line runs on smoothly where the add-on changed —
 * e.g. on a machine with a descending resistance profile, where a plate on the lever loads the
 * weakest point that the stack goes easy on.
 */
@Composable
private fun AddOnWeightingPanel(
    factor: Float,
    onChange: (Float) -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Add-on weighting", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Text(
                    "×${trimFloat(factor)}",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            Text(
                "How much 1 kg of add-on counts compared with 1 kg on the stack. Slide until the " +
                    "line runs on smoothly where you changed the add-on. Logged sets stay as they are.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, end = 4.dp),
            )
            Slider(
                value = factor,
                onValueChange = { onChange(Math.round(it * 10f) / 10f) },
                valueRange = 0.5f..4f,
                steps = 34,
                modifier = Modifier.padding(top = 4.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "As logged (×1)",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable { onChange(1f) }
                        .padding(horizontal = 8.dp, vertical = 7.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Cancel",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onCancel)
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
                Text(
                    "Save",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable(onClick = onSave)
                        .padding(horizontal = 18.dp, vertical = 7.dp),
                )
            }
        }
    }
}

@Composable
private fun EntryEditor(
    draft: LogEntryEntity,
    isNew: Boolean = false,
    weightConfig: WeightConfig,
    addOnEnabled: Boolean,
    onRepsSelected: (Float?) -> Unit,
    onWeightSelected: (Float?) -> Unit,
    onExtraSelected: (Float?) -> Unit,
    onDateShift: (Long) -> Unit,
    onYearShift: (Long) -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onCancel: () -> Unit,
) {
    val repsValues = remember { wheelValues(60f, step = 1f) }
    val weightValues = remember(weightConfig) { weightWheelValues(weightConfig) }
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 6.dp),
    ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
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
        // Add-on weight is rare, so it stays a quiet link unless this exercise has it switched on
        // (row menu in the tree) or the entry already has one.
        var showExtra by remember(draft.id) { mutableStateOf(draft.extraKg != null || addOnEnabled) }
        if (showExtra) {
            var extraText by remember(draft.id) { mutableStateOf(draft.extraKg?.let(::trimFloat) ?: "") }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Add-on", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                OutlinedTextField(
                    value = extraText,
                    onValueChange = {
                        extraText = it.replace(',', '.').filter { c -> c.isDigit() || c == '.' }
                        onExtraSelected(extraText.toFloatOrNull())
                    },
                    suffix = { Text("kg", fontSize = 12.sp) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.width(112.dp),
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "Remove",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onExtraSelected(null); extraText = ""; showExtra = false }
                        .padding(horizontal = 6.dp, vertical = 4.dp),
                )
            }
        } else {
            Text(
                "+ add-on weight",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { showExtra = true }
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (!isNew) {
                Text(
                    "Delete",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClick = onDelete)
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(
                "Cancel",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable(onClick = onCancel)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
            Text(
                "Save",
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
                    .clickable(onClick = onSave)
                    .padding(horizontal = 18.dp, vertical = 7.dp),
            )
        }
    }
    }
}

@Composable
private fun StepperButton(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 5.dp),
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}


// android.graphics needs an ARGB int; bridge from Compose Color.
private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255).toInt(), (red * 255).toInt(), (green * 255).toInt(), (blue * 255).toInt(),
)
