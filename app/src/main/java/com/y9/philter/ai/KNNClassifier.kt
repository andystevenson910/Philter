package com.y9.philter.ai

data class LabeledEmbedding(val embedding: FloatArray, val deleted: Boolean)

class KNNClassifier(private val k: Int = 5) {
    private val labeled = mutableListOf<LabeledEmbedding>()
    private val lock = Any()  // Synchronization lock

    fun addSample(embedding: FloatArray, deleted: Boolean) {
        synchronized(lock) {
            labeled.add(LabeledEmbedding(embedding, deleted))
        }
    }

    fun predict(embedding: FloatArray): Float {
        synchronized(lock) {
            if (labeled.isEmpty()) return 0.5f

            // Make a copy of the list to avoid concurrent modification
            val labeledCopy = labeled.toList()

            val neighbors = labeledCopy
                .map { it to cosineSimilarity(it.embedding, embedding) }
                .sortedByDescending { it.second }
                .take(k)

            val deletedCount = neighbors.count { it.first.deleted }
            return deletedCount.toFloat() / k
        }
    }

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        val dot = a.zip(b).sumOf { (x, y) -> (x * y).toDouble() }
        val normA = kotlin.math.sqrt(a.sumOf { (it * it).toDouble() })
        val normB = kotlin.math.sqrt(b.sumOf { (it * it).toDouble() })
        return (dot / (normA * normB)).toFloat()
    }
}