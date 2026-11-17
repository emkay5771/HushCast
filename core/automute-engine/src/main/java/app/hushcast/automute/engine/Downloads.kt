package app.hushcast.automute.engine

import java.io.BufferedOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Fetches a model file once (needs the INTERNET permission); call off the main thread. */
object Downloads {
    // One lock per destination file so Settings' "Download Models" and EpisodeAnalysisWorker can't
    // write the same .part file concurrently and race on the final rename.
    private val locks = ConcurrentHashMap<String, Any>()

    fun fetch(
        url: String,
        dest: File,
        sha256: String? = null,
        shouldAbort: () -> Boolean = { false },
        progress: (Long, Long) -> Unit = { _, _ -> }
    ): File {
        if (dest.exists() && dest.length() > 0L) return dest
        val lock = locks.getOrPut(dest.absolutePath) { Any() }
        synchronized(lock) {
            // Another caller may have finished the download while we waited for the lock
            if (dest.exists() && dest.length() > 0L) return dest
            return fetchLocked(url, dest, sha256, shouldAbort, progress)
        }
    }

    private fun fetchLocked(
        url: String,
        dest: File,
        sha256: String?,
        shouldAbort: () -> Boolean,
        progress: (Long, Long) -> Unit
    ): File {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete() // 0-byte leftover
        val part = File(dest.path + ".part")
        val existingBytes = if (part.exists()) part.length() else 0L

        var currentUrl = url
        require(currentUrl.startsWith("https://", ignoreCase = true)) {
            "Refusing non-HTTPS model download URL"
        }

        fun openConn(targetUrl: String, rangeStart: Long): HttpURLConnection {
            return (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 30_000
                if (rangeStart > 0L) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
            }
        }

        var conn = openConn(currentUrl, existingBytes)
        // Hugging Face redirects to a CDN on another host; validate every redirect stays HTTPS.
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            if (shouldAbort()) {
                conn.disconnect()
                throw java.util.concurrent.CancellationException("Download cancelled")
            }
            val loc = conn.getHeaderField("Location") ?: break
            val next = URL(URL(currentUrl), loc)
            if (!next.protocol.equals("https", ignoreCase = true)) {
                conn.disconnect()
                error("Refusing non-HTTPS redirect to ${next.protocol}://${next.host}")
            }
            conn.disconnect()
            currentUrl = next.toString()
            conn = openConn(currentUrl, existingBytes)
        }

        val status = conn.responseCode
        val isResume = status == 206 && existingBytes > 0L &&
            (conn.getHeaderField("Content-Range")?.startsWith("bytes $existingBytes-") == true)
        if (status !in 200..299 && status != 416) {
            conn.disconnect()
            error("HTTP $status downloading $url")
        }
        if (status == 416) {
            conn.disconnect()
            part.delete()
            return fetchLocked(url, dest, sha256, shouldAbort, progress)
        }
        if (!isResume && part.exists()) {
            part.delete()
        }

        val contentLen = conn.contentLengthLong
        val total = if (isResume && contentLen > 0L) existingBytes + contentLen else contentLen
        var done = if (isResume) existingBytes else 0L
        if (done > 0L) progress(done, total)

        try {
            conn.inputStream.use { input ->
                BufferedOutputStream(java.io.FileOutputStream(part, isResume), 1 shl 20).use { output ->
                    val buf = ByteArray(1 shl 18) // 256 KB
                    while (true) {
                        if (shouldAbort()) {
                            throw java.util.concurrent.CancellationException("Download cancelled")
                        }
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } catch (c: java.util.concurrent.CancellationException) {
            // Keep .part for Range-based resume on next run
            throw c
        } catch (t: Throwable) {
            // Keep .part on transient IO errors so next run can resume
            throw t
        } finally {
            conn.disconnect()
        }

        if (total > 0 && done != total) {
            error("incomplete download for ${dest.name}: $done of $total bytes")
        }
        if (sha256 != null) {
            val md = MessageDigest.getInstance("SHA-256")
            part.inputStream().use { input ->
                val buf = ByteArray(1 shl 16)
                while (true) {
                    if (shouldAbort()) throw java.util.concurrent.CancellationException("Download cancelled")
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256, ignoreCase = true)) {
                part.delete()
                error("checksum mismatch for $url")
            }
        }
        if (!part.renameTo(dest)) {
            if (dest.exists() && dest.length() > 0L) {
                part.delete()
                return dest
            }
            runCatching {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }.onFailure {
                error("could not move ${part.name} into place: ${it.message}")
            }
        }
        return dest
    }
}
