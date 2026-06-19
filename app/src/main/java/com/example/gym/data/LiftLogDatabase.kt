package com.example.gym.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        CategoryEntity::class,
        AreaEntity::class,
        ExerciseEntity::class,
        SetRowEntity::class,
        LogEntryEntity::class,
    ],
    version = 1,
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
                ).build().also { instance = it }
            }
    }
}
