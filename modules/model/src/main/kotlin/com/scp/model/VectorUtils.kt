package com.scp.model

import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/**
 * Utility functions for vector embeddings: IEEE 754 Little Endian serialization
 * to/from SQLite BLOB columns, and normalized cosine similarity calculation.
 */
public object VectorUtils {
    public fun toByteArray(floats: FloatArray?): ByteArray? {
        if (floats == null) return null
        val buffer = ByteBuffer.allocate(floats.size * 4).order(ByteOrder.LITTLE_ENDIAN)
        for (f in floats) {
            buffer.putFloat(f)
        }
        return buffer.array()
    }

    public fun toFloatArray(bytes: ByteArray?): FloatArray? {
        if (bytes == null || bytes.isEmpty()) return null
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val floats = FloatArray(bytes.size / 4)
        for (i in floats.indices) {
            floats[i] = buffer.getFloat()
        }
        return floats
    }

    public fun cosineSimilarity(a: FloatArray?, b: FloatArray?): Double {
        if (a == null || b == null || a.isEmpty() || b.isEmpty() || a.size != b.size) return 0.0
        var dotProduct = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val va = a[i].toDouble()
            val vb = b[i].toDouble()
            dotProduct += va * vb
            normA += va * va
            normB += vb * vb
        }
        if (normA <= 0.0 || normB <= 0.0) return 0.0
        return (dotProduct / (sqrt(normA) * sqrt(normB))).coerceIn(0.0, 1.0)
    }
}
