/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.igalia.wolvic.browser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;

import com.igalia.wolvic.BookmarksTestApplication;

import mozilla.components.concept.storage.BookmarkNode;
import mozilla.components.concept.storage.BookmarkNodeType;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.TimeUnit;

/**
 * Behavioral tests for Phase 8b additions to BookmarksStore.
 *
 * STATUS: All tests @Ignored — blocked by native library loading.
 *
 * Root cause: The Chromium build variant (oculusvrArm64ChromiumGenericDebug)
 * uses PlacesBookmarksStorage which links against libxul.so — GeckoView's
 * bundled native library. libxul.so is a multi-hundred-MB Android ARM64 .so
 * that cannot be loaded by a Linux x86-64 JVM unit test process. The
 * support-test-appservices dep provides linux-x86-64/libmegazord.so for
 * standalone appservices, but that is NOT the library this build variant uses.
 *
 * Resolution options:
 *   A. Run against oculusvrArm64GeckoGenericDebug — Gecko variant may have
 *      libxul.so available for host tests via GeckoView test infrastructure.
 *   B. Refactor BookmarksStore to accept a PlacesBookmarksStorage via
 *      constructor injection, enabling Mockito stubbing in tests without JNA.
 *   C. Verify on-device (original plan §9 step 1 — Quest 3 behavioral tests).
 *
 * The BookmarksTestApplication harness is retained — it correctly stubs
 * Services (no FxaAccountManager needed) and proves the Application cast works.
 * Re-enable these tests when one of the above approaches is adopted.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = BookmarksTestApplication.class)
public class BookmarksStoreComboBehaviorTest {

    private static final long TIMEOUT_SEC = 5;
    // "mobile" is the well-known ID for BookmarkRoot.Mobile — using the string literal
    // avoids calling into a Kotlin private field from Java.
    private static final String MOBILE_ROOT = "mobile";

    private BookmarksStore store;

    @Before
    public void setUp() {
        BookmarksTestApplication app = ApplicationProvider.getApplicationContext();
        store = new BookmarksStore(app);
    }

    // ── Folder creation ───────────────────────────────────────────────────────

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void ensureComboBookmarksFolder_returnsNonEmptyGuid() throws Exception {
        String guid = store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        assertNotNull("ensureComboBookmarksFolder() must return a non-null GUID", guid);
        assertFalse("ensureComboBookmarksFolder() must return a non-empty GUID", guid.isEmpty());
    }

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void ensureComboBookmarksFolder_isIdempotent() throws Exception {
        String guid1 = store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        String guid2 = store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        assertNotNull("first call must return a GUID", guid1);
        assertEquals("second call must return the same GUID (idempotent — no duplicate folder)",
                guid1, guid2);
    }

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void ensureComboBookmarksFolder_folderAppearsInMobileRoot() throws Exception {
        store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);

        java.util.List<BookmarkNode> mobileChildren =
                store.getTree(MOBILE_ROOT, false).get(TIMEOUT_SEC, TimeUnit.SECONDS);

        assertNotNull("Mobile root must have children after folder creation", mobileChildren);
        boolean found = mobileChildren.stream()
                .anyMatch(n -> BookmarksStore.COMBO_BOOKMARKS_TITLE.equals(n.getTitle())
                        && n.getType() == BookmarkNodeType.FOLDER);
        assertTrue("A folder named '" + BookmarksStore.COMBO_BOOKMARKS_TITLE +
                "' must appear in Mobile root", found);
    }

    // ── Bookmark add + retrieve ───────────────────────────────────────────────

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void addBookmarkReturningGuid_returnsNonEmptyGuid_distinctFromFolder() throws Exception {
        String folderGuid = store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);

        String bookmarkGuid = store.addBookmarkReturningGuid(
                folderGuid,
                "https://example.com/add-guid-test",
                "Add GUID Test"
        ).get(TIMEOUT_SEC, TimeUnit.SECONDS);

        assertNotNull("addBookmarkReturningGuid must return a non-null GUID", bookmarkGuid);
        assertFalse("addBookmarkReturningGuid must return a non-empty GUID", bookmarkGuid.isEmpty());
        assertNotEquals("bookmark GUID must differ from folder GUID", folderGuid, bookmarkGuid);
    }

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void getBookmarkByGuid_returnsNodeWithCorrectFields() throws Exception {
        String folderGuid = store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);
        String url = "https://example.com/fields-test";
        String title = "Fields Test";

        String bookmarkGuid = store.addBookmarkReturningGuid(folderGuid, url, title)
                .get(TIMEOUT_SEC, TimeUnit.SECONDS);

        BookmarkNode node = store.getBookmarkByGuid(bookmarkGuid).get(TIMEOUT_SEC, TimeUnit.SECONDS);

        assertNotNull("getBookmarkByGuid must return the added bookmark", node);
        assertEquals("url must match", url, node.getUrl());
        assertEquals("title must match", title, node.getTitle());
        assertEquals("parentGuid must be the combo folder", folderGuid, node.getParentGuid());
        assertEquals("type must be ITEM", BookmarkNodeType.ITEM, node.getType());
    }

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void getBookmarkByGuid_returnsNull_forUnknownGuid() throws Exception {
        BookmarkNode node = store.getBookmarkByGuid("non-existent-guid-12345")
                .get(TIMEOUT_SEC, TimeUnit.SECONDS);
        assertNull("getBookmarkByGuid must return null for an unknown GUID", node);
    }

    @Ignore("blocked by libxul.so JNA — see class Javadoc for resolution options")
    @Test
    public void addBookmark_multipleEntries_allRetrievable() throws Exception {
        String folderGuid = store.ensureComboBookmarksFolder().get(TIMEOUT_SEC, TimeUnit.SECONDS);

        String[][] entries = {
            {"https://example.com/multi-1", "Multi Entry 1"},
            {"https://example.com/multi-2", "Multi Entry 2"},
            {"https://example.com/multi-3", "Multi Entry 3"},
        };

        String[] guids = new String[entries.length];
        for (int i = 0; i < entries.length; i++) {
            guids[i] = store.addBookmarkReturningGuid(folderGuid, entries[i][0], entries[i][1])
                    .get(TIMEOUT_SEC, TimeUnit.SECONDS);
            assertNotNull("GUID for entry " + i + " must not be null", guids[i]);
        }

        for (int i = 0; i < entries.length; i++) {
            BookmarkNode node = store.getBookmarkByGuid(guids[i]).get(TIMEOUT_SEC, TimeUnit.SECONDS);
            assertNotNull("entry " + i + " must be retrievable by GUID", node);
            assertEquals("url for entry " + i, entries[i][0], node.getUrl());
            assertEquals("title for entry " + i, entries[i][1], node.getTitle());
        }
    }
}
