package com.example.gym.data

import androidx.room.TypeConverter
import java.time.LocalDate

class Converters {
    @TypeConverter
    fun dateToEpochDay(date: LocalDate?): Long? = date?.toEpochDay()

    @TypeConverter
    fun epochDayToDate(epochDay: Long?): LocalDate? = epochDay?.let(LocalDate::ofEpochDay)

    @TypeConverter
    fun flagToName(flag: Flag?): String? = flag?.name

    @TypeConverter
    fun nameToFlag(name: String?): Flag? = name?.let(Flag::valueOf)

    @TypeConverter
    fun weightModeToName(mode: WeightMode?): String? = mode?.name

    @TypeConverter
    fun nameToWeightMode(name: String?): WeightMode? = name?.let(WeightMode::valueOf)

    @TypeConverter
    fun roundModeToName(mode: WeightRoundMode?): String? = mode?.name

    @TypeConverter
    fun nameToRoundMode(name: String?): WeightRoundMode? = name?.let(WeightRoundMode::valueOf)
}
