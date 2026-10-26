package org.quietreader.core

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test

class ProtocolTest {
    private fun sample(name: String = "novel-example") = checkNotNull(javaClass.getResourceAsStream("/protocol/$name.json")).bufferedReader().use { it.readText() }
    private fun book() = Protocol.parse(sample(), "novel.json").document!!.getAsJsonArray("books")[0].asJsonObject
    private fun parse(text: String) = Protocol.parse(text, "输入.json")

    @Test fun userExamplesAndMinimalTemplatesAreAcceptedUnmodified() {
        for (name in listOf("novel-example", "poetry-example", "novel-template", "poetry-template")) {
            val result = parse(sample(name))
            assertEquals(name, emptyList<Diagnostic>(), result.errors)
            assertTrue(name, result.valid)
        }
    }

    @Test fun utf8BomIsAccepted() = assertTrue(parse("\uFEFF" + sample()).valid)

    @Test fun originalWhitespaceAndScriptLikeTextSurviveRoundTrip() {
        val b = book()
        val text = "  原文 空格\n\n<script>alert('纯文本')</script> \n"
        b.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text", text)
        val result = parse(Protocol.wrap(listOf(b)).toString())
        assertTrue(result.valid)
        assertEquals(text, Content.texts(Content.sections(result.document!!.getAsJsonArray("books")[0].asJsonObject)[0], "novel")["block-1"])
        assertEquals(result.document, parse(result.document.toString()).document)
    }

    @Test fun unknownFieldsWarnAndAreRemovedWithoutMutatingInput() {
        val original = JsonParser.parseString(sample()).asJsonObject
        val b = original.getAsJsonArray("books")[0].asJsonObject
        b.addProperty("futureMetadata", "discard")
        val result = Protocol.validate(original, "example.json")
        assertTrue(result.valid)
        assertEquals("books[0].futureMetadata", result.warnings.single().path)
        assertTrue(b.has("futureMetadata"))
        assertFalse(result.document!!.getAsJsonArray("books")[0].asJsonObject.has("futureMetadata"))
    }

    @Test fun invalidValuesHaveSpecificPathsAndRepairAdvice() {
        val b = book()
        b.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text", " ")
        val result = parse(Protocol.wrap(listOf(b)).toString())
        assertFalse(result.valid)
        val problem = result.errors.first { it.path == "books[0].chapters[0].blocks[0].text" }
        assertEquals("输入.json", problem.fileName)
        assertTrue(problem.suggestion.isNotBlank())
        assertNull(result.document)
    }

    @Test fun optionalStringsMayBeAbsentOrEmptyButNotNull() {
        val b = book()
        b.remove("author")
        b.addProperty("description", "")
        assertTrue(Protocol.validate(Protocol.wrap(listOf(b)), "x").valid)
        b.add("author", com.google.gson.JsonNull.INSTANCE)
        assertFalse(Protocol.validate(Protocol.wrap(listOf(b)), "x").valid)
    }

    @Test fun absentLanguageDefaultsToChinese() {
        val b = book().apply { remove("language") }
        val result = Protocol.validate(Protocol.wrap(listOf(b)), "x")
        assertEquals("zh-CN", result.document!!.getAsJsonArray("books")[0].asJsonObject.get("language").asString)
    }

