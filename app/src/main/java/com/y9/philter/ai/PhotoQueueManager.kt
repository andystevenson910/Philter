package com.y9.philter.ai

import android.content.Context
import android.util.Log
import com.y9.philter.Photo
import com.y9.philter.data.PhotoDecision
import com.y9.philter.data.PhotoDecisionDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class PhotoWithScore(val photo: Photo, val score: Float)

enum class QueuePhase {
    TRAINING,      // First 50 photos, random order
    SORTING,       // Currently scoring photos (transitioning to sorted)
    SORTED,        // Serving sorted queue
    REFILLING      // Adding more photos mid-queue
}

class PhotoQueueManager(
    private val dao: PhotoDecisionDao,
    private val context: Context
) {
    private val knn = KNNClassifier(k = 7)
    private val embedder = ImageEmbedder(context)

    private val photoLibrary = mutableListOf<Photo>()
    private val embeddingCache = mutableMapOf<Long, FloatArray>()

    private val queue = mutableListOf<PhotoWithScore>()
    private var currentIndex = 0

    private val seenPhotoIds = mutableSetOf<Long>()
    private var phase = QueuePhase.TRAINING
    private var consecutiveKeeps = 0

    // Track training decisions
    private var keepCount = 0
    private var deleteCount = 0

    private val MIN_KEEP_SAMPLES = 20
    private val MIN_DELETE_SAMPLES = 20
    private val SORTED_QUEUE_SIZE = 200
    private val REFILL_THRESHOLD = 150  // Start refilling earlier!
    private val REFILL_SIZE = 100
    private val SATURATION_THRESHOLD = 15

    private var isCurrentlyRefilling = false

    suspend fun initialize(allPhotos: List<Photo>) = withContext(Dispatchers.Default) {
        Log.d("QueueManager", "====== INITIALIZATION START ======")

        // Production: restore from database (removed debug auto-clear)
        val allDecisions = dao.getAllDecisions()
        Log.d("QueueManager", "Found ${allDecisions.size} previous decisions")

        for (decision in allDecisions) {
            seenPhotoIds.add(decision.photoId)
            when (decision.decision) {
                "KEEP" -> keepCount++
                "DELETE" -> deleteCount++
            }
        }
        Log.d("QueueManager", "Restored: $keepCount KEEPs, $deleteCount DELETEs")

        Log.d("QueueManager", "Total photos found: ${allPhotos.size}")

        photoLibrary.clear()
        photoLibrary.addAll(allPhotos)

        // Start appropriate phase
        if (keepCount >= MIN_KEEP_SAMPLES && deleteCount >= MIN_DELETE_SAMPLES) {
            fillSortedQueue()
        } else {
            fillTrainingQueue()
        }

        Log.d("QueueManager", "====== INITIALIZATION COMPLETE ======")
    }

    private suspend fun getEmbedding(photo: Photo): FloatArray = withContext(Dispatchers.Default) {
        embeddingCache.getOrPut(photo.id) {
            val embedding = embedder.extractEmbedding(photo.uri)
            if (embedding == null) {
                Log.e("QueueManager", "Failed to extract embedding for photo ${photo.id}")
            }
            embedding ?: FloatArray(1280) { 0f }
        }
    }

    private suspend fun fillTrainingQueue() {
        phase = QueuePhase.TRAINING
        queue.clear()
        currentIndex = 0

        Log.d("QueueManager", "📚 TRAINING PHASE STARTED")
        Log.d("QueueManager", "Need $MIN_KEEP_SAMPLES KEEPs and $MIN_DELETE_SAMPLES DELETEs")

        // Grab random photos for training (start with 30)
        val unseenPhotos = photoLibrary.filter { it.id !in seenPhotoIds }
        val trainingPhotos = unseenPhotos.shuffled().take(70)

        Log.d("QueueManager", "Loading initial ${trainingPhotos.size} training photos...")

        // Extract embeddings
        trainingPhotos.forEachIndexed { index, photo ->
            getEmbedding(photo) // Cache the embedding
            if ((index + 1) % 10 == 0) {
                Log.d("QueueManager", "Cached ${index + 1}/${trainingPhotos.size} embeddings")
            }
        }

        queue.addAll(trainingPhotos.map { PhotoWithScore(it, 0f) })
        Log.d("QueueManager", "Training queue ready: ${queue.size} photos")
    }

    private suspend fun addMoreTrainingPhotos(): Boolean {
        Log.d("QueueManager", "📚 Adding more training photos...")

        // Get unseen photos not already in queue
        val queuePhotoIds = queue.map { it.photo.id }.toSet()
        val unseenPhotos = photoLibrary.filter {
            it.id !in seenPhotoIds && it.id !in queuePhotoIds
        }

        if (unseenPhotos.isEmpty()) {
            Log.d("QueueManager", "⚠️ No more unseen photos available for training")
            return false
        }

        // Add 20 more random photos
        val morePhotos = unseenPhotos.shuffled().take(20)

        morePhotos.forEach { photo ->
            getEmbedding(photo) // Cache embedding
        }

        queue.addAll(morePhotos.map { PhotoWithScore(it, 0f) })
        Log.d("QueueManager", "Added ${morePhotos.size} more training photos. Queue now: ${queue.size}")
        return true
    }

    private suspend fun fillSortedQueue() = withContext(Dispatchers.Default) {
        phase = QueuePhase.SORTING
        Log.d("QueueManager", "🤖 SWITCHING TO AI SORTED MODE")
        Log.d("QueueManager", "Training complete: $keepCount KEEPs, $deleteCount DELETEs")

        // Grab 200 RANDOM photos from unseen library
        val unseenPhotos = photoLibrary.filter { it.id !in seenPhotoIds }
        val photosToScore = unseenPhotos.shuffled().take(SORTED_QUEUE_SIZE)

        Log.d("QueueManager", "Scoring ${photosToScore.size} random photos...")

        // Extract embeddings and score
        val scored = photosToScore.mapIndexed { index, photo ->
            val embedding = getEmbedding(photo)
            val score = knn.predict(embedding)

            if ((index + 1) % 20 == 0) {
                Log.d("QueueManager", "Scored ${index + 1}/${photosToScore.size} photos")
            }

            PhotoWithScore(photo, score)
        }

        // Sort by delete probability (highest first)
        val sorted = scored.sortedByDescending { it.score }

        queue.clear()
        queue.addAll(sorted)
        currentIndex = 0
        consecutiveKeeps = 0
        phase = QueuePhase.SORTED

        val topScore = sorted.firstOrNull()?.score ?: 0f
        val avgScore = sorted.map { it.score }.average().toFloat()

        Log.d("QueueManager", "✅ AI SORTED QUEUE READY")
        Log.d("QueueManager", "Queue size: ${queue.size}")
        Log.d("QueueManager", "Top score: ${"%.3f".format(topScore)}")
        Log.d("QueueManager", "Avg score: ${"%.3f".format(avgScore)}")
    }

    private suspend fun refillQueue() = withContext(Dispatchers.Default) {
        if (isCurrentlyRefilling) {
            Log.d("QueueManager", "⏭️ Refill already in progress, skipping")
            return@withContext
        }

        isCurrentlyRefilling = true
        Log.d("QueueManager", "🔄 REFILLING QUEUE (background)")

        // Grab 100 NEW random photos from unseen library
        val unseenPhotos = photoLibrary.filter { it.id !in seenPhotoIds }
        val newPhotos = unseenPhotos.shuffled().take(REFILL_SIZE)

        if (newPhotos.isEmpty()) {
            Log.d("QueueManager", "⚠️ No more unseen photos to refill")
            isCurrentlyRefilling = false
            return@withContext
        }

        Log.d("QueueManager", "Scoring ${newPhotos.size} new photos in background...")

        // Score ONLY the new photos
        val newScored = newPhotos.mapIndexed { index, photo ->
            val embedding = getEmbedding(photo)
            val score = knn.predict(embedding)
            PhotoWithScore(photo, score)
        }

        // Get remaining queue ONLY (don't include already-swiped photos)
        val remainingQueue = if (currentIndex < queue.size) {
            queue.subList(currentIndex, queue.size).toList()
        } else {
            emptyList()
        }

        // Merge and sort
        val merged = (remainingQueue + newScored).sortedByDescending { it.score }

        // Atomic update: clear and refill
        queue.clear()
        queue.addAll(merged)
        currentIndex = 0

        Log.d("QueueManager", "✅ Background refill complete: ${newScored.size} new + ${remainingQueue.size} existing = ${queue.size} total")
        isCurrentlyRefilling = false
    }

    private suspend fun flushAndRestart() {
        Log.d("QueueManager", "⚠️ SATURATION DETECTED!")
        Log.d("QueueManager", "$SATURATION_THRESHOLD consecutive KEEPs - flushing queue")
        consecutiveKeeps = 0
        fillSortedQueue()
    }

    suspend fun recordDecision(photo: Photo, decision: String) {
        dao.insert(PhotoDecision(photo.id, decision))
        seenPhotoIds.add(photo.id)

        // Train KNN
        val embedding = getEmbedding(photo)
        val isDelete = decision == "DELETE"
        knn.addSample(embedding, isDelete)

        if (phase == QueuePhase.TRAINING) {
            // Track training decisions
            when (decision) {
                "KEEP" -> keepCount++
                "DELETE" -> deleteCount++
            }
            Log.d("QueueManager", "📖 Training: $keepCount KEEP, $deleteCount DELETE (need $MIN_KEEP_SAMPLES/$MIN_DELETE_SAMPLES)")
        } else {
            Log.d("QueueManager", "📝 Recorded: $decision (consecutive keeps: $consecutiveKeeps)")
        }

        // Track saturation
        if (decision == "KEEP") {
            consecutiveKeeps++
        } else {
            consecutiveKeeps = 0
        }

        if (phase == QueuePhase.SORTED && consecutiveKeeps >= SATURATION_THRESHOLD) {
            flushAndRestart()
        }
    }

    suspend fun nextPhoto(): PhotoWithScore? {
        // Check if training just completed and we need to switch modes
        // Do this BEFORE getting the next photo to avoid returning stale training photos
        if (phase == QueuePhase.TRAINING &&
            keepCount >= MIN_KEEP_SAMPLES &&
            deleteCount >= MIN_DELETE_SAMPLES) {
            Log.d("QueueManager", "🎓 Training threshold reached! Transitioning to AI mode")
            fillSortedQueue()
            // After fillSortedQueue, we're in SORTED mode with a fresh queue starting at index 0
        }

        // Return null immediately if queue is empty
        if (currentIndex >= queue.size) {
            Log.d("QueueManager", "❌ Queue completely empty at start")
            return null
        }

        // Get the current photo and advance index
        val photo = queue[currentIndex++]
        val scoreStr = if (phase == QueuePhase.SORTED) " (score: ${"%.3f".format(photo.score)})" else ""
        Log.d("QueueManager", "➡️ Photo ${currentIndex}/${queue.size}$scoreStr")

        // NOW check if need to refill/add more after advancing

        // TRAINING PHASE: Check if finished the queue
        if (phase == QueuePhase.TRAINING && currentIndex >= queue.size) {
            // Need more photos for training (we wouldn't be in TRAINING phase if we had enough samples)
            Log.d("QueueManager", "📚 Training queue exhausted, adding more: $keepCount/$MIN_KEEP_SAMPLES KEEPs, $deleteCount/$MIN_DELETE_SAMPLES DELETEs")
            val added = addMoreTrainingPhotos()

            if (!added) {
                Log.d("QueueManager", "⚠️ No more photos available for training, but training incomplete")
            }
        }

        // SORTED PHASE: Check if need to refill proactively (in background)
        val remaining = queue.size - currentIndex
        if (phase == QueuePhase.SORTED &&
            remaining <= REFILL_THRESHOLD &&
            remaining > 0 &&
            !isCurrentlyRefilling) {
            val unseenCount = photoLibrary.count { it.id !in seenPhotoIds }
            if (unseenCount >= REFILL_SIZE) {
                Log.d("QueueManager", "⚠️ Queue low ($remaining left) - starting background refill")
                // Launch refill in background WITHOUT blocking this function
                CoroutineScope(Dispatchers.Default).launch {
                    refillQueue()
                }
            }
        }

        return photo
    }

    fun isRefilling(): Boolean {
        return isCurrentlyRefilling
    }

    fun getQueueSnapshot(): List<PhotoWithScore> {
        if (phase == QueuePhase.SORTING) {
            return emptyList()
        }
        return if (currentIndex < queue.size) {
            queue.subList(currentIndex, queue.size).toList()
        } else {
            emptyList()
        }
    }

    fun getCurrentIndex(): Int = currentIndex
    fun getCurrentPhase(): QueuePhase = phase

    fun getStats(): String {
        val remaining = queue.size - currentIndex
        return when (phase) {
            QueuePhase.TRAINING -> "Training: $keepCount KEEP, $deleteCount DELETE (need $MIN_KEEP_SAMPLES/$MIN_DELETE_SAMPLES)"
            QueuePhase.SORTING -> "Scoring photos... Please wait"
            QueuePhase.SORTED -> {
                val refillingText = if (isCurrentlyRefilling) " | Loading more..." else ""
                "AI Queue: $remaining left | Keeps: $consecutiveKeeps/$SATURATION_THRESHOLD$refillingText"
            }
            QueuePhase.REFILLING -> "AI Queue: $remaining left | Keeps: $consecutiveKeeps/$SATURATION_THRESHOLD"
        }
    }

    fun getTrainingStats(): Pair<Int, Int> {
        return Pair(keepCount, deleteCount)
    }

    suspend fun getDeletedPhotoIds(): Set<Long> = withContext(Dispatchers.Default) {
        val allDecisions = dao.getAllDecisions()
        allDecisions.filter { it.decision == "DELETE" }.map { it.photoId }.toSet()
    }

    suspend fun removeDeletedPhotos(photoIds: List<Long>) = withContext(Dispatchers.Default) {
        photoIds.forEach { photoId ->
            dao.deleteDecision(photoId)
            seenPhotoIds.remove(photoId)
            // Adjust counts if it was a training decision
            if (phase == QueuePhase.TRAINING) {
                deleteCount = maxOf(0, deleteCount - 1)
            }
        }
        Log.d("QueueManager", "Permanently removed ${photoIds.size} photos from database")
    }

    suspend fun restorePhoto(photo: Photo) = withContext(Dispatchers.Default) {
        // Change decision from DELETE to KEEP
        dao.insert(PhotoDecision(photo.id, "KEEP"))

        // Update counts
        if (phase == QueuePhase.TRAINING) {
            deleteCount = maxOf(0, deleteCount - 1)
            keepCount++
        }

        // Update KNN - remove the delete sample and add as keep
        val embedding = getEmbedding(photo)
        knn.addSample(embedding, false) // false = KEEP

        Log.d("QueueManager", "Restored photo ${photo.id} from trash")
    }

    fun cleanup() {
        Log.d("QueueManager", "🧹 Cleanup: Closing embedder")
        embedder.close()
        embeddingCache.clear()
    }
}