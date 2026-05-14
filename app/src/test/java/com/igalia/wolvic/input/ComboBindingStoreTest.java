/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.input;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.igalia.wolvic.TestApplication;
import com.thanford.glyphew.settings.Binding;
import com.thanford.glyphew.settings.ComboBindingStore;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Pins ComboBindingStore persistence invariants:
 *   — round-trip (save → load) fidelity for action and param
 *   — A_REMOVED sentinel survives the round-trip (parseBindings filters 0, not -1)
 *   — corrupt / future-schema blobs are handled gracefully
 *   — static path-key helpers are correct
 *
 * <p>Regression pins:
 * <ul>
 *   <li>A_REMOVED = -1 would break the overlay logic if parseBindings accidentally
 *       started filtering negative values the way it filters action==0.
 *   <li>Schema version guard prevents silent data loss when upgrading blobs.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboBindingStoreTest {

    private Context mCtx;
    private ComboBindingStore mStore;

    @Before
    public void setUp() {
        mCtx = ApplicationProvider.getApplicationContext();
        mCtx.getSharedPreferences(ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit();
        mStore = new ComboBindingStore(mCtx);
    }

    // ── Persistence round-trips ───────────────────────────────────────────────

    @Test
    public void load_onFreshInstall_returnsEmptyMap() {
        assertTrue(mStore.load().isEmpty());
    }

    @Test
    public void saveAndLoad_roundTrip_preservesAction() {
        Map<String, Binding> overrides = new LinkedHashMap<>();
        overrides.put("2,4,6", Binding.of(ComboDispatcher.A_SCROLL_UP));
        assertTrue(mStore.save(overrides));

        Map<String, Binding> loaded = mStore.load();
        assertEquals(1, loaded.size());
        assertEquals(ComboDispatcher.A_SCROLL_UP, loaded.get("2,4,6").action);
    }

    @Test
    public void saveAndLoad_roundTrip_preservesParam() {
        Map<String, Binding> overrides = new LinkedHashMap<>();
        overrides.put("6,8", Binding.of(ComboDispatcher.A_GOTO_BOOKMARK, "bk_42"));
        mStore.save(overrides);

        Map<String, Binding> loaded = mStore.load();
        assertEquals("bk_42", loaded.get("6,8").param);
    }

    @Test
    public void saveAndLoad_roundTrip_nullParam_staysNull() {
        Map<String, Binding> overrides = new LinkedHashMap<>();
        overrides.put("2,8", Binding.of(ComboDispatcher.A_BACK, null));
        mStore.save(overrides);

        Map<String, Binding> loaded = mStore.load();
        assertNull(loaded.get("2,8").param);
    }

    @Test
    public void clearAll_afterSave_returnsEmptyOnNextLoad() {
        Map<String, Binding> overrides = new LinkedHashMap<>();
        overrides.put("2,8", Binding.of(ComboDispatcher.A_REFRESH));
        mStore.save(overrides);

        assertTrue(mStore.clearAll());
        assertTrue("after clearAll, load should return empty", mStore.load().isEmpty());
    }

    // ── A_REMOVED sentinel ────────────────────────────────────────────────────

    @Test
    public void save_aRemovedSentinel_roundTrips() {
        // A_REMOVED = -1. parseBindings filters action==0 (missing/unknown) but
        // NOT negative values, so A_REMOVED must survive save→load for the
        // buildTables overlay logic to see the "delete this default" intent.
        Map<String, Binding> overrides = new LinkedHashMap<>();
        overrides.put("4,4,4", Binding.of(ComboDispatcher.A_REMOVED));
        mStore.save(overrides);

        Map<String, Binding> loaded = mStore.load();
        assertTrue("A_REMOVED binding must survive round-trip", loaded.containsKey("4,4,4"));
        assertEquals("A_REMOVED value must be preserved",
                ComboDispatcher.A_REMOVED, loaded.get("4,4,4").action);
    }

    // ── Error handling ────────────────────────────────────────────────────────

    @Test
    public void load_corruptBlob_returnsEmptyAndBacksUpCorruptKey() {
        SharedPreferences prefs = mCtx.getSharedPreferences(
                ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE);
        prefs.edit().putString(ComboBindingStore.KEY_BLOB, "NOT_JSON{{{").commit();

        Map<String, Binding> result = mStore.load();
        assertTrue("corrupt blob must return empty map", result.isEmpty());

        boolean hasBackup = prefs.getAll().keySet().stream()
                .anyMatch(k -> k.startsWith(ComboBindingStore.CORRUPT_PREFIX));
        assertTrue("corrupt blob must be written under CORRUPT_PREFIX key", hasBackup);
    }

    @Test
    public void load_futureSchemaVersion_returnsEmpty() {
        SharedPreferences prefs = mCtx.getSharedPreferences(
                ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE);
        prefs.edit().putString(ComboBindingStore.KEY_BLOB,
                "{\"version\":99,\"bindings\":{\"2\":{\"action\":1}}}").commit();

        Map<String, Binding> result = mStore.load();
        assertTrue("blob with schema version > supported must be ignored", result.isEmpty());
    }

    @Test
    public void load_illegalPositionKey_loadSucceedsAndKeyPathIsEmpty() {
        // Pins the "import with illegal position like positiona" scenario.
        // parseBindings preserves the key; buildTables calls keyToPath("positiona")
        // which returns int[0], triggering the "if (p.length == 0) continue" guard.
        // Net result: the illegal key is silently skipped, valid bindings survive.
        SharedPreferences prefs = mCtx.getSharedPreferences(
                ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE);
        prefs.edit().putString(ComboBindingStore.KEY_BLOB,
                "{\"version\":1,\"bindings\":"
                + "{\"positiona\":{\"action\":3},\"2,4\":{\"action\":1}}}").commit();

        Map<String, Binding> result = mStore.load();
        assertTrue("valid binding '2,4' must survive alongside illegal key",
                result.containsKey("2,4"));
        assertEquals("keyToPath for illegal position key must return empty array (buildTables skips it)",
                0, ComboBindingStore.keyToPath("positiona").length);
    }

    // ── Static path-key helpers ───────────────────────────────────────────────

    @Test
    public void pathToKey_returnsCSVWithoutBraces() {
        assertEquals("2,4,6", ComboBindingStore.pathToKey(new int[]{2, 4, 6}));
    }

    @Test
    public void pathToKey_singleNode_returnsDigit() {
        assertEquals("2", ComboBindingStore.pathToKey(new int[]{2}));
    }

    @Test
    public void pathToKey_emptyPath_returnsEmpty() {
        assertEquals("", ComboBindingStore.pathToKey(new int[]{}));
        assertEquals("", ComboBindingStore.pathToKey(null));
    }

    @Test
    public void pathToKeyForMode_4dir_prefixesWith4Colon() {
        assertEquals("4:2,8", ComboBindingStore.pathToKeyForMode(new int[]{2, 8}, true));
    }

    @Test
    public void pathToKeyForMode_8dir_prefixesWith8Colon() {
        assertEquals("8:2,8", ComboBindingStore.pathToKeyForMode(new int[]{2, 8}, false));
    }

    @Test
    public void keyMode_4prefixed_returns4Dir() {
        assertEquals(ComboBindingStore.MODE_4DIR, ComboBindingStore.keyMode("4:2,8"));
    }

    @Test
    public void keyMode_8prefixed_returns8Dir() {
        assertEquals(ComboBindingStore.MODE_8DIR, ComboBindingStore.keyMode("8:2,8"));
    }

    @Test
    public void keyMode_bareKey_returnsBoth() {
        assertEquals(ComboBindingStore.MODE_BOTH, ComboBindingStore.keyMode("2,8"));
        assertEquals(ComboBindingStore.MODE_BOTH, ComboBindingStore.keyMode(null));
    }

    @Test
    public void keyToPath_parsesBareCSVKey() {
        int[] path = ComboBindingStore.keyToPath("2,4,6");
        assertEquals(3, path.length);
        assertEquals(2, path[0]);
        assertEquals(4, path[1]);
        assertEquals(6, path[2]);
    }

    @Test
    public void keyToPath_parsesModeKey_stripPrefix() {
        int[] path = ComboBindingStore.keyToPath("4:2,8");
        assertEquals(2, path.length);
        assertEquals(2, path[0]);
        assertEquals(8, path[1]);
    }

    @Test
    public void keyToPath_emptyKey_returnsEmptyArray() {
        assertEquals(0, ComboBindingStore.keyToPath("").length);
        assertEquals(0, ComboBindingStore.keyToPath(null).length);
    }
}
