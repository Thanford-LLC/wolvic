/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.igalia.wolvic.TestApplication;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * TDD — CatalogValidator.
 *
 * Tests the security boundary: every rejection branch must be covered so
 * attacker-controlled JSON can never push unsafe content to the homepage.
 * Robolectric needed because org.json is stubbed in the plain Android test jar.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class CatalogValidatorTest {

    // ── helpers ───────────────────────────────────────────────────────────────

    private static JSONObject validTile() throws Exception {
        return new JSONObject()
                .put("url", "https://youtube.com/")
                .put("label", "YouTube")
                .put("icon", "youtube")
                .put("featured", false);
    }

    private static JSONObject validCategory(String id) throws Exception {
        return new JSONObject()
                .put("id", id)
                .put("label", "Video")
                .put("order", 1)
                .put("tiles", new JSONArray().put(validTile()));
    }

    private static JSONObject minimalValidCatalog() throws Exception {
        return new JSONObject()
                .put("catalog_version", 1)
                .put("generated_at", "2026-06-04T18:00:00Z")
                .put("categories", new JSONArray().put(validCategory("video")));
    }

    // ── Cycle 1: valid catalog passes ─────────────────────────────────────────

    @Test
    public void validCatalog_passes() throws Exception {
        assertTrue(CatalogValidator.isValid(minimalValidCatalog().toString()));
    }

    // ── Cycle 2: null / empty / non-JSON rejected ─────────────────────────────

    @Test
    public void nullInput_rejected() {
        assertFalse(CatalogValidator.isValid(null));
    }

    @Test
    public void emptyString_rejected() {
        assertFalse(CatalogValidator.isValid(""));
    }

    @Test
    public void htmlBody_rejected() {
        // Cloudflare Pages 200-fallback delivers HTML; must not be silently accepted
        assertFalse(CatalogValidator.isValid(
                "<!DOCTYPE html><html><body>Not found</body></html>"));
    }

    // ── Cycle 3: javascript: URL rejected ────────────────────────────────────

    @Test
    public void javascriptScheme_rejected() throws Exception {
        JSONObject tile = validTile().put("url", "javascript:alert(1)");
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    @Test
    public void dataScheme_rejected() throws Exception {
        JSONObject tile = validTile().put("url", "data:text/html,<h1>xss</h1>");
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    @Test
    public void fileScheme_rejected() throws Exception {
        JSONObject tile = validTile().put("url", "file:///etc/passwd");
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    @Test
    public void httpScheme_accepted() throws Exception {
        JSONObject tile = validTile().put("url", "http://example.com/");
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertTrue(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 4: unknown category id rejected ────────────────────────────────

    @Test
    public void unknownCategoryId_rejected() throws Exception {
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(validCategory("learn")));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 5: bounds — too many categories ────────────────────────────────

    @Test
    public void tooManyCategories_rejected() throws Exception {
        JSONArray cats = new JSONArray();
        // 10 categories > max 9
        String[] ids = {"vr","gaming","video","tools","social","search","vr","gaming","video","tools"};
        for (String id : ids) cats.put(validCategory(id));
        JSONObject catalog = minimalValidCatalog().put("categories", cats);
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 6: bounds — too many tiles per category ────────────────────────

    @Test
    public void tooManyTiles_rejected() throws Exception {
        JSONArray tiles = new JSONArray();
        for (int i = 0; i < 17; i++) tiles.put(validTile()); // > max 16
        JSONObject cat = validCategory("video").put("tiles", tiles);
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 7: bounds — label too long ─────────────────────────────────────

    @Test
    public void labelTooLong_rejected() throws Exception {
        String longLabel = "A".repeat(65); // > max 64
        JSONObject tile = validTile().put("label", longLabel);
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 8: bounds — URL too long ───────────────────────────────────────

    @Test
    public void urlTooLong_rejected() throws Exception {
        String longUrl = "https://example.com/" + "a".repeat(240); // > max 256 total
        JSONObject tile = validTile().put("url", longUrl);
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 9: bounds — icon key too long ──────────────────────────────────

    @Test
    public void iconKeyTooLong_rejected() throws Exception {
        String longKey = "i".repeat(33); // > max 32
        JSONObject tile = validTile().put("icon", longKey);
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 10: missing required top-level fields ───────────────────────────

    @Test
    public void missingCategories_rejected() throws Exception {
        JSONObject catalog = new JSONObject()
                .put("catalog_version", 1)
                .put("generated_at", "2026-06-04T18:00:00Z");
        // no "categories" key
        assertFalse(CatalogValidator.isValid(catalog.toString()));
    }

    // ── Cycle 11: unknown fields are silently ignored (forward-compat) ────────

    @Test
    public void unknownTopLevelField_ignored() throws Exception {
        JSONObject catalog = minimalValidCatalog().put("future_field", "ignored");
        assertTrue(CatalogValidator.isValid(catalog.toString()));
    }

    @Test
    public void unknownTileField_ignored() throws Exception {
        JSONObject tile = validTile().put("badge", "new");
        JSONObject cat = validCategory("video")
                .put("tiles", new JSONArray().put(tile));
        JSONObject catalog = minimalValidCatalog()
                .put("categories", new JSONArray().put(cat));
        assertTrue(CatalogValidator.isValid(catalog.toString()));
    }
}
