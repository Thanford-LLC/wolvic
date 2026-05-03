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
public class HomeBridge {

    private static final Handler mHandler = new Handler(Looper.getMainLooper());

    private volatile Session         mSession;
    private final Context            mContext;
    private final HomePrefs          mPrefs;
    private final BrowserIconsHelper mIcons;

    public HomeBridge(@NonNull Context context, @NonNull Session session,
                      @NonNull HomePrefs prefs, @NonNull BrowserIconsHelper icons) {
        mContext = context;
        mSession = session;
        mPrefs   = prefs;
        mIcons   = icons;
    }

    /** Called when navigating away from the homepage. Nulls the Session ref. */
    public void detach() {
        mSession = null;
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

    private void resolveOnUiThread(@NonNull String requestId, @NonNull String json) {
        final String escaped = json.replace("\\", "\\\\").replace("'", "\\'");
        final String script = "window.gwHome._resolve('" + requestId + "','" + escaped + "')";
        mHandler.post(() -> {
            final Session s = mSession;
            if (s != null) s.evaluateJavaScript(script);
        });
    }

    // ── Serialization ──────────────────────────────────────────────────────

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
