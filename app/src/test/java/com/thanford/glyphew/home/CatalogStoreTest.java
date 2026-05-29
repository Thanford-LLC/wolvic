/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import com.igalia.wolvic.TestApplication;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

/**
 * TDD — CatalogStore.
 *
 * Verifies the disk-cache read/write contract. Atomicity (write-to-tmp then
 * rename) is observable via the absence of a torn state when save() is
 * interrupted — we test the observable behaviour (read returns saved content
 * or null) rather than the internal tmp file, which is an implementation detail.
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class CatalogStoreTest {

    private Context mContext;
    private CatalogStore mStore;

    @Before
    public void setUp() {
        mContext = RuntimeEnvironment.getApplication();
        mStore = new CatalogStore(mContext);
        mStore.clear(); // start clean each test
    }

    // ── Cycle 1: no file → read returns null ─────────────────────────────────

    @Test
    public void readWhenEmpty_returnsNull() {
        assertNull(mStore.read());
    }

    // ── Cycle 2: save then read returns same content ──────────────────────────

    @Test
    public void saveAndRead_returnsSameContent() {
        String json = "{\"catalog_version\":1,\"categories\":[]}";
        mStore.save(json);
        assertEquals(json, mStore.read());
    }

    // ── Cycle 3: second save overwrites first ─────────────────────────────────

    @Test
    public void secondSave_overwritesFirst() {
        mStore.save("{\"v\":1}");
        mStore.save("{\"v\":2}");
        assertEquals("{\"v\":2}", mStore.read());
    }

    // ── Cycle 4: clear removes stored content ─────────────────────────────────

    @Test
    public void clear_removesContent() {
        mStore.save("{\"v\":1}");
        mStore.clear();
        assertNull(mStore.read());
    }

    // ── Cycle 5: hasCachedCatalog reflects presence ───────────────────────────

    @Test
    public void hasCachedCatalog_falseWhenEmpty() {
        assertFalse(mStore.hasCachedCatalog());
    }

    @Test
    public void hasCachedCatalog_trueAfterSave() {
        mStore.save("{\"v\":1}");
        assertTrue(mStore.hasCachedCatalog());
    }
}
