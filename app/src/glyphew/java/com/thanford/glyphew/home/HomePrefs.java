/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.preference.PreferenceManager;

/**
 * Persists homepage-specific user state across app restarts.
 * SharedPreferences key: "glyphew_homepage".
 *
 * All methods are safe to call from any thread (SharedPreferences I/O is fast
 * and does not need to be marshalled to the UI thread).
 */
public class HomePrefs {

    private static final String PREFS_NAME = "glyphew_homepage";

    private static final String KEY_CATEGORY_ORDER = "category_order_blob";
    private static final String KEY_FOLDER_ORDER   = "folder_order_blob";
    private static final String KEY_HINT_SEEN      = "homepage_hint_seen";
    private static final String KEY_LAST_ROW       = "last_row_index";
    private static final String KEY_LAST_COL       = "last_col_index";

    // ComboDispatcher stores this in the default (PreferenceManager) prefs, not a named file.
    private static final String KEY_4DIR_MODE = "glyphew_combo_4dir_mode";

    private final SharedPreferences mPrefs;
    private final SharedPreferences mDefaultPrefs;

    public HomePrefs(@NonNull Context context) {
        mPrefs        = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        mDefaultPrefs = PreferenceManager.getDefaultSharedPreferences(context);
    }

    // ── Category / folder order ────────────────────────────────────────────

    public void setCategoryOrder(@NonNull String jsonArray) {
        mPrefs.edit().putString(KEY_CATEGORY_ORDER, jsonArray).apply();
    }

    @NonNull
    public String getCategoryOrder() {
        return mPrefs.getString(KEY_CATEGORY_ORDER, "[]");
    }

    public void setFolderOrder(@NonNull String jsonArray) {
        mPrefs.edit().putString(KEY_FOLDER_ORDER, jsonArray).apply();
    }

    @NonNull
    public String getFolderOrder() {
        return mPrefs.getString(KEY_FOLDER_ORDER, "[]");
    }

    // ── Hint ───────────────────────────────────────────────────────────────

    public void markHintSeen() {
        mPrefs.edit().putBoolean(KEY_HINT_SEEN, true).apply();
    }

    public boolean isHintSeen() {
        return mPrefs.getBoolean(KEY_HINT_SEEN, false);
    }

    // ── Last position (row + page) ─────────────────────────────────────────

    public void setLastPosition(int rowIndex, int colIndex) {
        mPrefs.edit()
              .putInt(KEY_LAST_ROW, rowIndex)
              .putInt(KEY_LAST_COL, colIndex)
              .apply();
    }

    public int getLastRowIndex() {
        return mPrefs.getInt(KEY_LAST_ROW, 0);
    }

    public int getLastColIndex() {
        return mPrefs.getInt(KEY_LAST_COL, 0);
    }

    // ── Combo mode (read-only; written by ComboDispatcher) ────────────────

    /** Returns true when 4-dir mode is active (default). False = 8-dir mode. */
    public boolean is4DirMode() {
        return mDefaultPrefs.getBoolean(KEY_4DIR_MODE, true);
    }

    // ── Reset (for testing) ────────────────────────────────────────────────

    public void resetAll() {
        mPrefs.edit().clear().apply();
    }
}
