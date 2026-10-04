package com.tarjs.app.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream

class InputTooLargeException(message: String) : IllegalArgumentException(message)

fun copyWithLimit(input: InputStream, output: OutputStream, maxBytes: Long): Long {
    require(maxBytes > 0L) { "Maximum size must be positive" }
    val buffer = ByteArray(64 * 1024)
    var copied = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        if (read == 0) continue
        if (copied + read > maxBytes) throw InputTooLargeException("Input exceeds the allowed size")
        output.write(buffer, 0, read)
        copied += read
    }
    return copied
}

fun readGzipUtf8WithLimit(input: InputStream, maxBytes: Int): String {
    val output = ByteArrayOutputStream(minOf(maxBytes, 64 * 1024))
    GZIPInputStream(input.buffered()).use { gzip -> copyWithLimit(gzip, output, maxBytes.toLong()) }
    return output.toString(Charsets.UTF_8.name())
}
