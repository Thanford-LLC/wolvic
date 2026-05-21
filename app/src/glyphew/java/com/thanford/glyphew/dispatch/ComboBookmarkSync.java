/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.dispatch;

import android.content.Context;

import androidx.annotation.NonNull;

import com.igalia.wolvic.browser.BookmarksStore;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.input.ComboDispatcher;
import com.thanford.glyphew.settings.Binding;
import com.thanford.glyphew.settings.ComboBindingStore;

import java.util.Map;

/**
 * Phase 8b — forward deletion cascade for Combo Bookmarks.
 *
 * <p>Listens for {@link BookmarksStore.BookmarkListener#onBookmarksUpdated()} and
 * reconciles every {@link ComboDispatcher#A_GOTO_BOOKMARK} binding against the
 * live bookmark store. Any binding whose bookmark URL is no longer bookmarked is purged
 * (the user deleted the bookmark directly in Bookmark Manager).
 *
 * <p>Register in {@code VRBrowserActivity.onCreate} after ComboDispatcher boots.
 * The BookmarkListener callbacks are already posted on the main thread
 * (BookmarksStore.kt:197-217), so direct SharedPreferences writes are safe.
 */
public final class ComboBookmarkSync implements BookmarksStore.BookmarkListener {

    private final Context mAppContext;
    private final ComboDispatcher mDispatcher;

    public ComboBookmarkSync(@NonNull Context appContext,
                             @NonNull ComboDispatcher dispatcher) {
        mAppContext = appContext;
        mDispatcher = dispatcher;
    }

    /** Call from VRBrowserActivity.onCreate to wire the listener. */
    public void register() {
        final SessionStore ss = SessionStore.get();
        if (ss == null) return;
        ss.getBookmarkStore().addListener(this);
    }

    /** Call from VRBrowserActivity.onDestroy to prevent leaks. */
    public void unregister() {
        final SessionStore ss = SessionStore.get();
        if (ss == null) return;
        ss.getBookmarkStore().removeListener(this);
    }

    @Override
    public void onBookmarksUpdated() {
        reconcile();
    }

    @Override
    public void onBookmarkAdded() {
        // Addition never orphans a binding — no action needed.
    }

    private void reconcile() {
        final SessionStore ss = SessionStore.get();
        if (ss == null) return;
        final BookmarksStore store = ss.getBookmarkStore();
        if (store == null) return;

        final Map<String, Binding> bindings = mDispatcher.getAllBindings();
        for (Map.Entry<String, Binding> entry : bindings.entrySet()) {
            final Binding b = entry.getValue();
            if (b.action != ComboDispatcher.A_GOTO_BOOKMARK || b.param == null) continue;

            // param is the bookmark URL. If the URL is no longer bookmarked anywhere
            // (user deleted the Combo Bookmark in the Bookmark Manager), purge the binding.
            final String url = b.param;
            store.isBookmarked(url).thenAccept(exists -> {
                if (!exists) {
                    new ComboBindingStore(mAppContext).purgeBookmarkBindings(
                            ComboDispatcher.A_GOTO_BOOKMARK, url);
                    mDispatcher.reloadBindings();
                }
            });
        }
    }
}
