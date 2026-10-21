package com.orbity.shared

/** Live listening: 16 kHz mono, sent in 160 ms chunks of 8-bit µ-law (16 KB/s). */
const val AUDIO_SAMPLE_RATE = 16_000
const val AUDIO_CHUNK_SAMPLES = 2_560

/** G.711 µ-law: halves 16-bit PCM to 8 bits per sample with speech-friendly quantisation. */
object MuLaw {
    private const val BIAS = 0x84
    private const val CLIP = 32_635

    fun encode(pcm: ShortArray, count: Int = pcm.size): ByteArray =
        ByteArray(count) { encode(pcm[it]) }

    fun decode(data: ByteArray): ShortArray = ShortArray(data.size) { decode(data[it]) }

    fun encode(sample: Short): Byte {
        var s = sample.toInt()
        val sign = if (s < 0) 0x80 else 0
        if (s < 0) s = -s
        if (s > CLIP) s = CLIP
        s += BIAS
        var exponent = 7
        var mask = 0x4000
        while (s and mask == 0 && exponent > 0) {
            exponent--
            mask = mask shr 1
        }
        val mantissa = (s shr (exponent + 3)) and 0x0F
        return (sign or (exponent shl 4) or mantissa).inv().toByte()
    }

    fun decode(code: Byte): Short {
        val u = code.toInt().inv() and 0xFF
        val exponent = (u shr 4) and 0x07
        val mantissa = u and 0x0F
        val magnitude = (((mantissa shl 3) + BIAS) shl exponent) - BIAS
        return (if (u and 0x80 != 0) -magnitude else magnitude).toShort()
    }
}
