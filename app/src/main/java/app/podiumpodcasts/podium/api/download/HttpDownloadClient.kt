package app.podiumpodcasts.podium.api.download

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException

sealed interface HttpDownloadClientResult {
    data class Success(val totalBytes: Long = 0L) : HttpDownloadClientResult
    data class Failure(
        val e: Exception,
        val isRetryable: Boolean = true
    ) : HttpDownloadClientResult
}

class HttpDownloadClient(
    private val client: HttpClient = sharedClient
) {
    companion object {
        val sharedClient: HttpClient by lazy {
            HttpClient {
                followRedirects = true
                install(HttpTimeout) {
                    connectTimeoutMillis = 15_000
                    socketTimeoutMillis = 60_000
                }
            }
        }

        private val CONTENT_RANGE_REGEX = Regex("""bytes\s+(\d+)-(\d+)/(\d+|\*)""", RegexOption.IGNORE_CASE)
    }

    suspend fun download(
        url: String,
        output: File,
        onProgress: (progress: Long, total: Long) -> Unit
    ): HttpDownloadClientResult {
        val metaFile = File(output.parentFile, "${output.name}.meta")
        return try {
            val existingLength = if (output.exists()) output.length() else 0L
            val savedValidator = if (existingLength > 0L && metaFile.exists()) {
                metaFile.readText().trim().takeIf { it.isNotBlank() }
            } else {
                null
            }

            var finalTotalBytes = 0L

            client.prepareGet(url) {
                if (existingLength > 0L && savedValidator != null) {
                    header(HttpHeaders.Range, "bytes=$existingLength-")
                    header(HttpHeaders.IfRange, savedValidator)
                }
            }.execute { httpResponse ->
                val status = httpResponse.status
                if (!status.isSuccess()) {
                    val retryable = status.value >= 500 || status.value == 429 || status.value == 408
                    throw HttpStatusException(status.value, status.description, retryable)
                }

                val responseContentLength = httpResponse.contentLength()
                val newValidator = httpResponse.headers[HttpHeaders.ETag]?.takeIf { it.isNotBlank() }
                    ?: httpResponse.headers[HttpHeaders.LastModified]?.takeIf { it.isNotBlank() }

                val appendMode: Boolean
                var progress: Long
                val totalBytes: Long

                if (status == HttpStatusCode.PartialContent && existingLength > 0L && savedValidator != null) {
                    val contentRange = httpResponse.headers[HttpHeaders.ContentRange]
                    val match = contentRange?.let { CONTENT_RANGE_REGEX.find(it.trim()) }
                    val rangeStart = match?.groupValues?.getOrNull(1)?.toLongOrNull()
                    val rangeTotal = match?.groupValues?.getOrNull(3)?.toLongOrNull()

                    if (rangeStart != null && rangeStart == existingLength) {
                        appendMode = true
                        progress = existingLength
                        totalBytes = rangeTotal
                            ?: if (responseContentLength != null && responseContentLength > 0L) {
                                existingLength + responseContentLength
                            } else {
                                -1L
                            }
                    } else {
                        // Server returned unexpected Content-Range; restart cleanly from 0
                        throw IOException("Unexpected Content-Range '$contentRange' for offset $existingLength")
                    }
                } else {
                    appendMode = false
                    progress = 0L
                    totalBytes = responseContentLength ?: -1L
                }

                if (newValidator != null) {
                    metaFile.writeText(newValidator)
                } else if (metaFile.exists()) {
                    metaFile.delete()
                }

                val channel: ByteReadChannel = httpResponse.bodyAsChannel()
                val buffer = ByteBuffer.allocate(16 * 1024)
                var bytesThisResponse = 0L

                FileOutputStream(output, appendMode).channel.use { fileChannel ->
                    while (!channel.isClosedForRead) {
                        buffer.clear()
                        val read = channel.readAvailable(buffer)
                        if (read == -1) break

                        buffer.flip()
                        while (buffer.hasRemaining()) {
                            fileChannel.write(buffer)
                        }

                        bytesThisResponse += read
                        progress += read
                        if (totalBytes > 0L) {
                            onProgress(progress, totalBytes)
                        }
                    }
                }

                if (responseContentLength != null && responseContentLength > 0L && bytesThisResponse != responseContentLength) {
                    throw IOException(
                        "Incomplete download: expected $responseContentLength bytes, received $bytesThisResponse bytes"
                    )
                }

                finalTotalBytes = if (totalBytes > 0L) totalBytes else progress
            }

            if (metaFile.exists()) metaFile.delete()
            HttpDownloadClientResult.Success(totalBytes = finalTotalBytes)
        } catch (c: CancellationException) {
            throw c
        } catch (e: HttpStatusException) {
            if (!e.isRetryable) {
                output.delete()
                metaFile.delete()
            }
            HttpDownloadClientResult.Failure(e, isRetryable = e.isRetryable)
        } catch (e: Exception) {
            HttpDownloadClientResult.Failure(e, isRetryable = e is IOException)
        }
    }
}

class HttpStatusException(
    val statusCode: Int,
    statusDescription: String,
    val isRetryable: Boolean
) : IOException("HTTP $statusCode $statusDescription")