package org.quietreader.core

import com.google.gson.JsonObject

/** Repository paths are always relative; credentials never come from a catalog. */
object RemoteLibrary {
    data class Entry(val id:String,val path:String,val title:String,val author:String,val type:String,val tags:List<String>,val bytes:Long)
    data class Catalog(val libraryId:String,val books:List<Entry>)
    fun path(value:String):String {
        require(value.isNotBlank()&&!value.startsWith('/')&&!value.contains('\\')&&!value.contains(':')){"必须使用仓库内相对路径"}
        require(value.split('/').none{it.isEmpty()||it=="."||it==".."}){"路径包含无效目录"}
        return value
    }
    fun catalog(document:JsonObject):Catalog {
        require(document.get("schemaVersion")?.asInt==1){"不支持的书库索引版本"}
        val libraryId=document.get("libraryId")?.asString.orEmpty()
        require(libraryId.isNotBlank()){"缺少 libraryId"}
        val ids=mutableSetOf<String>();val paths=mutableSetOf<String>()
        val books=document.getAsJsonArray("books")?:error("缺少 books")
        return Catalog(libraryId,books.map{element->
            val book=element.asJsonObject
            fun text(key:String)=book.get(key)?.asString.orEmpty()
            val id=text("id");val location=path(text("path"));val type=text("type")
            require(id.isNotBlank()&&ids.add(id)){"书籍 ID 为空或重复"}
            require(paths.add(location)&&location.endsWith(".json",true)){"书籍路径重复或不是 JSON"}
            require(text("title").isNotBlank()&&type in listOf("poetry","novel")){"书籍元数据无效"}
            val bytes=book.get("bytes")?.asLong?:0L
            require(bytes in 0..Protocol.MAX_FILE_BYTES.toLong()){"书籍大小超过导入上限"}
            Entry(id,location,text("title"),text("author"),type,book.getAsJsonArray("tags")?.map{it.asString}.orEmpty(),bytes)
        })
    }
}
