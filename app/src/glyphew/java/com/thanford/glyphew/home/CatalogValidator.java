/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import androidx.annotation.Nullable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Validates a remote catalog JSON string before it is persisted or applied.
 *
 * Treats the input as untrusted even though we own the server — defence against
 * CDN compromise, partial-write fallback (Cloudflare 200+HTML), or supply-chain
 * issues in the publish pipeline.
 *
 * All validation is pure (no I/O, no Android context) so it is testable on the
 * JVM without Robolectric.
 */
public final class CatalogValidator {

    private static final Set<String> ALLOWED_CATEGORY_IDS = new HashSet<>(Arrays.asList(
            "vr", "gaming", "video", "tools", "social", "search"
    ));

    private static final int MAX_CATEGORIES   = 9;
    private static final int MAX_TILES        = 16;
    private static final int MAX_URL_LEN      = 256;
    private static final int MAX_LABEL_LEN    = 64;
    private static final int MAX_ICON_KEY_LEN = 32;

    private CatalogValidator() {}

    /**
     * Returns true iff {@code json} is a well-formed, policy-compliant catalog.
     * Any validation failure returns false (never throws).
     */
    public static boolean isValid(@Nullable String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            JSONObject root = new JSONObject(json);
            // Required top-level field
            if (!root.has("categories")) return false;

            JSONArray categories = root.getJSONArray("categories");
            if (categories.length() > MAX_CATEGORIES) return false;

            for (int i = 0; i < categories.length(); i++) {
                JSONObject cat = categories.getJSONObject(i);
                if (!validateCategory(cat)) return false;
            }
            return true;
        } catch (Exception e) {
            // JSON parse failure (including HTML fallback bodies) → reject
            return false;
        }
    }

    private static boolean validateCategory(JSONObject cat) throws Exception {
        String id = cat.optString("id", "");
        if (!ALLOWED_CATEGORY_IDS.contains(id)) return false;

        if (!cat.has("tiles")) return false;
        JSONArray tiles = cat.getJSONArray("tiles");
        if (tiles.length() > MAX_TILES) return false;

        for (int i = 0; i < tiles.length(); i++) {
            if (!validateTile(tiles.getJSONObject(i))) return false;
        }
        return true;
    }

    private static boolean validateTile(JSONObject tile) {
        String url   = tile.optString("url", "");
        String label = tile.optString("label", "");
        String icon  = tile.optString("icon", "");

        if (url.length() > MAX_URL_LEN)      return false;
        if (label.length() > MAX_LABEL_LEN)  return false;
        if (icon.length() > MAX_ICON_KEY_LEN) return false;

        // Scheme allowlist — only http:// and https://
        if (!url.startsWith("https://") && !url.startsWith("http://")) return false;

        return true;
    }
}
