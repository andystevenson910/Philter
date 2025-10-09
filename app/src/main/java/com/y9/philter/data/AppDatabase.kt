package com.y9.philter.data

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [PhotoDecision::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun photoDecisionDao(): PhotoDecisionDao
}