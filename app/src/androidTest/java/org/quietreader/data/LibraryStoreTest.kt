package org.quietreader.data

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.test.AndroidTestCase
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.quietreader.core.Anchor
import org.quietreader.core.Content
import org.quietreader.core.Protocol
import org.quietreader.core.ReadingSettings

/** Exercises Android SQLite itself, including rollback, foreign keys, migration and durable reopen. */
@Suppress("DEPRECATION")
class LibraryStoreTest : AndroidTestCase() {
    private lateinit var store: LibraryStore
    private val databaseName = "library-store-instrumentation.db"

    override fun setUp() {
        super.setUp()
        context.deleteDatabase(databaseName)
        store = LibraryStore(context,databaseName)
    }

    override fun tearDown() {
        store.close()
        context.deleteDatabase(databaseName)
        super.tearDown()
    }

    fun testCancelRollsBackEntireCurrentFile() {
        var cancel = false
        try {
            store.importFile(Protocol.wrap(listOf(novel("a"),novel("b"))),emptyMap(),{ cancel }) { done,_ -> if(done==1) cancel=true }
            fail("Expected cancellation")
        } catch (_: ImportCancelledException) { }
        assertTrue(store.listBooks().isEmpty())
        assertTrue(store.sections("a").isEmpty())
        store.importFile(Protocol.wrap(listOf(novel("prior"))),emptyMap())
        try { store.importFile(Protocol.wrap(listOf(novel("c"))),emptyMap(),{ true }); fail("Expected cancellation") }
        catch (_: ImportCancelledException) { }
        assertEquals(listOf("prior"),store.listBooks().map { it.id })
    }

    fun testInvalidFileDoesNotPartiallyImport() {
        val bad=novel("bad"); bad.addProperty("title","")
        try { store.importFile(Protocol.wrap(listOf(novel("good"),bad)),emptyMap()); fail("Expected validation failure") }
        catch (_: IllegalArgumentException) { }
        assertEquals(0,store.listBooks().size)
    }

    fun testPersistenceSkipCopyAndReferences() {
        val original=novel("a")
        store.importFile(Protocol.wrap(listOf(original)),emptyMap())
        assertEquals(1,store.importFile(Protocol.wrap(listOf(original)),emptyMap()).skipped)
        assertEquals(1,store.importFile(Protocol.wrap(listOf(original)),mapOf("a" to "copy")).copied)
        val copyId=store.listBooks().first { it.id!="a" }.id
        val copy=store.book(copyId)!!
        val volumeId=copy.getAsJsonArray("volumes")[0].asJsonObject.get("id").asString
        val chapter=Content.sections(copy).first()
        assertEquals(volumeId,chapter.get("volumeId").asString)
        assertFalse(volumeId=="v1")
        assertFalse(chapter.get("id").asString=="c1")
        assertFalse(Content.texts(chapter,"novel").containsKey("p1"))
        assertTrue(Protocol.validate(Protocol.wrap(listOf(copy)),"copy").valid)
        store.close(); store=LibraryStore(context,databaseName)
        assertEquals(2,store.listBooks().size)
        assertEquals("山城来信",store.book("a")!!.get("title").asString)
    }

    fun testUpdateKeepsAndInvalidatesAnchorsWithoutLosingNotes() {
        store.importFile(Protocol.wrap(listOf(novel("a"))),emptyMap())
        val id=store.saveAnnotation(Anchor("a","c1","p1",0,2,"雨停"),"note","请保留这条笔记")
        store.savePosition(Anchor("a","c1","p1",3,3),0.3f)
        store.importFile(Protocol.wrap(listOf(novel("a"))),mapOf("a" to "update"))
        assertFalse(store.annotations("a").single().anchor.orphaned)
        val changed=novel("a")
        changed.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text","这里已经换成完全不同的内容。")
        store.importFile(Protocol.wrap(listOf(changed)),mapOf("a" to "update"))
        val note=store.annotations("a").single()
        assertEquals(id,note.id); assertTrue(note.anchor.orphaned); assertEquals("请保留这条笔记",note.note)
        assertTrue(store.position("a")!!.orphaned)
        assertEquals(3,store.listBooks().single().contentRevision)
    }

