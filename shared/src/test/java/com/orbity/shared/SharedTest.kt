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
        val state = CameraState(
            Flash.AUTO, true, Filter.VINTAGE, 2.5f, 0.6f, 10f, -3, -12, 12, 1f / 6,
            CaptureMode.NIGHT, listOf(CaptureMode.PHOTO, CaptureMode.VIDEO, CaptureMode.NIGHT), recording = true,
        )
        assertEquals(state, CameraState.decode(state.encode()))
        assertEquals(CameraState(), CameraState.decode(CameraState().encode()))
    }

    @Test
    fun cameraStateRejectsGarbage() {
        assertNull(CameraState.decode("nonsense".toByteArray()))
        assertNull(CameraState.decode(ByteArray(0)))
        // Old 10-field format from a previous app version.
        assertNull(CameraState.decode("OFF;false;ORIGINAL;1;1;1;0;0;0;0".toByteArray()))
    }

    @Test
    fun frameHeaderRoundTrips() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3)
        val payload = frameWithHeader(123_456, jpeg)
        assertEquals(123_456, frameSeq(payload))
        assertArrayEquals(jpeg, payload.copyOfRange(FRAME_HEADER_BYTES, payload.size))
        assertEquals(123_456, frameSeq(seqBytes(123_456)))
        assertNull(frameSeq(byteArrayOf(1, 2)))
    }

    @Test
    fun muLawRoundTripIsClose() {
        for (sample in listOf(0, 1, -1, 100, -100, 1000, -1000, 8000, -8000, 32767, -32768)) {
            val decoded = MuLaw.decode(MuLaw.encode(sample.toShort())).toInt()
            // µ-law step size grows with magnitude: allow ~3% (or a few units near zero).
            val tolerance = maxOf(8, (kotlin.math.abs(sample) * 0.035).toInt())
            assertEquals("sample $sample", sample.toDouble(), decoded.toDouble(), tolerance.toDouble())
        }
    }

    @Test
    fun muLawKnownValues() {
        // G.711 reference: silence encodes to 0xFF, full-scale positive to 0x80.
        assertEquals(0xFF.toByte(), MuLaw.encode(0.toShort()))
        assertEquals(0x80.toByte(), MuLaw.encode(Short.MAX_VALUE))
        assertEquals(0x00.toByte(), MuLaw.encode(Short.MIN_VALUE))
    }

    @Test
    fun focusPointParsing() {
        assertEquals(0.25f to 0.75f, parseFocusPoint("0.2500,0.7500"))
        assertNull(parseFocusPoint("1.5,0.5"))
        assertNull(parseFocusPoint("0.5"))
        assertNull(parseFocusPoint("a,b"))
    }

    /**
     * Forward model of how the phone builds a watch frame (centre square of the buffer, rotated
     * clockwise, optionally mirrored), used to check that watchPointToBuffer inverts it.
     */
    private fun bufferToWatch(px: Float, py: Float, w: Int, h: Int, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
        val side = minOf(w, h)
        val x = (px - (w - side) / 2f) / side
        val y = (py - (h - side) / 2f) / side
        val (u, v) = when (rotation) {
            90 -> 1f - y to x
            180 -> 1f - x to 1f - y
            270 -> y to 1f - x
            else -> x to y
        }
        return (if (mirrored) 1f - u else u) to v
    }

    @Test
    fun watchTapMapsBackToBuffer() {
        val samples = listOf(400f to 100f, 320f to 240f, 120f to 400f, 560f to 30f)
        for (rotation in listOf(0, 90, 180, 270)) for (mirrored in listOf(false, true)) for ((px, py) in samples) {
            val (u, v) = bufferToWatch(px, py, 640, 480, rotation, mirrored)
            if (u !in 0f..1f || v !in 0f..1f) continue // outside the square the watch shows
            val (bx, by) = watchPointToBuffer(u, v, 640, 480, rotation, mirrored)
            assertEquals("x r=$rotation m=$mirrored", px, bx, 1e-3f)
            assertEquals("y r=$rotation m=$mirrored", py, by, 1e-3f)
        }
    }

    @Test
    fun watchTapCornersFollowRotation() {
        // Top-left of the watch picture with the phone rotated 90°: rotating the buffer clockwise
        // moved its bottom-left corner of the square to the top-left.
        val (x, y) = watchPointToBuffer(0f, 0f, 640, 480, 90, mirrored = false)
        assertEquals(80f, x, 1e-3f)
        assertEquals(480f, y, 1e-3f)
    }
}
