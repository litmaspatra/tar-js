package com.tarjs.app.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream

class TarJsCoreTest {
 @Test fun ownerIsRightAndOtherIsLeft(){ assertTrue(MessageDirection.isMine("42","42","Kartik","Kartik")); assertFalse(MessageDirection.isMine("42","7","Alice","Kartik")) }
 @Test fun ambiguousExportUsesNameFallback(){ assertTrue(MessageDirection.isMine(null,null,"Kartik","Kartik")); assertFalse(MessageDirection.isMine(null,null,"Alice","Kartik")) }
 @Test fun tgsIsDecompressed(){ val f=File.createTempFile("sticker",".tgs"); GZIPOutputStream(FileOutputStream(f)).use{it.write("{\"v\":\"5\"}".toByteArray())}; assertEquals("{\"v\":\"5\"}",MediaResolver(f.parentFile).decompressTgs(f));f.delete() }
 @Test fun cacheKeySeparatesArchives(){ val r=MediaResolver(File(System.getProperty("java.io.tmpdir"))); assertNotEquals(r.cacheFile("a","photos/x.jpg"),r.cacheFile("b","photos/x.jpg")) }
}
