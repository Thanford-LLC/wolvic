/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.igalia.wolvic.browser;

import static org.junit.Assert.assertNotNull;

import com.igalia.wolvic.TestApplication;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Contract tests for Phase 8b additions to BookmarksStore.
 *
 * <p>BookmarksStore couples to VRBrowserApplication → PlacesBookmarksStorage (SQLite),
 * so runtime behavior is verified on-device (plan §9 step 1). These tests verify
 * the method signatures exist and return CompletableFuture so callers can compose them.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class BookmarksStoreComboTest {

    @Test
    public void bookmarksStore_hasEnsureComboBookmarksFolderMethod() throws NoSuchMethodException {
        Method m = BookmarksStore.class.getMethod("ensureComboBookmarksFolder");
        assertNotNull("ensureComboBookmarksFolder() must exist on BookmarksStore", m);
        // Return type must be CompletableFuture so callers can chain on it
        assert CompletableFuture.class.isAssignableFrom(m.getReturnType())
                : "ensureComboBookmarksFolder() must return CompletableFuture";
    }

    @Test
    public void bookmarksStore_hasAddBookmarkReturningGuidMethod() throws NoSuchMethodException {
        Method m = BookmarksStore.class.getMethod(
                "addBookmarkReturningGuid", String.class, String.class, String.class);
        assertNotNull("addBookmarkReturningGuid(parent, url, title) must exist on BookmarksStore", m);
        assert CompletableFuture.class.isAssignableFrom(m.getReturnType())
                : "addBookmarkReturningGuid() must return CompletableFuture";
    }

    @Test
    public void bookmarksStore_hasGetBookmarkByGuidMethod() throws NoSuchMethodException {
        Method m = BookmarksStore.class.getMethod("getBookmarkByGuid", String.class);
        assertNotNull("getBookmarkByGuid(guid) must exist on BookmarksStore", m);
        assert CompletableFuture.class.isAssignableFrom(m.getReturnType())
                : "getBookmarkByGuid() must return CompletableFuture";
    }
}
