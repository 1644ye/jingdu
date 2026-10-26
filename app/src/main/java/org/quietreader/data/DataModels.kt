package org.quietreader.data

import org.quietreader.core.Anchor

data class BookSummary(
    val id: String,
    val type: String,
    val title: String,
    val author: String,
    val description: String,
    val tags: List<String>,
    val favorite: Boolean,
    val status: String,
    val progress: Float,
    val lastRead: Long,
    val importedAt: Long,
    val contentRevision: Int = 1
)

data class SectionSummary(val id: String, val title: String, val order: Int, val volumeTitle: String = "", val volumeId: String = "")
data class ShelfGroup(val id: Long, val name: String)
data class AnnotationRecord(val id: Long, val anchor: Anchor, val kind: String, val note: String, val createdAt: Long)
data class FavoritePoem(val bookId: String, val poemId: String, val title: String)
data class SearchHit(val anchor: Anchor, val sectionTitle: String, val snippet: String)
data class ImportResult(val imported: Int, val updated: Int, val skipped: Int, val copied: Int)

class ImportCancelledException : RuntimeException("导入已取消，当前文件已回滚。")
