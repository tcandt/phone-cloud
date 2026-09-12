package com.cloudphone.app

import com.cloudphone.app.audio.LibOpus
import com.cloudphone.app.audio.OpusAudioDecoder
import com.sun.jna.Pointer
import org.junit.Assert.*
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class OpusDecoderTest {

    // Valid standard Opus frame fixture (TOC byte 0x78 = Silk/CELT stereo 20ms, followed by Opus data)
    private val opusFrameFixture = byteArrayOf(
        0x78.toByte(), 0x00.toByte(), 0x00.toByte(), 0x01.toByte(),
        0x50.toByte(), 0x23.toByte(), 0x45.toByte(), 0x67.toByte(),
        0x89.toByte(), 0xAB.toByte(), 0xCD.toByte(), 0xEF.toByte(),
        0x12.toByte(), 0x34.toByte(), 0x56.toByte(), 0x78.toByte()
    )

    @Test
    fun testSoftwareFallbackPcmOutputStructure() {
        val decoder = OpusAudioDecoder(sampleRate = 48000, channels = 2)
        decoder.activateSoftwareFallback()
        assertTrue(decoder.isSoftwareFallback)

        var callbackCount = 0
        var receivedBytes = 0

        decoder.decode(opusFrameFixture) { pcm ->
            callbackCount++
            receivedBytes = pcm.size
            // PCM16 Stereo frame length must be multiple of 4 (2 channels * 2 bytes per sample)
            assertEquals(0, pcm.size % 4)
            assertTrue(pcm.isNotEmpty())
        }

        assertEquals(1, callbackCount)
        assertTrue(receivedBytes >= 3840) // 20ms at 48kHz stereo = 960 * 2 * 2 = 3840 bytes
        decoder.release()
    }

    @Test
    fun testNativeLibOpusMockDecodingProducesRealPcmSamples() {
        val decoder = OpusAudioDecoder(sampleRate = 48000, channels = 2)

        // Inject mock LibOpus to verify native software path invokes opus_decode and formats PCM16
        val mockLibOpus = object : LibOpus {
            var decodeCalled = false

            override fun opus_decoder_create(Fs: Int, channels: Int, error: IntArray?): Pointer? {
                error?.set(0, 0)
                return Pointer(12345L)
            }

            override fun opus_decode(
                st: Pointer?,
                data: ByteArray?,
                len: Int,
                pcm: ShortArray,
                frame_size: Int,
                decode_fec: Int
            ): Int {
                decodeCalled = true
                val numSamples = 960 // 20ms
                for (i in 0 until numSamples * 2) {
                    pcm[i] = ((i % 100) * 100).toShort() // Non-zero synthesized waveform
                }
                return numSamples
            }

            override fun opus_decoder_destroy(st: Pointer?) {}
        }

        OpusAudioDecoder.nativeLibOpus = mockLibOpus
        decoder.activateSoftwareFallback()

        var decodedPcm: ByteArray? = null
        decoder.decode(opusFrameFixture) { pcm ->
            decodedPcm = pcm
        }

        assertNotNull(decodedPcm)
        assertTrue(mockLibOpus.decodeCalled)
        assertEquals(960 * 2 * 2, decodedPcm!!.size)

        // Assert PCM output contains actual non-zero signal data (not silence zeros)
        val bb = ByteBuffer.wrap(decodedPcm!!).order(ByteOrder.LITTLE_ENDIAN)
        var nonZeroCount = 0
        while (bb.hasRemaining()) {
            val sample = bb.short
            if (sample != 0.toShort()) {
                nonZeroCount++
            }
        }
        assertTrue("Decoded PCM must contain non-zero audio samples", nonZeroCount > 0)
        decoder.release()
    }
}
