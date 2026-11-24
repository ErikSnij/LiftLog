package com.example.gym.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.gym.LiftLogApp
import com.example.gym.data.LogEntryEntity
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/** The time windows the progress screen can show. [weeks] null = the whole history. */
enum class ProgressRange(val label: String, val weeks: Int?, val caption: String) {
    THREE_MONTHS("3M", 13, "Last 3 months"),
    SIX_MONTHS("6M", 26, "Last 6 months"),
    ONE_YEAR("1Y", 52, "Last year"),
    ALL("All", null, "All time"),
}

/** Green when up at least 1%, red when down at least 1%, [neutral] in between; tuned for light and dark. */
@Composable
internal fun trendColor(pct: Float, neutral: Color = MaterialTheme.colorScheme.primary): Color {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return when {
        pct >= 1f -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        pct <= -1f -> if (dark) Color(0xFFE57373) else Color(0xFFC62828)
        else -> neutral
    }
}

/**
 * Colours for the children's lines in the chart, chosen to stay clear of the red/green used for
 * direction. A child keeps its colour (by its place in the tree) whatever the range.
 */
@Composable
private fun seriesPalette(): List<Color> {
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    return if (dark) {
        listOf(
            Color(0xFF64B5F6), Color(0xFFFFB74D), Color(0xFFBA68C8), Color(0xFF4DD0E1),
            Color(0xFFFFF176), Color(0xFF9FA8DA), Color(0xFFF48FB1), Color(0xFFBCAAA4),
        )
    } else {
        listOf(
            Color(0xFF1E88E5), Color(0xFFEF6C00), Color(0xFF8E24AA), Color(0xFF00838F),
            Color(0xFFF9A825), Color(0xFF3949AB), Color(0xFFD81B60), Color(0xFF6D4C41),
        )
    }
}

/** A child's line drawn inside its parent's chart. */
private class ChartSeries(val node: ProgressNode, val name: String, val points: List<Float>, val color: Color)

/** "+8%" / "−3%" / "0%" (or one decimal: "+8.4%"). Always a dot decimal, like the rest of the app. */
internal fun signedPct(pct: Float, decimals: Int = 0): String {
    val rounded = if (decimals == 0) Math.round(pct).toFloat() else Math.round(pct * 10f) / 10f
    val magnitude = if (decimals == 0) abs(rounded).toInt().toString() else String.format(Locale.US, "%.1f", abs(rounded))
    return when {
        rounded > 0f -> "+$magnitude%"
        rounded < 0f -> "−$magnitude%"
        else -> "$magnitude%"
    }
}

// ---- Tree index: names, parents and children of every node, from the tree screen's model ----

private class NodeInfo(
    val node: ProgressNode,
    val name: String,
    val parent: ProgressNode?,
    val children: List<ProgressNode>,
    val archived: Boolean = false,
    /** For exercises: the area it's in, to tell same-named exercises apart. */
    val context: String? = null,
)

private fun buildNodeIndex(tree: TreeUi): Map<ProgressNode, NodeInfo> {
    val out = HashMap<ProgressNode, NodeInfo>()
    val overall = ProgressNode.OVERALL
    out[overall] = NodeInfo(
        overall, "Overall", null,
        tree.categories.map { ProgressNode(ProgressLevel.CATEGORY, it.id) },
    )
    for (c in tree.categories) {
        val cNode = ProgressNode(ProgressLevel.CATEGORY, c.id)
        out[cNode] = NodeInfo(cNode, c.name, overall, c.muscleGroups.map { ProgressNode(ProgressLevel.MUSCLE_GROUP, it.id) })
        for (g in c.muscleGroups) {
            val gNode = ProgressNode(ProgressLevel.MUSCLE_GROUP, g.id)
            out[gNode] = NodeInfo(gNode, g.name, cNode, g.areas.map { ProgressNode(ProgressLevel.AREA, it.id) })
            for (a in g.areas) {
                val aNode = ProgressNode(ProgressLevel.AREA, a.id)
                out[aNode] = NodeInfo(aNode, a.name, gNode, a.exercises.map { ProgressNode(ProgressLevel.EXERCISE, it.id) })
                for (e in a.exercises) {
                    val eNode = ProgressNode(ProgressLevel.EXERCISE, e.id)
                    out[eNode] = NodeInfo(eNode, e.name, aNode, emptyList(), archived = e.archived, context = a.name)
                }
            }
        }
    }
    return out
}

private fun pathTo(node: ProgressNode, index: Map<ProgressNode, NodeInfo>): List<ProgressNode> {
    val path = ArrayList<ProgressNode>()
    var cur: ProgressNode? = node
    while (cur != null) {
        path += cur
        cur = index[cur]?.parent
    }
    return path.reversed()
}

