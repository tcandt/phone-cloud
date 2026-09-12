package com.cloudphone.app.audio

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.util.Log
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * JNA binding interface for the native libopus library.
 * Pre-compiled libopus.so is provided across all Android ABIs (arm64-v8a, armeabi-v7a, x86, x86_64).
 */
interface LibOpus : Library {
    fun opus_decoder_create(Fs: Int, channels: Int, error: IntArray?): Pointer?
    fun opus_decode(st: Pointer?, data: ByteArray?, len: Int, pcm: ShortArray, frame_size: Int, decode_fec: Int): Int
    fun opus_decoder_destroy(st: Pointer?)
}

class OpusAudioDecoder(
    private val sampleRate: Int = 48000,
    private val channels: Int = 2
) {
    companion object {
        private const val TAG = "OpusAudioDecoder"
        private const val MIME_TYPE = "audio/opus"
        private const val MAX_SAMPLES_PER_CHANNEL = 5760 // Max 120ms frame at 48 kHz

        private fun logW(tag: String, msg: String) {
            try { Log.w(tag, msg) } catch (_: Throwable) { println("[$tag] W: $msg") }
        }

        private fun logI(tag: String, msg: String) {
            try { Log.i(tag, msg) } catch (_: Throwable) { println("[$tag] I: $msg") }
        }

        // Lazy singleton for the native libopus JNA instance
        var nativeLibOpus: LibOpus? = try {
            Native.load("opus", LibOpus::class.java)
        } catch (t: Throwable) {
            try {
                Native.load("opusjni", LibOpus::class.java)
            } catch (t2: Throwable) {
                logW(TAG, "Native libopus not loaded: ${t.message}")
                null
            }
        }
    }

    private var codec: MediaCodec? = null
    var isSoftwareFallback = false
        private set
    private var isInitialized = false

    private var nativeDecoder: Pointer? = null

    init {
        initDecoder()
    }

    private fun initDecoder() {
        try {
            // First check if MediaCodec has a hardware/platform Opus decoder
            val decoderName = findOpusDecoderName()
            if (decoderName != null) {
                val mediaCodec = MediaCodec.createByCodecName(decoderName)
                val format = MediaFormat.createAudioFormat(MIME_TYPE, sampleRate, channels)

                // Opus identification header (19 bytes RFC 7845)
                val csd0 = ByteBuffer.allocate(19).order(ByteOrder.nativeOrder())
                csd0.put("OpusHead".toByteArray(Charsets.US_ASCII))
                csd0.put(1.toByte()) // Version
                csd0.put(channels.toByte()) // Channel count
                csd0.putShort(0.toShort()) // Pre-skip
                csd0.putInt(sampleRate) // Input sample rate
                csd0.putShort(0.toShort()) // Output gain
                csd0.put(0.toByte()) // Channel mapping family
                csd0.flip()
                format.setByteBuffer("csd-0", csd0)

                val csd1 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(0L)
                csd1.flip()
                format.setByteBuffer("csd-1", csd1)

                val csd2 = ByteBuffer.allocate(8).order(ByteOrder.nativeOrder()).putLong(80000000L)
                csd2.flip()
                format.setByteBuffer("csd-2", csd2)

                mediaCodec.configure(format, null, null, 0)
                mediaCodec.start()
                codec = mediaCodec
                isInitialized = true
                Log.i(TAG, "Initialized MediaCodec Opus decoder: $decoderName ($sampleRate Hz, $channels ch)")
                return
            }
        } catch (e: Throwable) {
            Log.w(TAG, "MediaCodec Opus decoder initialization failed: ${e.message}. Activating native software fallback.")
        }

        // Activate Software Fallback via native libopus
        activateSoftwareFallback()
    }

    fun activateSoftwareFallback() {
        isSoftwareFallback = true
        val lib = nativeLibOpus
        if (lib != null) {
            try {
                val err = IntArray(1)
                nativeDecoder = lib.opus_decoder_create(sampleRate, channels, err)
                if (nativeDecoder != null && err[0] == 0) {
                    Log.i(TAG, "Initialized native libopus software decoder ($sampleRate Hz, $channels ch)")
                } else {
                    Log.w(TAG, "Failed to create native libopus decoder: error code ${err[0]}")
                    nativeDecoder = null
                }
            } catch (t: Throwable) {
                Log.w(TAG, "Error initializing native libopus decoder: ${t.message}")
                nativeDecoder = null
            }
        } else {
            Log.w(TAG, "Native libopus JNA instance unavailable; PLC fallback will be active.")
        }
        isInitialized = true
    }

    private fun findOpusDecoderName(): String? {
        try {
            val list = MediaCodecList(MediaCodecList.ALL_CODECS)
            for (info in list.codecInfos) {
                if (info.isEncoder) continue
                for (type in info.supportedTypes) {
                    if (type.equals(MIME_TYPE, ignoreCase = true)) {
                        return info.name
                    }
                }
            }
        } catch (e: Throwable) {
            Log.w(TAG, "Error querying MediaCodecList: ${e.message}")
        }
        return null
    }

    /**
     * Decodes an Opus packet and passes PCM16 stereo samples to the callback.
     * If MediaCodec is unavailable, native libopus decodes the payload into PCM16 samples.
     * Silence/PLC is used exclusively for lost packets or decoding errors.
     */
    fun decode(opusPacket: ByteArray, onPcmDecoded: (ByteArray) -> Unit) {
        if (!isInitialized || opusPacket.isEmpty()) return

        val c = codec
        if (c != null && !isSoftwareFallback) {
            try {
                val inIndex = c.dequeueInputBuffer(5000L)
                if (inIndex >= 0) {
                    val inBuf = c.getInputBuffer(inIndex)
                    if (inBuf != null) {
                        inBuf.clear()
                        inBuf.put(opusPacket)
                        c.queueInputBuffer(inIndex, 0, opusPacket.size, System.nanoTime() / 1000, 0)
                    }
                }

                val bufferInfo = MediaCodec.BufferInfo()
                var outIndex = c.dequeueOutputBuffer(bufferInfo, 0L)
                while (outIndex >= 0) {
                    val outBuf = c.getOutputBuffer(outIndex)
                    if (outBuf != null && bufferInfo.size > 0) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        val pcm = ByteArray(bufferInfo.size)
                        outBuf.get(pcm)
                        onPcmDecoded(pcm)
                    }
                    c.releaseOutputBuffer(outIndex, false)
                    outIndex = c.dequeueOutputBuffer(bufferInfo, 0L)
                }
                return
            } catch (e: Throwable) {
                Log.w(TAG, "MediaCodec decode frame error: ${e.message}")
            }
        }

        // Native libopus software decoding path
        val lib = nativeLibOpus
        val decoderPtr = nativeDecoder
        if (lib != null && decoderPtr != null) {
            val pcmShorts = ShortArray(MAX_SAMPLES_PER_CHANNEL * channels)
            val samplesDecoded = try {
                lib.opus_decode(
                    decoderPtr,
                    opusPacket,
                    opusPacket.size,
                    pcmShorts,
                    MAX_SAMPLES_PER_CHANNEL,
                    0
                )
            } catch (t: Throwable) {
                Log.w(TAG, "Native opus_decode exception: ${t.message}")
                -1
            }

            if (samplesDecoded > 0) {
                val byteCount = samplesDecoded * channels * 2
                val pcmBytes = ByteArray(byteCount)
                val bb = ByteBuffer.wrap(pcmBytes).order(ByteOrder.LITTLE_ENDIAN)
                for (i in 0 until samplesDecoded * channels) {
                    bb.putShort(pcmShorts[i])
                }
                onPcmDecoded(pcmBytes)
                return
            }
        }

        // Packet Loss Concealment (PLC) / fallback silence (20ms) only when decode fails
        val samplesPerFrame = (sampleRate * 0.020).toInt() // 20ms = 960 samples
        val pcmLength = samplesPerFrame * channels * 2 // 16-bit stereo = 3840 bytes
        val silencePcm = ByteArray(pcmLength)
        onPcmDecoded(silencePcm)
    }

    fun release() {
        try {
            codec?.stop()
            codec?.release()
        } catch (e: Throwable) {
            Log.w(TAG, "Error releasing MediaCodec: ${e.message}")
        }
        codec = null

        nativeDecoder?.let {
            try {
                nativeLibOpus?.opus_decoder_destroy(it)
            } catch (e: Throwable) {
                Log.w(TAG, "Error destroying native libopus decoder: ${e.message}")
            }
            nativeDecoder = null
        }

        isInitialized = false
    }
}
