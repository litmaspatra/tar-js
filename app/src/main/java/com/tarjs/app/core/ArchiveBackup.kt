package com.tarjs.app.core

import java.io.EOFException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Optional, explicit local backup of the archive database. Android cloud backup
 * stays disabled; nothing leaves the device unless the user saves or shares the
 * exported file themselves. When a passphrase is given the backup is encrypted
 * with PBKDF2-HmacSHA256 + AES-256-GCM.
 */
object ArchiveBackup {
    private const val MAGIC = "TARJSBAK"
    private const val VERSION_PLAIN = 1
    private const val VERSION_ENCRYPTED = 2
    private const val SALT_LEN = 16
    private const val IV_LEN = 12
    private const val TAG_LEN_BITS = 128
    private const val ITERATIONS = 120_000
    private const val SQLITE_HEADER = "SQLite format 3"

    fun exportBackup(dbFile: File, out: OutputStream, passphrase: String?) {
        out.write(MAGIC.toByteArray(Charsets.US_ASCII))
        if (passphrase.isNullOrEmpty()) {
            out.write(VERSION_PLAIN)
            dbFile.inputStream().buffered(256 * 1024).use { it.copyTo(out, 256 * 1024) }
            out.flush()
            return
        }
        out.write(VERSION_ENCRYPTED)
        val salt = ByteArray(SALT_LEN).also(SecureRandom()::nextBytes)
        val iv = ByteArray(IV_LEN).also(SecureRandom()::nextBytes)
        out.write(salt)
        out.write(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_LEN_BITS, iv))
        CipherOutputStream(out, cipher).use { encrypted ->
            dbFile.inputStream().buffered(256 * 1024).use { it.copyTo(encrypted, 256 * 1024) }
        }
    }

    fun restoreBackup(input: InputStream, passphrase: String?, targetFile: File) {
        val buffered = input.buffered(256 * 1024)
        val magic = ByteArray(MAGIC.length).also { readFully(buffered, it) }
        require(String(magic, Charsets.US_ASCII) == MAGIC) { "Not a TAR-JS backup file" }
        val plain = when (buffered.read()) {
            VERSION_PLAIN -> buffered
            VERSION_ENCRYPTED -> {
                require(!passphrase.isNullOrEmpty()) { "This backup is protected by a passphrase" }
                val salt = ByteArray(SALT_LEN).also { readFully(buffered, it) }
                val iv = ByteArray(IV_LEN).also { readFully(buffered, it) }
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key(passphrase, salt), GCMParameterSpec(TAG_LEN_BITS, iv))
                CipherInputStream(buffered, cipher)
            }
            else -> throw IllegalArgumentException("Unsupported backup version")
        }
        val temp = File(targetFile.parentFile, targetFile.name + ".restore-tmp")
        try {
            temp.outputStream().buffered(256 * 1024).use { out -> plain.copyTo(out, 256 * 1024) }
            temp.inputStream().use { check ->
                val header = ByteArray(SQLITE_HEADER.length).also { readFully(check, it) }
                require(String(header, Charsets.US_ASCII) == SQLITE_HEADER) { "Backup did not contain a valid archive" }
            }
            File(targetFile.parentFile, targetFile.name + "-wal").delete()
            File(targetFile.parentFile, targetFile.name + "-shm").delete()
            File(targetFile.parentFile, targetFile.name + "-journal").delete()
            if (targetFile.exists() && !targetFile.delete()) error("Could not replace the current archive")
            check(temp.renameTo(targetFile)) { "Could not move restored archive into place" }
        } finally {
            temp.delete()
        }
    }

    private fun key(passphrase: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(passphrase.toCharArray(), salt, ITERATIONS, 256)
        return SecretKeySpec(factory.generateSecret(spec).encoded, "AES")
    }

    private fun readFully(stream: InputStream, buffer: ByteArray) {
        var offset = 0
        while (offset < buffer.size) {
            val read = stream.read(buffer, offset, buffer.size - offset)
            if (read < 0) throw EOFException()
            offset += read
        }
    }
}