private fun descendantExercises(node: ProgressNode, index: Map<ProgressNode, NodeInfo>): List<ProgressNode> {
    val info = index[node] ?: return emptyList()
    if (node.level == ProgressLevel.EXERCISE) return listOf(node)
    return info.children.flatMap { descendantExercises(it, index) }
}

private fun childrenLabel(level: ProgressLevel) = when (level) {
    ProgressLevel.OVERALL -> "Categories"
    ProgressLevel.CATEGORY -> "Muscle groups"
    ProgressLevel.MUSCLE_GROUP -> "Muscles"
    ProgressLevel.AREA -> "Exercises"
    ProgressLevel.EXERCISE -> ""
}

private fun agoLabel(date: LocalDate, today: LocalDate): String {
    val days = ChronoUnit.DAYS.between(date, today)
    return when {
        days <= 0L -> "today"
        days == 1L -> "yesterday"
        days < 14L -> "$days days ago"
        else -> "${days / 7} wk ago"
    }
}

// ---- Screen ---------------------------------------------------------------------------------

/**
 * Drill-down view of the progress index: one node of the tree at a time (Overall, a category, a
 * muscle group, a muscle or an exercise) with its chart, highlights and children ranked by change.
 * Tap a child to zoom in; the breadcrumb or Back zooms out.
 */
@Composable
fun ProgressScreen(onBack: () -> Unit, onOpenHistory: (Long) -> Unit, modifier: Modifier = Modifier) {
    val vm: TreeViewModel = viewModel()
    val tree by vm.tree.collectAsStateWithLifecycle()
    val history by vm.progressHistory.collectAsStateWithLifecycle()
    val index = remember(tree) { buildNodeIndex(tree) }
    val stack = vm.progressStack
    val current = stack.last()
    val range = vm.progressRange

    fun back() {
        if (!vm.progressBack()) onBack()
    }
    BackHandler { back() }

    Column(modifier = modifier.fillMaxSize()) {
        // Top bar, matching the app's other screens.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "‹ Back",
                fontSize = 15.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { back() }
                    .padding(horizontal = 4.dp, vertical = 2.dp)
                    .padding(end = 8.dp),
            )
            Text("Progress", fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        Breadcrumb(
            path = pathTo(current, index),
            index = index,
            onSelect = vm::progressGoTo,
        )

        AnimatedContent(
            targetState = current,
            transitionSpec = {
                // Deeper = forward (slide in from the right); shallower = back.
                val forward = targetState.level.ordinal >= initialState.level.ordinal
                val sign = if (forward) 1 else -1
                (slideInHorizontally(tween(320, easing = FastOutSlowInEasing)) { sign * it / 5 } + fadeIn(tween(260)))
                    .togetherWith(slideOutHorizontally(tween(320, easing = FastOutSlowInEasing)) { -sign * it / 5 } + fadeOut(tween(180)))
            },
            label = "progress-node",
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { node ->
            val h = history
            if (h == null || !tree.loaded) {
                // Still loading: a blank page for the moment, rather than a misleading "empty".
                Box(Modifier.fillMaxSize())
            } else if (h.isEmpty) {
                EmptyState(
                    title = "Nothing to chart yet",
                    body = "Log a few sessions and your progress shows up here.",
                )
            } else {
                NodePage(
                    node = node,
                    index = index,
                    history = h,
                    range = range,
                    onRange = vm::updateProgressRange,
                    onOpen = vm::progressPush,
                    onOpenHistory = onOpenHistory,
                )
            }
        }
    }
}

@Composable
private fun Breadcrumb(path: List<ProgressNode>, index: Map<ProgressNode, NodeInfo>, onSelect: (ProgressNode) -> Unit) {
    val scroll = rememberScrollState()
    // Keep the current (last) crumb in view when the path grows.
    LaunchedEffect(path) { scroll.animateScrollTo(scroll.maxValue) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scroll)
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        path.forEachIndexed { i, node ->
            val isLast = i == path.lastIndex
            val name = index[node]?.name ?: ""
            Text(
                text = name,
                fontSize = 13.sp,
                fontWeight = if (isLast) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isLast) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                maxLines = 1,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .then(if (isLast) Modifier else Modifier.clickable { onSelect(node) })
                    .padding(horizontal = 4.dp, vertical = 4.dp),
            )
            if (!isLast) {
                Text("›", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
            }
        }
    }
}

