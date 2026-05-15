/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.webkit.JavascriptInterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.palette.graphics.Palette;

import com.igalia.wolvic.browser.BookmarksStore;
import com.igalia.wolvic.browser.components.BrowserIconsHelper;
import com.igalia.wolvic.browser.engine.Session;

import mozilla.components.concept.storage.BookmarkNode;
import mozilla.components.concept.storage.BookmarkNodeType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;

/**
 * JS bridge exposed as window.gwHome on the Glyphew homepage.
 *
 * Async methods receive a requestId and resolve via
 * evaluateJavaScript("window.gwHome._resolve(id, json)") on the UI thread.
 *
 * Threading:
 *   @JavascriptInterface methods run on the JS-binder thread.
 *   All Session/WSession calls are marshalled via mHandler to the UI thread.
 */
public class HomeBridge implements BookmarksStore.BookmarkListener {

    private static final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile Session         mSession;
    private final Context            mContext;
    private final HomePrefs          mPrefs;
    private final BrowserIconsHelper mIcons;
    /** Non-null when a BookmarksStore is available; holds the listener registration. */
    private @Nullable BookmarksStore mRegisteredStore;

    /** Async bridge results keyed by requestId — JS polls via pollResult(). */
    private final java.util.concurrent.ConcurrentHashMap<String, String> mPendingResults =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** Set when bookmarks change; JS polls via checkRefreshPending(). */
    private final java.util.concurrent.atomic.AtomicBoolean mRefreshPending =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    public HomeBridge(@NonNull Context context, @NonNull Session session,
                      @NonNull HomePrefs prefs, @NonNull BrowserIconsHelper icons) {
        mContext = context;
        mSession = session;
        mPrefs   = prefs;
        mIcons   = icons;
        final BookmarksStore store = session.getBookmarksStore();
        if (store != null) {
            store.addListener(this);
            mRegisteredStore = store;
        }
    }

    /** Called when navigating away from the homepage. Nulls the Session ref. */
    public void detach() {
        mSession = null;
        if (mRegisteredStore != null) {
            mRegisteredStore.removeListener(this);
            mRegisteredStore = null;
        }
    }

    // ── BookmarksStore.BookmarkListener ─────────────────────────────────────

    @Override
    public void onBookmarksUpdated() { pushRefresh(); }

    @Override
    public void onBookmarkAdded() { pushRefresh(); }

    private void pushRefresh() {
        mRefreshPending.set(true);
    }

    // ── Polling bridge ─────────────────────────────────────────────────────

    /**
     * JS polls this (every ~20 ms) until the result for a given requestId arrives.
     * Returns the JSON string when ready, "" when still pending.
     * Called from the JS-binder thread — always safe regardless of renderer state.
     */
    @JavascriptInterface
    @NonNull
    public String pollResult(@NonNull String requestId) {
        String result = mPendingResults.remove(requestId);
        return result != null ? result : "";
    }

    /**
     * Returns true (and clears the flag) if bookmarks changed while the homepage was not
     * visible. JS polls this periodically to decide whether to re-run tryBridgeUpgrade().
     */
    @JavascriptInterface
    public boolean checkRefreshPending() {
        return mRefreshPending.getAndSet(false);
    }

    // ── Sync methods ───────────────────────────────────────────────────────

    @JavascriptInterface
    public void getCatalog(String requestId) {
        resolveOnUiThread(requestId, HomeCatalog.json());
    }

    @JavascriptInterface
    public void getPrefs(String requestId) {
        final JSONObject obj = new JSONObject();
        try {
            obj.put("hintSeen",     mPrefs.isHintSeen());
            obj.put("lastRowIndex", mPrefs.getLastRowIndex());
            obj.put("lastColIndex", mPrefs.getLastColIndex());
            obj.put("is4DirMode",   mPrefs.is4DirMode());
        } catch (JSONException ignored) {}
        resolveOnUiThread(requestId, obj.toString());
    }

    @JavascriptInterface
    public void markHintSeen() {
        mPrefs.markHintSeen();
    }

