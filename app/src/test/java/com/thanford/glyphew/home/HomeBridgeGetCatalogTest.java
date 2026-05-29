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

import android.content.Context;
import android.webkit.JavascriptInterface;

import com.igalia.wolvic.TestApplication;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Method;

/**
 * TDD — HomeBridge.getCatalog().
 *
 * Verifies:
 * 1. The method exists and is annotated @JavascriptInterface.
 * 2. When no remote cache exists, it returns the bundled fallback (a non-empty
 *    valid catalog JSON string).
 * 3. When a remote cache has been saved, it returns that instead.
 *
 * HomeBridge has Android dependencies (Session, BrowserIconsHelper) so we
 * test getCatalog() behavior through CatalogStore directly and via the
 * reflection-based annotation check — consistent with the existing
 * HomeBridgeBookmarkCategoriesTest pattern.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class HomeBridgeGetCatalogTest {

    private Context mContext;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        // Ensure no leftover cache from prior tests
        new CatalogStore(mContext).clear();
    }

    // ── Cycle 1: getCatalog method exists with @JavascriptInterface ───────────

    @Test
    public void getCatalog_methodExists_withJavascriptInterfaceAnnotation()
            throws NoSuchMethodException {
        Method m = HomeBridge.class.getMethod("getCatalog");
        assertNotNull("getCatalog() must exist on HomeBridge", m);
        assertNotNull("must be annotated @JavascriptInterface",
                m.getAnnotation(JavascriptInterface.class));
    }

    @Test
    public void getCatalog_returnsString() throws NoSuchMethodException {
        Method m = HomeBridge.class.getMethod("getCatalog");
        assertEquals("must return String", String.class, m.getReturnType());
    }

    // ── Cycle 2: no remote cache → bundled fallback JSON is non-empty ─────────

    @Test
    public void noCachePresent_bundledFallbackIsValidJson() throws Exception {
        // No CatalogStore cache — HomeBridge.getCatalog() should read from assets
        // We test the CatalogStore side: read() returns null, so the caller falls
        // back to bundled. We verify bundled fallback via the static helper directly.
        String bundled = HomeBridge.readBundledCatalog(mContext);
        assertNotNull("bundled catalog must not be null", bundled);
        // Must be parseable JSON with a categories array
        JSONObject obj = new JSONObject(bundled);
        assertTrue("bundled catalog must have categories", obj.has("categories"));
    }

    // ── Cycle 3: remote cache present → getCatalog returns cached content ─────

    @Test
    public void remoteCachePresent_getCatalogPrefersCachedContent() throws Exception {
        String remoteJson = "{\"catalog_version\":1,\"categories\":["
                + "{\"id\":\"video\",\"label\":\"Remote\",\"order\":1,\"tiles\":["
                + "{\"url\":\"https://remote.example.com/\",\"label\":\"Remote\","
                + "\"icon\":\"youtube\",\"featured\":false}]}]}";

        CatalogStore store = new CatalogStore(mContext);
        store.save(remoteJson);

        // readEffectiveCatalog() is the static helper getCatalog() delegates to
        String effective = HomeBridge.readEffectiveCatalog(mContext);
        assertEquals("should return cached remote catalog", remoteJson, effective);
    }

    // ── Cycle 4: corrupt cache → falls back to bundled ───────────────────────

    @Test
    public void corruptCache_fallsBackToBundled() throws Exception {
        CatalogStore store = new CatalogStore(mContext);
        store.save("not json {{{{");

        String effective = HomeBridge.readEffectiveCatalog(mContext);
        // Should fall back to bundled — must be parseable
        new JSONObject(effective); // throws if not valid JSON
        // And it must be the bundled one (not the corrupt string)
        assertTrue("should not return corrupt cache", !effective.startsWith("not json"));
    }
}
