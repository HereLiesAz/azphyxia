package com.hereliesaz.illumera.data.trailer

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener

@UnstableApi
class YoutubeChunkedDataSourceFactory(
    private val chunkSizeBytes: Long = CHUNK_SIZE
) : DataSource.Factory {

    companion object {
        private const val TAG = "YTChunkedDS"
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }

    override fun createDataSource(): DataSource {
        val upstream = DefaultHttpDataSource.Factory()
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(15_000)
            .setAllowCrossProtocolRedirects(true)
            .createDataSource()
        return YoutubeChunkedDataSource(upstream, chunkSizeBytes)
    }

    private class YoutubeChunkedDataSource(
        private val upstream: DefaultHttpDataSource,
        private val chunkSize: Long
    ) : DataSource {

        private var isYouTubeStream = false
        private var totalContentLength = C.LENGTH_UNSET.toLong()
        private var currentChunkStart = 0L
        private var currentChunkEnd = 0L
        private var bytesReadInChunk = 0L
        private var originalDataSpec: DataSpec? = null
        private var shortReadRetries = 0
        private val maxShortReadRetries = 2

        override fun addTransferListener(transferListener: TransferListener) {
            upstream.addTransferListener(transferListener)
        }

        override fun open(dataSpec: DataSpec): Long {
            val host = dataSpec.uri.host.orEmpty()
            isYouTubeStream = host.contains("googlevideo.com")

            if (!isYouTubeStream) return upstream.open(dataSpec)

            originalDataSpec = dataSpec
            currentChunkStart = dataSpec.position
            totalContentLength = dataSpec.length
            return openNextChunk()
        }

        private fun openNextChunk(): Long {
            val end = if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                minOf(currentChunkStart + chunkSize - 1, currentChunkStart + totalContentLength - 1)
            } else {
                currentChunkStart + chunkSize - 1
            }
            currentChunkEnd = end
            shortReadRetries = 0
            openRange(currentChunkStart, currentChunkEnd)
            bytesReadInChunk = 0
            return if (totalContentLength != C.LENGTH_UNSET.toLong()) totalContentLength else C.LENGTH_UNSET.toLong()
        }

        /** Opens a ranged HTTP request for [start]-[end] against the original spec. */
        private fun openRange(start: Long, end: Long) {
            val spec = originalDataSpec ?: throw IllegalStateException("No DataSpec")
            val rangedUri = spec.uri.buildUpon()
                .appendQueryParameter("range", "$start-$end")
                .build()

            val rangedSpec = spec.buildUpon()
                .setUri(rangedUri)
                .setPosition(0)
                .setLength(C.LENGTH_UNSET.toLong())
                .build()

            upstream.open(rangedSpec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (!isYouTubeStream) return upstream.read(buffer, offset, length)

            val bytesRead = upstream.read(buffer, offset, length)
            if (bytesRead == C.RESULT_END_OF_INPUT) {
                val chunkBytesReceived = bytesReadInChunk
                upstream.close()

                if (chunkBytesReceived < (currentChunkEnd - currentChunkStart + 1)) {
                    // A short read here means the connection was cut before the range
                    // we asked for was fully delivered (a network blip, or the CDN
                    // dropping the connection) — the only chunk that's legitimately
                    // shorter than requested is the true final chunk, which is capped
                    // to totalContentLength above and wouldn't hit this branch again
                    // after a successful retry. Retry the remaining sub-range instead
                    // of silently reporting end-of-stream and truncating playback.
                    if (shortReadRetries < maxShortReadRetries) {
                        shortReadRetries++
                        val retryStart = currentChunkStart + chunkBytesReceived
                        return try {
                            openRange(retryStart, currentChunkEnd)
                            val retryBytesRead = upstream.read(buffer, offset, length)
                            if (retryBytesRead > 0) {
                                bytesReadInChunk += retryBytesRead
                                shortReadRetries = 0
                            }
                            retryBytesRead
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to retry short read at $retryStart: ${e.message}")
                            C.RESULT_END_OF_INPUT
                        }
                    }
                    return C.RESULT_END_OF_INPUT
                }

                currentChunkStart += chunkBytesReceived
                if (totalContentLength != C.LENGTH_UNSET.toLong()) {
                    totalContentLength -= chunkBytesReceived
                    if (totalContentLength <= 0) return C.RESULT_END_OF_INPUT
                }

                return try {
                    openNextChunk()
                    val nextBytesRead = upstream.read(buffer, offset, length)
                    if (nextBytesRead > 0) bytesReadInChunk += nextBytesRead
                    nextBytesRead
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to open next chunk at $currentChunkStart: ${e.message}")
                    C.RESULT_END_OF_INPUT
                }
            }

            bytesReadInChunk += bytesRead
            return bytesRead
        }

        override fun getUri(): Uri? = upstream.uri

        override fun close() {
            upstream.close()
            originalDataSpec = null
        }
    }
}