    fun testCancelledUpdateRestoresContentAnchorsAndPoemFavorites() {
        val original=poetry("poems")
        store.importFile(Protocol.wrap(listOf(original)),emptyMap())
        val anchor=Anchor("poems","poem1","line1",0,2,"床前")
        store.saveAnnotation(anchor,"note","更新取消必须保留")
        store.savePosition(anchor,0.5f)
        store.togglePoemFavorite("poems","poem1")
        val group=store.createGroup("诗集")
        store.setMembership(listOf("poems"),group,true)
        val before=store.book("poems")
        val updated=original.deepCopy()
        updated.getAsJsonArray("poems")[0].asJsonObject.getAsJsonArray("stanzas")[0].asJsonObject.getAsJsonArray("lines")[0].asJsonObject.addProperty("original","已经完全变化的正文")
        var cancel=false
        try {
            store.importFile(Protocol.wrap(listOf(updated,novel("new"))),mapOf("poems" to "update"),{cancel}) { done,_ -> if(done==1) cancel=true }
            fail("Expected cancellation after update but before file commit")
        } catch(_:ImportCancelledException) { }
        assertEquals(before,store.book("poems")); assertNull(store.book("new"))
        assertFalse(store.annotations("poems").single().anchor.orphaned)
        assertEquals("更新取消必须保留",store.annotations("poems").single().note)
        assertFalse(store.position("poems")!!.orphaned)
        assertEquals(setOf(group),store.groupIds("poems"))
        assertTrue(store.isPoemFavorite("poems","poem1"))
        assertEquals(1,store.listBooks().single().contentRevision)
    }

    fun testSearchUsesLiteralChineseAndWildcardCharacters() {
        val b=novel("a")
        b.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text","月光，月光；100%_完成。")
        store.importFile(Protocol.wrap(listOf(b)),emptyMap())
        assertEquals(2,store.search("a","月光").size)
        val hit=store.search("a","%_").single()
        assertEquals("%_",hit.anchor.quote)
        assertEquals("p1",hit.anchor.contentId)
        assertTrue(store.search("a","不存在").isEmpty())
    }

    fun testGeneratedContextNeverSplitsEmojiSurrogatePair() {
        val b=novel("emoji")
        val text="文".repeat(23)+"😀"+"尾".repeat(60)
        b.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text",text)
        store.importFile(Protocol.wrap(listOf(b)),emptyMap())
        store.savePosition(Anchor("emoji","c1","p1",0,0),0f)
        val position=store.position("emoji")!!
        assertEquals(23,position.end)
        assertEquals("文".repeat(23),position.quote)
        assertTrue(position.suffix.startsWith("😀"))
        val hit=store.search("emoji","😀").single()
        assertEquals(23,hit.anchor.start); assertEquals(25,hit.anchor.end)
        assertTrue(store.validateBackup(store.backup()).isEmpty())
    }

    fun testBackupRoundTripPreservesAllRelationshipsAndSettings() {
        store.importFile(Protocol.wrap(listOf(novel("a"),poetry("poems"))),emptyMap())
        val group=store.createGroup("私藏")
        val second=store.createGroup("在读")
        store.setMembership(listOf("a"),group,true); store.setMembership(listOf("a"),second,true)
        store.toggleFavorite("a"); store.togglePoemFavorite("poems","poem1")
        store.savePosition(Anchor("a","c1","p1",0,2,"雨停"),0.5f)
        store.saveAnnotation(Anchor("a","c1","p1",0,2,"雨停"),"note","备份中的笔记")
        store.saveSettings(ReadingSettings(fontSize=28f,theme="dark"))
        store.saveSettings(ReadingSettings(fontSize=32f,theme="sepia"),"a")
        val backup=store.backup()
        assertEquals(emptyList<String>(),store.validateBackup(backup))
        val exported=store.exportContent(listOf("a","poems"))
        store.deleteBooks(listOf("a","poems"))
        store.deleteGroup(group); store.deleteGroup(second)
        val protected=store.restoreBackup(backup)
        assertTrue(protected.exists())
        val safety=JsonParser.parseString(protected.readText()).asJsonObject
        assertEquals(emptyList<String>(),store.validateBackup(safety))
        assertEquals(0,safety.getAsJsonObject("content").getAsJsonArray("books").size())
        assertEquals(exported,store.exportContent(listOf("a","poems")))
        assertEquals(setOf(group,second),store.groupIds("a"))
        assertEquals(1,store.favoritePoems().size)
        assertEquals(32f,store.settings("a").fontSize,0.001f)
        assertEquals(28f,store.settings().fontSize,0.001f)
        assertEquals("雨停",store.position("a")!!.quote)
        assertEquals("备份中的笔记",store.annotations("a").single().note)
        assertTrue(store.listBooks().first { it.id=="a" }.favorite)
        protected.delete()
    }

