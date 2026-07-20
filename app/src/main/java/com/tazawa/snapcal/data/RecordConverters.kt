package com.tazawa.snapcal.data

import androidx.room.TypeConverter

class RecordConverters {
    @TypeConverter
    fun recordStatusToString(status: RecordStatus): String = status.storedValue

    @TypeConverter
    fun stringToRecordStatus(value: String): RecordStatus = RecordStatus.fromStoredValue(value)
}

