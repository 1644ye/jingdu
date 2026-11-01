package org.quietreader.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class RemoteLibraryTest {
    @Test fun vectorDetectsOfflineConflictAndCausalContinuation(){
        val a=ProgressVersions.advance("a",emptyList())
        val b=ProgressVersions.advance("b",emptyList())
        assertEquals(ProgressVersions.Relation.CONCURRENT,ProgressVersions.compare(a,b))
        val continued=ProgressVersions.advance("b",listOf(a,b))
        assertEquals(ProgressVersions.Relation.AFTER,ProgressVersions.compare(continued,a))
        assertEquals(ProgressVersions.Relation.AFTER,ProgressVersions.compare(continued,b))
        assertEquals(ProgressVersions.Relation.SAME,ProgressVersions.compare(continued,continued.toMap()))
    }
    @Test fun conflictResolutionIncludesEveryCandidate(){
        val result=ProgressVersions.advance("c",listOf(mapOf("a" to 7L),mapOf("b" to 4L)))
        assertEquals(mapOf("a" to 7L,"b" to 4L,"c" to 1L),result)
    }
    @Test fun catalogKeepsChineseDirectoryAndStableIdentity(){
        val json=JsonParser.parseString("""{"schemaVersion":1,"libraryId":"mine","books":[{"id":"poem","path":"books/诗集/唐诗.json","title":"唐诗","author":"作者","type":"poetry","bytes":50,"tags":["精读"]}]}""").asJsonObject
        val catalog=RemoteLibrary.catalog(json)
        assertEquals("books/诗集/唐诗.json",catalog.books.single().path)
        assertEquals(listOf("精读"),catalog.books.single().tags)
        json.getAsJsonArray("books").add(json.getAsJsonArray("books")[0].deepCopy())
        try{RemoteLibrary.catalog(json);fail("Duplicate identity accepted")}catch(_:IllegalArgumentException){}
    }
    @Test fun catalogRejectsPathsOutsideRepository(){
        listOf("../book.json","/book.json","https://example.com/book.json","a/../book.json","a\\book.json").forEach{path->
            try{RemoteLibrary.path(path);fail("Invalid path accepted: $path")}catch(_:IllegalArgumentException){}
        }
    }
}
