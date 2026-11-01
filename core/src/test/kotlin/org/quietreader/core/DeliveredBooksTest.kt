package org.quietreader.core

import org.junit.Assert.*
import org.junit.Test
import java.io.File

class DeliveredBooksTest {
    private val root=File("../artifacts/reading-library").takeIf{it.isDirectory}?:File("artifacts/reading-library")
    private fun book(path:String)=Protocol.parse(File(root,path).readText(Charsets.UTF_8),path).let{result->
        assertTrue(result.errors.joinToString("\n"){it.message},result.valid)
        assertTrue("交付文件不应含未知字段",result.warnings.isEmpty())
        result.document!!.getAsJsonArray("books").single().asJsonObject
    }
    @Test fun deliveredTangPoemsAreAcceptedByActualParser(){
        val poems=book("books/诗集/唐诗三百首.json").getAsJsonArray("poems")
        assertEquals(319,poems.size())
        assertEquals(319,poems.map{it.asJsonObject.get("id").asString}.distinct().size)
    }
    @Test fun deliveredEnglishNovelHasAll117Chapters(){
        val novel=book("books/小说/基督山伯爵-英文.json")
        assertEquals("en",novel.get("language").asString)
        val chapters=novel.getAsJsonArray("chapters")
        assertEquals(117,chapters.size())
        assertTrue(chapters.first().asJsonObject.get("title").asString.startsWith("Chapter 1."))
        assertTrue(chapters.last().asJsonObject.get("title").asString.startsWith("Chapter 117."))
        assertTrue(chapters.all{it.asJsonObject.getAsJsonArray("blocks").size()>0})
    }
}
