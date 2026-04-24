/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Persists user combo overrides to SharedPreferences as a single JSON blob.
 * Keys use a mode-prefixed CSV format: "4:2,4,6" (4-dir only), "8:2,4,6"
 * (8-dir only), or bare "2,4,6" (both modes — legacy / shared bindings).
 * Values are {action:int, param:string|null}.
 *
 * <p>Corrupt blobs are backed up to a timestamped key and defaults are returned
 * so the app always boots. Schema versioning is in place from v1 so future
 * breaking changes land a {@link #migrateIfNeeded} transform, not a silent drop.
 */
public final class ComboBindingStore {

    /** Binding applies to both 4-dir and 8-dir tables (legacy / default). */
    public static final int MODE_BOTH  = 0;
    /** Binding applies to the 4-dir table only. */
    public static final int MODE_4DIR  = 1;
    /** Binding applies to the 8-dir table only. */
    public static final int MODE_8DIR  = 2;

    private static final String TAG = "FD/Store";

    public static final String PREFS_FILE = "fingerdance_combos";
    public static final String KEY_BLOB = "bindings_blob";
    public static final String CORRUPT_PREFIX = "bindings_blob_corrupt_";

    // Phase 6: one-shot flag — first successful long-press hint has been shown.
    // Cleared only by clearing app data (matches pref file lifecycle).
    private static final String KEY_LONGPRESS_HINT_SEEN = "fd_onboard_settings_hint_seen";

    public static final int SCHEMA_VERSION = 1;

    private static final String JSON_VERSION = "version";
    private static final String JSON_BINDINGS = "bindings";
    private static final String JSON_ACTION = "action";
    private static final String JSON_PARAM = "param";

    @NonNull
    private final Context mAppContext;

    public ComboBindingStore(@NonNull Context appContext) {
        mAppContext = appContext.getApplicationContext();
    }

    @NonNull
    private SharedPreferences prefs() {
        return mAppContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
    }

    /**
     * Phase 6: returns true once the first-launch long-press onboarding hint has
     * been shown. One-shot — never flips back to false for the install lifetime
     * (matches the pref-file lifecycle; clearing app data re-arms the hint).
     */
    public boolean hasSeenLongPressHint() {
        return prefs().getBoolean(KEY_LONGPRESS_HINT_SEEN, false);
    }

    /**
     * Phase 6: mark the long-press onboarding hint as shown. Call immediately
     * after the hint UI is presented so a process kill mid-hint doesn't double-
     * show. Idempotent — safe to call repeatedly.
     */
    public void markLongPressHintSeen() {
        prefs().edit().putBoolean(KEY_LONGPRESS_HINT_SEEN, true).apply();
    }

    /**
     * Load user overrides. On missing blob, returns an empty map (caller falls
     * back to defaults). On parse failure, backs up the corrupt blob under a
     * timestamped key and returns empty. Never throws.
     */
    @NonNull
    public Map<String, Binding> load() {
        String blob = prefs().getString(KEY_BLOB, null);
        if (blob == null || blob.isEmpty()) {
            return new LinkedHashMap<>();
        }
        try {
            JSONObject root = new JSONObject(blob);
            int version = root.optInt(JSON_VERSION, 0);
            if (version > SCHEMA_VERSION) {
                Log.e(TAG, "schema v" + version + " > supported v" + SCHEMA_VERSION
                        + "; ignoring overrides");
                return new LinkedHashMap<>();
            }
            Map<String, Binding> migrated = migrateIfNeeded(version, root);
            if (migrated != null) {
                return migrated;
            }
            return parseBindings(root);
        } catch (JSONException e) {
            Log.w(TAG, "corrupt bindings blob, restoring defaults", e);
            backupCorruptBlob(blob);
            return new LinkedHashMap<>();
        }
    }

    /**
     * Persist the full in-memory override map. Replaces prior blob.
     * Returns true on successful commit.
     */
    public boolean save(@NonNull Map<String, Binding> overrides) {
        try {
            JSONObject root = new JSONObject();
            root.put(JSON_VERSION, SCHEMA_VERSION);
            JSONObject bindings = new JSONObject();
            for (Map.Entry<String, Binding> e : overrides.entrySet()) {
                if (e.getKey() == null || e.getKey().isEmpty() || e.getValue() == null) {
                    continue;
                }
                JSONObject entry = new JSONObject();
                entry.put(JSON_ACTION, e.getValue().action);
                if (e.getValue().param != null) {
                    entry.put(JSON_PARAM, e.getValue().param);
                }
                bindings.put(e.getKey(), entry);
            }
            root.put(JSON_BINDINGS, bindings);
            boolean ok = prefs().edit().putString(KEY_BLOB, root.toString()).commit();
            if (!ok) {
                Log.e(TAG, "prefs commit failed");
            }
            return ok;
        } catch (JSONException e) {
            Log.e(TAG, "failed to serialize overrides", e);
            return false;
        }
    }

    /**
     * Clear all user overrides. Used by Reset-to-defaults.
     */
    public boolean clearAll() {
        return prefs().edit().remove(KEY_BLOB).commit();
    }

    /**
     * Remove any overrides whose binding targets the given bookmark id. Called
     * from the BookmarksStore listener on bookmark deletion. The caller is
     * responsible for marshalling to the main thread before invoking this;
     * SharedPreferences writes block and must not race dispatcher reads.
     *
     * @return number of bindings pruned
     */
    public int purgeBookmarkBindings(int bookmarkAction, @NonNull String bookmarkId) {
        Map<String, Binding> current = load();
        int before = current.size();
        Iterator<Map.Entry<String, Binding>> it = current.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Binding> e = it.next();
            Binding b = e.getValue();
            if (b.action == bookmarkAction && bookmarkId.equals(b.param)) {
                it.remove();
            }
        }
        int removed = before - current.size();
        if (removed > 0) {
            save(current);
            Log.i(TAG, "pruned " + removed + " bookmark bindings for id=" + bookmarkId);
        }
        return removed;
    }

    /**
     * Schema migration hook. Returns a fully-migrated binding map when a
     * migration ran, or null when the caller should parse the blob normally.
     * v1 is the initial release; no migration body yet. Bump SCHEMA_VERSION
     * and add a case here when introducing a breaking change.
     */
    @Nullable
    private Map<String, Binding> migrateIfNeeded(int fromVersion, @NonNull JSONObject root) {
        return null;
    }

    @NonNull
    private Map<String, Binding> parseBindings(@NonNull JSONObject root) throws JSONException {
        Map<String, Binding> out = new LinkedHashMap<>();
        JSONObject bindings = root.optJSONObject(JSON_BINDINGS);
        if (bindings == null) {
            return out;
        }
        Iterator<String> keys = bindings.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.isEmpty()) {
                continue;
            }
            JSONObject entry = bindings.optJSONObject(key);
            if (entry == null) {
                continue;
            }
            int action = entry.optInt(JSON_ACTION, 0);
            if (action == 0) {
                Log.w(TAG, "dropping binding with missing/unknown action for key=" + key);
                continue;
            }
            String param = entry.isNull(JSON_PARAM) ? null : entry.optString(JSON_PARAM, null);
            out.put(key, new Binding(action, param));
        }
        return out;
    }

    private void backupCorruptBlob(@NonNull String blob) {
        String backupKey = CORRUPT_PREFIX + System.currentTimeMillis();
        if (!prefs().edit().putString(backupKey, blob).commit()) {
            Log.e(TAG, "failed to write corrupt-blob backup key=" + backupKey);
        }
    }

    // ---- Path key helpers ---------------------------------------------------

    /**
     * Encode a path int[] to the bare CSV persistence key (e.g. "2,4,6"). Empty
     * and null paths return "" — callers must guard. Produces a MODE_BOTH key.
     */
    @NonNull
    public static String pathToKey(@Nullable int[] path) {
        if (path == null || path.length == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(path.length * 2);
        for (int i = 0; i < path.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(path[i]);
        }
        return sb.toString();
    }

    /**
     * Encode a path int[] to a mode-scoped key: "4:2,4,6" for 4-dir, "8:2,4,6"
     * for 8-dir. Falls back to bare CSV when path is null/empty.
     */
    @NonNull
    public static String pathToKeyForMode(@Nullable int[] path, boolean is4Dir) {
        String base = pathToKey(path);
        if (base.isEmpty()) return base;
        return (is4Dir ? "4:" : "8:") + base;
    }

    /**
     * Decode the mode prefix from a storage key.
     * @return {@link #MODE_4DIR}, {@link #MODE_8DIR}, or {@link #MODE_BOTH}.
     */
    public static int keyMode(@Nullable String key) {
        if (key == null) return MODE_BOTH;
        if (key.startsWith("4:")) return MODE_4DIR;
        if (key.startsWith("8:")) return MODE_8DIR;
        return MODE_BOTH;
    }

    /**
     * Decode a storage key back to an int[], stripping any mode prefix first.
     * Returns empty array for empty/invalid input. Never throws on malformed content.
     */
    @NonNull
    public static int[] keyToPath(@Nullable String key) {
        if (key == null || key.isEmpty()) {
            return new int[0];
        }
        // Strip "4:" or "8:" mode prefix before CSV parsing.
        if (key.length() > 2 && (key.startsWith("4:") || key.startsWith("8:"))) {
            key = key.substring(2);
        }
        String[] parts = key.split(",");
        int[] out = new int[parts.length];
        int n = 0;
        for (String p : parts) {
            try {
                out[n++] = Integer.parseInt(p.trim());
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        if (n != out.length) {
            int[] trimmed = new int[n];
            System.arraycopy(out, 0, trimmed, 0, n);
            return trimmed;
        }
        return out;
    }

    /**
     * In-memory mutation helper used by dispatcher. Callers own synchronization;
     * see ComboDispatcher's volatile-reassignment pattern (A1 fix).
     */
    @NonNull
    public static Map<String, Binding> copy(@NonNull Map<String, Binding> source) {
        return new HashMap<>(source);
    }
}
