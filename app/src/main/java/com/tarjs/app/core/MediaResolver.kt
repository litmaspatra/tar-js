package com.tarjs.app.core

import java.io.File
import java.util.zip.GZIPInputStream

object MessageDirection {
    fun isMine(ownerId:String?, senderId:String?, senderName:String?, ownerName:String?):Boolean = ownerId != null && senderId == ownerId || ownerId == null && ownerName != null && senderName == ownerName
}

data class MediaItem(val relativePath:String,val kind:String)
class MediaResolver(private val privateCache:File) {
    fun cacheFile(sourceKey:String, relativePath:String):File = File(privateCache, "${sourceKey.hashCode()}_${relativePath.hashCode()}_${File(relativePath).name}")
    fun materialize(source:File, sourceKey:String, relativePath:String):File? { if(!source.isFile)return null; val target=cacheFile(sourceKey,relativePath); return try { if(!target.exists()){target.parentFile?.mkdirs();source.copyTo(target)};target } catch(_:Exception){null} }
    fun decompressTgs(source:File):String? = try { GZIPInputStream(source.inputStream()).bufferedReader().use{it.readText()} } catch(_:Exception){null}
}
