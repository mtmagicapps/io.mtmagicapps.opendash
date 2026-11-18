package io.mtmagicapps.opendash.protocol

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Losslessly remuxes an MPEG-TS (.ts) dashcam recording into a standard MP4 (.mp4) container
 * using Android's [MediaExtractor] and [MediaMuxer] without re-encoding.
 */
object TsToMp4Remuxer {
    private const val TAG = "TsToMp4Remuxer"
    private const val BUFFER_SIZE_BYTES = 2 * 1024 * 1024

    private val supportedAudioMimes = setOf(
        MediaFormat.MIMETYPE_AUDIO_AAC,
        MediaFormat.MIMETYPE_AUDIO_MPEG
    )

    fun remux(tsFile: File, mp4File: File): Boolean {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        return try {
            extractor.setDataSource(tsFile.absolutePath)
            muxer = MediaMuxer(mp4File.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val trackMap = mutableMapOf<Int, Int>()
            val aacSampleRates = mutableMapOf<Int, Int>()
            var hasVideo = false
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                val isSupported = mime.startsWith("video/") || mime in supportedAudioMimes
                if (!isSupported) {
                    Log.d(TAG, "Skipping unsupported track $i ($mime)")
                    continue
                }
                val muxerTrack = runCatching { muxer.addTrack(format) }.getOrNull() ?: continue
                trackMap[i] = muxerTrack
                extractor.selectTrack(i)
                if (mime.startsWith("video/")) hasVideo = true
                if (mime == MediaFormat.MIMETYPE_AUDIO_AAC) {
                    val sr = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                        format.getInteger(MediaFormat.KEY_SAMPLE_RATE).coerceAtLeast(8000)
                    } else 44100
                    aacSampleRates[i] = sr
                }
            }

            if (!hasVideo || trackMap.isEmpty()) {
                Log.w(TAG, "No supported video track found in ${tsFile.name}")
                return false
            }

            muxer.start()
            muxerStarted = true

            val buffer = ByteBuffer.allocate(BUFFER_SIZE_BYTES)
            val bufferInfo = MediaCodec.BufferInfo()
            val lastPtsByTrack = mutableMapOf<Int, Long>()

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break

                val extractorTrack = extractor.sampleTrackIndex
                val muxerTrack = trackMap[extractorTrack]
                if (muxerTrack != null) {
                    val basePts = extractor.sampleTime.coerceAtLeast(0L)
                    val flags = extractor.sampleFlags and (
                        MediaCodec.BUFFER_FLAG_KEY_FRAME or
                            MediaCodec.BUFFER_FLAG_CODEC_CONFIG or
                            MediaCodec.BUFFER_FLAG_END_OF_STREAM
                        )
                    val aacSampleRate = aacSampleRates[extractorTrack]
                    if (aacSampleRate != null) {
                        val frames = stripAdtsFrames(buffer, sampleSize)
                        val frameDurationUs = (1024L * 1_000_000L) / aacSampleRate
                        for ((idx, range) in frames.withIndex()) {
                            var pts = basePts + idx * frameDurationUs
                            val prevPts = lastPtsByTrack[muxerTrack]
                            if (prevPts != null && pts <= prevPts) {
                                pts = prevPts + 1L
                            }
                            lastPtsByTrack[muxerTrack] = pts
                            bufferInfo.set(range.first, range.second, pts, flags)
                            muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                        }
                    } else {
                        var pts = basePts
                        val prevPts = lastPtsByTrack[muxerTrack]
                        if (prevPts != null && pts <= prevPts) {
                            pts = prevPts + 1L
                        }
                        lastPtsByTrack[muxerTrack] = pts
                        bufferInfo.set(0, sampleSize, pts, flags)
                        muxer.writeSampleData(muxerTrack, buffer, bufferInfo)
                    }
                }
                extractor.advance()
            }

            muxer.stop()
            muxerStarted = false
            mp4File.exists() && mp4File.length() > 0L
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remux ${tsFile.name} to MP4: ${e.message}", e)
            false
        } finally {
            if (muxerStarted) {
                runCatching { muxer?.stop() }
            }
            runCatching { muxer?.release() }
            runCatching { extractor.release() }
        }
    }

    /**
     * Strips 7-byte (or 9-byte CRC) ADTS headers from AAC samples extracted from MPEG-TS so
     * [MediaMuxer] writes raw AAC frames required by MP4 (`mp4a`) decoders such as ExoPlayer.
     */
    internal fun stripAdtsFrames(buffer: ByteBuffer, totalSize: Int): List<Pair<Int, Int>> {
        if (totalSize < 7) return listOf(0 to totalSize)
        val result = ArrayList<Pair<Int, Int>>(1)
        var offset = 0
        while (offset + 7 <= totalSize) {
            val b0 = buffer.get(offset).toInt() and 0xFF
            val b1 = buffer.get(offset + 1).toInt() and 0xFF
            if (b0 != 0xFF || (b1 and 0xF0) != 0xF0) break
            val headerLen = if ((b1 and 0x01) == 1) 7 else 9
            val b3 = buffer.get(offset + 3).toInt() and 0xFF
            val b4 = buffer.get(offset + 4).toInt() and 0xFF
            val b5 = buffer.get(offset + 5).toInt() and 0xFF
            val frameLen = ((b3 and 0x03) shl 11) or (b4 shl 3) or ((b5 and 0xE0) ushr 5)
            if (frameLen <= headerLen || offset + frameLen > totalSize) break
            result.add((offset + headerLen) to (frameLen - headerLen))
            offset += frameLen
        }
        return if (result.isNotEmpty()) result else listOf(0 to totalSize)
    }
}
