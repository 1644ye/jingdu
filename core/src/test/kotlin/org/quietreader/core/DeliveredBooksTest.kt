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
        var lineCount=0
        poems.forEach{element->
            val poem=element.asJsonObject
            assertFalse(poem.get("background").asString.isBlank())
            val lines=poem.getAsJsonArray("stanzas").flatMap{it.asJsonObject.getAsJsonArray("lines").map{line->line.asJsonObject}}
            assertTrue("${poem.get("title")} 应有实际词语注释",lines.any{it.get("note").asString.isNotBlank()})
            lines.forEach{line->
                val pinyin=line.get("pinyin").asString
                assertFalse(pinyin.isBlank())
                assertFalse("拼音不应残留未转换汉字：$pinyin",Regex("[\\u3400-\\u9fff□]").containsMatchIn(pinyin))
                assertFalse(line.get("original").asString.contains('□'));lineCount++
            }
        }
        assertEquals(1613,lineCount)
        fun pronunciation(fragment:String)=poems.flatMap{it.asJsonObject.getAsJsonArray("stanzas").flatMap{stanza->stanza.asJsonObject.getAsJsonArray("lines").map{it.asJsonObject}}}.first{it.get("original").asString.contains(fragment)}.get("pinyin").asString
        assertTrue(pronunciation("白日依山尽").startsWith("bái rì yī shān jìn"))
        assertTrue(pronunciation("十四为君妇").contains("wéi jūn fù"))
        assertTrue(pronunciation("高堂明镜悲白发").contains("bái fà"))
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
