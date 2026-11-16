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

    fun fetch(url: String, dest: File, sha256: String? = null, progress: (Long, Long) -> Unit = { _, _ -> }): File {
        if (dest.exists() && dest.length() > 0L) return dest
        val lock = locks.getOrPut(dest.absolutePath) { Any() }
        synchronized(lock) {
            // Another caller may have finished the download while we waited for the lock
            if (dest.exists() && dest.length() > 0L) return dest
            return fetchLocked(url, dest, sha256, progress)
        }
    }

    private fun fetchLocked(url: String, dest: File, sha256: String?, progress: (Long, Long) -> Unit): File {
        dest.parentFile?.mkdirs()
        if (dest.exists()) dest.delete() // 0-byte leftover
        val part = File(dest.path + ".part")
        if (part.exists()) part.delete()
        val md = if (sha256 != null) MessageDigest.getInstance("SHA-256") else null
        var currentUrl = url
        var conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 30_000
        }
        // Hugging Face redirects to a CDN on another host, which HttpURLConnection won't follow on its own
        var hops = 0
        while (conn.responseCode in 300..399 && hops++ < 5) {
            val loc = conn.getHeaderField("Location") ?: break
            val next = URL(URL(currentUrl), loc)
            conn.disconnect()
            currentUrl = next.toString()
            conn = (next.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 30_000
            }
        }
        val status = conn.responseCode
        if (status !in 200..299) {
            conn.disconnect()
            error("HTTP $status downloading $url")
        }
        val total = conn.contentLengthLong
        var done = 0L
        try {
            conn.inputStream.use { input ->
                BufferedOutputStream(part.outputStream(), 1 shl 20).use { output ->
                    val buf = ByteArray(1 shl 18) // 256 KB
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        md?.update(buf, 0, n)
                        done += n
                        progress(done, total)
                    }
                }
            }
        } catch (t: Throwable) {
            part.delete()
            throw t
        } finally {
            conn.disconnect()
        }
        if (total > 0 && done != total) {
            part.delete()
            error("incomplete download for ${dest.name}: $done of $total bytes")
        }
        if (sha256 != null && md != null) {
            val got = md.digest().joinToString("") { "%02x".format(it) }
            if (!got.equals(sha256, ignoreCase = true)) {
                part.delete()
                error("checksum mismatch for $url")
            }
        }
        if (!part.renameTo(dest)) {
            // Fall back to copy+delete (e.g. if rename is refused); accept an already-placed file
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
