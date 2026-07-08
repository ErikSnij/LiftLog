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

/**
 * v2 → v3: add sort_order column to category, muscle_group, area so the user can manually
 * reorder them. Initialised to the row's id so existing insertion order is preserved.
 */
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `category` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `category` SET `sort_order` = id")
        db.execSQL("ALTER TABLE `muscle_group` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `muscle_group` SET `sort_order` = id")
        db.execSQL("ALTER TABLE `area` ADD COLUMN `sort_order` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `area` SET `sort_order` = id")
    }
}

/**
 * v3 → v4:
 * - `archived` moves from set_row to exercise (exercise-level retirement).
 *   Exercises where every set row was archived become archived; others stay active.
 * - New `body_weight` table (one entry per day, unique constraint on date).
 */
val MIGRATION_3_4 = object : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // 1. Add archived flag to exercise
        db.execSQL("ALTER TABLE `exercise` ADD COLUMN `archived` INTEGER NOT NULL DEFAULT 0")
        // Carry forward: archive exercise if all its set rows were archived
        db.execSQL(
            "UPDATE `exercise` SET `archived` = 1 WHERE id IN (" +
                "SELECT exerciseId FROM set_row " +
                "GROUP BY exerciseId " +
                "HAVING SUM(CASE WHEN archived = 0 THEN 1 ELSE 0 END) = 0" +
                ")",
        )

        // 2. Recreate set_row without the archived column
        db.execSQL(
            "CREATE TABLE `set_row_new` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`exerciseId` INTEGER NOT NULL, " +
                "`note` TEXT, " +
                "`flag` TEXT NOT NULL, " +
                "FOREIGN KEY(`exerciseId`) REFERENCES `exercise`(`id`) " +
                "ON UPDATE NO ACTION ON DELETE CASCADE)",
        )
        db.execSQL("INSERT INTO `set_row_new` (id, exerciseId, note, flag) SELECT id, exerciseId, note, flag FROM `set_row`")
        db.execSQL("DROP TABLE `set_row`")
        db.execSQL("ALTER TABLE `set_row_new` RENAME TO `set_row`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_set_row_exerciseId` ON `set_row` (`exerciseId`)")

        // 3. Body weight table
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `body_weight` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`weight` REAL NOT NULL, " +
                "`date` INTEGER NOT NULL)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_body_weight_date` ON `body_weight` (`date`)")
    }
}

/** v4 → v5: new `sync_queue` table tracking which dates still need to be uploaded to TrainHub. */
val MIGRATION_4_5 = object : Migration(4, 5) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `sync_queue` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`date` INTEGER NOT NULL, " +
                "`synced` INTEGER NOT NULL DEFAULT 0, " +
                "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                "`lastAttemptAt` INTEGER, " +
                "`lastError` TEXT)",
        )
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_sync_queue_date` ON `sync_queue` (`date`)")
    }
}

/**
 * v5 → v6: backfill `sync_queue` with every date that already has log entries. MIGRATION_4_5
 * only created the (empty) table — nothing ever enqueued the dates that existed before TrainHub
 * sync shipped, so only dates touched (logged/edited) after that point ever made it to the
 * server. This queues every pre-existing date as unsynced so the worker picks them all up.
 */
val MIGRATION_5_6 = object : Migration(5, 6) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO `sync_queue` (date, synced) " +
                "SELECT DISTINCT date, 0 FROM `log_entry` " +
                "WHERE date NOT IN (SELECT date FROM `sync_queue`)",
        )
    }
}

/**
 * v6 → v7: new `body_metrics_sync_queue` table, mirroring `sync_queue` but for the separate
 * `POST /body-metrics` endpoint. Backfilled with every existing `body_weight` date up front —
 * MIGRATION_4_5 shipped without doing this for sync_queue and it silently orphaned pre-existing
 * dates (see MIGRATION_5_6), so this one doesn't repeat that mistake.
 */
val MIGRATION_6_7 = object : Migration(6, 7) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `body_metrics_sync_queue` (" +
                "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                "`date` INTEGER NOT NULL, " +
                "`synced` INTEGER NOT NULL DEFAULT 0, " +
                "`attempts` INTEGER NOT NULL DEFAULT 0, " +
                "`lastAttemptAt` INTEGER, " +
                "`lastError` TEXT)",
        )
        db.execSQL(
            "CREATE UNIQUE INDEX IF NOT EXISTS `index_body_metrics_sync_queue_date` " +
                "ON `body_metrics_sync_queue` (`date`)",
        )
        db.execSQL(
            "INSERT INTO `body_metrics_sync_queue` (date, synced) " +
                "SELECT DISTINCT date, 0 FROM `body_weight`",
        )
    }
}

/** Escape single quotes for inline SQL literals (none of our names use them, but be safe). */
private fun String.sqlEscape(): String = replace("'", "''")