@Composable
private fun EmptyState(title: String, body: String) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (title.isNotEmpty()) Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (body.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(body, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Matches the engine's staleness: an exercise drops out after 8 weeks without a log. */
private const val STALE_DAYS = 8 * 7L

/** A child (or highlight) row's data, computed once per node + range. */
private class ChildRow(val node: ProgressNode, val name: String, val subtitle: String?, val trend: Trend?, val lastLogged: LocalDate?)

@Composable
private fun NodePage(
    node: ProgressNode,
    index: Map<ProgressNode, NodeInfo>,
    history: ProgressHistory,
    range: ProgressRange,
    onRange: (ProgressRange) -> Unit,
    onOpen: (ProgressNode) -> Unit,
    onOpenHistory: (Long) -> Unit,
) {
    val info = index[node]
    val today = remember { LocalDate.now() }
    val trend = remember(history, node, range) { history.trend(node, range.weeks) }

    // Children: active ones ranked by change, then the ones not trained in this window.
    val (active, inactive) = remember(history, node, range, index) {
        val rows = info?.children.orEmpty().mapNotNull { child ->
            val ci = index[child] ?: return@mapNotNull null
            val t = history.trend(child, range.weeks)
            // Archived exercises only show while they still move this window's line.
            if (ci.archived && t == null) return@mapNotNull null
            ChildRow(child, ci.name, if (ci.archived) "archived" else null, t, history.lastLogged[child])
        }
        // "Not trained recently" = nothing logged for 8+ weeks, whatever the range: a child last
        // trained near the window's start would otherwise sit among the active ones as a flat 0%.
        fun recent(r: ChildRow) = r.trend != null &&
            r.lastLogged != null && ChronoUnit.DAYS.between(r.lastLogged, today) < STALE_DAYS
        rows.filter(::recent).sortedByDescending { it.trend!!.changePct } to
            rows.filterNot(::recent).sortedByDescending { it.lastLogged ?: LocalDate.MIN }
    }

    // Highlights across every exercise beneath this node.
    val exercises = remember(history, node, range, index) {
        descendantExercises(node, index).mapNotNull { e ->
            val ei = index[e] ?: return@mapNotNull null
            if (ei.archived) return@mapNotNull null
            ChildRow(e, ei.name, ei.context, history.trend(e, range.weeks), history.lastLogged[e])
        }
    }
    val activeExerciseCount = exercises.count { it.trend != null }

    var scrubIndex by remember(node, range) { mutableStateOf<Int?>(null) }
    val haptics = LocalHapticFeedback.current

    // Each child's line in the chart, coloured by its place in the tree; a tapped legend chip
    // spotlights one of them.
    val palette = seriesPalette()
    val seriesColors = remember(info, palette) {
        info?.children.orEmpty().mapIndexed { i, c -> c to palette[i % palette.size] }.toMap()
    }
    val overlays = remember(active, seriesColors) {
        active.mapNotNull { row ->
            val t = row.trend ?: return@mapNotNull null
            ChartSeries(row.node, row.name, t.points, seriesColors[row.node] ?: return@mapNotNull null)
        }
    }
    var focusedChild by remember(node) { mutableStateOf<ProgressNode?>(null) }
    // Exercise level: which points of the line are real sessions (the rest is filled in between).
    val sessions = remember(history, node, range) {
        if (node.level == ProgressLevel.EXERCISE) history.sessionPoints(node, range.weeks) else emptyList()
    }
    val focus = focusedChild?.takeIf { f -> overlays.any { it.node == f } }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 32.dp),
    ) {
        // ---- Hero: name, big % change (follows the scrub), caption ----
        val shownPct = when {
            trend == null -> null
            scrubIndex != null -> trend.points[scrubIndex!!.coerceIn(0, trend.points.lastIndex)] - 100f
            else -> trend.changePct
        }
        val heroColor = trendColor(shownPct ?: 0f, MaterialTheme.colorScheme.onSurface)
        val animatedPct by animateFloatAsState(shownPct ?: 0f, tween(if (scrubIndex != null) 90 else 450), label = "hero-pct")
        val animatedColor by animateColorAsState(heroColor, tween(250), label = "hero-color")

        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 14.dp)) {
            Text(
                text = (info?.name ?: "").uppercase(),
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = if (shownPct == null) "—" else signedPct(animatedPct, decimals = 1),
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (shownPct == null) MaterialTheme.colorScheme.onSurfaceVariant else animatedColor,
            )
            val caption = when {
                trend == null -> "Not trained in this period"
                scrubIndex != null -> "Week of ${formatDate(trend.startWeek.plusWeeks(scrubIndex!!.toLong()))}"
                node.level == ProgressLevel.EXERCISE ->
                    "${range.caption} · ${sessions.size} session${if (sessions.size == 1) "" else "s"} · estimated 1RM"
                else -> "${range.caption} · $activeExerciseCount exercise${if (activeExerciseCount == 1) "" else "s"}"
            }
            Text(caption, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // ---- Chart card with the range selector ----
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp),
        ) {
            Column(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                if (trend != null) {
                    ProgressChart(
                        trend = trend,
                        fillColor = trendColor(trend.changePct),
                        // With children drawn in, the total is a bold neutral line; alone, it
                        // carries the direction colour itself.
                        lineColor = if (overlays.isEmpty()) trendColor(trend.changePct) else MaterialTheme.colorScheme.onSurface,
                        overlays = overlays,
                        focus = focus,
                        sessionMarks = sessions,
                        scrubIndex = scrubIndex,
                        onScrub = { i ->
                            if (i != null && i != scrubIndex) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            scrubIndex = i
                        },
                        modifier = Modifier.fillMaxWidth().height(210.dp).padding(horizontal = 12.dp),
                    )
                } else {
                    Box(Modifier.fillMaxWidth().height(210.dp), contentAlignment = Alignment.Center) {
                        Text(
                            text = if (range == ProgressRange.ALL) "No sets with reps and weight yet"
                            else "Nothing logged here in this period — try All",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (trend != null && overlays.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    ChartLegend(
                        series = overlays,
                        scrubIndex = scrubIndex,
                        focus = focus,
                        onToggle = { n -> focusedChild = if (focus == n) null else n },
                    )
                }
                Spacer(Modifier.height(10.dp))
                RangeSelector(range, onRange, Modifier.padding(horizontal = 12.dp))
            }
        }

        if (node.level == ProgressLevel.EXERCISE) {
            // Tapping a set pins its week on the chart; scrubbing/tapping the chart lights the sets
            // of that week here.
            RecentSets(
                exerciseId = node.id,
                startWeek = trend?.startWeek,
                weekCount = trend?.points?.size ?: 0,
                highlightWeek = scrubIndex,
                onPickWeek = { i ->
                    if (i != null && i != scrubIndex) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    scrubIndex = i
                },
                onOpenHistory = { onOpenHistory(node.id) },
            )
        } else {
            BreakdownSections(node, exercises, active, inactive, seriesColors, today, onOpen)
        }
    }
}

/** Highlights across every exercise beneath the node, then its children ranked by change. */
@Composable
private fun BreakdownSections(
    node: ProgressNode,
    exercises: List<ChildRow>,
    active: List<ChildRow>,
    inactive: List<ChildRow>,
    seriesColors: Map<ProgressNode, Color>,
    today: LocalDate,
    onOpen: (ProgressNode) -> Unit,
) {
    Column {
        // ---- Highlights ----
        // Only where they add something: above the exercise level (at a muscle the list below
        // already is the exercises) and with enough of them to pick from.
        val showHighlights = node.level.ordinal < ProgressLevel.AREA.ordinal &&
            exercises.count { it.trend != null } >= 3
        if (showHighlights) {
            val best = exercises.filter { (it.trend?.changePct ?: 0f) >= 1f }.maxByOrNull { it.trend!!.changePct }
            val worst = exercises.filter { (it.trend?.changePct ?: 0f) <= -1f }.minByOrNull { it.trend!!.changePct }
            // Something from the recent routine (logged in the last ~6 months) that's been skipped
            // for 3+ weeks — not an exercise abandoned long ago.
            val gap = exercises
                .filter { it.lastLogged != null && ChronoUnit.DAYS.between(it.lastLogged, today) in 21L..182L }
                .minByOrNull { it.lastLogged!! }
            if (best != null || worst != null || gap != null) {
                SectionLabel("Highlights")
                CardList {
                    if (best != null) {
                        HighlightRow(HighlightKind.GAIN, "Top gain", best, signedPct(best.trend!!.changePct)) { onOpen(best.node) }
                    }
                    if (worst != null) {
                        if (best != null) RowDivider()
                        HighlightRow(HighlightKind.DROP, "Biggest drop", worst, signedPct(worst.trend!!.changePct)) { onOpen(worst.node) }
                    }
                    if (gap != null) {
                        if (best != null || worst != null) RowDivider()
                        HighlightRow(HighlightKind.GAP, "Longest gap", gap, agoLabel(gap.lastLogged!!, today)) { onOpen(gap.node) }
                    }
                }
            }
        }

        // ---- Children ----
        if (active.isNotEmpty()) {
            SectionLabel(childrenLabel(node.level))
            CardList {
                active.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    ChildTrendRow(row, today, seriesColors[row.node]) { onOpen(row.node) }
                }
            }
        }
        if (inactive.isNotEmpty()) {
            SectionLabel(if (active.isEmpty()) childrenLabel(node.level) else "Not trained recently")
            CardList {
                inactive.forEachIndexed { i, row ->
                    if (i > 0) RowDivider()
                    ChildTrendRow(row, today, seriesColor = null) { onOpen(row.node) }
                }
            }
        }
    }
}

