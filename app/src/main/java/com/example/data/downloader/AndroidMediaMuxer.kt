package com.example.data.downloader

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

/**
 * Robust Android media muxer using android.media.MediaMuxer and MediaExtractor.
 * Combines separate video-only (H.264/AVC or VP9) and audio-only (AAC or Opus) streams
 * into a single playable video file with synchronized audio, without re-encoding.
 *
 * Employs chronological sample interleaving to prevent libstagefright buffer overflows
 * and timestamp inversion errors common across Android OEMs.
 */
object AndroidMediaMuxer {

    private const val TAG = "AndroidMediaMuxer"

    fun mux(videoFile: File, audioFile: File, outputFile: File): Boolean {
        if (!videoFile.exists() || videoFile.length() < 100 * 1024L) {
            Log.e(TAG, "Video file missing or too small (${videoFile.length()} bytes)")
            return false
        }
        if (!audioFile.exists() || audioFile.length() < 50 * 1024L) {
            Log.e(TAG, "Audio file missing or too small (${audioFile.length()} bytes)")
            return false
        }

        var videoExtractor: MediaExtractor? = null
        var audioExtractor: MediaExtractor? = null
        var muxer: MediaMuxer? = null

        try {
            Log.i(TAG, "Starting muxing: video=${videoFile.name} (${videoFile.length()} bytes), audio=${audioFile.name} (${audioFile.length()} bytes)")

            videoExtractor = MediaExtractor().apply {
                setDataSource(videoFile.absolutePath)
            }
            audioExtractor = MediaExtractor().apply {
                setDataSource(audioFile.absolutePath)
            }

            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    Log.i(TAG, "Found video track $i: mime=$mime")
                    break
                }
            }

            var audioTrackIndex = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("audio/")) {
                    audioTrackIndex = i
                    audioFormat = format
                    Log.i(TAG, "Found audio track $i: mime=$mime")
                    break
                }
            }

            if (videoTrackIndex == -1 || videoFormat == null) {
                Log.e(TAG, "No valid video track found in ${videoFile.name}")
                return false
            }
            if (audioTrackIndex == -1 || audioFormat == null) {
                Log.e(TAG, "No valid audio track found in ${audioFile.name}")
                return false
            }

            val videoMime = videoFormat.getString(MediaFormat.KEY_MIME)?.lowercase() ?: ""
            val audioMime = audioFormat.getString(MediaFormat.KEY_MIME)?.lowercase() ?: ""

            Log.i(TAG, "Muxing formats: video=$videoMime, audio=$audioMime")

            if (outputFile.exists()) {
                outputFile.delete()
            }

            // Determine output container format
            val outputFormat = if (audioMime.contains("opus") || videoMime.contains("vp9")) {
                Log.i(TAG, "Using MUXER_OUTPUT_WEBM format for VP9/Opus tracks")
                MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM
            } else {
                Log.i(TAG, "Using MUXER_OUTPUT_MPEG_4 format for AVC/AAC tracks")
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
            }

            muxer = MediaMuxer(outputFile.absolutePath, outputFormat)
            val muxerVideoTrack = muxer.addTrack(videoFormat)
            val muxerAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()

            // Allocate 2MB buffer for high-bitrate I-frames (4K/1080p/720p keyframes)
            val maxBufferSize = 2 * 1024 * 1024
            val buffer = ByteBuffer.allocate(maxBufferSize)
            val bufferInfo = MediaCodec.BufferInfo()

            videoExtractor.selectTrack(videoTrackIndex)
            audioExtractor.selectTrack(audioTrackIndex)

            var videoSamplesWritten = 0
            var audioSamplesWritten = 0

            var hasMoreVideo = true
            var hasMoreAudio = true

            // Interleaved reading: write samples in order of presentation time to avoid buffer overflow
            while (hasMoreVideo || hasMoreAudio) {
                val videoTime = if (hasMoreVideo) videoExtractor.sampleTime else Long.MAX_VALUE
                val audioTime = if (hasMoreAudio) audioExtractor.sampleTime else Long.MAX_VALUE

                if (hasMoreVideo && (!hasMoreAudio || videoTime <= audioTime)) {
                    val sampleSize = videoExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        hasMoreVideo = false
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = videoExtractor.sampleTime.coerceAtLeast(0L)
                        bufferInfo.flags = videoExtractor.sampleFlags
                        muxer.writeSampleData(muxerVideoTrack, buffer, bufferInfo)
                        videoSamplesWritten++
                        videoExtractor.advance()
                    }
                } else if (hasMoreAudio) {
                    val sampleSize = audioExtractor.readSampleData(buffer, 0)
                    if (sampleSize < 0) {
                        hasMoreAudio = false
                    } else {
                        bufferInfo.offset = 0
                        bufferInfo.size = sampleSize
                        bufferInfo.presentationTimeUs = audioExtractor.sampleTime.coerceAtLeast(0L)
                        bufferInfo.flags = audioExtractor.sampleFlags
                        muxer.writeSampleData(muxerAudioTrack, buffer, bufferInfo)
                        audioSamplesWritten++
                        audioExtractor.advance()
                    }
                }
            }

            Log.i(TAG, "Muxed $videoSamplesWritten video frames and $audioSamplesWritten audio frames successfully")

            muxer.stop()
            muxer.release()
            muxer = null

            val outputSize = outputFile.length()
            Log.i(TAG, "Muxing complete! Output file created: ${outputFile.name} ($outputSize bytes)")
            return outputSize > 100 * 1024L
        } catch (e: Exception) {
            Log.e(TAG, "Error during media muxing: ${e.message}", e)
            if (outputFile.exists()) outputFile.delete()
            return false
        } finally {
            try { muxer?.release() } catch (_: Throwable) {}
            try { videoExtractor?.release() } catch (_: Throwable) {}
            try { audioExtractor?.release() } catch (_: Throwable) {}
        }
    }
}
