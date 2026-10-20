package com.orbity.shared

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SharedTest {

    private val identity = channels(1f, 1f, 1f)

    /** Applies a 4x5 colour matrix to an RGB colour (alpha = 255), like Android does. */
    private fun apply(m: FloatArray, r: Float, g: Float, b: Float): List<Float> {
        val c = floatArrayOf(r, g, b, 255f)
        return (0 until 3).map { i -> (0 until 4).sumOf { k -> (m[i * 5 + k] * c[k]).toDouble() }.toFloat() + m[i * 5 + 4] }
    }

    @Test
    fun composingWithIdentityChangesNothing() {
        val m = saturation(1.3f) then contrast(1.2f, 10f)
        assertArrayEquals(m, m then identity, 1e-5f)
        assertArrayEquals(m, identity then m, 1e-5f)
    }

    @Test
    fun thenAppliesLeftMatrixFirst() {
        // contrast then brightness-shift must equal applying them one after the other.
        val a = contrast(1.5f)
        val b = channels(0.5f, 1f, 1f, offsetR = 20f)
        val step = apply(a, 100f, 150f, 200f)
        val expected = apply(b, step[0], step[1], step[2])
        val actual = apply(a then b, 100f, 150f, 200f)
        for (i in 0 until 3) assertEquals(expected[i], actual[i], 1e-3f)
    }

    @Test
    fun monoProducesGrey() {
        val (r, g, b) = apply(Filter.MONO.matrix!!, 200f, 40f, 90f)
        assertEquals(r, g, 1e-3f)
        assertEquals(g, b, 1e-3f)
    }

    @Test
    fun filtersKeepWhiteAndBlackInRange() {
        for (f in Filter.entries) {
            val m = f.matrix ?: continue
            assertEquals("${f.name} matrix size", 20, m.size)
            // Alpha row must stay untouched.
            assertArrayEquals(f.name, floatArrayOf(0f, 0f, 0f, 1f, 0f), m.copyOfRange(15, 20), 1e-6f)
            apply(m, 128f, 128f, 128f).forEach { v -> assert(v in 0f..255f) { "${f.name} mid-grey -> $v" } }
        }
    }

    @Test
    fun cameraStateRoundTrips() {
        val state = CameraState(Flash.AUTO, true, Filter.VINTAGE, 2.5f, 0.6f, 10f, -3, -12, 12, 1f / 6)
        assertEquals(state, CameraState.decode(state.encode()))
        assertEquals(CameraState(), CameraState.decode(CameraState().encode()))
    }

    @Test
    fun cameraStateRejectsGarbage() {
        assertNull(CameraState.decode("nonsense".toByteArray()))
        assertNull(CameraState.decode(ByteArray(0)))
        assertNull(CameraState.decode("OFF;false;ORIGINAL;1;1;1;0;0;0".toByteArray()))
    }
}
