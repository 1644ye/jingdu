package org.quietreader.core

import org.junit.Assert.*
import org.junit.Test

class AnchorsTest {
    private fun anchor(start: Int, end: Int, quote: String = "", prefix: String = "", suffix: String = "") =
        Anchor("book", "chapter", "block", start, end, quote, prefix, suffix)

    @Test fun layoutChangeWithSameTextPreservesExactOffset() {
        val a = anchor(2, 4, "明月")
        assertEquals(a, Anchors.relocate(a, "床前明月光", "床前明月光"))
    }

    @Test fun uniqueQuoteAndContextMoveWithinSameContent() {
        val a = anchor(2, 4, "明月", "床前", "光")
        val result = Anchors.relocate(a, "床前明月光", "注：床前明月光")
        assertFalse(result.orphaned)
        assertEquals(4, result.start)
        assertEquals(6, result.end)
        assertEquals("block", result.contentId)
    }

    @Test fun repeatedQuoteCanBeDisambiguatedByRecordedContext() {
        val a = anchor(2, 4, "明月", "床前", "光")
        val result = Anchors.relocate(a, "床前明月光", "明月在天。床前明月光")
        assertFalse(result.orphaned)
        assertEquals(7, result.start)
    }

    @Test fun duplicateContextsAreOrphanedInsteadOfChoosingFirst() {
        val a = anchor(2, 4, "明月", "床前", "光")
        val result = Anchors.relocate(a, "床前明月光", "床前明月光。床前明月光")
        assertTrue(result.orphaned)
        assertEquals(a.quote, result.quote)
        assertEquals(a.start, result.start)
    }

    @Test fun changedContextIsNotSilentlyRebound() {
        assertTrue(Anchors.relocate(anchor(2, 4, "明月", "床前", "光"), "床前明月光", "山间明月光").orphaned)
    }

    @Test fun deletedContentRetainsAnnotationButMarksItOrphaned() {
        val a = anchor(0, 2, "原文")
        assertEquals(a.copy(orphaned = true), Anchors.relocate(a, "原文", null))
    }

    @Test fun quoteMustAgreeWithRecordedOriginalRange() {
        assertTrue(Anchors.relocate(anchor(0, 1, "错误"), "原文", "原文").orphaned)
        assertTrue(Anchors.relocate(anchor(-1, 2), "原文", "原文").orphaned)
    }

    @Test fun utf16OffsetsMatchAndroidIncludingEmoji() {
        val a = anchor(2, 4, "明月")
        val result = Anchors.relocate(a, "🌙明月", "甲🌙明月")
        assertFalse(result.orphaned)
        assertEquals(3, result.start)
        assertEquals(5, result.end)
    }

    @Test fun changedCaretWithoutQuoteCannotBeReliablyRelocated() {
        assertTrue(Anchors.relocate(anchor(1, 1), "原文", "新原文").orphaned)
    }

    @Test fun orphanDoesNotAutomaticallyBindAgain() {
        val a = anchor(0, 2, "原文").copy(orphaned = true)
        assertTrue(Anchors.relocate(a, "原文", "原文").orphaned)
    }
}
