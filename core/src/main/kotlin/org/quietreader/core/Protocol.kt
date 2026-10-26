package org.quietreader.core

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.StringReader
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CancellationException

data class Diagnostic(val fileName: String, val path: String, val message: String, val suggestion: String)
data class ValidationResult(val document: JsonObject?, val errors: List<Diagnostic>, val warnings: List<Diagnostic>) {
    val valid: Boolean get() = document != null && errors.isEmpty()
}

/** Protocol v1. Parsing and semantic validation never mutate the caller's JSON tree. */
object Protocol {
    const val MAX_FILE_BYTES = 32 * 1024 * 1024
    const val MAX_DEPTH = 32
    const val MAX_NODES = 1_000_000
    const val MAX_BOOKS = 1_000
    const val MAX_SECTIONS = 10_000
    const val MAX_CONTENT_ITEMS = 200_000
    const val MAX_TEXT_LENGTH = 4_000_000
    const val MAX_DIAGNOSTICS = 200

    fun parse(text: String, fileName: String): ValidationResult {
        val errors = mutableListOf<Diagnostic>()
        fun failed(path: String, message: String, suggestion: String = "请修复该字段后重新选择文件。") =
            ValidationResult(null, listOf(Diagnostic(fileName, path, message, suggestion)), emptyList())
        if (utf8Size(text) > MAX_FILE_BYTES) return failed("$", "文件超过 32 MiB 上限", "请拆分为多个 JSON 文件。")
        val source = text.removePrefix("\uFEFF")
        val reader = JsonReader(StringReader(source)).apply { strictness = Strictness.STRICT }
        var count = 0
        fun read(depth: Int): JsonElement {
            cancelled()
            if (depth > MAX_DEPTH) throw IllegalArgumentException("嵌套深度超过 $MAX_DEPTH 层（${reader.path}）")
            if (++count > MAX_NODES) throw IllegalArgumentException("内容节点数量超过 $MAX_NODES")
            return when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    JsonObject().also { obj ->
                        while (reader.hasNext()) {
                            val name = reader.nextName()
                            if (obj.has(name)) {
                                if (errors.size < MAX_DIAGNOSTICS) errors += Diagnostic(fileName, reader.path.removePrefix("$."), "字段重复：$name", "删除重复字段；同一对象的字段只能出现一次。")
                            }
                            obj.add(name, read(depth + 1))
                        }
                        reader.endObject()
                    }
                }
                JsonToken.BEGIN_ARRAY -> {
                    reader.beginArray()
                    JsonArray().also { array ->
                        while (reader.hasNext()) array.add(read(depth + 1))
                        reader.endArray()
                    }
                }
                JsonToken.STRING -> JsonPrimitive(reader.nextString().also {
                    if (it.length > MAX_TEXT_LENGTH) throw IllegalArgumentException("单个文本超过 $MAX_TEXT_LENGTH 个 UTF-16 单元（${reader.path}）")
                })
                JsonToken.NUMBER -> JsonPrimitive(BigDecimal(reader.nextString()))
                JsonToken.BOOLEAN -> JsonPrimitive(reader.nextBoolean())
                JsonToken.NULL -> { reader.nextNull(); JsonNull.INSTANCE }
                else -> throw IllegalArgumentException("此处需要 JSON 值（${reader.path}）")
            }
        }
        return try {
            reader.use {
                val root = read(0)
                if (reader.peek() != JsonToken.END_DOCUMENT) return failed("$", "JSON 结束后还有多余内容", "一个文件只能有一个根对象。")
                if (errors.isNotEmpty()) return ValidationResult(null, errors, emptyList())
                if (!root.isJsonObject) return failed("$", "根节点必须是对象", "使用提供的模板，根节点应由 { 和 } 包围。")
                validate(root.asJsonObject, fileName)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed(reader.path.removePrefix("$."), "JSON 解析失败：${e.message ?: "无效格式"}", "检查引号、逗号、括号；错误中的 line/column 为行号和列号。")
        }
    }

    fun validate(document: JsonObject, fileName: String): ValidationResult {
        val errors = mutableListOf<Diagnostic>()
        val warnings = mutableListOf<Diagnostic>()
        fun error(path: String, message: String, suggestion: String = "参照模板填写正确类型的字段。") {
            if (errors.size < MAX_DIAGNOSTICS) errors += Diagnostic(fileName, path, message, suggestion)
        }
        // Check untrusted trees passed directly to validate, including unknown fields.
        val pending = java.util.ArrayDeque<Pair<JsonElement, Int>>()
        pending.add(document to 0)
        var nodes = 0
        while (pending.isNotEmpty()) {
            cancelled()
            val (node, depth) = pending.removeLast()
            if (++nodes > MAX_NODES || depth > MAX_DEPTH) {
                error("$", "内容数量或嵌套深度超过上限", "拆分文件；嵌套不超过 $MAX_DEPTH 层，节点不超过 $MAX_NODES。")
                return ValidationResult(null, errors, warnings)
            }
            when {
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { pending.add(it.value to depth + 1) }
                node.isJsonArray -> node.asJsonArray.forEach { pending.add(it to depth + 1) }
                node.isJsonPrimitive && node.asJsonPrimitive.isString && node.asString.length > MAX_TEXT_LENGTH -> {
                    error("$", "单个文本超过 $MAX_TEXT_LENGTH 个 UTF-16 单元", "拆分过长段落。")
                    return ValidationResult(null, errors, warnings)
                }
            }
        }
        fun clean(obj: JsonObject, allowed: Set<String>, path: String): JsonObject {
            obj.keySet().filter { it !in allowed }.forEach { key ->
                if (warnings.size < MAX_DIAGNOSTICS) warnings += Diagnostic(fileName, if (path.isEmpty()) key else "$path.$key", "未知字段将被忽略，不会保存", "如需保留，请改用协议定义的字段。")
            }
            return JsonObject()
        }
        fun string(obj: JsonObject, out: JsonObject, key: String, path: String, required: Boolean = false, nonempty: Boolean = false): String? {
            val value = obj.get(key)
            val at = if (path.isEmpty()) key else "$path.$key"
            if (value == null) {
                if (required) error(at, "缺少必填字段 $key", "请填写 $key。")
                return null
            }
            if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) {
                error(at, "必须是字符串，不能使用 null 或其他类型", "使用双引号包围文本；可选文本也可以省略。")
                return null
            }
            val result = value.asString
            if (nonempty && result.isBlank()) error(at, "必须是非空字符串", "填写实际内容，不能只有空格。")
            out.addProperty(key, result)
            return result
        }
        fun array(obj: JsonObject, key: String, path: String, required: Boolean = true, nonempty: Boolean = true, limit: Int = MAX_CONTENT_ITEMS): JsonArray {
            val value = obj.get(key)
            val at = if (path.isEmpty()) key else "$path.$key"
            if (value == null && !required) return JsonArray()
            if (value == null || !value.isJsonArray) { error(at, "必须是数组"); return JsonArray() }
            val result = value.asJsonArray
            if (nonempty && result.isEmpty) error(at, "实际内容数组不能为空", "至少填写一项内容。")
            if (result.size() > limit) { error(at, "数量超过 $limit 上限", "请拆分内容文件。"); return JsonArray() }
            return result
        }
        fun objects(array: JsonArray, path: String, visit: (JsonObject, String) -> JsonObject): JsonArray = JsonArray().also { out ->
            array.forEachIndexed { i, value ->
                cancelled()
                if (!value.isJsonObject) error("$path[$i]", "必须是对象")
                else out.add(visit(value.asJsonObject, "$path[$i]"))
            }
        }
        fun tags(obj: JsonObject, out: JsonObject, path: String) {
            if (!obj.has("tags")) return
            val result = JsonArray()
            array(obj, "tags", path, nonempty = false, limit = 1000).forEachIndexed { i, value ->
                if (!value.isJsonPrimitive || !value.asJsonPrimitive.isString) error("$path.tags[$i]", "标签必须是字符串")
                else result.add(value.asString)
            }
            out.add("tags", result)
        }
        val root = clean(document, setOf("schemaVersion", "kind", "books"), "")
        val version = document.get("schemaVersion")
        if (version == null || !version.isJsonPrimitive || !version.asJsonPrimitive.isNumber || runCatching { version.asBigDecimal.compareTo(BigDecimal.ONE) != 0 }.getOrDefault(true)) {
            error("schemaVersion", "仅支持数字版本 1", "请使用版本 1 模板，不要将其他版本强行改名。")
        }
        root.addProperty("schemaVersion", 1)
        if (string(document, root, "kind", "", required = true) != "ebook-library") error("kind", "必须为 ebook-library", "完整备份请使用恢复备份入口。")
        val bookIds = hashSetOf<String>()
        val books = objects(array(document, "books", "", limit = MAX_BOOKS), "books") { book, path ->
            val type = book.get("type")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            val common = setOf("id", "type", "title", "author", "language", "description", "tags")
            val out = clean(book, common + if (type == "poetry") setOf("poems") else setOf("volumes", "chapters"), path)
            val id = string(book, out, "id", path, required = true, nonempty = true)
            if (id != null && !bookIds.add(id)) error("$path.id", "文件中出现重复书籍 ID：$id", "每本书使用不同 ID；更新已有书籍时沿用原 ID。")
            string(book, out, "type", path, required = true)
            if (type != "poetry" && type != "novel") error("$path.type", "仅支持 poetry 或 novel")
            string(book, out, "title", path, required = true, nonempty = true)
            listOf("author", "language", "description").forEach { string(book, out, it, path) }
            if (!book.has("language")) out.addProperty("language", "zh-CN")
            tags(book, out, path)
            val innerIds = hashSetOf<String>()
            var contentCount = 0
            fun internalId(obj: JsonObject, target: JsonObject, at: String): String? {
                val value = string(obj, target, "id", at, required = true, nonempty = true)
                if (++contentCount > MAX_CONTENT_ITEMS) error(at, "单本书内容节点超过 $MAX_CONTENT_ITEMS", "请拆分成多本书。")
                if (value != null && !innerIds.add(value)) error("$at.id", "书内出现重复 ID：$value", "诗作、诗节、诗行、卷、章节和内容块的 ID 必须在所属书籍内唯一。")
                return value
            }
            if (type == "poetry") {
                listOf("volumes", "chapters").filter { book.has(it) }.forEach { error("$path.$it", "诗集不能包含小说结构 $it", "删除该字段，诗集内容放入 poems。") }
                out.add("poems", objects(array(book, "poems", path, limit = MAX_SECTIONS), "$path.poems") { poem, pp ->
                    val po = clean(poem, setOf("id", "title", "author", "dynasty", "genre", "tags", "background", "translation", "appreciation", "stanzas"), pp)
                    internalId(poem, po, pp)
                    string(poem, po, "title", pp, required = true, nonempty = true)
                    listOf("author", "dynasty", "genre", "background", "translation", "appreciation").forEach { string(poem, po, it, pp) }
                    tags(poem, po, pp)
                    po.add("stanzas", objects(array(poem, "stanzas", pp), "$pp.stanzas") { stanza, sp ->
                        val so = clean(stanza, setOf("id", "lines"), sp)
                        internalId(stanza, so, sp)
                        so.add("lines", objects(array(stanza, "lines", sp), "$sp.lines") { line, lp ->
                            val lo = clean(line, setOf("id", "original", "pinyin", "translation", "note"), lp)
                            internalId(line, lo, lp)
                            string(line, lo, "original", lp, required = true, nonempty = true)
                            listOf("pinyin", "translation", "note").forEach { string(line, lo, it, lp) }
                            lo
                        })
                        so
                    })
                    po
                })
            } else if (type == "novel") {
                if (book.has("poems")) error("$path.poems", "小说不能包含 poems", "删除该字段，小说内容放入 chapters。")
                val volumes = hashSetOf<String>()
                if (book.has("volumes")) out.add("volumes", objects(array(book, "volumes", path, nonempty = false, limit = MAX_SECTIONS), "$path.volumes") { volume, vp ->
                    val vo = clean(volume, setOf("id", "title"), vp)
                    internalId(volume, vo, vp)?.let { volumes.add(it) }
                    string(volume, vo, "title", vp, required = true, nonempty = true)
                    vo
                })
                out.add("chapters", objects(array(book, "chapters", path, limit = MAX_SECTIONS), "$path.chapters") { chapter, cp ->
                    val co = clean(chapter, setOf("id", "title", "volumeId", "blocks"), cp)
                    internalId(chapter, co, cp)
                    string(chapter, co, "title", cp, required = true, nonempty = true)
                    string(chapter, co, "volumeId", cp, nonempty = true)?.let { if (it !in volumes) error("$cp.volumeId", "引用的卷不存在：$it", "添加对应卷，或删除 volumeId 表示不分卷。") }
                    co.add("blocks", objects(array(chapter, "blocks", cp), "$cp.blocks") { block, bp ->
                        val bo = clean(block, setOf("id", "type", "text"), bp)
                        internalId(block, bo, bp)
                        val blockType = string(block, bo, "type", bp, required = true)
                        if (blockType !in setOf("paragraph", "heading", "separator")) error("$bp.type", "仅支持 paragraph、heading、separator")
                        string(block, bo, "text", bp, required = blockType != "separator", nonempty = blockType != "separator")
                        bo
                    })
                    co
                })
            }
            out
        }
        root.add("books", books)
        return ValidationResult(if (errors.isEmpty()) root else null, errors, warnings)
    }

    fun copyBook(book: JsonObject): JsonObject {
        val copy = book.deepCopy()
        val ids = hashMapOf<String, String>()
        fun remap(node: JsonElement) {
            if (node.isJsonObject) {
                val obj = node.asJsonObject
                obj.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.let {
                    val old = it.asString
                    val replacement = UUID.randomUUID().toString()
                    ids[old] = replacement
                    obj.addProperty("id", replacement)
                }
                obj.entrySet().forEach { remap(it.value) }
            } else if (node.isJsonArray) node.asJsonArray.forEach { remap(it) }
        }
        // A book ID is a separate namespace from its internal IDs.
        copy.addProperty("id", UUID.randomUUID().toString())
        copy.entrySet().filter { it.key != "id" }.forEach { remap(it.value) }
        copy.getAsJsonArray("chapters")?.forEach { chapter ->
            val obj = chapter.asJsonObject
            obj.get("volumeId")?.let { ref -> ids[ref.asString]?.let { obj.addProperty("volumeId", it) } }
        }
        return copy
    }

    fun wrap(books: List<JsonObject>): JsonObject = JsonObject().apply {
        addProperty("schemaVersion", 1)
        addProperty("kind", "ebook-library")
        add("books", JsonArray().apply { books.forEach { add(it.deepCopy()) } })
    }

    private fun cancelled() { if (Thread.currentThread().isInterrupted) throw CancellationException("操作已取消") }

    private fun utf8Size(text: String): Long {
        var bytes = 0L
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            bytes += when {
                ch.code < 0x80 -> 1
                ch.code < 0x800 -> 2
                ch.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate() -> { i++; 4 }
                else -> 3
            }
            if (bytes > MAX_FILE_BYTES) return bytes
            i++
        }
        return bytes
    }
}
