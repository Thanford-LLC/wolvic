/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.webkit.JavascriptInterface;

import com.igalia.wolvic.TestApplication;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

/**
 * TDD Cycle 4 — Phase 8b: HomeBridge.getBookmarkCategories.
 *
 * <p>Full behavioral tests (real BookmarksStore data) are deferred to on-device
 * (plan §9 steps 2-3). These tests verify:
 * <ol>
 *   <li>getBookmarkCategories exists and is annotated @JavascriptInterface.
 *   <li>buildCategory helper produces correctly-paginated JSON.
 * </ol>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class HomeBridgeBookmarkCategoriesTest {

    @Test
    public void getBookmarkCategories_methodExists_withJavascriptInterfaceAnnotation()
            throws NoSuchMethodException {
        Method m = HomeBridge.class.getMethod("getBookmarkCategories", String.class);
        assertNotNull("getBookmarkCategories(requestId) must exist on HomeBridge", m);
        assertNotNull("must be annotated @JavascriptInterface",
                m.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void buildBookmarkCategory_paginatesCorrectly() throws Exception {
        // 9 items → page 0 (8 items), page 1 (1 item)
        org.json.JSONArray items = new org.json.JSONArray();
        for (int i = 0; i < 9; i++) {
            JSONObject item = new JSONObject();
            item.put("url", "https://site" + i + ".com");
            item.put("title", "Site " + i);
            item.put("guid", "guid-" + i);
            items.put(item);
        }

        JSONObject cat = HomeBridge.buildBookmarkCategory("bookmarks", "Bookmarks", items, false);
        assertNotNull(cat);
        assertEquals("bookmarks", cat.getString("id"));
        assertEquals("Bookmarks", cat.getString("title"));

        JSONArray pages = cat.getJSONArray("pages");
        assertEquals("9 items should produce 2 pages", 2, pages.length());
        assertEquals("page 0 should have 8 tiles", 8, pages.getJSONArray(0).length());
        assertEquals("page 1 should have 1 tile", 1, pages.getJSONArray(1).length());
    }

    @Test
    public void buildBookmarkCategory_exactlyEightItems_producesOnePage() throws Exception {
        JSONArray items = new JSONArray();
        for (int i = 0; i < 8; i++) {
            JSONObject item = new JSONObject();
            item.put("url", "https://site" + i + ".com");
            item.put("title", "Site " + i);
            item.put("guid", "guid-" + i);
            items.put(item);
        }
        JSONObject cat = HomeBridge.buildBookmarkCategory("bookmarks", "Bookmarks", items, false);
        JSONArray pages = cat.getJSONArray("pages");
        assertEquals("exactly 8 items → 1 page", 1, pages.length());
        assertEquals(8, pages.getJSONArray(0).length());
    }

    @Test
    public void buildBookmarkCategory_comboFlag_setsCategoryKind() throws Exception {
        JSONArray items = new JSONArray();
        JSONObject item = new JSONObject();
        item.put("url", "https://github.com");
        item.put("title", "GitHub");
        item.put("guid", "guid-gh");
        items.put(item);

        JSONObject cat = HomeBridge.buildBookmarkCategory("combo-bookmarks", "Combo Bookmarks", items, true);
        assertTrue("isCombo category must have kind=combo_bookmark",
                "combo_bookmark".equals(cat.optString("kind")));
    }
}
