package org.quietreader.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.core.Anchor
import org.quietreader.core.Anchors
import org.quietreader.core.Content
import org.quietreader.core.Protocol
import org.quietreader.core.ReadingSettings
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** One application-scoped instance. All methods are synchronous and must run off the UI thread. */
class LibraryStore(context: Context, databaseName: String = "quiet-reader.db") :
    SQLiteOpenHelper(context.applicationContext, databaseName, null, DATABASE_VERSION) {
    private val appContext = context.applicationContext
    private val gson = Gson()

    init { setWriteAheadLoggingEnabled(true) }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
        db.rawQuery("PRAGMA busy_timeout=5000",null).use { it.moveToFirst() }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE books(id TEXT PRIMARY KEY NOT NULL,type TEXT NOT NULL,title TEXT NOT NULL,
            author TEXT NOT NULL,description TEXT NOT NULL,tags TEXT NOT NULL,metadata TEXT NOT NULL,
            favorite INTEGER NOT NULL DEFAULT 0,status TEXT NOT NULL DEFAULT 'unread',progress REAL NOT NULL DEFAULT 0,
            last_read INTEGER NOT NULL DEFAULT 0,imported_at INTEGER NOT NULL,content_revision INTEGER NOT NULL DEFAULT 1)""")
        db.execSQL("""CREATE TABLE sections(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            id TEXT NOT NULL,ord INTEGER NOT NULL,title TEXT NOT NULL,volume_title TEXT NOT NULL,volume_id TEXT NOT NULL DEFAULT '',json TEXT NOT NULL,
            PRIMARY KEY(book_id,id))""")
        db.execSQL("""CREATE TABLE texts(book_id TEXT NOT NULL,section_id TEXT NOT NULL,id TEXT NOT NULL,
            text TEXT NOT NULL,ord INTEGER NOT NULL,PRIMARY KEY(book_id,id),
            FOREIGN KEY(book_id,section_id) REFERENCES sections(book_id,id) ON DELETE CASCADE)""")
        db.execSQL("CREATE TABLE positions(book_id TEXT PRIMARY KEY REFERENCES books(id) ON DELETE CASCADE,anchor TEXT NOT NULL,progress REAL NOT NULL)")
        db.execSQL("CREATE TABLE annotations(id INTEGER PRIMARY KEY AUTOINCREMENT,book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,anchor TEXT NOT NULL,kind TEXT NOT NULL,note TEXT NOT NULL,created_at INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE shelf_groups(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE memberships(book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,group_id INTEGER NOT NULL REFERENCES shelf_groups(id) ON DELETE CASCADE,PRIMARY KEY(book_id,group_id))")
        db.execSQL("CREATE TABLE poem_favorites(book_id TEXT NOT NULL,poem_id TEXT NOT NULL,PRIMARY KEY(book_id,poem_id),FOREIGN KEY(book_id,poem_id) REFERENCES sections(book_id,id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE settings(scope TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL)")
        db.execSQL("CREATE TABLE metadata(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)")
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        require(oldVersion in 1 until newVersion && newVersion == DATABASE_VERSION) { "无法迁移数据库版本 $oldVersion → $newVersion；已保留原数据。" }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE books ADD COLUMN content_revision INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE sections ADD COLUMN volume_id TEXT NOT NULL DEFAULT ''")
            db.rawQuery("SELECT book_id,id,json FROM sections",null).use { c ->
                while(c.moveToNext()) {
                    val volumeId=json(c.getString(2)).s("volumeId")
                    if(volumeId.isNotEmpty()) db.update("sections",ContentValues().apply { put("volume_id",volumeId) },"book_id=? AND id=?",arrayOf(c.getString(0),c.getString(1)))
                }
            }
        }
        createIndexes(db)
        // SQLiteOpenHelper wraps this in a transaction; any failure preserves the original database.
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        throw IllegalStateException("数据库版本 $oldVersion 高于当前应用支持的 $newVersion，不能降级；数据未删除。")
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS section_order ON sections(book_id,ord)")
        db.execSQL("CREATE INDEX IF NOT EXISTS text_section_order ON texts(book_id,section_id,ord)")
        db.execSQL("CREATE INDEX IF NOT EXISTS annotation_book ON annotations(book_id,created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS membership_group ON memberships(group_id,book_id)")
    }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = writableDatabase
        db.beginTransaction()
        try { return block(db).also { db.setTransactionSuccessful() } }
        finally { db.endTransaction() }
    }

    @Synchronized fun listBooks(): List<BookSummary> = readableDatabase.rawQuery("SELECT * FROM books ORDER BY last_read DESC,imported_at DESC", null).use { c ->
        buildList { while (c.moveToNext()) add(BookSummary(c.s("id"),c.s("type"),c.s("title"),c.s("author"),c.s("description"),
            JsonParser.parseString(c.s("tags")).asJsonArray.map { it.asString },c.i("favorite") != 0,c.s("status"),
            c.f("progress"),c.l("last_read"),c.l("imported_at"),c.i("content_revision"))) }
    }

    @Synchronized fun book(id: String): JsonObject? {
        val metadata = readableDatabase.rawQuery("SELECT metadata,type FROM books WHERE id=?", arrayOf(id)).use { c ->
            if (!c.moveToFirst()) return null
            json(c.getString(0)) to c.getString(1)
        }
        val array = JsonArray()
        readableDatabase.rawQuery("SELECT json FROM sections WHERE book_id=? ORDER BY ord", arrayOf(id)).use { c ->
            while (c.moveToNext()) array.add(json(c.getString(0)))
        }
        return metadata.first.apply { add(if (metadata.second == "poetry") "poems" else "chapters", array) }
    }

    @Synchronized fun sections(bookId: String): List<SectionSummary> = readableDatabase.rawQuery(
        "SELECT id,title,ord,volume_title,volume_id FROM sections WHERE book_id=? ORDER BY ord", arrayOf(bookId)).use { c ->
        buildList { while(c.moveToNext()) add(SectionSummary(c.getString(0),c.getString(1),c.getInt(2),c.getString(3),c.getString(4))) }
    }

    @Synchronized fun section(bookId: String, sectionId: String): JsonObject? = readableDatabase.rawQuery(
        "SELECT json FROM sections WHERE book_id=? AND id=?", arrayOf(bookId,sectionId)).use { c -> if(c.moveToFirst()) json(c.getString(0)) else null }

    @Synchronized fun importFile(document: JsonObject, choices: Map<String,String> = emptyMap(),
        cancelled: () -> Boolean = { false }, progress: (Int,Int) -> Unit = { _,_ -> }): ImportResult {
        checkCancellation(cancelled)
        val validation = Protocol.validate(document,"导入文件")
        require(validation.valid) { validation.errors.joinToString("\n") { "${it.path}：${it.message}" } }
        val books = validation.document!!.getAsJsonArray("books").map { it.asJsonObject }
        return transaction { db ->
            var imported = 0; var updated = 0; var skipped = 0; var copied = 0
            books.forEachIndexed { index, input ->
                checkCancellation(cancelled)
                val id = input.s("id")
                val exists = existsBook(id)
                val choice = choices[id] ?: "skip"
                require(choice in setOf("skip","update","copy")) { "未知冲突处理方式：$choice" }
                when {
                    exists && choice == "skip" -> skipped++
                    exists && choice == "copy" -> { writeNewBook(db,Protocol.copyBook(input),cancelled); copied++ }
                    exists -> { updateBook(db,input,cancelled); updated++ }
                    else -> { writeNewBook(db,input,cancelled); imported++ }
                }
                progress(index + 1,books.size)
                checkCancellation(cancelled)
            }
            ImportResult(imported,updated,skipped,copied)
        }
    }

    private fun writeNewBook(db: SQLiteDatabase, book: JsonObject, cancelled: () -> Boolean) {
        val values = metadataValues(book).apply { put("id",book.s("id")); put("imported_at",System.currentTimeMillis()) }
        db.insertOrThrow("books",null,values)
        writeSections(db,book,cancelled)
    }

    private fun metadataValues(book: JsonObject): ContentValues {
        val metadata = book.deepCopy().apply { remove("poems"); remove("chapters") }
        require(metadata.toString().toByteArray(Charsets.UTF_8).size <= MAX_METADATA_BYTES) { "${book.s("id")}：书籍元数据超过 256 KiB；请缩短简介、标签或卷目录。当前操作未保存。" }
        return ContentValues().apply {
            put("type",book.s("type")); put("title",book.s("title")); put("author",book.s("author"))
            put("description",book.s("description")); put("tags",(book.getAsJsonArray("tags") ?: JsonArray()).toString())
            put("metadata",metadata.toString())
        }
    }

    private fun writeSections(db: SQLiteDatabase, book: JsonObject, cancelled: () -> Boolean) {
        val volumes = book.getAsJsonArray("volumes")?.associate { it.asJsonObject.s("id") to it.asJsonObject.s("title") } ?: emptyMap()
        val bookId = book.s("id"); val type = book.s("type")
        Content.sections(book).forEachIndexed { index, section ->
            checkCancellation(cancelled)
            val sectionId = section.s("id")
            val sectionJson = section.toString()
            require(sectionJson.toByteArray(Charsets.UTF_8).size <= MAX_SECTION_BYTES) {
                "$bookId/$sectionId：单章或单首诗超过 1 MiB 上限；请按章节或诗作拆分后重试。当前文件未写入。"
            }
            db.insertOrThrow("sections",null,ContentValues().apply {
                put("book_id",bookId); put("id",sectionId); put("ord",index); put("title",section.s("title"))
                put("volume_title",volumes[section.s("volumeId")] ?: ""); put("volume_id",section.s("volumeId")); put("json",sectionJson)
            })
            Content.texts(section,type).entries.forEachIndexed { textIndex, (id,text) ->
                if (textIndex % 128 == 0) checkCancellation(cancelled)
                db.insertOrThrow("texts",null,ContentValues().apply {
                    put("book_id",bookId); put("section_id",sectionId); put("id",id); put("text",text); put("ord",textIndex)
                })
            }
        }
    }

    private fun updateBook(db: SQLiteDatabase, book: JsonObject, cancelled: () -> Boolean) {
        val id = book.s("id")
        // Read only texts actually referenced by records; do not assemble the old full book.
        val notes = annotations(id)
        val oldPosition = position(id)
        val anchors = notes.map { it.anchor } + listOfNotNull(oldPosition)
        val oldTexts = anchors.associate { it.contentId to text(it.bookId,it.sectionId,it.contentId) }
        val favoriteIds = db.rawQuery("SELECT poem_id FROM poem_favorites WHERE book_id=?",arrayOf(id)).use { c -> buildList { while(c.moveToNext()) add(c.getString(0)) } }
        db.update("books",metadataValues(book),"id=?",arrayOf(id))
        db.execSQL("UPDATE books SET content_revision=content_revision+1 WHERE id=?",arrayOf(id))
        db.delete("sections","book_id=?",arrayOf(id))
        writeSections(db,book,cancelled)
        fun relocated(anchor: Anchor): Anchor = Anchors.relocate(anchor,oldTexts[anchor.contentId],text(id,anchor.sectionId,anchor.contentId))
        notes.forEach { note ->
            db.update("annotations",ContentValues().apply { put("anchor",gson.toJson(relocated(note.anchor))) },"id=?",arrayOf(note.id.toString()))
        }
        oldPosition?.let { anchor -> db.update("positions",ContentValues().apply { put("anchor",gson.toJson(relocated(anchor))) },"book_id=?",arrayOf(id)) }
        if (book.s("type") == "poetry") {
            val ids = Content.sections(book).map { it.s("id") }.toSet()
            favoriteIds.filter { it in ids }.forEach { poemId -> db.insertOrThrow("poem_favorites",null,ContentValues().apply { put("book_id",id); put("poem_id",poemId) }) }
        }
    }

    @Synchronized fun exportContent(ids: List<String>): JsonObject = Protocol.wrap(ids.distinct().map { book(it) ?: throw IllegalArgumentException("书籍不存在：$it") })

    @Synchronized fun deleteBooks(ids: List<String>) = transaction { db ->
        ids.distinct().forEach { id -> db.delete("settings","scope=?",arrayOf(id)); db.delete("books","id=?",arrayOf(id)) }
        Unit
    }

    @Synchronized fun editBook(id: String,title: String,author: String,description: String,tags: List<String>) {
        require(title.isNotBlank()) { "书名不能为空。" }
        val metadata = readableDatabase.rawQuery("SELECT metadata FROM books WHERE id=?",arrayOf(id)).use { c -> require(c.moveToFirst()) { "书籍不存在。" }; json(c.getString(0)) }
        metadata.addProperty("title",title); metadata.addProperty("author",author); metadata.addProperty("description",description)
        metadata.add("tags",JsonArray().apply { tags.forEach { add(it) } })
        writableDatabase.update("books",metadataValues(metadata),"id=?",arrayOf(id))
    }

    @Synchronized fun toggleFavorite(id: String) { writableDatabase.execSQL("UPDATE books SET favorite=1-favorite WHERE id=?",arrayOf(id)) }
    @Synchronized fun setStatus(id: String,status: String) {
        require(status in STATUSES) { "未知阅读状态。" }
        writableDatabase.update("books",ContentValues().apply { put("status",status) },"id=?",arrayOf(id))
    }

    @Synchronized fun groups(): List<ShelfGroup> = readableDatabase.rawQuery("SELECT id,name FROM shelf_groups ORDER BY id",null).use { c -> buildList { while(c.moveToNext()) add(ShelfGroup(c.getLong(0),c.getString(1))) } }
    @Synchronized fun createGroup(name: String): Long { require(name.isNotBlank()) { "分组名称不能为空。" }; return writableDatabase.insertOrThrow("shelf_groups",null,ContentValues().apply { put("name",name.trim()) }) }
    @Synchronized fun renameGroup(id: Long,name: String) { require(name.isNotBlank()) { "分组名称不能为空。" }; writableDatabase.update("shelf_groups",ContentValues().apply { put("name",name.trim()) },"id=?",arrayOf(id.toString())) }
    @Synchronized fun deleteGroup(id: Long) { writableDatabase.delete("shelf_groups","id=?",arrayOf(id.toString())) }
    @Synchronized fun groupIds(bookId: String): Set<Long> = readableDatabase.rawQuery("SELECT group_id FROM memberships WHERE book_id=?",arrayOf(bookId)).use { c -> buildSet { while(c.moveToNext()) add(c.getLong(0)) } }
    @Synchronized fun setMembership(bookIds: List<String>,groupId: Long,add: Boolean) = transaction { db ->
        bookIds.distinct().forEach { id ->
            if (add) db.insertWithOnConflict("memberships",null,ContentValues().apply { put("book_id",id); put("group_id",groupId) },SQLiteDatabase.CONFLICT_IGNORE)
            else db.delete("memberships","book_id=? AND group_id=?",arrayOf(id,groupId.toString()))
        }; Unit
    }

    @Synchronized fun position(bookId: String): Anchor? = readableDatabase.rawQuery("SELECT anchor FROM positions WHERE book_id=?",arrayOf(bookId)).use { c -> if(c.moveToFirst()) gson.fromJson(c.getString(0),Anchor::class.java) else null }
    @Synchronized fun savePosition(anchor: Anchor,progress: Float) = transaction { db ->
        require(progress.isFinite() && progress in 0f..1f) { "阅读进度无效。" }
        val normalized = checkedAnchor(anchor,pointQuote = true)
        db.insertWithOnConflict("positions",null,ContentValues().apply { put("book_id",anchor.bookId); put("anchor",gson.toJson(normalized)); put("progress",progress) },SQLiteDatabase.CONFLICT_REPLACE)
        db.execSQL("UPDATE books SET progress=?,last_read=?,status=CASE WHEN ? >= 0.999 THEN 'read' WHEN status='read' THEN 'read' ELSE 'reading' END WHERE id=?",
            arrayOf<Any>(progress,System.currentTimeMillis(),progress,anchor.bookId)); Unit
    }

    @Synchronized fun annotations(bookId: String? = null,query: String = ""): List<AnnotationRecord> {
        val args = mutableListOf<String>(); val where = mutableListOf<String>()
        if (bookId != null) { where += "book_id=?"; args += bookId }
        if (query.isNotEmpty()) { where += "(note LIKE ? ESCAPE '\\' OR anchor LIKE ? ESCAPE '\\')"; args += like(query); args += like(query) }
        return readableDatabase.rawQuery("SELECT id,anchor,kind,note,created_at FROM annotations" + (if(where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")) + " ORDER BY created_at DESC,id DESC",args.toTypedArray()).use { c ->
            buildList { while(c.moveToNext()) add(AnnotationRecord(c.getLong(0),gson.fromJson(c.getString(1),Anchor::class.java),c.getString(2),c.getString(3),c.getLong(4))) }
        }
    }

    @Synchronized fun saveAnnotation(anchor: Anchor,kind: String,note: String): Long {
        require(kind in KINDS) { "未知记录类型。" }
        require(note.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) { "单条笔记不能超过 128 KiB。" }
        val normalized = checkedAnchor(anchor,pointQuote = kind == "bookmark")
        return writableDatabase.insertOrThrow("annotations",null,ContentValues().apply { put("book_id",anchor.bookId); put("anchor",gson.toJson(normalized)); put("kind",kind); put("note",note); put("created_at",System.currentTimeMillis()) })
    }
    @Synchronized fun editAnnotation(id: Long,note: String) {
        require(note.toByteArray(Charsets.UTF_8).size <= MAX_NOTE_BYTES) { "单条笔记不能超过 128 KiB。" }
        writableDatabase.update("annotations",ContentValues().apply { put("note",note) },"id=?",arrayOf(id.toString()))
    }
    @Synchronized fun deleteAnnotation(id: Long) { writableDatabase.delete("annotations","id=?",arrayOf(id.toString())) }

    private fun checkedAnchor(anchor: Anchor,pointQuote: Boolean = false): Anchor {
        require(!anchor.orphaned) { "原位置已变化，不能保存为新记录。" }
        val text = text(anchor.bookId,anchor.sectionId,anchor.contentId) ?: throw IllegalArgumentException("正文位置不存在。")
        require(anchor.start >= 0 && anchor.end >= anchor.start && anchor.end <= text.length) { "正文偏移超出范围。" }
        require(safeBoundary(text,anchor.start)==anchor.start && safeBoundary(text,anchor.end)==anchor.end) { "选区不能从 Unicode 字符中间开始或结束。" }
        val end = if(pointQuote && anchor.end == anchor.start) safeBoundary(text,(anchor.start + 24).coerceAtMost(text.length)) else anchor.end
        val quote = text.substring(anchor.start,end)
        require(anchor.quote.isEmpty() || anchor.quote == text.substring(anchor.start,anchor.end)) { "选中文本已变化，请重新选择。" }
        return anchor.copy(end=end,quote=quote,prefix=text.substring(safeBoundary(text,(anchor.start-24).coerceAtLeast(0)),anchor.start),suffix=text.substring(end,safeBoundary(text,(end+24).coerceAtMost(text.length))))
    }

    @Synchronized fun isPoemFavorite(bookId: String,poemId: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM poem_favorites WHERE book_id=? AND poem_id=?",arrayOf(bookId,poemId)).use { it.moveToFirst() }
    @Synchronized fun togglePoemFavorite(bookId: String,poemId: String) {
        if(isPoemFavorite(bookId,poemId)) writableDatabase.delete("poem_favorites","book_id=? AND poem_id=?",arrayOf(bookId,poemId))
        else {
            require(readableDatabase.rawQuery("SELECT 1 FROM books WHERE id=? AND type='poetry'",arrayOf(bookId)).use { it.moveToFirst() }) { "只有诗集中的诗作可以收藏。" }
            writableDatabase.insertOrThrow("poem_favorites",null,ContentValues().apply { put("book_id",bookId); put("poem_id",poemId) })
        }
    }
    @Synchronized fun favoritePoems(): List<FavoritePoem> = readableDatabase.rawQuery("SELECT f.book_id,f.poem_id,s.title FROM poem_favorites f JOIN sections s ON s.book_id=f.book_id AND s.id=f.poem_id ORDER BY s.title",null).use { c -> buildList { while(c.moveToNext()) add(FavoritePoem(c.getString(0),c.getString(1),c.getString(2))) } }

    @Synchronized fun settings(bookId: String? = null): ReadingSettings {
        fun read(scope: String): ReadingSettings? = readableDatabase.rawQuery("SELECT json FROM settings WHERE scope=?",arrayOf(scope)).use { c -> if(c.moveToFirst()) gson.fromJson(c.getString(0),ReadingSettings::class.java) else null }
        return bookId?.let { read(it) } ?: read("") ?: ReadingSettings()
    }
    @Synchronized fun saveSettings(settings: ReadingSettings,bookId: String? = null) {
        val data = gson.toJsonTree(settings).asJsonObject
        require(settingsErrors(data).isEmpty()) { settingsErrors(data).joinToString("；") }
        require(bookId == null || existsBook(bookId)) { "书籍不存在。" }
        writableDatabase.insertWithOnConflict("settings",null,ContentValues().apply { put("scope",bookId ?: ""); put("json",data.toString()) },SQLiteDatabase.CONFLICT_REPLACE)
    }
    @Synchronized fun clearBookSettings(bookId: String) { writableDatabase.delete("settings","scope=?",arrayOf(bookId)) }

    @Synchronized fun search(bookId: String,query: String,limit: Int = 200): List<SearchHit> {
        if(query.isEmpty() || limit <= 0) return emptyList()
        val result = mutableListOf<SearchHit>()
        readableDatabase.rawQuery("SELECT t.section_id,t.id,t.text,s.title FROM texts t JOIN sections s ON s.book_id=t.book_id AND s.id=t.section_id WHERE t.book_id=? AND t.text LIKE ? ESCAPE '\\' ORDER BY s.ord,t.ord LIMIT ?",arrayOf(bookId,like(query),limit.coerceAtMost(1000).toString())).use { c ->
            while(c.moveToNext() && result.size < limit.coerceAtMost(1000)) {
                val text = c.getString(2); var from = 0
                while(from <= text.length - query.length && result.size < limit.coerceAtMost(1000)) {
                    val at = text.indexOf(query,from,ignoreCase=true); if(at < 0) break
                    val end = at + query.length
                    val anchor = Anchor(bookId,c.getString(0),c.getString(1),at,end,text.substring(at,end),text.substring(safeBoundary(text,(at-24).coerceAtLeast(0)),at),text.substring(end,safeBoundary(text,(end+24).coerceAtMost(text.length))))
                    val left = safeBoundary(text,(at-24).coerceAtLeast(0)); val right = safeBoundary(text,(end+40).coerceAtMost(text.length))
                    result += SearchHit(anchor,c.getString(3),(if(left>0) "…" else "") + text.substring(left,right) + if(right<text.length) "…" else "")
                    from = end.coerceAtLeast(at+1)
                }
            }
        }
        return result
    }

    @Synchronized fun initializeExamples(doc: JsonObject) = transaction { db ->
        val initialized = db.rawQuery("SELECT 1 FROM metadata WHERE key='examples_initialized'",null).use { it.moveToFirst() }
        if(!initialized) {
            importFile(doc,emptyMap())
            db.insertOrThrow("metadata",null,ContentValues().apply { put("key","examples_initialized"); put("value","1") })
        }; Unit
    }

    /** Versioned, device-independent snapshot. Content stays separate from private reading data. */
    @Synchronized fun backup(): JsonObject = transaction { db ->
        val summaries = listBooks()
        JsonObject().apply {
            addProperty("schemaVersion",1); addProperty("kind","ebook-backup"); addProperty("createdAt",System.currentTimeMillis())
            add("content",Protocol.wrap(summaries.map { book(it.id)!! }))
            add("library",JsonArray().apply { summaries.forEach { b -> add(JsonObject().apply {
                addProperty("bookId",b.id); addProperty("favorite",b.favorite); addProperty("status",b.status)
                addProperty("progress",b.progress); addProperty("lastRead",b.lastRead); addProperty("importedAt",b.importedAt); addProperty("revision",b.contentRevision)
            }) } })
            add("groups",JsonArray().apply { groups().forEach { add(gson.toJsonTree(it)) } })
            add("memberships",rows(db,"SELECT book_id,group_id FROM memberships ORDER BY book_id,group_id") { c ->
                JsonObject().apply { addProperty("bookId",c.getString(0)); addProperty("groupId",c.getLong(1)) }
            })
            add("positions",rows(db,"SELECT anchor,progress FROM positions ORDER BY book_id") { c ->
                JsonObject().apply { add("anchor",json(c.getString(0))); addProperty("progress",c.getFloat(1)) }
            })
            add("annotations",JsonArray().apply { annotations().forEach { add(gson.toJsonTree(it)) } })
            add("poemFavorites",rows(db,"SELECT book_id,poem_id FROM poem_favorites ORDER BY book_id,poem_id") { c ->
                JsonObject().apply { addProperty("bookId",c.getString(0)); addProperty("poemId",c.getString(1)) }
            })
            add("settings",rows(db,"SELECT scope,json FROM settings ORDER BY scope") { c ->
                JsonObject().apply { addProperty("scope",c.getString(0)); add("settings",json(c.getString(1))) }
            })
            add("metadata",JsonObject().apply { db.rawQuery("SELECT key,value FROM metadata",null).use { c -> while(c.moveToNext()) addProperty(c.getString(0),c.getString(1)) } })
        }
    }

    private fun rows(db: SQLiteDatabase,sql: String,mapper: (Cursor) -> JsonObject): JsonArray = JsonArray().apply {
        db.rawQuery(sql,null).use { c -> while(c.moveToNext()) add(mapper(c)) }
    }

    /** Validates every foreign key and numeric/text field before any write occurs. */
    @Synchronized fun validateBackup(document: JsonObject): List<String> {
        val errors = mutableListOf<String>()
        fun error(path: String,message: String) { if(errors.size < 200) errors += "$path：$message" }
        val pending=java.util.ArrayDeque<Pair<JsonElement,Int>>()
        pending.add(document to 0)
        var visited=0
        while(pending.isNotEmpty()) {
            val (node,depth)=pending.removeLast()
            if(++visited>3_000_000 || depth>48) return listOf("备份内容数量或嵌套深度超过上限（300 万节点、48 层）。")
            when {
                node.isJsonObject -> node.asJsonObject.entrySet().forEach { pending.add(it.value to depth+1) }
                node.isJsonArray -> node.asJsonArray.forEach { pending.add(it to depth+1) }
            }
        }
        fun objectAt(element: JsonElement?,path: String): JsonObject? {
            if(element == null || !element.isJsonObject) { error(path,"必须是对象"); return null }; return element.asJsonObject
        }
        fun string(obj: JsonObject,key: String,path: String,empty: Boolean = false): String? {
            val e = obj.get(key)
            if(e == null || !e.isJsonPrimitive || !e.asJsonPrimitive.isString || (!empty && e.asString.isBlank())) {
                error("$path.$key",if(empty) "必须是字符串" else "必须是非空字符串"); return null
            }; return e.asString
        }
        fun bool(obj: JsonObject,key: String,path: String): Boolean? {
            val e = obj.get(key)
            if(e == null || !e.isJsonPrimitive || !e.asJsonPrimitive.isBoolean) { error("$path.$key","必须是布尔值"); return null }; return e.asBoolean
        }
        fun integer(obj: JsonObject,key: String,path: String,min: Long = 0,max: Long = Long.MAX_VALUE): Long? {
            val e = obj.get(key)
            val n = if(e?.isJsonPrimitive == true && e.asJsonPrimitive.isNumber) runCatching { e.asBigDecimal.longValueExact() }.getOrNull() else null
            if(n == null || n < min || n > max) { error("$path.$key","必须是 $min 至 $max 的整数"); return null }; return n
        }
        fun fraction(obj: JsonObject,key: String,path: String): Double? {
            val e = obj.get(key)
            val n = if(e?.isJsonPrimitive == true && e.asJsonPrimitive.isNumber) runCatching { e.asDouble }.getOrNull() else null
            if(n == null || !n.isFinite() || n !in 0.0..1.0) { error("$path.$key","必须是 0 至 1 的有限数值"); return null }; return n
        }
        fun array(key: String): List<JsonObject> {
            val e = document.get(key)
            if(e == null || !e.isJsonArray) { error(key,"必须是数组"); return emptyList() }
            if(e.asJsonArray.size() > 500_000) { error(key,"记录数量超过 500000 上限"); return emptyList() }
            return e.asJsonArray.mapIndexed { i,item -> objectAt(item,"$key[$i]") ?: JsonObject() }
        }
        if(integer(document,"schemaVersion","$") != 1L) error("schemaVersion","仅支持备份版本 1")
        if(string(document,"kind","$") != "ebook-backup") error("kind","必须为 ebook-backup；普通内容文件请使用导入")
        integer(document,"createdAt","$")
        val content = objectAt(document.get("content"),"content")
        val books = mutableMapOf<String,JsonObject>()
        val sectionIds = mutableSetOf<Pair<String,String>>()
        val poetrySections = mutableSetOf<Pair<String,String>>()
        val texts = mutableMapOf<Triple<String,String,String>,String>()
        if(content != null) {
            if(integer(content,"schemaVersion","content") != 1L) error("content.schemaVersion","仅支持内容版本 1")
            if(string(content,"kind","content") != "ebook-library") error("content.kind","必须为 ebook-library")
            val items = content.get("books")
            if(items?.isJsonArray != true) error("content.books","必须是数组")
            else items.asJsonArray.forEachIndexed { index,item ->
                val book = objectAt(item,"content.books[$index]") ?: return@forEachIndexed
                val result = Protocol.validate(Protocol.wrap(listOf(book)),"备份")
                if(!result.valid) { result.errors.forEach { error("content.books[$index].${it.path.removePrefix("books[0].")}",it.message) }; return@forEachIndexed }
                val clean = result.document!!.getAsJsonArray("books")[0].asJsonObject
                val id = clean.s("id")
                val metadata=clean.deepCopy().apply { remove("poems"); remove("chapters") }
                if(metadata.toString().toByteArray(Charsets.UTF_8).size>MAX_METADATA_BYTES) error("content.books[$index]","书籍元数据超过 256 KiB")
                if(books.put(id,clean) != null) error("content.books[$index].id","重复书籍 ID")
                Content.sections(clean).forEach { section ->
                    val sid = section.s("id"); sectionIds += id to sid
                    if(section.toString().toByteArray(Charsets.UTF_8).size > MAX_SECTION_BYTES) error("content.books[$index]/$sid","单章或单首诗超过 1 MiB 上限")
                    if(clean.s("type") == "poetry") poetrySections += id to sid
                    Content.texts(section,clean.s("type")).forEach { (cid,text) -> texts[Triple(id,sid,cid)] = text }
                }
            }
        }
        fun bookReference(obj: JsonObject,path: String,key: String = "bookId"): String? = string(obj,key,path)?.also {
            if(it !in books) error("$path.$key","引用的书籍不存在")
        }
        fun anchor(obj: JsonObject,path: String): Anchor? {
            val a = objectAt(obj.get("anchor"),"$path.anchor") ?: return null
            val p = "$path.anchor"; val bookId = bookReference(a,p)
            val sectionId = string(a,"sectionId",p); val contentId = string(a,"contentId",p)
            val start = integer(a,"start",p,max=Int.MAX_VALUE.toLong()); val end = integer(a,"end",p,max=Int.MAX_VALUE.toLong())
            val quote = string(a,"quote",p,true); val prefix = string(a,"prefix",p,true); val suffix = string(a,"suffix",p,true)
            val orphaned = bool(a,"orphaned",p)
            if(bookId == null || sectionId == null || contentId == null || start == null || end == null || quote == null || prefix == null || suffix == null || orphaned == null) return null
            if(end < start) { error(p,"结束位置不能小于开始位置"); return null }
            if(!orphaned) {
                val text = texts[Triple(bookId,sectionId,contentId)]
                if(text == null) error(p,"有效记录指向的章节或正文不存在；位置变化的记录须标记 orphaned")
                else if(end > text.length || text.substring(start.toInt(),end.toInt()) != quote) error(p,"偏移或选中文本与正文不一致")
            }
            return Anchor(bookId,sectionId,contentId,start.toInt(),end.toInt(),quote,prefix,suffix,orphaned)
        }
        val librarySeen = mutableSetOf<String>(); val libraryProgress = mutableMapOf<String,Double>()
        array("library").forEachIndexed { i,obj ->
            val p = "library[$i]"; val id = bookReference(obj,p)
            if(id != null && !librarySeen.add(id)) error("$p.bookId","重复书籍记录")
            bool(obj,"favorite",p); val status = string(obj,"status",p)
            if(status != null && status !in STATUSES) error("$p.status","必须为 unread、reading 或 read")
            fraction(obj,"progress",p)?.let { if(id != null) libraryProgress[id] = it }
            integer(obj,"lastRead",p); integer(obj,"importedAt",p); integer(obj,"revision",p,min=1,max=Int.MAX_VALUE.toLong())
        }
        if(librarySeen != books.keys) error("library","必须且只能包含每本书的一条阅读状态记录")
        val groupIds = mutableSetOf<Long>(); val groupNames = mutableSetOf<String>()
        array("groups").forEachIndexed { i,obj ->
            val p = "groups[$i]"; integer(obj,"id",p,min=1)?.let { if(!groupIds.add(it)) error("$p.id","重复分组 ID") }
            string(obj,"name",p)?.let { if(!groupNames.add(it)) error("$p.name","重复分组名称") }
        }
        val membershipSeen = mutableSetOf<Pair<String,Long>>()
        array("memberships").forEachIndexed { i,obj ->
            val p = "memberships[$i]"; val b = bookReference(obj,p); val g = integer(obj,"groupId",p,min=1)
            if(g != null && g !in groupIds) error("$p.groupId","引用的分组不存在")
            if(b != null && g != null && !membershipSeen.add(b to g)) error(p,"重复分组成员关系")
        }
        val positionSeen = mutableSetOf<String>()
        array("positions").forEachIndexed { i,obj ->
            val p = "positions[$i]"; val a = anchor(obj,p); val progress = fraction(obj,"progress",p)
            if(a != null) {
                if(!positionSeen.add(a.bookId)) error(p,"一本书只能有一条最近阅读位置")
                if(progress != null && kotlin.math.abs(progress-(libraryProgress[a.bookId] ?: progress)) > 0.00001) error(p,"位置进度与书籍状态不一致")
            }
        }
        val noteIds = mutableSetOf<Long>()
        array("annotations").forEachIndexed { i,obj ->
            val p = "annotations[$i]"; integer(obj,"id",p,min=1)?.let { if(!noteIds.add(it)) error("$p.id","重复记录 ID") }
            anchor(obj,p); val kind = string(obj,"kind",p)
            if(kind != null && kind !in KINDS) error("$p.kind","必须为 bookmark、highlight 或 note")
            string(obj,"note",p,true)?.let { if(it.toByteArray(Charsets.UTF_8).size>MAX_NOTE_BYTES) error("$p.note","单条笔记超过 128 KiB") }
            integer(obj,"createdAt",p)
        }
        val favoriteSeen = mutableSetOf<Pair<String,String>>()
        array("poemFavorites").forEachIndexed { i,obj ->
            val p = "poemFavorites[$i]"; val b = bookReference(obj,p); val poem = string(obj,"poemId",p)
            if(b != null && poem != null) {
                if((b to poem) !in poetrySections) error(p,"引用的诗作不存在或并非诗集内容")
                if(!favoriteSeen.add(b to poem)) error(p,"重复诗作收藏")
            }
        }
        val settingScopes = mutableSetOf<String>()
        array("settings").forEachIndexed { i,obj ->
            val p = "settings[$i]"; val scope = string(obj,"scope",p,true)
            if(scope != null) {
                if(scope.isNotEmpty() && scope !in books) error("$p.scope","单书设置引用的书籍不存在")
                if(!settingScopes.add(scope)) error("$p.scope","重复设置范围")
            }
            objectAt(obj.get("settings"),"$p.settings")?.let { s -> settingsErrors(s).forEach { error("$p.settings",it) } }
        }
        objectAt(document.get("metadata"),"metadata")?.entrySet()?.forEach { (key,value) ->
            if(!value.isJsonPrimitive || !value.asJsonPrimitive.isString) error("metadata.$key","必须是字符串")
        }
        return errors
    }

    /** Returns the pre-restore backup, durably written before touching existing rows. */
    @Synchronized fun restoreBackup(document: JsonObject): File {
        val errors = validateBackup(document)
        require(errors.isEmpty()) { errors.joinToString("\n") }
        val currentSnapshot=backup()
        val safetyErrors=validateBackup(currentSnapshot)
        check(safetyErrors.isEmpty()) { "无法生成可恢复的现有数据备份；本次恢复未开始。\n${safetyErrors.joinToString("\n")}" }
        val recoveryDir = File(appContext.filesDir,"recovery")
        check(recoveryDir.isDirectory || recoveryDir.mkdirs()) { "无法创建恢复保护目录；原数据未改变。" }
        val protected = File(recoveryDir,"before-restore-${System.currentTimeMillis()}-${UUID.randomUUID().toString().take(8)}.json")
        val temp = File(recoveryDir,"${protected.name}.tmp")
        try {
            FileOutputStream(temp).use { stream ->
                val writer = stream.writer(Charsets.UTF_8)
                gson.toJson(currentSnapshot,writer); writer.flush(); stream.fd.sync()
            }
            check(temp.renameTo(protected)) { "无法保存恢复保护备份；原数据未改变。" }
        } finally { if(temp.exists()) temp.delete() }
        transaction { db ->
            db.delete("books",null,null); db.delete("shelf_groups",null,null); db.delete("settings",null,null); db.delete("metadata",null,null)
            document.getAsJsonObject("content").getAsJsonArray("books").forEach { item ->
                val clean = Protocol.validate(Protocol.wrap(listOf(item.asJsonObject)),"备份").document!!.getAsJsonArray("books")[0].asJsonObject
                writeNewBook(db,clean,{ false })
            }
            document.getAsJsonArray("library").forEach { element -> val obj = element.asJsonObject
                db.update("books",ContentValues().apply {
                    put("favorite",if(obj.get("favorite").asBoolean) 1 else 0); put("status",obj.s("status")); put("progress",obj.get("progress").asFloat)
                    put("last_read",obj.get("lastRead").asLong); put("imported_at",obj.get("importedAt").asLong); put("content_revision",obj.get("revision").asInt)
                },"id=?",arrayOf(obj.s("bookId")))
            }
            document.getAsJsonArray("groups").forEach { item -> val obj=item.asJsonObject
                db.insertOrThrow("shelf_groups",null,ContentValues().apply { put("id",obj.get("id").asLong); put("name",obj.s("name")) })
            }
            document.getAsJsonArray("memberships").forEach { item -> val obj=item.asJsonObject
                db.insertOrThrow("memberships",null,ContentValues().apply { put("book_id",obj.s("bookId")); put("group_id",obj.get("groupId").asLong) })
            }
            document.getAsJsonArray("positions").forEach { item -> val obj=item.asJsonObject; val a=obj.getAsJsonObject("anchor")
                db.insertOrThrow("positions",null,ContentValues().apply { put("book_id",a.s("bookId")); put("anchor",a.toString()); put("progress",obj.get("progress").asFloat) })
            }
            document.getAsJsonArray("annotations").forEach { item -> val obj=item.asJsonObject; val a=obj.getAsJsonObject("anchor")
                db.insertOrThrow("annotations",null,ContentValues().apply { put("id",obj.get("id").asLong); put("book_id",a.s("bookId")); put("anchor",a.toString()); put("kind",obj.s("kind")); put("note",obj.s("note")); put("created_at",obj.get("createdAt").asLong) })
            }
            document.getAsJsonArray("poemFavorites").forEach { item -> val obj=item.asJsonObject
                db.insertOrThrow("poem_favorites",null,ContentValues().apply { put("book_id",obj.s("bookId")); put("poem_id",obj.s("poemId")) })
            }
            document.getAsJsonArray("settings").forEach { item -> val obj=item.asJsonObject
                db.insertOrThrow("settings",null,ContentValues().apply { put("scope",obj.s("scope")); put("json",obj.getAsJsonObject("settings").toString()) })
            }
            document.getAsJsonObject("metadata").entrySet().forEach { (key,value) ->
                db.insertOrThrow("metadata",null,ContentValues().apply { put("key",key); put("value",value.asString) })
            }
            db.insertWithOnConflict("metadata",null,ContentValues().apply { put("key","examples_initialized"); put("value","1") },SQLiteDatabase.CONFLICT_REPLACE)
            db.rawQuery("PRAGMA foreign_key_check",null).use { check(!it.moveToFirst()) { "恢复关联关系校验失败，已回滚。" } }
        }
        return protected
    }

    private fun settingsErrors(obj: JsonObject): List<String> = buildList {
        fun text(key: String): String? {
            val v=obj.get(key); if(v?.isJsonPrimitive != true || !v.asJsonPrimitive.isString || v.asString.isBlank()) { add("$key 必须是非空字符串"); return null }; return v.asString
        }
        fun number(key: String,min: Double,max: Double) {
            val v=obj.get(key); val n=if(v?.isJsonPrimitive == true && v.asJsonPrimitive.isNumber) runCatching { v.asDouble }.getOrNull() else null
            if(n == null || !n.isFinite() || n < min || n > max) add("$key 必须在 $min 至 $max 之间")
        }
        val mode=text("mode"); if(mode != null && mode !in setOf("scroll","page","paged")) add("mode 不受支持")
        val theme=text("theme"); if(theme != null && theme !in setOf("system","light","sepia","dark","custom")) add("theme 不受支持")
        // Optional fields preserve compatibility with backups written before custom palettes.
        listOf("customBackground","customSurface","customAccent").forEach { key ->
            if(obj.has(key)) text(key)?.let { if(!it.matches(Regex("#[0-9a-fA-F]{6}"))) add("$key 必须为 #RRGGBB 六位颜色值") }
        }
        text("fontFamily")?.let { if(it.length>256) add("fontFamily 太长") }
        number("fontSize",12.0,64.0); number("lineSpacing",1.0,3.0); number("paragraphSpacing",0.0,80.0); number("margin",0.0,120.0)
        listOf("keepAwake","indent","showOriginal","showPinyin","showTranslation","showNotes","showAnalysis").forEach { key ->
            val v=obj.get(key); if(v?.isJsonPrimitive != true || !v.asJsonPrimitive.isBoolean) add("$key 必须是布尔值")
        }
    }

    private fun text(bookId: String,sectionId: String,contentId: String): String? = readableDatabase.rawQuery("SELECT text FROM texts WHERE book_id=? AND section_id=? AND id=?",arrayOf(bookId,sectionId,contentId)).use { c -> if(c.moveToFirst()) c.getString(0) else null }
    private fun existsBook(id: String): Boolean = readableDatabase.rawQuery("SELECT 1 FROM books WHERE id=?",arrayOf(id)).use { it.moveToFirst() }
    private fun checkCancellation(cancelled: () -> Boolean) { if(cancelled() || Thread.currentThread().isInterrupted) throw ImportCancelledException() }
    private fun safeBoundary(text: String,index: Int): Int = if(index>0 && index<text.length && Character.isLowSurrogate(text[index]) && Character.isHighSurrogate(text[index-1])) index-1 else index
    private fun like(value: String) = "%" + value.replace("\\","\\\\").replace("%","\\%").replace("_","\\_") + "%"
    private fun json(value: String): JsonObject = JsonParser.parseString(value).asJsonObject
    private fun JsonObject.s(key: String): String = get(key)?.takeUnless { it.isJsonNull }?.asString ?: ""
    private fun Cursor.s(key: String) = getString(getColumnIndexOrThrow(key))
    private fun Cursor.i(key: String) = getInt(getColumnIndexOrThrow(key))
    private fun Cursor.l(key: String) = getLong(getColumnIndexOrThrow(key))
    private fun Cursor.f(key: String) = getFloat(getColumnIndexOrThrow(key))

    companion object {
        const val DATABASE_VERSION = 3
        const val MAX_SECTION_BYTES = 1024 * 1024
        const val MAX_METADATA_BYTES = 256 * 1024
        const val MAX_NOTE_BYTES = 128 * 1024
        private val STATUSES = setOf("unread","reading","read")
        private val KINDS = setOf("bookmark","highlight","note")
    }
}