@Composable
internal fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.3.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, top = 22.dp, bottom = 8.dp),
    )
}

@Composable
private fun CardList(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    ) {
        Column { content() }
    }
}

@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 16.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}

@Composable
private fun RangeSelector(range: ProgressRange, onRange: (ProgressRange) -> Unit, modifier: Modifier = Modifier) {
    SegmentedSelector(
        labels = ProgressRange.entries.map { it.label },
        selectedIndex = range.ordinal,
        onSelect = { onRange(ProgressRange.entries[it]) },
        modifier = modifier,
    )
}

/** A full-width segmented control: one pill per option, the chosen one raised. Shared with History. */
@Composable
internal fun SegmentedSelector(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    verticalPadding: Dp = 7.dp,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        labels.forEachIndexed { i, label ->
            val selected = i == selectedIndex
            val bg by animateColorAsState(
                if (selected) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent,
                tween(200),
                label = "segment-bg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(bg)
                    .clickable { onSelect(i) }
                    .padding(vertical = verticalPadding),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    fontSize = 13.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ChildTrendRow(row: ChildRow, today: LocalDate, seriesColor: Color?, onClick: () -> Unit) {
    val t = row.trend
    val muted = t == null
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 10.dp, top = 11.dp, bottom = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (seriesColor != null && t != null) {
            // Matches this child's line in the chart above.
            Box(Modifier.size(9.dp).clip(CircleShape).background(seriesColor))
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                row.name,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = if (muted) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val last = row.lastLogged
            val sub = listOfNotNull(
                row.subtitle,
                if (last != null) "last ${formatDate(last)} · ${agoLabel(last, today)}" else "never logged",
            ).joinToString(" · ")
            Text(
                sub,
                fontSize = 11.5.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (muted) 0.7f else 0.9f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (t != null) {
            val color = trendColor(t.changePct, MaterialTheme.colorScheme.onSurfaceVariant)
            Sparkline(t.points, seriesColor ?: color, Modifier.size(width = 56.dp, height = 22.dp))
            Spacer(Modifier.width(10.dp))
            ChangePill(signedPct(t.changePct), color)
        }
        Chevron()
    }
}

@Composable
internal fun ChangePill(text: String, color: Color) {
    Box(
        modifier = Modifier
            .widthIn(min = 54.dp)
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun Chevron() {
    Text(
        "›",
        fontSize = 20.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.padding(start = 8.dp),
    )
}

private enum class HighlightKind { GAIN, DROP, GAP }

@Composable
private fun HighlightRow(kind: HighlightKind, label: String, row: ChildRow, value: String, onClick: () -> Unit) {
    val color = when (kind) {
        HighlightKind.GAIN -> trendColor(1f)
        HighlightKind.DROP -> trendColor(-1f)
        HighlightKind.GAP -> MaterialTheme.colorScheme.tertiary
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 14.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(32.dp).clip(CircleShape).background(color.copy(alpha = 0.15f)),
            contentAlignment = Alignment.Center,
        ) {
            HighlightGlyph(kind, color, Modifier.size(16.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, fontSize = 11.5.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                listOfNotNull(row.name, row.subtitle).joinToString(" · "),
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(value, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(start = 8.dp))
        Chevron()
    }
}

/** Up arrow, down arrow, or a small clock — drawn, so they look the same on every device font. */
@Composable
private fun HighlightGlyph(kind: HighlightKind, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        when (kind) {
            HighlightKind.GAIN, HighlightKind.DROP -> {
                // A small rising (or falling) trend line ending in an arrow head.
                val up = kind == HighlightKind.GAIN
                fun y(f: Float) = if (up) h * (1f - f) else h * f
                val line = Path().apply {
                    moveTo(w * 0.08f, y(0.22f))
                    lineTo(w * 0.40f, y(0.52f))
                    lineTo(w * 0.58f, y(0.38f))
                    lineTo(w * 0.90f, y(0.80f))
                }
                drawPath(line, color, style = stroke)
                val head = Path().apply {
                    moveTo(w * 0.60f, y(0.80f))
                    lineTo(w * 0.90f, y(0.80f))
                    lineTo(w * 0.90f, y(0.50f))
                }
                drawPath(head, color, style = stroke)
            }
            HighlightKind.GAP -> {
                drawCircle(color, radius = w * 0.42f, style = stroke)
                drawLine(color, Offset(w / 2, h / 2), Offset(w / 2, h * 0.24f), stroke.width, StrokeCap.Round)
                drawLine(color, Offset(w / 2, h / 2), Offset(w * 0.70f, h * 0.60f), stroke.width, StrokeCap.Round)
            }
        }
    }
}

/** Tiny line for list rows: shape only, no axes. */
@Composable
internal fun Sparkline(points: List<Float>, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (points.size < 2) return@Canvas
        val lo = points.min()
        val hi = points.max()
        val span = hi - lo
        val stroke = 1.6.dp.toPx()
        val h = size.height - stroke
        val path = Path()
        points.forEachIndexed { i, v ->
            val x = size.width * i / (points.size - 1)
            val y = if (span < 0.5f) size.height / 2 else stroke / 2 + h * (1f - (v - lo) / span)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/**
 * One chip per child line: its colour, name and % change — at the scrubbed week while scrubbing,
 * so you can read who moved the total that week. Tap a chip to spotlight its line; tap it again
 * to show them all.
 */
@Composable
private fun ChartLegend(
    series: List<ChartSeries>,
    scrubIndex: Int?,
    focus: ProgressNode?,
    onToggle: (ProgressNode) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (s in series) key(s.node) {
            val pct = (scrubIndex?.let { s.points.getOrNull(it) } ?: s.points.last()) - 100f
            val selected = focus == s.node
            val dimmed = focus != null && !selected
            val bg by animateColorAsState(
                if (selected) s.color.copy(alpha = 0.22f) else MaterialTheme.colorScheme.surfaceContainerHigh,
                tween(200),
                label = "legend-bg",
            )
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(bg)
                    .clickable { onToggle(s.node) }
                    .padding(start = 9.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(s.color.copy(alpha = if (dimmed) 0.35f else 1f)),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    s.name,
                    fontSize = 12.5.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (dimmed) 0.5f else 1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    signedPct(pct),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = trendColor(pct, MaterialTheme.colorScheme.onSurfaceVariant).copy(alpha = if (dimmed) 0.5f else 1f),
                    maxLines = 1,
                )
            }
        }
    }
}

// ---- The main chart -------------------------------------------------------------------------

/** A tidy gridline step for a span of [range] percentage points (aiming for about three lines). */
private fun niceStep(range: Float): Float {
    val raw = range / 3f
    return listOf(1f, 2f, 2.5f, 5f, 10f, 20f, 25f, 50f, 100f, 200f).firstOrNull { it >= raw } ?: 500f
}

/**
 * The node's index as % change since the window's start: a smooth line over a soft gradient,
 * gridlines labelled in %, a dashed 0% baseline and calendar ticks underneath. Each child's own
 * line ([overlays]) is drawn thin behind it, so you can see which one moved the total; [focus]
 * spotlights one and fades the rest. [sessionMarks] (exercise level) dots the weeks actually logged.
 * Drag across it to scrub (the hero number follows), or tap to pin a week; tap the same week again
 * to clear it.
 */
@Composable
private fun ProgressChart(
    trend: Trend,
    fillColor: Color,
    lineColor: Color,
    overlays: List<ChartSeries>,
    focus: ProgressNode?,
    sessionMarks: List<Int>,
    scrubIndex: Int?,
    onScrub: (Int?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val points = trend.points
    val n = points.size
    val reveal = remember(trend) { Animatable(0f) }
    LaunchedEffect(trend) { reveal.animateTo(1f, tween(750, easing = FastOutSlowInEasing)) }

    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val guideColor = MaterialTheme.colorScheme.onSurface
    val backgroundColor = MaterialTheme.colorScheme.surfaceContainerLow
    val density = LocalDensity.current
    val labelPx = with(density) { 10.sp.toPx() }
    val rightGutter: Dp = 40.dp
    val bottomGutter: Dp = 22.dp
    val currentScrub by rememberUpdatedState(scrubIndex)
    val currentOnScrub by rememberUpdatedState(onScrub)

    // Y range in % change over the total and every child line, always including 0 and at least
    // ±2 points tall, snapped to gridlines.
    val pcts = points.map { it - 100f }
    val overlayPcts = overlays.map { o -> o.points.map { it - 100f } }
    val rawLo = minOf(0f, pcts.min(), overlayPcts.minOfOrNull { it.min() } ?: 0f)
    val rawHi = maxOf(0f, pcts.max(), overlayPcts.maxOfOrNull { it.max() } ?: 0f)
    val mid = (rawLo + rawHi) / 2
    val half = maxOf((rawHi - rawLo) / 2, 2f)
    val step = niceStep(half * 2)
    val yLo = floor((mid - half) / step) * step
    val yHi = ceil((mid + half) / step) * step
    val gridDecimals = if (step % 1f == 0f) 0 else 1

    val endWeek = trend.startWeek.plusWeeks((n - 1).toLong())
    val ticks = remember(trend) { dateTicks(trend.startWeek, endWeek, maxTicks = 4) }

    Canvas(
        modifier = modifier
            .pointerInput(n) {
                val gutter = rightGutter.toPx()
                fun indexAt(x: Float): Int {
                    val w = size.width - gutter
                    return ((x / w) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                }
                detectTapGestures { o ->
                    val i = indexAt(o.x)
                    currentOnScrub(if (currentScrub == i) null else i)
                }
            }
            .pointerInput(n) {
                val gutter = rightGutter.toPx()
                fun indexAt(x: Float): Int {
                    val w = size.width - gutter
                    return ((x / w) * (n - 1)).roundToInt().coerceIn(0, n - 1)
                }
                detectHorizontalDragGestures(
                    onDragStart = { o -> currentOnScrub(indexAt(o.x)) },
                    onDragEnd = { currentOnScrub(null) },
                    onDragCancel = { currentOnScrub(null) },
                ) { change, _ ->
                    change.consume()
                    currentOnScrub(indexAt(change.position.x))
                }
            },
    ) {
        val left = 0f
        val right = size.width - rightGutter.toPx()
        val top = 6.dp.toPx()
        val bottom = size.height - bottomGutter.toPx()
        val w = right - left
        fun xOf(i: Int) = left + w * i / (n - 1).coerceAtLeast(1)
        fun yOf(pct: Float) = bottom - (pct - yLo) / (yHi - yLo) * (bottom - top)

        // Gridlines with % labels on the right; 0% dashed and a touch stronger.
        val labelPaint = android.graphics.Paint().apply {
            textSize = labelPx
            isAntiAlias = true
            this.color = labelColor.toArgb()
        }
        var g = yLo
        while (g <= yHi + 0.001f) {
            val y = yOf(g)
            if (abs(g) < 0.001f) {
                drawLine(
                    gridColor, Offset(left, y), Offset(right, y), 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            } else {
                drawLine(gridColor.copy(alpha = 0.35f), Offset(left, y), Offset(right, y), 1.dp.toPx())
            }
            drawIntoCanvas {
                it.nativeCanvas.drawText(signedPct(g, gridDecimals), right + 8.dp.toPx(), y + labelPx / 3, labelPaint)
            }
            g += step
        }

        // Calendar ticks along the bottom (or just the first and last week when too short).
        val totalDays = ChronoUnit.DAYS.between(trend.startWeek, endWeek).toFloat().coerceAtLeast(1f)
        val tickPaint = android.graphics.Paint(labelPaint).apply { textAlign = android.graphics.Paint.Align.CENTER }
        val dateY = size.height - 4.dp.toPx()
        val labelled = if (ticks.isNotEmpty()) ticks.map { it.date to it.label }
        else listOf(trend.startWeek to formatMonthDay(trend.startWeek), endWeek to formatMonthDay(endWeek))
        for ((date, label) in labelled) {
            val x = left + w * ChronoUnit.DAYS.between(trend.startWeek, date) / totalDays
            // A faint vertical line per calendar tick, as on the History chart.
            if (ticks.isNotEmpty()) drawLine(gridColor.copy(alpha = 0.18f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            val halfWidth = tickPaint.measureText(label) / 2
            drawIntoCanvas { it.nativeCanvas.drawText(label, x.coerceIn(left + halfWidth, right - halfWidth), dateY, tickPaint) }
        }

        // The line: smooth (horizontal tangents, so it never overshoots a real value) over a fade.
        fun Path.traceLine(values: List<Float>) {
            val count = minOf(n, values.size)
            for (i in 0 until count) {
                val x = xOf(i)
                val y = yOf(values[i])
                if (i == 0) moveTo(x, y) else {
                    val px = xOf(i - 1)
                    val py = yOf(values[i - 1])
                    val cx = (px + x) / 2
                    cubicTo(cx, py, cx, y, x, y)
                }
            }
        }
        val line = Path().apply { traceLine(pcts) }
        val fill = Path().apply {
            traceLine(pcts)
            lineTo(xOf(n - 1), bottom)
            lineTo(xOf(0), bottom)
            close()
        }
        clipRect(left = left - 4.dp.toPx(), right = left + (w + 8.dp.toPx()) * reveal.value) {
            drawPath(fill, Brush.verticalGradient(listOf(fillColor.copy(alpha = 0.26f), fillColor.copy(alpha = 0f)), startY = top, endY = bottom))
            // Children first (thin), the spotlit one last so it sits on top of its siblings.
            val order = overlays.indices.sortedBy { overlays[it].node == focus }
            for (k in order) {
                val o = overlays[k]
                val spot = o.node == focus
                val alpha = when {
                    focus == null -> 0.85f
                    spot -> 1f
                    else -> 0.14f
                }
                drawPath(
                    Path().apply { traceLine(overlayPcts[k]) },
                    o.color.copy(alpha = alpha),
                    style = Stroke(width = (if (spot) 2.4 else 1.5).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
            drawPath(
                line,
                lineColor.copy(alpha = if (focus != null) 0.55f else 1f),
                style = Stroke(width = 2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
            )
            // Real sessions as small dots on the line; between them it's filled in.
            for (i in sessionMarks) {
                if (i !in 0 until n) continue
                val c = Offset(xOf(i), yOf(pcts[i]))
                drawCircle(backgroundColor, radius = 4.2.dp.toPx(), center = c)
                drawCircle(lineColor, radius = 2.8.dp.toPx(), center = c)
            }
        }

        val s = scrubIndex
        if (s != null && s in 0 until n) {
            // Scrub guide: a hairline and a haloed dot on the line.
            val x = xOf(s)
            val y = yOf(pcts[s])
            drawLine(guideColor.copy(alpha = 0.35f), Offset(x, top), Offset(x, bottom), 1.dp.toPx())
            val spotIndex = overlays.indexOfFirst { it.node == focus }
            if (spotIndex >= 0) {
                overlayPcts[spotIndex].getOrNull(s)?.let { v ->
                    val oy = yOf(v)
                    drawCircle(backgroundColor, radius = 4.5.dp.toPx(), center = Offset(x, oy))
                    drawCircle(overlays[spotIndex].color, radius = 3.2.dp.toPx(), center = Offset(x, oy))
                }
            }
            drawCircle(lineColor.copy(alpha = 0.22f), radius = 10.dp.toPx(), center = Offset(x, y))
            drawCircle(backgroundColor, radius = 5.5.dp.toPx(), center = Offset(x, y))
            drawCircle(lineColor, radius = 4.dp.toPx(), center = Offset(x, y))
        } else if (reveal.value >= 0.999f) {
            // Resting state: mark where it is now.
            val x = xOf(n - 1)
            val y = yOf(pcts[n - 1])
            drawCircle(backgroundColor, radius = 5.dp.toPx(), center = Offset(x, y))
            drawCircle(lineColor, radius = 3.5.dp.toPx(), center = Offset(x, y))
        }
    }
}

// ---- Exercise level: recent sets + into the full history ------------------------------------

@Composable
private fun RecentSets(
    exerciseId: Long,
    startWeek: LocalDate?,
    weekCount: Int,
    highlightWeek: Int?,
    onPickWeek: (Int?) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current
    val dao = remember { (context.applicationContext as LiftLogApp).database.dao() }
    val entries by remember(exerciseId) { dao.observeExerciseHistory(exerciseId) }
        .collectAsStateWithLifecycle(emptyList())
    val exercise by remember(exerciseId) { dao.observeExercise(exerciseId) }.collectAsStateWithLifecycle(null)
    val addOnFactor = exercise?.addOnFactor ?: 1f
    val bodyWeights by remember { dao.observeBodyWeightHistory() }.collectAsStateWithLifecycle(emptyList())
    val sortedBW = remember(bodyWeights) { bodyWeights.sortedBy { it.date } }

    // The chart's week index this set falls in (null when the chart doesn't cover it).
    fun weekOf(e: LogEntryEntity): Int? = startWeek
        ?.let { ChronoUnit.WEEKS.between(it, weekStart(e.date)).toInt() }
        ?.takeIf { it in 0 until weekCount }

    SectionLabel("Recent sets")
    CardList {
        val recent = entries.take(6)
        // A week picked on the chart may be older than the six shown: its sets join the end of the
        // list so the link is never lost.
        val older = if (highlightWeek == null) emptyList()
        else entries.drop(6).filter { weekOf(it) == highlightWeek }
        if (recent.isEmpty()) {
            Text(
                "No sets logged yet",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
        (recent + older).forEachIndexed { i, e ->
            if (i > 0) RowDivider()
            val week = weekOf(e)
            val bw = if (e.weight == null) bodyWeightOn(sortedBW, e.date) else null
            val lit = week != null && week == highlightWeek
            val tint by animateColorAsState(
                if (lit) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else Color.Transparent,
                tween(140),
                label = "set-tint",
            )
            val accent = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(tint)
                    .clickable(enabled = week != null) { onPickWeek(if (lit) null else week) }
                    .padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(formatDate(e.date), fontSize = 13.sp, color = accent, modifier = Modifier.width(96.dp))
                Text(
                    formatValue(e.reps, e.weight, bw) + (e.extraKg?.let { " +${trimFloat(it)}" } ?: ""),
                    fontSize = 14.5.sp,
                    fontWeight = if (lit) FontWeight.SemiBold else FontWeight.Medium,
                    color = if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                val oneRm = epley(e.reps, effectiveWeight(e.weight ?: bw, e.extraKg, addOnFactor))
                if (oneRm != null) {
                    Text("1RM ${round1(oneRm)}", fontSize = 12.sp, color = accent)
                }
            }
        }
        RowDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenHistory)
                .padding(start = 16.dp, end = 10.dp, top = 13.dp, bottom = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Open full history",
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            Chevron()
        }
    }
}