    @Test fun allInternalIdsShareOneNamespace() {
        val b = book()
        b.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("id", "volume-1")
        val result = Protocol.validate(Protocol.wrap(listOf(b)), "x")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.message.contains("重复 ID") })
    }

    @Test fun bookAndInternalIdsHaveSeparateNamespaces() {
        val b = book().apply { addProperty("id", "volume-1") }
        assertTrue(Protocol.validate(Protocol.wrap(listOf(b)), "x").valid)
        val copy = Protocol.copyBook(b)
        val volume = copy.getAsJsonArray("volumes")[0].asJsonObject.get("id").asString
        assertNotEquals(copy.get("id").asString, volume)
        assertEquals(volume, copy.getAsJsonArray("chapters")[0].asJsonObject.get("volumeId").asString)
    }

    @Test fun duplicateBookIdsAreRejectedEvenWithDifferentTitles() {
        val a = book()
        val b = book().apply { addProperty("title", "不同标题") }
        assertFalse(Protocol.validate(Protocol.wrap(listOf(a, b)), "x").valid)
        b.addProperty("id", "different")
        b.addProperty("title", a.get("title").asString)
        assertTrue(Protocol.validate(Protocol.wrap(listOf(a, b)), "x").valid)
    }

    @Test fun missingVolumeReferenceIsRejected() {
        val b = book().apply { remove("volumes") }
        val result = Protocol.validate(Protocol.wrap(listOf(b)), "x")
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.path.endsWith("volumeId") })
        b.getAsJsonArray("chapters")[0].asJsonObject.remove("volumeId")
        assertTrue(Protocol.validate(Protocol.wrap(listOf(b)), "x").valid)
    }

    @Test fun typeCannotSilentlyDiscardWrongKnownStructure() {
        val b = book().apply { add("poems", com.google.gson.JsonArray()) }
        assertFalse(Protocol.validate(Protocol.wrap(listOf(b)), "x").valid)
        val poem = parse(sample("poetry-example")).document!!.getAsJsonArray("books")[0].asJsonObject
        poem.add("chapters", com.google.gson.JsonArray())
        assertFalse(Protocol.validate(Protocol.wrap(listOf(poem)), "x").valid)
    }

    @Test fun versionAndKindAreStrict() {
        for (value in listOf("2", "\"1\"", "null", "true")) {
            assertFalse(parse(sample().replace("\"schemaVersion\": 1", "\"schemaVersion\": $value")).valid)
        }
        assertFalse(parse(sample().replace("ebook-library", "ebook-backup")).valid)
    }

    @Test fun actualContentArraysCannotBeEmpty() {
        val b = book()
        b.getAsJsonArray("chapters")[0].asJsonObject.add("blocks", com.google.gson.JsonArray())
        assertFalse(Protocol.validate(Protocol.wrap(listOf(b)), "x").valid)
    }

    @Test fun separatorDoesNotRequireText() {
        val result = parse(sample())
        assertTrue(result.valid)
        assertFalse(Content.texts(Content.sections(book())[0], "novel").containsKey("block-3"))
    }

    @Test fun duplicateJsonKeysAreRejectedRatherThanLastValueWinning() {
        val result = parse(sample().replace("\"schemaVersion\": 1", "\"schemaVersion\": 1, \"schemaVersion\": 1"))
        assertFalse(result.valid)
        assertTrue(result.errors.any { it.message.contains("重复") })
    }

    @Test fun malformedAndLenientJsonAreRejected() {
        for (text in listOf("{}{}", "{unquoted: 1}", "{'single': 1}", "{\"a\":1,}", "/*注释*/{}", "{\"a\": NaN}", "[1]", "")) {
            assertFalse(text, parse(text).valid)
        }
        assertTrue(parse("{\n\"a\": } ").errors.first().message.contains("line"))
    }

    @Test fun nestingLimitIsEnforcedBeforeStackOverflow() {
        val text = "[".repeat(40) + "0" + "]".repeat(40)
        assertTrue(parse(text).errors.first().message.contains("深度"))
    }

    @Test fun utf8ByteLimitCountsChineseBytesNotOnlyCharacters() {
        val text = "中".repeat(Protocol.MAX_FILE_BYTES / 3 + 1)
        assertTrue(text.length < Protocol.MAX_FILE_BYTES)
        val result = parse(text)
        assertFalse(result.valid)
        assertTrue(result.errors.first().message.contains("32 MiB"))
    }

    @Test fun directValidationAlsoEnforcesDepthLimit() {
        val root = com.google.gson.JsonObject()
        var current = root
        repeat(Protocol.MAX_DEPTH + 1) {
            val child = com.google.gson.JsonObject()
            current.add("nested", child)
            current = child
        }
        val result = Protocol.validate(root, "直接调用.json")
        assertFalse(result.valid)
        assertTrue(result.errors.first().message.contains("深度"))
    }

    @Test fun copyGeneratesAllNewIdsAndRebuildsReferences() {
        val old = book()
        val copy = Protocol.copyBook(old)
        assertNotEquals(old.get("id"), copy.get("id"))
        assertNotEquals(old.getAsJsonArray("chapters")[0].asJsonObject.get("id"), copy.getAsJsonArray("chapters")[0].asJsonObject.get("id"))
        assertEquals(copy.getAsJsonArray("volumes")[0].asJsonObject.get("id"), copy.getAsJsonArray("chapters")[0].asJsonObject.get("volumeId"))
        assertTrue(Protocol.validate(Protocol.wrap(listOf(old, copy)), "x").valid)
        assertEquals(Content.texts(Content.sections(old)[0], "novel").values.toList(), Content.texts(Content.sections(copy)[0], "novel").values.toList())
    }
}
