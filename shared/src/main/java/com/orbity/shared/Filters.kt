package com.orbity.shared

/**
 * Colour filters as 4x5 row-major colour matrices, the layout used by both
 * android.graphics.ColorMatrix and Compose's ColorMatrix (offsets are in 0..255).
 * The arrays are shared; never mutate them.
 */
enum class Filter(val label: String, val matrix: FloatArray?) {
    ORIGINAL("Original", null),
    VIVID("Vivid", saturation(1.4f) then contrast(1.12f)),
    WARM("Warm", channels(1.08f, 1f, 0.86f, offsetR = 8f) then saturation(1.1f)),
    COOL("Cool", channels(0.9f, 1f, 1.1f, offsetB = 8f)),
    CHROME("Chrome", contrast(1.15f) then saturation(1.2f) then channels(1f, 1.02f, 1.05f)),
    DRAMATIC("Dramatic", contrast(1.35f) then saturation(0.75f)),
    FADE("Fade", contrast(0.8f, brightness = 20f) then saturation(0.8f)),
    VINTAGE(
        "Vintage",
        saturation(0.6f) then
            channels(1.08f, 1f, 0.85f, offsetR = 12f, offsetG = 6f) then
            contrast(0.85f, brightness = 10f)
    ),
    SEPIA(
        "Sepia",
        floatArrayOf(
            0.393f, 0.769f, 0.189f, 0f, 0f,
            0.349f, 0.686f, 0.168f, 0f, 0f,
            0.272f, 0.534f, 0.131f, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    ),
    MONO("Mono", saturation(0f)),
    NOIR("Noir", saturation(0f) then contrast(1.45f)),
}

internal fun saturation(s: Float): FloatArray {
    // Rec. 709 luminance weights.
    val r = 0.2126f * (1 - s)
    val g = 0.7152f * (1 - s)
    val b = 0.0722f * (1 - s)
    return floatArrayOf(
        r + s, g, b, 0f, 0f,
        r, g + s, b, 0f, 0f,
        r, g, b + s, 0f, 0f,
        0f, 0f, 0f, 1f, 0f,
    )
}

/** Scales around mid-grey, then shifts by [brightness] (0..255 units). */
internal fun contrast(c: Float, brightness: Float = 0f): FloatArray {
    val t = 128f * (1 - c) + brightness
    return floatArrayOf(
        c, 0f, 0f, 0f, t,
        0f, c, 0f, 0f, t,
        0f, 0f, c, 0f, t,
        0f, 0f, 0f, 1f, 0f,
    )
}

internal fun channels(
    r: Float, g: Float, b: Float,
    offsetR: Float = 0f, offsetG: Float = 0f, offsetB: Float = 0f,
): FloatArray = floatArrayOf(
    r, 0f, 0f, 0f, offsetR,
    0f, g, 0f, 0f, offsetG,
    0f, 0f, b, 0f, offsetB,
    0f, 0f, 0f, 1f, 0f,
)

/** Composes two colour matrices: applies this one first, then [next]. */
internal infix fun FloatArray.then(next: FloatArray): FloatArray {
    val out = FloatArray(20)
    for (i in 0 until 4) {
        for (j in 0 until 5) {
            var v = if (j == 4) next[i * 5 + 4] else 0f
            for (k in 0 until 4) v += next[i * 5 + k] * this[k * 5 + j]
            out[i * 5 + j] = v
        }
    }
    return out
}
