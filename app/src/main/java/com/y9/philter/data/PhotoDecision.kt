package com.y9.philter.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "photo_decisions")
data class PhotoDecision(
    @PrimaryKey val photoId: Long,
    val decision: String, // "KEEP", "DELETE", "SECURE"
    val timestamp: Long = System.currentTimeMillis(),
    val inTrash: Boolean = false
)