package com.example.gym.ui

/** Which category/muscle-group/muscle a list item belongs to. */
internal data class PinnedIds(val categoryId: Long, val muscleGroupId: Long?, val areaId: Long?)

/** A currently-laid-out list item: its key and top edge relative to the list viewport. */
internal class VisibleItem(val key: String, val offset: Int)

/**
 * The stack of sticky header bars to draw over the list, and the top edge (y, px) of each. A bar
 * whose level isn't present (no group / no area) has its Y ignored.
 */
internal data class PinnedStack(
    val categoryId: Long,
    val muscleGroupId: Long?,
    val areaId: Long?,
    val categoryY: Int,
    val groupY: Int,
    val areaY: Int,
)

private const val NO_BOUNDARY = Int.MAX_VALUE / 2

/** 1 = category header, 2 = muscle-group header, 3 = muscle (area) header, 0 = anything else. */
private fun headerLevel(key: String): Int = when (key.firstOrNull()) {
    'C' -> 1
    'G' -> 2
    'A' -> 3
    else -> 0
}

/**
 * Multi-level sticky headers with push physics, computed purely from where the visible items are.
 *
 * A header is pinned once its real row has scrolled above the spot its bar would occupy (stacked
 * directly under the bars of the levels above it). Its bar then stays there until the next header
 * of the same or a higher level arrives from below, which pushes the bar (and any bars under it)
 * up and out of the way. Nothing is reserved or padded, so there are never blank gaps: the real
 * header rows stay in the list and simply scroll beneath the opaque bars.
 *
 * [items] must be in list order (first = the item straddling the viewport's top edge). Headers that
 * have already scrolled out above the viewport are identified from that first item's owners.
 */
internal fun computePinnedStack(
    items: List<VisibleItem>,
    idsOf: (String) -> PinnedIds?,
    catH: Int,
    groupH: Int,
    areaH: Int,
): PinnedStack? {
    val first = items.firstOrNull() ?: return null
    val firstIds = idsOf(first.key) ?: return null
    val firstLevel = headerLevel(first.key)

    var categoryId = firstIds.categoryId
    var groupId = firstIds.muscleGroupId
    var areaId = firstIds.areaId
    // Index in [items] of each level's current header; -1 = scrolled out above the viewport.
    var catIdx = if (firstLevel == 1) 0 else -1
    var groupIdx = if (firstLevel == 2) 0 else -1
    var areaIdx = if (firstLevel == 3) 0 else -1

    // A later header takes over its level once its row passes the spot its bar would sit.
    for (j in 1 until items.size) {
        val item = items[j]
        val level = headerLevel(item.key)
        if (level == 0) continue
        val ids = idsOf(item.key) ?: continue
        val pinTop = when (level) {
            1 -> 0
            2 -> catH
            else -> catH + if (groupId != null) groupH else 0
        }
        if (item.offset >= pinTop) continue
        when (level) {
            1 -> {
                categoryId = ids.categoryId
                groupId = null
                areaId = null
                catIdx = j
                groupIdx = -1
                areaIdx = -1
            }
            2 -> if (ids.categoryId == categoryId) {
                groupId = ids.muscleGroupId
                areaId = null
                groupIdx = j
                areaIdx = -1
            }
            else -> if (ids.categoryId == categoryId && ids.muscleGroupId == groupId) {
                areaId = ids.areaId
                areaIdx = j
            }
        }
    }

    // Where the current section of [level] ends: the next header at that level or higher.
    fun boundary(level: Int, currentIdx: Int): Int {
        for (j in maxOf(currentIdx + 1, 1) until items.size) {
            if (headerLevel(items[j].key) in 1..level) return items[j].offset
        }
        return NO_BOUNDARY
    }

    val h1 = catH
    val h2 = if (groupId != null) groupH else 0
    val h3 = if (areaId != null) areaH else 0

    // A bar sits at its stacked position, unless the section boundary is close enough to push it
    // (together with every bar below it) up so the whole stack ends at the boundary.
    val areaY = minOf(h1 + h2, boundary(3, areaIdx) - h3)
    val groupY = minOf(h1, boundary(2, groupIdx) - h2 - h3)
    val categoryY = minOf(0, boundary(1, catIdx) - h1 - h2 - h3)

    return PinnedStack(categoryId, groupId, areaId, categoryY, groupY, areaY)
}
