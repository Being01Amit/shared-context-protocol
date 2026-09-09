package com.scp.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class VectorUtilsTest {
    @Test
    fun `toByteArray and toFloatArray roundtrip correctly`() {
        val original = floatArrayOf(0.1f, -0.5f, 3.14159f, 42.0f)
        val bytes = VectorUtils.toByteArray(original)
        val restored = VectorUtils.toFloatArray(bytes)

        assertTrue(bytes != null, "bytes should not be null")
        assertTrue(restored != null, "restored floats should not be null")
        assertEquals(original.size, restored?.size)
        for (i in original.indices) {
            assertEquals(original[i], restored[i], 1e-6f)
        }
    }

    @Test
    fun `null or empty input returns null`() {
        assertNull(VectorUtils.toByteArray(null))
        assertNull(VectorUtils.toFloatArray(null))
        assertNull(VectorUtils.toFloatArray(byteArrayOf()))
    }

    @Test
    fun `cosineSimilarity handles identical orthogonal and opposite vectors`() {
        val v1 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val v2 = floatArrayOf(1.0f, 2.0f, 3.0f)
        val v3 = floatArrayOf(0.0f, 0.0f, 1.0f)

        assertEquals(1.0, VectorUtils.cosineSimilarity(v1, v2), 1e-6)
        assertTrue(VectorUtils.cosineSimilarity(v1, v3) > 0.0)
        assertEquals(0.0, VectorUtils.cosineSimilarity(floatArrayOf(1.0f, 0.0f), floatArrayOf(0.0f, 1.0f)), 1e-6)
    }
}
