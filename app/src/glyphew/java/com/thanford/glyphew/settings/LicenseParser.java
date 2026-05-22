/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.thanford.glyphew.settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class LicenseParser {

    private LicenseParser() {}

    @NonNull
    public static List<LicenseEntry> parse(@Nullable String json) {
        if (json == null || json.isEmpty()) return Collections.emptyList();
        try {
            JSONObject root    = new JSONObject(json);
            JSONArray  entries = root.getJSONArray("entries");
            List<LicenseEntry> result = new ArrayList<>(entries.length());
            for (int i = 0; i < entries.length(); i++) {
                JSONObject obj = entries.getJSONObject(i);
                result.add(new LicenseEntry(
                        obj.optString("name"),
                        obj.optString("copyright"),
                        obj.optString("license"),
                        obj.optString("license_url"),
                        obj.optString("source_url")));
            }
            return result;
        } catch (JSONException e) {
            return Collections.emptyList();
        }
    }
}
