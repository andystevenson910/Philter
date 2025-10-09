package com.y9.philter.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface PhotoDecisionDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(decision: PhotoDecision)

    @Query("SELECT * FROM photo_decisions WHERE photoId = :id")
    suspend fun getDecision(id: Long): PhotoDecision?

    @Query("SELECT photoId FROM photo_decisions")
    suspend fun getAllReviewedPhotoIds(): List<Long>

    @Query("SELECT * FROM photo_decisions")
    suspend fun getAllDecisions(): List<PhotoDecision>

    @Query("SELECT * FROM photo_decisions WHERE inTrash = 1")
    suspend fun getTrashPhotos(): List<PhotoDecision>

    @Query("UPDATE photo_decisions SET inTrash = 0 WHERE inTrash = 1")
    suspend fun emptyTrash()

    @Query("UPDATE photo_decisions SET inTrash = 0 WHERE photoId = :id")
    suspend fun restoreFromTrash(id: Long)

    @Query("DELETE FROM photo_decisions")
    suspend fun deleteAll()

    @Query("DELETE FROM photo_decisions WHERE photoId = :id")
    suspend fun deleteDecision(id: Long)
}