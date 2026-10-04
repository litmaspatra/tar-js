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
 @Test fun rcloneParserHandlesBomCommentsAndCryptRemote(){
  val config="\uFEFF# generated\n; comment\n[backblaze]\ntype = b2\naccount = abc#fragment\n\n[b2crypt]\ntype=crypt\nremote = backblaze:telegram\nfilename_encryption = standard\n"
  val remotes=RcloneConfigParser.parse(config)
  assertEquals(listOf("backblaze","b2crypt"), remotes.map{it.name}); assertEquals("b2",remotes[0].type); assertTrue(remotes[1].isEncrypted); assertEquals("backblaze",remotes[1].backend)
 }
 @Test fun remoteNavigationKeepsNestedPathAndFindsResultJson(){
  val remote=RcloneRemote("b2crypt","crypt",mapOf("remote" to "backblaze:telegram"))
  val path=RcloneRemoteNavigator.resolve(remote,"exports/2026")
  assertEquals("b2crypt:exports/2026",path.toString()); assertEquals("b2crypt:exports/2026/result.json",RcloneRemoteNavigator.resultJsonCandidates(path).single().toString())
 }
}
