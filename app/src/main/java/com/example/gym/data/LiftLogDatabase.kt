package com.example.gym.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CategoryEntity::class,
        MuscleGroupEntity::class,
        AreaEntity::class,
        ExerciseEntity::class,
        SetRowEntity::class,
        LogEntryEntity::class,
        BodyWeightEntity::class,
        SyncQueueEntity::class,
    ],
    version = 5,
    exportSchema = true,
)
@TypeConverters(Converters::class)
abstract class LiftLogDatabase : RoomDatabase() {
    abstract fun dao(): LiftLogDao

    companion object {
        @Volatile
        private var instance: LiftLogDatabase? = null

        fun get(context: Context): LiftLogDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    LiftLogDatabase::class.java,
                    "liftlog.db",
                ).addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5).build().also { instance = it }
            }
    }
}
