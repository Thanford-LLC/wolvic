/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings

import com.igalia.wolvic.TestApplication
import com.igalia.wolvic.browser.BookmarksStore
import mozilla.components.concept.storage.BookmarkNode
import mozilla.components.concept.storage.BookmarkNodeType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Regression for the import-summary bookmark count + combo bookmark round-trip.
 *
 * The export must split bookmarks two ways:
 *  - plain bookmarks[] = every ITEM the user has EXCEPT those in the Combo Bookmarks
 *    folder. The homepage (about://home) is a legitimate user bookmark and is included.
 *  - combo_bookmarks[] = the ITEMs inside the Combo Bookmarks folder. These are already
 *    represented by their A_GOTO_BOOKMARK combo bindings, so they must NOT inflate the
 *    plain count, but they carry the title needed to recreate the Library entry on import.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class)
class ComboBookmarkExportFilterTest {

    private fun item(title: String, url: String) =
        BookmarkNode(BookmarkNodeType.ITEM, "guid-$url", "mobile", null, title, url, 0L, 0L, null)

    private fun folder(title: String, children: List<BookmarkNode>) =
        BookmarkNode(BookmarkNodeType.FOLDER, "folder-$title", "mobile", null, title, null, 0L, 0L, children)

    // Mirrors the user's device state: 1 combo bookmark + 1 about://home + 1 plain bookmark.
    private fun deviceTree() = listOf(
        folder(
            BookmarksStore.COMBO_BOOKMARKS_TITLE,
            listOf(item("Startpage", "https://www.startpage.com/"))
        ),
        item("Glyphew", "about://home"),
        item("Prime Video", "https://www.primevideo.com/x")
    )

    @Test
    fun plainBookmarks_includeHomepage_excludeComboBookmark() {
        val flat = ArrayList<BookmarkNode>()
        ComboExportImport.flattenBookmarkItems(deviceTree(), flat)
        val arr = ComboExportImport.buildBookmarkArray(flat)

        val urls = (0 until arr.length()).map { arr.getJSONObject(it).getString("url") }
        assertEquals("plain = homepage + Prime Video, not the combo bookmark", 2, arr.length())
        assertTrue(urls.contains("about://home"))
        assertTrue(urls.contains("https://www.primevideo.com/x"))
        assertFalse(urls.contains("https://www.startpage.com/"))
    }

    @Test
    fun comboBookmarks_collectOnlyComboFolderItems() {
        val flat = ArrayList<BookmarkNode>()
        ComboExportImport.collectComboBookmarkItems(deviceTree(), flat)
        val arr = ComboExportImport.buildBookmarkArray(flat)

        assertEquals("only the combo bookmark should be collected", 1, arr.length())
        assertEquals("https://www.startpage.com/", arr.getJSONObject(0).getString("url"))
        assertEquals("Startpage", arr.getJSONObject(0).getString("title"))
    }
}
