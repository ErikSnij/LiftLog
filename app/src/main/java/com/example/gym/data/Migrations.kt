package com.example.gym.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Maps a muscle (formerly "area") onto its muscle group. Used by both the v1→v2 migration
 * (to reorganise existing data) and the seeder (fresh installs), so the two stay in sync.
 * Group names are unique across categories.
 */
val AREA_TO_MUSCLE_GROUP: List<Pair<String, String>> = listOf(
    // UPPER
    "Chest" to "Chest", "Fly chest" to "Chest", "Upper chest" to "Chest",
    "Lats" to "Lats", "Serratus anterior" to "Lats", "Row" to "Lats",
    "Traps & rear" to "Traps", "Traps row - Upper" to "Traps", "Traps row - Mid" to "Traps",
    "Front delts" to "Shoulders", "Medior delts" to "Shoulders", "Rear delts" to "Shoulders",
    "Biceps" to "Arms", "Triceps" to "Arms", "Forearms" to "Arms",
    "Abs" to "Abs",
    "Erectors" to "Lower back",
    // LOWER
    "Legs" to "Quads", "Quads" to "Quads",
    "Hamstrings" to "Hamstrings",
    "Glutes" to "Glutes",
    "Calves" to "Calves",
    "Adductors" to "Hips", "Abductors" to "Hips",
)

/**
 * v1 → v2: insert the muscle_group level between category and area. Each existing muscle (area)
 * keeps its category but is re-parented to a muscle group per [AREA_TO_MUSCLE_GROUP]; anything
 * unmapped lands in a per-category "Other" group so no row is orphaned. All exercises, set rows
 * and log entries are preserved (area ids are kept, so the exercise → area links stay intact).
 */
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. New muscle_group table + index.
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `muscle_group` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`categoryId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "FOREIGN KEY(`categoryId`) REFERENCES `category`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_muscle_group_categoryId` ON `muscle_group` (`categoryId`)")

        // 2. One muscle group per (category, groupName) pair actually used, placed under the
        //    category that owns the mapped areas. Plus an "Other" fallback for every category.
        db.execSQL("INSERT INTO `muscle_group` (categoryId, name) SELECT id, 'Other' FROM `category`")
        val groupToAreas = AREA_TO_MUSCLE_GROUP.groupBy({ it.second }, { it.first })
        for ((group, areas) in groupToAreas) {
            // The group's category = the category of whichever of its areas actually exists.
            val inList = areas.joinToString(", ") { "'${it.sqlEscape()}'" }
            db.execSQL(
                "INSERT INTO `muscle_group` (categoryId, name) " +
                    "SELECT categoryId, '${group.sqlEscape()}' FROM `area` " +
                    "WHERE name IN ($inList) LIMIT 1",
            )
        }

        // 3. Recreate `area` with muscleGroupId instead of categoryId (preserving ids).
        db.execSQL(
            "CREATE TABLE `area_new` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`muscleGroupId` INTEGER NOT NULL, `name` TEXT NOT NULL, " +
                "FOREIGN KEY(`muscleGroupId`) REFERENCES `muscle_group`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        // Default every area into its category's "Other" group (keeps the category link).
        db.execSQL(
            "INSERT INTO `area_new` (id, muscleGroupId, name) " +
                "SELECT a.id, mg.id, a.name FROM `area` a " +
                "JOIN `muscle_group` mg ON mg.categoryId = a.categoryId AND mg.name = 'Other'",
        )
        // Move mapped areas to their real group.
        for ((area, group) in AREA_TO_MUSCLE_GROUP) {
            db.execSQL(
                "UPDATE `area_new` SET muscleGroupId = " +
                    "(SELECT id FROM `muscle_group` WHERE name = '${group.sqlEscape()}') " +
                    "WHERE name = '${area.sqlEscape()}'",
            )
        }
        // Drop any "Other" group that ended up unused.
        db.execSQL(
            "DELETE FROM `muscle_group` WHERE name = 'Other' " +
                "AND id NOT IN (SELECT DISTINCT muscleGroupId FROM `area_new`)",
        )

        db.execSQL("DROP TABLE `area`")
        db.execSQL("ALTER TABLE `area_new` RENAME TO `area`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_area_muscleGroupId` ON `area` (`muscleGroupId`)")
    }
}

/** Escape single quotes for inline SQL literals (none of our names use them, but be safe). */
private fun String.sqlEscape(): String = replace("'", "''")
