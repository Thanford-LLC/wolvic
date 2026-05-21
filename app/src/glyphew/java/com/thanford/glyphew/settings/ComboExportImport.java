/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import com.igalia.wolvic.browser.BookmarksStore;
import com.igalia.wolvic.browser.engine.SessionStore;
import com.igalia.wolvic.input.ComboDispatcher;

import mozilla.components.concept.storage.BookmarkNode;
import mozilla.components.concept.storage.BookmarkNodeType;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Section 3.3 / 3.4 — Combined backup export/import (combos + bookmarks).
 *
 * <p><b>Envelope schema (v1):</b>
 * <pre>
 * {
 *   "schema_version": 1,
 *   "exported_at":    "2026-05-18T17:42:00Z",
 *   "exported_by":    { "app_version": "1.0", "package": "...", "skin": "signature", "mode": "4dir" },
 *   "combos":         { /* ComboBindingStore v1 blob *‌/ },
 *   "bookmarks":      [{"title": "...", "url": "..."}, ...]
 * }
 * </pre>
 *
 * <p>Files are written to {@code Downloads/Glyphew/} via MediaStore (API 29+), which
 * survives app uninstall. A fallback to app-external storage is used below API 29.
 */
public final class ComboExportImport {

    private static final String TAG = "GW/ExportImport";

    /** Envelope schema version — independent of ComboBindingStore version. */
    public static final int ENVELOPE_VERSION = 1;

    /** SharedPrefs key for the first-run import one-shot gate. */
    public static final String PREF_IMPORT_OFFERED = "gw_import_offered_v1";

    private static final String SKIN_V1       = "signature";
    private static final String EXPORT_FOLDER = "Glyphew";
    private static final String FILE_PREFIX   = "glyphew-backup-";
    private static final String FILE_EXT      = ".json";

    private ComboExportImport() {}

    // ── Export entry (for import picker) ────────────────────────────────────

    /** Represents one importable backup — either a MediaStore URI or a fallback file. */
    public static final class ExportEntry {
        /** MediaStore content URI, or null for fallback-file entries. */
        @Nullable public final Uri uri;
        /** Fallback java.io.File, or null for MediaStore entries. */
        @Nullable public final File file;
        /** Human-readable display name (filename without path). */
        @NonNull  public final String displayName;

        ExportEntry(@NonNull Uri uri, @NonNull String displayName) {
            this.uri = uri; this.file = null; this.displayName = displayName;
        }
        ExportEntry(@NonNull File file, @NonNull String displayName) {
            this.uri = null; this.file = file; this.displayName = displayName;
        }
    }

    // ── Export ───────────────────────────────────────────────────────────────

    /** Combo-count + bookmark-count from a parsed envelope. Used by the import picker. */
    public static final class EnvelopeCounts {
        public final int combos;
        public final int bookmarks;
        EnvelopeCounts(int combos, int bookmarks) {
            this.combos = combos; this.bookmarks = bookmarks;
        }
    }

    /**
     * Extracts importable combo count and bookmark count from an envelope.
     * A_GOTO_BOOKMARK bindings are included in the combo count — their param is a
     * portable URL, applied directly on import. Never throws.
     */
    @NonNull
    public static EnvelopeCounts countEnvelope(@NonNull JSONObject envelope) {
        int combos = 0, bookmarks = 0;
        try {
            JSONObject combosBlob = envelope.optJSONObject("combos");
            if (combosBlob != null) {
                JSONObject bindings = combosBlob.optJSONObject("bindings");
                if (bindings != null) {
                    Iterator<String> keys = bindings.keys();
                    while (keys.hasNext()) {
                        JSONObject entry = bindings.optJSONObject(keys.next());
                        if (entry == null) continue;
                        int action = entry.optInt("action", 0);
                        if (action != 0) combos++;
                    }
                }
            }
            JSONArray bkArr = envelope.optJSONArray("bookmarks");
            if (bkArr != null) bookmarks = bkArr.length();
        } catch (Exception ignored) {}
        return new EnvelopeCounts(combos, bookmarks);
    }

    /** Called on the main thread when async export completes. */
    public interface ExportCallback {
        /**
         * @param displayName Filename of the written backup (null if file write failed).
         * @param clipboardOk True if JSON was copied to clipboard.
         */
        void onDone(@Nullable String displayName, boolean clipboardOk);
    }

    /**
     * Exports combos + bookmarks asynchronously.
     * Bookmarks are collected recursively from the Mobile root (includes all
     * subfolders such as Combo Bookmarks). Writes to {@code Downloads/Glyphew/}
     * (API 29+) or app-external storage (API &lt; 29).
     * Always calls {@code onDone} on the main thread, regardless of outcome.
     */
    public static void exportAsync(@NonNull Context ctx,
                                   @NonNull ComboDispatcher dispatcher,
                                   @NonNull ExportCallback onDone) {
        Handler main = new Handler(Looper.getMainLooper());
        // Obtain the store reference on the main thread — SessionStore is main-thread-bound.
        // Combo bindings are exported verbatim from SharedPreferences regardless of store
        // availability; the store is only needed to enumerate bookmark entries for the
        // human-readable bookmarks[] array.
        SessionStore ss = SessionStore.get();
        BookmarksStore bkStore = (ss != null) ? ss.getBookmarkStore() : null;
        new Thread(() -> {
            JSONObject env = null; String name = null;
            try {
                JSONArray bkArr = new JSONArray();
                JSONArray comboArr = new JSONArray();
                if (bkStore != null) {
                    List<BookmarkNode> tree = getBookmarkTree(bkStore);
                    List<BookmarkNode> plain = new ArrayList<>();
                    flattenBookmarkItems(tree, plain);
                    List<BookmarkNode> combo = new ArrayList<>();
                    collectComboBookmarkItems(tree, combo);
                    bkArr    = buildBookmarkArray(plain);
                    comboArr = buildBookmarkArray(combo);
                }
                env  = buildEnvelope(ctx, bkArr, comboArr);
                name = writeBackup(ctx, env);
            } catch (Exception e) { Log.e(TAG, "Export failed", e); }
            final JSONObject envF = env; final String nameF = name;
            main.post(() -> {
                boolean clip = (envF != null) && copyToClipboard(ctx, envF);
                onDone.onDone(nameF, clip);
            });
        }).start();
    }

    /**
     * Collects every ITEM-type bookmark the user has, matching exactly what the Bookmark
     * Manager shows. Uses a single recursive {@code getTree(Root, true)} call (the same
     * mechanism as {@code BookmarksView.updateBookmarks}) and walks the resulting tree
     * in-memory — avoiding the fragile per-folder blocking re-queries that previously
     * returned nothing during export. Designed to be called from a background thread.
     */
    @NonNull
    private static List<BookmarkNode> collectAllBookmarks(@NonNull BookmarksStore store) {
        List<BookmarkNode> result = new ArrayList<>();
        flattenBookmarkItems(getBookmarkTree(store), result);
        Log.i(TAG, "collectAllBookmarks: " + result.size() + " items");
        return result;
    }

    /**
     * Fetches the full recursive bookmark tree (Root with all descendants) on the calling
     * thread, matching what the Bookmark Manager queries. Returns an empty list on failure.
     * Call from a background thread — {@code getTree(...).join()} blocks.
     */
    @NonNull
    private static List<BookmarkNode> getBookmarkTree(@NonNull BookmarksStore store) {
        try {
            List<BookmarkNode> tree =
                    store.getTree(mozilla.appservices.places.BookmarkRoot.Root.getId(), true).join();
            if (tree != null) return tree;
        } catch (Exception e) {
            Log.w(TAG, "getBookmarkTree failed: " + e.getMessage());
        }
        return new ArrayList<>();
    }

    /**
     * Recursively appends all ITEM nodes from {@code nodes} (walking FOLDER children) into
     * {@code out}. The Combo Bookmarks folder is skipped entirely: its entries are already
     * represented by their A_GOTO_BOOKMARK combo bindings, so exporting them as plain
     * bookmarks would double-count and, on import, create stray duplicates.
     */
    static void flattenBookmarkItems(@Nullable List<BookmarkNode> nodes,
                                     @NonNull List<BookmarkNode> out) {
        if (nodes == null) return;
        for (BookmarkNode node : nodes) {
            if (node.getType() == BookmarkNodeType.ITEM) {
                out.add(node);
            } else if (node.getType() == BookmarkNodeType.FOLDER) {
                if (BookmarksStore.COMBO_BOOKMARKS_TITLE.equals(node.getTitle())) continue;
                flattenBookmarkItems(node.getChildren(), out);
            }
        }
    }

    /**
     * Collects the ITEM children of the Combo Bookmarks folder into {@code out}. These carry
     * the title needed to recreate the Library entry on import; their combo behaviour is
     * restored separately via the A_GOTO_BOOKMARK bindings in the combos blob.
     */
    static void collectComboBookmarkItems(@Nullable List<BookmarkNode> nodes,
                                          @NonNull List<BookmarkNode> out) {
        if (nodes == null) return;
        for (BookmarkNode node : nodes) {
            if (node.getType() != BookmarkNodeType.FOLDER) continue;
            if (BookmarksStore.COMBO_BOOKMARKS_TITLE.equals(node.getTitle())) {
                List<BookmarkNode> children = node.getChildren();
                if (children == null) continue;
                for (BookmarkNode child : children) {
                    if (child.getType() == BookmarkNodeType.ITEM) out.add(child);
                }
            } else {
                collectComboBookmarkItems(node.getChildren(), out);
            }
        }
    }

    // ── Import — entry discovery ─────────────────────────────────────────────

    /**
     * Returns the last {@code maxCount} backup entries, newest first.
     * Uses MediaStore on API 29+, falls back to app-external files.
     */
    @NonNull
    public static List<ExportEntry> listExportEntries(@NonNull Context ctx, int maxCount) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return listEntriesMediaStore(ctx, maxCount);
        }
        return listEntriesFallback(ctx, maxCount);
    }

    /**
     * Deletes the backup file backing {@code entry}. Handles both MediaStore-URI entries
     * (API 29+) and fallback File entries. Returns true if a file was actually removed.
     * The backups live in shared Downloads, so this is the only way to remove them from
     * inside the app — uninstalling Glyphew does not.
     */
    public static boolean deleteEntry(@NonNull Context ctx, @NonNull ExportEntry entry) {
        try {
            if (entry.uri != null) {
                return ctx.getContentResolver().delete(entry.uri, null, null) > 0;
            }
            if (entry.file != null) {
                return entry.file.delete();
            }
        } catch (Exception e) {
            Log.w(TAG, "deleteEntry failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * Returns a valid envelope from the clipboard, or null if none is present.
     */
    @Nullable
    public static JSONObject tryReadClipboardEnvelope(@NonNull Context ctx) {
        ClipboardManager cm = (ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null || !cm.hasPrimaryClip()) return null;
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return null;
        CharSequence text = clip.getItemAt(0).getText();
        if (text == null) return null;
        String s = text.toString().trim();
        if (!s.startsWith("{\"schema_version\":")) return null;
        try {
            JSONObject env = new JSONObject(s);
            if (validateEnvelope(ctx, env) != null) return null;
            return env;
        } catch (JSONException e) {
            return null;
        }
    }

    /**
     * Reads the JSON envelope from an {@link ExportEntry}. Returns null on failure.
     */
    @Nullable
    public static JSONObject readEnvelopeFromEntry(@NonNull Context ctx,
                                                   @NonNull ExportEntry entry) {
        if (entry.uri != null) {
            return readEnvelopeFromUri(ctx, entry.uri);
        }
        if (entry.file != null) {
            return readEnvelopeFromFile(entry.file);
        }
        return null;
    }

    // ── Import — validation & apply ──────────────────────────────────────────

    /**
     * Validates the structural integrity of the envelope (schema version + required fields).
     * Conflict checks are NOT performed here — conflicting bindings are silently skipped
     * during {@link #applyEnvelope}.
     *
     * @return null on success, or an error key on failure.
     */
    @Nullable
    public static String validateEnvelope(@NonNull Context ctx,
                                          @NonNull JSONObject envelope) {
        int version;
        try {
            version = envelope.getInt("schema_version");
        } catch (JSONException e) {
            return "missing schema_version";
        }
        if (version != ENVELOPE_VERSION) return "unsupported_version";
        if (envelope.optJSONObject("combos") == null) return "missing combos blob";
        return null;
    }

    /**
     * Applies the combos portion of a validated envelope (synchronous).
     *
     * <p>A_GOTO_BOOKMARK bindings are applied directly — their {@code param} is the bookmark
     * URL, which is portable across devices. Skipped silently: any path that already has a
     * different custom binding on the destination (the user's existing customisations win).
     *
     * @return true on success.
     */
    public static boolean applyEnvelope(@NonNull Context ctx,
                                        @NonNull JSONObject envelope) {
        JSONObject combosBlob = envelope.optJSONObject("combos");
        if (combosBlob == null) return false;

        Map<String, Binding> imported;
        try {
            imported = parseBindingsBlob(combosBlob);
        } catch (JSONException e) {
            Log.e(TAG, "applyEnvelope: parse failed", e);
            return false;
        }

        // Skip any path that conflicts with an existing custom binding on the
        // destination — preserves the user's prior customisations.
        Map<String, Binding> currentOverrides = new ComboBindingStore(ctx).load();
        imported.entrySet().removeIf(e -> {
            Binding dest = currentOverrides.get(e.getKey());
            return dest != null
                    && dest.action != e.getValue().action
                    && dest.action != ComboDispatcher.A_REMOVED;
        });

        Map<String, Binding> merged = new LinkedHashMap<>(currentOverrides);
        merged.putAll(imported);
        return new ComboBindingStore(ctx).save(merged);
    }

    /**
     * Imports bookmark entries from a validated envelope asynchronously, skipping any URL
     * that already exists in the store so re-importing the same backup is idempotent.
     * Calls {@code onDone(null)} on success or {@code onDone(errorMsg)} on failure.
     * An empty or absent bookmarks array is treated as success.
     */
    public static void importBookmarksAsync(@NonNull Context ctx,
                                            @NonNull JSONObject envelope,
                                            @NonNull Consumer<String> onDone) {
        JSONArray arr = envelope.optJSONArray("bookmarks");
        if (arr == null || arr.length() == 0) {
            new Handler(Looper.getMainLooper()).post(() -> onDone.accept(null));
            return;
        }
        BookmarksStore store = SessionStore.get().getBookmarkStore();
        if (store == null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    onDone.accept("Bookmarks store not ready"));
            return;
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            // Build the set of URLs already present, then import only the new ones.
            java.util.Set<String> existing = new java.util.HashSet<>();
            for (BookmarkNode node : collectAllBookmarks(store)) {
                if (node.getUrl() != null) existing.add(node.getUrl());
            }
            JSONArray toAdd = filterNewBookmarks(arr, existing);
            for (int i = 0; i < toAdd.length(); i++) {
                JSONObject obj = toAdd.optJSONObject(i);
                String url   = obj.optString("url",   null);
                String title = obj.optString("title", "");
                try {
                    store.addBookmark(url, title).join();
                } catch (Exception e) {
                    Log.w(TAG, "Skipping bookmark: " + url + " — " + e.getMessage());
                }
            }
            main.post(() -> onDone.accept(null));
        }).start();
    }

    /**
     * Recreates Library entries in the Combo Bookmarks folder for the envelope's
     * {@code combo_bookmarks} array, so an imported combo bookmark is visible in the Bookmark
     * Manager (not just bound). Restores only entries whose A_GOTO_BOOKMARK binding actually
     * took effect (see {@link #filterComboBookmarksToRestore}) and skips ones already present.
     * Must run after {@link #applyEnvelope}. Calls {@code onDone(null)} on the main thread.
     */
    public static void importComboBookmarksAsync(@NonNull Context ctx,
                                                 @NonNull JSONObject envelope,
                                                 @NonNull Consumer<String> onDone) {
        JSONArray arr = envelope.optJSONArray("combo_bookmarks");
        if (arr == null || arr.length() == 0) {
            new Handler(Looper.getMainLooper()).post(() -> onDone.accept(null));
            return;
        }
        BookmarksStore store = SessionStore.get().getBookmarkStore();
        if (store == null) {
            new Handler(Looper.getMainLooper()).post(() ->
                    onDone.accept("Bookmarks store not ready"));
            return;
        }
        // URLs that actually have an applied A_GOTO_BOOKMARK binding — gathered on the main
        // thread from the now-merged binding store.
        java.util.Set<String> boundUrls = new java.util.HashSet<>();
        for (Binding b : new ComboBindingStore(ctx).load().values()) {
            if (b.action == ComboDispatcher.A_GOTO_BOOKMARK && b.param != null) {
                boundUrls.add(b.param);
            }
        }
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            java.util.Set<String> existing = new java.util.HashSet<>();
            List<BookmarkNode> comboItems = new ArrayList<>();
            collectComboBookmarkItems(getBookmarkTree(store), comboItems);
            for (BookmarkNode n : comboItems) {
                if (n.getUrl() != null) existing.add(n.getUrl());
            }
            JSONArray toAdd = filterComboBookmarksToRestore(arr, boundUrls, existing);
            if (toAdd.length() > 0) {
                try {
                    String folderGuid = store.ensureComboBookmarksFolder().join();
                    for (int i = 0; i < toAdd.length(); i++) {
                        JSONObject obj = toAdd.optJSONObject(i);
                        if (obj == null) continue;
                        String url   = obj.optString("url", null);
                        String title = obj.optString("title", "");
                        if (url == null || url.isEmpty()) continue;
                        store.addBookmarkReturningGuid(folderGuid, url, title).join();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "importComboBookmarks failed: " + e.getMessage());
                }
            }
            main.post(() -> onDone.accept(null));
        }).start();
    }

    // ── Private — build helpers ──────────────────────────────────────────────

    /**
     * Builds a complete export envelope from the current binding store and pre-collected
     * bookmark data. Combo bindings are emitted verbatim — A_GOTO_BOOKMARK stores the
     * bookmark URL directly (portable across devices), so no GUID→URL transform is needed.
     *
     * <p>Package-private so unit tests can exercise this directly without a live
     * BookmarksStore or ComboDispatcher.
     */
    @NonNull
    static JSONObject buildEnvelope(@NonNull Context ctx,
                                    @NonNull JSONArray bookmarks,
                                    @NonNull JSONArray comboBookmarks)
            throws JSONException {
        JSONObject meta = new JSONObject();
        meta.put("app_version",   getVersionName(ctx));
        meta.put("package",       ctx.getPackageName());
        meta.put("skin",          SKIN_V1);
        meta.put("mode",          is4DirMode(ctx) ? "4dir" : "8dir");
        meta.put("account_email", JSONObject.NULL);

        JSONObject envelope = new JSONObject();
        envelope.put("schema_version",  ENVELOPE_VERSION);
        envelope.put("exported_at",     isoNow());
        envelope.put("exported_by",     meta);
        envelope.put("combos",          readRawBlob(ctx));
        envelope.put("bookmarks",       bookmarks);
        envelope.put("combo_bookmarks", comboBookmarks);
        return envelope;
    }

    /**
     * Filters {@code bookmarks} down to entries whose URL is not already in
     * {@code existingUrls}. Used to make re-import idempotent — clicking import
     * multiple times must not create duplicate bookmark entries. Package-private for testing.
     */
    @NonNull
    static JSONArray filterNewBookmarks(@NonNull JSONArray bookmarks,
                                        @NonNull java.util.Set<String> existingUrls) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < bookmarks.length(); i++) {
            JSONObject obj = bookmarks.optJSONObject(i);
            if (obj == null) continue;
            String url = obj.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            if (existingUrls.contains(url)) continue;
            result.put(obj);
        }
        return result;
    }

    /**
     * Selects which {@code combo_bookmarks} entries to recreate as Library entries on import.
     * An entry is restored only when (a) its URL has an A_GOTO_BOOKMARK binding that actually
     * took effect on this device ({@code boundUrls}) — otherwise the entry would be orphaned —
     * and (b) it is not already in the Combo Bookmarks folder ({@code existingUrls}), keeping
     * re-import idempotent. Package-private for testing.
     */
    @NonNull
    static JSONArray filterComboBookmarksToRestore(@NonNull JSONArray comboBookmarks,
                                                   @NonNull java.util.Set<String> boundUrls,
                                                   @NonNull java.util.Set<String> existingUrls) {
        JSONArray result = new JSONArray();
        for (int i = 0; i < comboBookmarks.length(); i++) {
            JSONObject obj = comboBookmarks.optJSONObject(i);
            if (obj == null) continue;
            String url = obj.optString("url", null);
            if (url == null || url.isEmpty()) continue;
            if (!boundUrls.contains(url)) continue;
            if (existingUrls.contains(url)) continue;
            result.put(obj);
        }
        return result;
    }

    @NonNull
    static JSONArray buildBookmarkArray(@Nullable List<BookmarkNode> nodes)
            throws JSONException {
        JSONArray arr = new JSONArray();
        if (nodes == null) return arr;
        for (BookmarkNode node : nodes) {
            if (node.getType() != BookmarkNodeType.ITEM) continue;
            String url = node.getUrl();
            if (url == null || url.isEmpty()) continue;
            JSONObject obj = new JSONObject();
            obj.put("title", node.getTitle() != null ? node.getTitle() : "");
            obj.put("url",   url);
            arr.put(obj);
        }
        return arr;
    }

    // ── Private — write ──────────────────────────────────────────────────────

    /**
     * Writes the envelope to storage; uses MediaStore on API 29+, fallback otherwise.
     *
     * @return display filename on success; {@code ""} if content matches the last backup
     *         (no new file written); {@code null} on write failure.
     */
    @Nullable
    private static String writeBackup(@NonNull Context ctx, @NonNull JSONObject envelope) {
        // Dedup: skip write if combos + bookmarks are identical to the most recent backup.
        List<ExportEntry> recent = listExportEntries(ctx, 1);
        if (!recent.isEmpty()) {
            JSONObject prev = readEnvelopeFromEntry(ctx, recent.get(0));
            if (prev != null && isSameContent(prev, envelope)) {
                Log.i(TAG, "Export content unchanged — skipping write");
                return ""; // signal "already up to date"
            }
        }

        String ts = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date());
        String fileName = FILE_PREFIX + ts + FILE_EXT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return writeToMediaStore(ctx, envelope, fileName) ? fileName : null;
        }
        return writeToFallback(ctx, envelope, fileName) ? fileName : null;
    }

    /** True if the "combos" and "bookmarks" payload is identical in both envelopes. */
    private static boolean isSameContent(@NonNull JSONObject prev, @NonNull JSONObject curr) {
        try {
            Object prevCombos = prev.opt("combos");
            Object currCombos = curr.opt("combos");
            if (!objectsEqual(prevCombos, currCombos)) return false;
            Object prevBk = prev.opt("bookmarks");
            Object currBk  = curr.opt("bookmarks");
            return objectsEqual(prevBk, currBk);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean objectsEqual(@Nullable Object a, @Nullable Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.toString().equals(b.toString());
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private static boolean writeToMediaStore(@NonNull Context ctx,
                                             @NonNull JSONObject envelope,
                                             @NonNull String fileName) {
        String relativePath = Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_FOLDER + "/";
        ContentValues cv = new ContentValues();
        cv.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        cv.put(MediaStore.Downloads.MIME_TYPE, "application/json");
        cv.put(MediaStore.Downloads.RELATIVE_PATH, relativePath);

        Uri uri = ctx.getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
        if (uri == null) {
            Log.e(TAG, "MediaStore insert returned null");
            return false;
        }
        try (OutputStream os = ctx.getContentResolver().openOutputStream(uri)) {
            if (os == null) { ctx.getContentResolver().delete(uri, null, null); return false; }
            os.write(envelope.toString(2).getBytes("UTF-8"));
            Log.i(TAG, "Exported to MediaStore: " + fileName);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "MediaStore write failed", e);
            ctx.getContentResolver().delete(uri, null, null);
            return false;
        }
    }

    private static boolean writeToFallback(@NonNull Context ctx,
                                           @NonNull JSONObject envelope,
                                           @NonNull String fileName) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File folder = new File(base, EXPORT_FOLDER);
        if (!folder.exists() && !folder.mkdirs()) { Log.e(TAG, "mkdir failed: " + folder); return false; }
        File out = new File(folder, fileName);
        try (FileWriter fw = new FileWriter(out)) {
            fw.write(envelope.toString(2));
            Log.i(TAG, "Exported (fallback) to " + out);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Fallback write failed", e);
            return false;
        }
    }

    private static boolean copyToClipboard(@NonNull Context ctx, @NonNull JSONObject envelope) {
        ClipboardManager cm = (ClipboardManager)
                ctx.getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) return false;
        try {
            cm.setPrimaryClip(ClipData.newPlainText("Glyphew backup", envelope.toString()));
            return true;
        } catch (Exception e) {
            Log.w(TAG, "Clipboard copy failed", e);
            return false;
        }
    }

    // ── Private — list entries ───────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.Q)
    @NonNull
    private static List<ExportEntry> listEntriesMediaStore(@NonNull Context ctx, int maxCount) {
        String[] proj = {
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_MODIFIED
        };
        String sel  = MediaStore.Downloads.DISPLAY_NAME + " LIKE ?";
        String[] args = { FILE_PREFIX + "%" };
        String sort = MediaStore.Downloads.DATE_MODIFIED + " DESC";

        List<ExportEntry> result = new ArrayList<>();
        try (Cursor cur = ctx.getContentResolver().query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, sel, args, sort)) {
            if (cur == null) return result;
            int idCol   = cur.getColumnIndexOrThrow(MediaStore.Downloads._ID);
            int nameCol = cur.getColumnIndexOrThrow(MediaStore.Downloads.DISPLAY_NAME);
            while (cur.moveToNext() && result.size() < maxCount) {
                long id   = cur.getLong(idCol);
                String nm = cur.getString(nameCol);
                if (nm == null || !nm.endsWith(FILE_EXT)) continue;
                Uri uri = ContentUris.withAppendedId(
                        MediaStore.Downloads.EXTERNAL_CONTENT_URI, id);
                result.add(new ExportEntry(uri, nm));
            }
        } catch (Exception e) {
            Log.e(TAG, "MediaStore query failed", e);
        }
        return result;
    }

    @NonNull
    private static List<ExportEntry> listEntriesFallback(@NonNull Context ctx, int maxCount) {
        File base = ctx.getExternalFilesDir(null);
        if (base == null) base = ctx.getFilesDir();
        File folder = new File(base, EXPORT_FOLDER);
        List<ExportEntry> result = new ArrayList<>();
        if (!folder.isDirectory()) return result;
        File[] files = folder.listFiles(
                f -> f.isFile() && f.getName().startsWith(FILE_PREFIX)
                        && f.getName().endsWith(FILE_EXT));
        if (files == null) return result;
        Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = 0; i < Math.min(maxCount, files.length); i++) {
            result.add(new ExportEntry(files[i], files[i].getName()));
        }
        return result;
    }

    // ── Private — read ───────────────────────────────────────────────────────

    @Nullable
    private static JSONObject readEnvelopeFromUri(@NonNull Context ctx, @NonNull Uri uri) {
        try (InputStream is = ctx.getContentResolver().openInputStream(uri)) {
            if (is == null) return null;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return new JSONObject(baos.toString("UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "Cannot read envelope from URI " + uri, e);
            return null;
        }
    }

    @Nullable
    private static JSONObject readEnvelopeFromFile(@NonNull File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            try (java.io.FileInputStream fis = new java.io.FileInputStream(file)) {
                //noinspection ResultOfMethodCallIgnored
                fis.read(bytes);
            }
            return new JSONObject(new String(bytes, "UTF-8"));
        } catch (Exception e) {
            Log.e(TAG, "Cannot read envelope from " + file, e);
            return null;
        }
    }

    // ── Private — misc helpers ───────────────────────────────────────────────

    @NonNull
    private static JSONObject readRawBlob(@NonNull Context ctx) throws JSONException {
        android.content.SharedPreferences prefs = ctx.getSharedPreferences(
                ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE);
        String blob = prefs.getString(ComboBindingStore.KEY_BLOB, null);
        if (blob != null && !blob.isEmpty()) return new JSONObject(blob);
        JSONObject empty = new JSONObject();
        empty.put("version",  ComboBindingStore.SCHEMA_VERSION);
        empty.put("bindings", new JSONObject());
        return empty;
    }

    @NonNull
    private static Map<String, Binding> parseBindingsBlob(@NonNull JSONObject root)
            throws JSONException {
        Map<String, Binding> out = new LinkedHashMap<>();
        JSONObject bindings = root.optJSONObject("bindings");
        if (bindings == null) return out;
        Iterator<String> keys = bindings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.isEmpty()) continue;
            JSONObject entry = bindings.optJSONObject(key);
            if (entry == null) continue;
            int action = entry.optInt("action", 0);
            if (action == 0) continue;
            String param = entry.isNull("param") ? null : entry.optString("param", null);
            out.put(key, new Binding(action, param));
        }
        return out;
    }

    @NonNull
    private static String isoNow() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(new Date());
    }

    @NonNull
    private static String getVersionName(@NonNull Context ctx) {
        try {
            return ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0).versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "unknown";
        }
    }

    private static boolean is4DirMode(@NonNull Context ctx) {
        return android.preference.PreferenceManager
                .getDefaultSharedPreferences(ctx)
                .getBoolean(ComboDispatcher.COMBO_MODE_4DIR_KEY, true);
    }
}
