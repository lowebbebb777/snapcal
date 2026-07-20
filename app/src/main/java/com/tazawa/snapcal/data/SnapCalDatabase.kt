package com.tazawa.snapcal.data

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [ScreenshotRecord::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(RecordConverters::class)
abstract class SnapCalDatabase : RoomDatabase() {
    abstract fun screenshotRecordDao(): ScreenshotRecordDao
}

