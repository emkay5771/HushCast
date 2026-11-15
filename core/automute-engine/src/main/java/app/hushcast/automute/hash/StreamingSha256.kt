package app.hushcast.automute.hash

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * Streaming SHA-256 hasher for DAI resilience.
 * Computes the full-file SHA-256 on the fly during download (zero extra disk reads).
 */
object StreamingSha256 {
    fun copyWithSha256(
        input: InputStream,
        output: OutputStream,
        bufferSize: Int = 65536,
        onBytesCopied: (Long) -> Unit = {}
    ): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(bufferSize)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            md.update(buf, 0, n)
            output.write(buf, 0, n)
            total += n
            onBytesCopied(total)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun hashFile(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(65536)
        file.inputStream().use { input ->
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