    @JavascriptInterface
    public void setCategoryOrder(@Nullable String jsonArray) {
        if (jsonArray != null) mPrefs.setCategoryOrder(jsonArray);
    }

    @JavascriptInterface
    public void setFolderOrder(@Nullable String jsonArray) {
        if (jsonArray != null) mPrefs.setFolderOrder(jsonArray);
    }

    @JavascriptInterface
    public void setLastPosition(@Nullable String rowStr, @Nullable String colStr) {
        try {
            int row = Integer.parseInt(rowStr != null ? rowStr : "0");
            int col = Integer.parseInt(colStr != null ? colStr : "0");
            mPrefs.setLastPosition(row, col);
        } catch (NumberFormatException ignored) {}
    }

    /**
     * Navigate to a URL. Only http(s) schemes accepted.
     * Validated and marshalled to the UI thread before calling Session.loadUri.
     */
    @JavascriptInterface
    public void openUrl(@Nullable String url) {
        if (url == null) return;
        final String trimmed = url.trim();
        final Uri uri;
        try { uri = Uri.parse(trimmed); } catch (Exception e) { return; }
        final String scheme = uri.getScheme();
        if (scheme == null) return;
        final String lower = scheme.toLowerCase(Locale.ROOT);
        if (!"http".equals(lower) && !"https".equals(lower)) return;
        mHandler.post(() -> {
            final Session s = mSession;
            if (s != null) s.loadUri(trimmed);
        });
    }

    // ── Async methods ──────────────────────────────────────────────────────

    /**
     * Returns two bookmark categories as a JSON array: Combo Bookmarks (if non-empty) then
     * Standard Bookmarks (if non-empty). Each category uses the same page structure as the
     * static catalog — an array of pages, each page up to 8 tile objects.
     *
     * <p>Replaces {@link #getFolders(String)} which is now removed from the public JS API.
     */
    @JavascriptInterface
    public void getBookmarkCategories(final String requestId) {
        final Session s = mSession;
        if (s == null) {
            android.util.Log.w("HomeBridge", "getBookmarkCategories: mSession is null");
            resolveOnUiThread(requestId, "[]"); return;
        }

        final BookmarksStore store = s.getBookmarksStore();
        if (store == null) {
            android.util.Log.w("HomeBridge", "getBookmarkCategories: getBookmarksStore() returned null");
            resolveOnUiThread(requestId, "[]"); return;
        }

        android.util.Log.d("HomeBridge", "getBookmarkCategories: starting chain");

        store.ensureComboBookmarksFolder().thenCompose(comboGuid -> {
            android.util.Log.d("HomeBridge", "getBookmarkCategories: comboGuid=" + comboGuid);

            // Fetch combo items and mobile root items in parallel, then merge.
            java.util.concurrent.CompletableFuture<java.util.List<BookmarkNode>> comboFuture =
                    store.getBookmarks(comboGuid)
                         .thenApply(nodes -> nodes != null ? nodes : java.util.Collections.<BookmarkNode>emptyList());

            java.util.concurrent.CompletableFuture<java.util.List<BookmarkNode>> mobileFuture =
                    store.getBookmarks(mozilla.appservices.places.BookmarkRoot.Mobile.getId())
                         .thenApply(nodes -> nodes != null ? nodes : java.util.Collections.<BookmarkNode>emptyList());

            return comboFuture.thenCombine(mobileFuture, (comboItems, mobileItems) -> {
                android.util.Log.d("HomeBridge", "getBookmarkCategories: comboItems=" + comboItems.size()
                        + " mobileItems=" + mobileItems.size());
                try {
                    JSONArray result = new JSONArray();

                    // Combo Bookmarks — only if non-empty
                    if (!comboItems.isEmpty()) {
                        JSONArray serialized = serializeBookmarkItems(comboItems);
                        result.put(buildBookmarkCategory(
                                "combo-bookmarks", BookmarksStore.COMBO_BOOKMARKS_TITLE,
                                serialized, true));
                    }

                    // Standard Bookmarks — Mobile root ITEMs only, excluding the combo subfolder
                    java.util.List<BookmarkNode> standard = new java.util.ArrayList<>();
                    for (BookmarkNode node : mobileItems) {
                        if (node == null) continue;
                        if (node.getType() != BookmarkNodeType.ITEM) continue;
                        standard.add(node);
                    }
                    if (!standard.isEmpty()) {
                        JSONArray serialized = serializeBookmarkItems(standard);
                        result.put(buildBookmarkCategory(
                                "bookmarks", "Bookmarks", serialized, false));
                    }

                    android.util.Log.d("HomeBridge", "getBookmarkCategories: result categories=" + result.length());
                    return result.toString();
                } catch (Exception e) {
                    android.util.Log.e("HomeBridge", "getBookmarkCategories: serialize failed", e);
                    return "[]";
                }
            });
        }).thenAccept(json -> {
            android.util.Log.d("HomeBridge", "getBookmarkCategories: resolving len=" + json.length());
            resolveOnUiThread(requestId, json);
        }).exceptionally(e -> {
            android.util.Log.e("HomeBridge", "getBookmarkCategories: chain failed", e);
            resolveOnUiThread(requestId, "[]");
            return null;
        });
    }