    fun testRestoreFailureRollsBackExistingData() {
        store.importFile(Protocol.wrap(listOf(novel("replacement"))),emptyMap())
        val replacement=store.backup()
        store.deleteBooks(listOf("replacement"))
        store.importFile(Protocol.wrap(listOf(novel("original"))),emptyMap())
        store.writableDatabase.execSQL("CREATE TRIGGER fail_restore BEFORE INSERT ON books WHEN NEW.id='replacement' BEGIN SELECT RAISE(ABORT,'forced restoration failure'); END")
        try { store.restoreBackup(replacement); fail("Expected injected SQLite failure") }
        catch (_: android.database.sqlite.SQLiteException) { }
        assertEquals(listOf("original"),store.listBooks().map { it.id })
        assertNotNull(store.section("original","c1"))
        store.writableDatabase.execSQL("DROP TRIGGER fail_restore")
    }

    fun testMalformedBackupRejectedBeforeWriting() {
        store.importFile(Protocol.wrap(listOf(novel("a"))),emptyMap())
        store.saveAnnotation(Anchor("a","c1","p1",0,2,"雨停"),"note","保留")
        val corrupt=store.backup()
        corrupt.getAsJsonArray("annotations")[0].asJsonObject.getAsJsonObject("anchor").addProperty("bookId","missing")
        assertTrue(store.validateBackup(corrupt).any { it.contains("书籍不存在") })
        try { store.restoreBackup(corrupt); fail("Expected invalid backup rejection") } catch (_: IllegalArgumentException) { }
        assertEquals("保留",store.annotations("a").single().note)
        val badNumber=store.backup()
        badNumber.getAsJsonArray("library")[0].asJsonObject.addProperty("progress","0.5")
        assertTrue(store.validateBackup(badNumber).isNotEmpty())
        val badQuote=store.backup()
        badQuote.getAsJsonArray("annotations")[0].asJsonObject.getAsJsonObject("anchor").addProperty("quote","错字")
        assertTrue(store.validateBackup(badQuote).isNotEmpty())
    }

    fun testExamplesAreNotReinsertedAfterDeletionOrReopen() {
        val examples=Protocol.wrap(listOf(novel("demo")))
        store.initializeExamples(examples)
        store.deleteBooks(listOf("demo"))
        store.close(); store=LibraryStore(context,databaseName)
        store.initializeExamples(examples)
        assertTrue(store.listBooks().isEmpty())
    }