    @JavascriptInterface
    public void getFolders(final String requestId) {
        final Session s = mSession;
        if (s == null) { resolveOnUiThread(requestId, "[]"); return; }

        final BookmarksStore store = s.getBookmarksStore();
        if (store == null) { resolveOnUiThread(requestId, "[]"); return; }

        // getTree returns CompletableFuture<List<BookmarkNode>?> (Kotlin nullable)
        store.getTree(mozilla.appservices.places.BookmarkRoot.Mobile.getId(), true)
             .thenAccept(nodes -> {
                 final String json = serializeFolders(nodes);
                 resolveOnUiThread(requestId, json);
             })
             .exceptionally(e -> {
                 resolveOnUiThread(requestId, "[]");
                 return null;
             });
    }

    /**
     * Returns a base64-encoded PNG for a user-bookmark favicon (catalog icons are bundled).
     * Resolves with an empty string on failure — JS side must treat "" as "no icon".
     */
    @JavascriptInterface
    public void getFavicon(final String requestId, @Nullable final String url) {
        if (url == null || url.isEmpty()) { resolveOnUiThread(requestId, "\"\""); return; }
        mIcons.loadIconBitmap(url).thenAccept(bitmap -> {
            if (bitmap == null) { resolveOnUiThread(requestId, "\"\""); return; }
            try {
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
                String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
                resolveOnUiThread(requestId, "\"data:image/png;base64," + b64 + "\"");
            } catch (Exception e) {
                resolveOnUiThread(requestId, "\"\"");
            }
        }).exceptionally(e -> { resolveOnUiThread(requestId, "\"\""); return null; });
    }