    fun testDowngradeRefusesWithoutClearingDatabase() {
        store.importFile(Protocol.wrap(listOf(novel("a"))),emptyMap())
        store.writableDatabase.version=LibraryStore.DATABASE_VERSION+1
        store.close()
        store=LibraryStore(context,databaseName)
        try { store.listBooks(); fail("A newer schema must not be reset") }
        catch (_: IllegalStateException) { }
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path,null,SQLiteDatabase.OPEN_READONLY).use { db ->
            db.rawQuery("SELECT title FROM books WHERE id='a'",null).use { c -> assertTrue(c.moveToFirst()); assertEquals("山城来信",c.getString(0)) }
        }
    }

    fun testOversizedSectionFailsAtomicallyWithUsefulMessage() {
        val huge=novel("too-large")
        huge.getAsJsonArray("chapters")[0].asJsonObject.getAsJsonArray("blocks")[0].asJsonObject.addProperty("text","文".repeat(400_000))
        try {
            store.importFile(Protocol.wrap(listOf(novel("first"),huge)),emptyMap())
            fail("Oversized section must fail")
        } catch(e: IllegalArgumentException) { assertTrue(e.message!!.contains("1 MiB")) }
        assertTrue(store.listBooks().isEmpty())
    }

    fun testVersionOneMigrationPreservesBooksAndNotes() {
        store.importFile(Protocol.wrap(listOf(novel("a"))),emptyMap())
        store.saveAnnotation(Anchor("a","c1","p1",0,2,"雨停"),"note","迁移后保留")
        recreateLegacyDatabase(1)
        assertEquals(LibraryStore.DATABASE_VERSION,store.readableDatabase.version)
        assertEquals(1,store.listBooks().single().contentRevision)
        assertEquals("迁移后保留",store.annotations("a").single().note)
        assertNotNull(store.section("a","c1"))
        assertEquals("v1",store.sections("a").single().volumeId)
    }

    fun testVersionTwoMigrationPreservesDistinctSameTitleVolumeIds() {
        val book=novel("a")
        book.getAsJsonArray("volumes").add(JsonParser.parseString("""{"id":"v2","title":"第一卷"}"""))
        book.getAsJsonArray("chapters").add(JsonParser.parseString("""{"id":"c2","volumeId":"v2","title":"第二章","blocks":[{"id":"p3","type":"paragraph","text":"同名的第二卷，也必须是独立的卷。"}]}"""))
        store.importFile(Protocol.wrap(listOf(book)),emptyMap())
        store.importFile(Protocol.wrap(listOf(book)),mapOf("a" to "update"))
        store.savePosition(Anchor("a","c1","p1",0,2,"雨停"),0.25f)
        store.saveAnnotation(Anchor("a","c1","p1",0,2,"雨停"),"note","版本二笔记")
        val before=store.exportContent(listOf("a"))
        recreateLegacyDatabase(2)
        assertEquals(LibraryStore.DATABASE_VERSION,store.readableDatabase.version)
        assertEquals(2,store.listBooks().single().contentRevision)
        val sections=store.sections("a")
        assertEquals(listOf("v1","v2"),sections.map { it.volumeId })
        assertEquals(listOf("第一卷","第一卷"),sections.map { it.volumeTitle })
        assertEquals(before,store.exportContent(listOf("a")))
        assertEquals("雨停",store.position("a")!!.quote)
        assertEquals("版本二笔记",store.annotations("a").single().note)
    }

    private fun recreateLegacyDatabase(version:Int) {
        val db=store.readableDatabase
        val tables=mutableListOf<Pair<String,String>>()
        // Android creates android_metadata itself when the legacy database is opened.
        // Copy application tables only; sqlite_sequence and other SQLite tables are also excluded.
        db.rawQuery("SELECT name,sql FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' AND name <> 'android_metadata'",null).use { c ->
            while(c.moveToNext()) tables+=c.getString(0) to c.getString(1)
        }
        val records=tables.associate { (name,_) ->
            val rows=mutableListOf<ContentValues>()
            db.rawQuery("SELECT * FROM $name",null).use { c -> while(c.moveToNext()) {
                rows+=ContentValues().apply { c.columnNames.forEachIndexed { i,key -> if(key!="volume_id" && !(version==1 && key=="content_revision")) when(c.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> putNull(key)
                    Cursor.FIELD_TYPE_INTEGER -> put(key,c.getLong(i))
                    Cursor.FIELD_TYPE_FLOAT -> put(key,c.getDouble(i))
                    else -> put(key,c.getString(i))
                } } }
            } }
            name to rows
        }
        store.close(); context.deleteDatabase(databaseName)
        SQLiteDatabase.openOrCreateDatabase(context.getDatabasePath(databaseName),null).use { legacy ->
            tables.forEach { (name,sql) ->
                var legacySql=sql
                if(name=="books" && version==1) legacySql=legacySql.replace(",content_revision INTEGER NOT NULL DEFAULT 1","")
                if(name=="sections") legacySql=legacySql.replace(",volume_id TEXT NOT NULL DEFAULT ''","")
                legacy.execSQL(legacySql)
            }
            records.forEach { (table,rows) -> rows.forEach { legacy.insertOrThrow(table,null,it) } }
            legacy.version=version
        }
        store=LibraryStore(context,databaseName)
    }

    private fun novel(id: String): JsonObject = JsonParser.parseString("""{
        "id":"$id","type":"novel","title":"山城来信","author":"示例作者",
        "volumes":[{"id":"v1","title":"第一卷"}],
        "chapters":[{"id":"c1","volumeId":"v1","title":"来信","blocks":[
        {"id":"p1","type":"paragraph","text":"雨停的时候，一封信送到了旧书店。"},
        {"id":"p2","type":"heading","text":"傍晚"},
        {"id":"separator","type":"separator"}]}]}""").asJsonObject

    private fun poetry(id: String): JsonObject = JsonParser.parseString("""{
        "id":"$id","type":"poetry","title":"唐诗","poems":[{"id":"poem1","title":"静夜思",
        "stanzas":[{"id":"stanza1","lines":[{"id":"line1","original":"床前明月光，","pinyin":"chuáng qián míng yuè guāng"}]}]}]}""").asJsonObject
}