    /**
     * Returns a base64-encoded PNG for a bundled catalog icon in glyphew/icons/.
     * Resolves synchronously (local asset read) — returns "" on any failure.
     */
    @JavascriptInterface
    @NonNull
    public String getIcon(@Nullable String relPath) {
        if (relPath == null || relPath.isEmpty()) return "";
        try {
            java.io.InputStream is = mContext.getAssets().open("glyphew/icons/" + relPath);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            is.close();
            String b64 = Base64.encodeToString(baos.toByteArray(), Base64.NO_WRAP);
            return "data:image/png;base64," + b64;
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * Returns the dominant brand color (as #RRGGBB hex string) for a user-bookmark favicon.
     * Resolves with "" on failure — JS side falls back to --text-muted @ 60% alpha.
     */
    @JavascriptInterface
    public void getBrandColor(final String requestId, @Nullable final String url) {
        if (url == null || url.isEmpty()) { resolveOnUiThread(requestId, "\"\""); return; }
        mIcons.loadIconBitmap(url).thenAccept(bitmap -> {
            if (bitmap == null) { resolveOnUiThread(requestId, "\"\""); return; }
            // thenAccept runs on a background thread — synchronous generate() is safe here.
            final Palette palette = Palette.from(bitmap).generate();
            final int color = palette.getDominantColor(0);
            if (color == 0) { resolveOnUiThread(requestId, "\"\""); return; }
            final String hex = String.format(Locale.ROOT, "\"#%06X\"", (0xFFFFFF & color));
            resolveOnUiThread(requestId, hex);
        }).exceptionally(e -> { resolveOnUiThread(requestId, "\"\""); return null; });
    }

    // ── Resolve helper ─────────────────────────────────────────────────────

    /** Stores the result for JS to pick up via pollResult(). Thread-safe; no evaluateJavaScript. */
    private void resolveOnUiThread(@NonNull String requestId, @NonNull String json) {
        mPendingResults.put(requestId, json);
    }

    // ── Serialization ──────────────────────────────────────────────────────

    /**
     * Builds a category JSON object for the homepage catalog from bookmark items.
     * Each page holds up to 8 tiles; items overflow into additional pages.
     *
     * <p>Package-visible so tests can exercise pagination without Android context.
     */
    @NonNull
    static JSONObject buildBookmarkCategory(
            @NonNull String id, @NonNull String title,
            @NonNull JSONArray items, boolean isCombo) throws JSONException {
        final int PAGE_SIZE = 8;
        final JSONObject cat = new JSONObject();
        cat.put("id",    id);
        cat.put("title", title);
        cat.put("icon",  "★");
        cat.put("kind",  isCombo ? "combo_bookmark" : "user");

        final JSONArray pages = new JSONArray();
        JSONArray currentPage = null;
        for (int i = 0; i < items.length(); i++) {
            if (currentPage == null || currentPage.length() >= PAGE_SIZE) {
                currentPage = new JSONArray();
                pages.put(currentPage);
            }
            currentPage.put(items.getJSONObject(i));
        }
        cat.put("pages", pages);
        return cat;
    }

    @NonNull
    private static JSONArray serializeBookmarkItems(@NonNull List<BookmarkNode> nodes)
            throws JSONException {
        final JSONArray result = new JSONArray();
        for (BookmarkNode node : nodes) {
            if (node == null) continue;
            final JSONObject tile = new JSONObject();
            final String url   = node.getUrl()   != null ? node.getUrl()   : "";
            final String titleStr = node.getTitle() != null ? node.getTitle() : "";
            tile.put("url",    url);
            tile.put("title",  titleStr);
            tile.put("guid",   node.getGuid() != null ? node.getGuid() : "");
            tile.put("name",   titleStr.isEmpty() ? url : titleStr);
            tile.put("domain", url.replaceFirst("^https?://", "").split("/")[0]);
            tile.put("icon",   JSONObject.NULL); // async favicon loaded by homepage.js
            tile.put("color",  JSONObject.NULL);
            tile.put("letter", titleStr.isEmpty() ? "B"
                    : String.valueOf(titleStr.charAt(0)).toUpperCase(Locale.ROOT));
            result.put(tile);
        }
        return result;
    }

    @NonNull
    private static String serializeFolders(@Nullable List<BookmarkNode> nodes) {
        final JSONArray result = new JSONArray();
        if (nodes == null) return result.toString();

        for (BookmarkNode node : nodes) {
            if (node == null) continue;
            if (node.getType() != BookmarkNodeType.FOLDER) continue;

            try {
                final JSONObject folder = new JSONObject();
                folder.put("guid",     node.getGuid() != null ? node.getGuid() : "");
                folder.put("title",    node.getTitle() != null ? node.getTitle() : "");
                folder.put("editable", true);

                final JSONArray items = new JSONArray();
                final List<BookmarkNode> children = node.getChildren();
                if (children != null) {
                    for (BookmarkNode child : children) {
                        if (child == null) continue;
                        if (child.getType() != BookmarkNodeType.ITEM) continue;
                        final JSONObject entry = new JSONObject();
                        // DOM injection in JS uses textContent — XSS risk is on the JS side
                        entry.put("url",   child.getUrl()   != null ? child.getUrl()   : "");
                        entry.put("title", child.getTitle() != null ? child.getTitle() : "");
                        items.put(entry);
                    }
                }
                folder.put("items", items);
                result.put(folder);
            } catch (JSONException e) {
                // Skip malformed node; continue with the rest
            }
        }
        return result.toString();
    }
}
