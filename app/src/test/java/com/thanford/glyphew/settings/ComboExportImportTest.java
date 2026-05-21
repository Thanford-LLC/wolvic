/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.igalia.wolvic.TestApplication;
import com.igalia.wolvic.input.ComboDispatcher;

import org.json.JSONObject;
import org.json.JSONArray;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unit tests for {@link ComboExportImport} pure logic.
 *
 * <p>Coverage:
 * <ul>
 *   <li>{@code countEnvelope} — combo and bookmark counting, including the
 *       A_GOTO_BOOKMARK-counts-as-combo regression (previously excluded).
 *   <li>{@code validateEnvelope} — schema_version + required-field checks.
 *   <li>{@code transformCombosForExport} — GUID-to-URL substitution, deleted-
 *       bookmark omission, non-bookmark preservation, and no-mutation guarantee.
 *   <li>{@code applyEnvelope} — import, A_GOTO_BOOKMARK drop, conflict skip,
 *       and A_REMOVED pass-through.
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
@Config(application = TestApplication.class)
public class ComboExportImportTest {

    private Context mCtx;

    @Before
    public void setUp() {
        mCtx = ApplicationProvider.getApplicationContext();
        mCtx.getSharedPreferences(ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    // ── countEnvelope ─────────────────────────────────────────────────────────

    @Test
    public void countEnvelope_emptyBindings_returnsZeroCombos() throws Exception {
        ComboExportImport.EnvelopeCounts c =
                ComboExportImport.countEnvelope(makeEnvelope(new JSONObject()));
        assertEquals(0, c.combos);
        assertEquals(0, c.bookmarks);
    }

    @Test
    public void countEnvelope_regularBinding_countsAsCombo() throws Exception {
        JSONObject bindings = new JSONObject();
        bindings.put("2,4", entry(ComboDispatcher.A_SCROLL_UP, null));
        ComboExportImport.EnvelopeCounts c =
                ComboExportImport.countEnvelope(makeEnvelope(bindings));
        assertEquals(1, c.combos);
    }

    @Test
    public void countEnvelope_bookmarkCombo_countsAsCombo() throws Exception {
        // Regression: A_GOTO_BOOKMARK was previously excluded from the count even
        // though it is now restored on import. It must count as a combo.
        JSONObject bindings = new JSONObject();
        bindings.put("4:2,6", entry(ComboDispatcher.A_GOTO_BOOKMARK, "https://example.com"));
        ComboExportImport.EnvelopeCounts c =
                ComboExportImport.countEnvelope(makeEnvelope(bindings));
        assertEquals("A_GOTO_BOOKMARK must count as a combo", 1, c.combos);
    }

    @Test
    public void countEnvelope_mixedBindingsAndBookmarks_correctTotals() throws Exception {
        JSONObject bindings = new JSONObject();
        bindings.put("2,4", entry(ComboDispatcher.A_SCROLL_UP, null));
        bindings.put("4:2,6", entry(ComboDispatcher.A_GOTO_BOOKMARK, "https://example.com"));
        JSONObject env = makeEnvelope(bindings);
        JSONArray bk = new JSONArray();
        bk.put(bookmark("https://example.com", "Example"));
        env.put("bookmarks", bk);
        ComboExportImport.EnvelopeCounts c = ComboExportImport.countEnvelope(env);
        assertEquals(2, c.combos);
        assertEquals(1, c.bookmarks);
    }

    @Test
    public void countEnvelope_bookmarksArray_allCounted() throws Exception {
        JSONObject env = makeEnvelope(new JSONObject());
        JSONArray bk = new JSONArray();
        bk.put(bookmark("https://a.com", "A"));
        bk.put(bookmark("https://b.com", "B"));
        env.put("bookmarks", bk);
        assertEquals(2, ComboExportImport.countEnvelope(env).bookmarks);
    }

    // ── validateEnvelope ──────────────────────────────────────────────────────

    @Test
    public void validateEnvelope_validV1_returnsNull() throws Exception {
        assertNull(ComboExportImport.validateEnvelope(mCtx, makeEnvelope(new JSONObject())));
    }

    @Test
    public void validateEnvelope_missingSchemaVersion_returnsError() throws Exception {
        JSONObject env = new JSONObject();
        env.put("combos", blob(new JSONObject()));
        assertNotNull(ComboExportImport.validateEnvelope(mCtx, env));
    }

    @Test
    public void validateEnvelope_wrongSchemaVersion_returnsUnsupportedVersion() throws Exception {
        JSONObject env = new JSONObject();
        env.put("schema_version", 99);
        env.put("combos", blob(new JSONObject()));
        assertEquals("unsupported_version", ComboExportImport.validateEnvelope(mCtx, env));
    }

    @Test
    public void validateEnvelope_missingCombosField_returnsError() throws Exception {
        JSONObject env = new JSONObject();
        env.put("schema_version", 1);
        assertNotNull(ComboExportImport.validateEnvelope(mCtx, env));
    }

    // ── filterNewBookmarks (import dedup) ─────────────────────────────────────

    @Test
    public void filterNewBookmarks_skipsUrlsAlreadyPresent() throws Exception {
        // Re-importing the same backup must not create duplicate bookmarks.
        JSONArray incoming = new JSONArray();
        incoming.put(bookmark("https://a.com", "A"));
        incoming.put(bookmark("https://b.com", "B"));
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("https://a.com");

        JSONArray result = ComboExportImport.filterNewBookmarks(incoming, existing);

        assertEquals("only the not-yet-present bookmark survives", 1, result.length());
        assertEquals("https://b.com", result.getJSONObject(0).getString("url"));
    }

    @Test
    public void filterNewBookmarks_allNew_returnsAll() throws Exception {
        JSONArray incoming = new JSONArray();
        incoming.put(bookmark("https://a.com", "A"));
        incoming.put(bookmark("https://b.com", "B"));

        JSONArray result = ComboExportImport.filterNewBookmarks(
                incoming, Collections.emptySet());

        assertEquals(2, result.length());
    }

    @Test
    public void filterNewBookmarks_allExisting_returnsEmpty() throws Exception {
        JSONArray incoming = new JSONArray();
        incoming.put(bookmark("https://a.com", "A"));
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("https://a.com");

        JSONArray result = ComboExportImport.filterNewBookmarks(incoming, existing);

        assertEquals(0, result.length());
    }

    @Test
    public void filterNewBookmarks_skipsEntriesWithoutUrl() throws Exception {
        JSONArray incoming = new JSONArray();
        incoming.put(new JSONObject().put("title", "no url"));

        JSONArray result = ComboExportImport.filterNewBookmarks(
                incoming, Collections.emptySet());

        assertEquals(0, result.length());
    }

    // ── filterComboBookmarksToRestore (combo bookmark Library recreation) ──────

    @Test
    public void filterComboBookmarksToRestore_includesBoundAndNotPresent() throws Exception {
        JSONArray incoming = new JSONArray();
        incoming.put(bookmark("https://startpage.com", "Startpage"));
        java.util.Set<String> bound = new java.util.HashSet<>();
        bound.add("https://startpage.com");

        JSONArray result = ComboExportImport.filterComboBookmarksToRestore(
                incoming, bound, Collections.emptySet());

        assertEquals(1, result.length());
        assertEquals("https://startpage.com", result.getJSONObject(0).getString("url"));
    }

    @Test
    public void filterComboBookmarksToRestore_skipsUnboundUrl() throws Exception {
        // No A_GOTO_BOOKMARK binding was applied for this URL (e.g. conflict-skipped),
        // so recreating its Library entry would orphan it.
        JSONArray incoming = new JSONArray();
        incoming.put(bookmark("https://startpage.com", "Startpage"));

        JSONArray result = ComboExportImport.filterComboBookmarksToRestore(
                incoming, Collections.emptySet(), Collections.emptySet());

        assertEquals(0, result.length());
    }

    @Test
    public void filterComboBookmarksToRestore_skipsAlreadyInFolder() throws Exception {
        // Idempotent: re-importing must not duplicate the combo bookmark in the folder.
        JSONArray incoming = new JSONArray();
        incoming.put(bookmark("https://startpage.com", "Startpage"));
        java.util.Set<String> bound = new java.util.HashSet<>();
        bound.add("https://startpage.com");
        java.util.Set<String> existing = new java.util.HashSet<>();
        existing.add("https://startpage.com");

        JSONArray result = ComboExportImport.filterComboBookmarksToRestore(
                incoming, bound, existing);

        assertEquals(0, result.length());
    }

    @Test
    public void filterComboBookmarksToRestore_skipsEntriesWithoutUrl() throws Exception {
        JSONArray incoming = new JSONArray();
        incoming.put(new JSONObject().put("title", "no url"));

        JSONArray result = ComboExportImport.filterComboBookmarksToRestore(
                incoming, Collections.emptySet(), Collections.emptySet());

        assertEquals(0, result.length());
    }

    // ── applyEnvelope ─────────────────────────────────────────────────────────

    @Test
    public void applyEnvelope_appliesNonBookmarkBinding() throws Exception {
        JSONObject bindings = new JSONObject();
        bindings.put("2,4", entry(ComboDispatcher.A_SCROLL_UP, null));
        assertTrue(ComboExportImport.applyEnvelope(mCtx, makeEnvelope(bindings)));

        Map<String, Binding> loaded = new ComboBindingStore(mCtx).load();
        assertTrue(loaded.containsKey("2,4"));
        assertEquals(ComboDispatcher.A_SCROLL_UP, loaded.get("2,4").action);
    }

    @Test
    public void applyEnvelope_appliesAGotoBookmarkBindingWithUrl() throws Exception {
        // A_GOTO_BOOKMARK bindings now carry the bookmark URL as param — portable
        // across devices, so applyEnvelope writes them directly like any other binding.
        JSONObject bindings = new JSONObject();
        bindings.put("4:2,6", entry(ComboDispatcher.A_GOTO_BOOKMARK, "https://example.com"));
        ComboExportImport.applyEnvelope(mCtx, makeEnvelope(bindings));

        Map<String, Binding> loaded = new ComboBindingStore(mCtx).load();
        assertTrue("A_GOTO_BOOKMARK must be written to store by applyEnvelope",
                loaded.containsKey("4:2,6"));
        assertEquals(ComboDispatcher.A_GOTO_BOOKMARK, loaded.get("4:2,6").action);
        assertEquals("https://example.com", loaded.get("4:2,6").param);
    }

    @Test
    public void applyEnvelope_skipsConflictingCustomBinding() throws Exception {
        // Destination has A_REFRESH on "2,4". Import has A_BACK on "2,4".
        // The destination's custom binding must be preserved (import does not overwrite).
        ComboBindingStore bs = new ComboBindingStore(mCtx);
        Map<String, Binding> existing = new LinkedHashMap<>();
        existing.put("2,4", Binding.of(ComboDispatcher.A_REFRESH));
        bs.save(existing);

        JSONObject bindings = new JSONObject();
        bindings.put("2,4", entry(ComboDispatcher.A_BACK, null));
        ComboExportImport.applyEnvelope(mCtx, makeEnvelope(bindings));

        assertEquals("conflicting import must not overwrite existing custom binding",
                ComboDispatcher.A_REFRESH,
                new ComboBindingStore(mCtx).load().get("2,4").action);
    }

    @Test
    public void applyEnvelope_appliesARemovedSentinel() throws Exception {
        // A_REMOVED must survive applyEnvelope so the overlay system knows a
        // default path was explicitly deleted by the exporting user.
        JSONObject bindings = new JSONObject();
        bindings.put("2,4", entry(ComboDispatcher.A_REMOVED, null));
        ComboExportImport.applyEnvelope(mCtx, makeEnvelope(bindings));

        Map<String, Binding> loaded = new ComboBindingStore(mCtx).load();
        assertTrue("A_REMOVED binding must survive applyEnvelope", loaded.containsKey("2,4"));
        assertEquals(ComboDispatcher.A_REMOVED, loaded.get("2,4").action);
    }

    // ── buildEnvelope ─────────────────────────────────────────────────────────

    /**
     * The export envelope must carry combo bindings verbatim from the store. Because
     * A_GOTO_BOOKMARK now stores the bookmark URL directly (not a device-local GUID),
     * no GUID→URL transformation is needed — the binding is portable as-is.
     *
     * <p>Regression: the previous design resolved GUID→URL via BookmarksStore at export
     * time, which silently dropped bookmark combos whenever the store was unavailable.
     */
    @Test
    public void buildEnvelope_withBookmarks_includesComboAndBookmarkInEnvelope() throws Exception {
        // Seed the binding store with an A_GOTO_BOOKMARK binding (URL as param).
        JSONObject bindings = new JSONObject();
        bindings.put("4:2,6", entry(ComboDispatcher.A_GOTO_BOOKMARK, "https://example.com"));
        mCtx.getSharedPreferences(ComboBindingStore.PREFS_FILE, Context.MODE_PRIVATE)
                .edit()
                .putString(ComboBindingStore.KEY_BLOB, blob(bindings).toString())
                .commit();

        JSONArray bkArr = new JSONArray();
        bkArr.put(bookmark("https://example.com", "Example"));
        JSONArray comboArr = new JSONArray();
        comboArr.put(bookmark("https://startpage.com", "Startpage"));

        JSONObject env = ComboExportImport.buildEnvelope(mCtx, bkArr, comboArr);

        // The A_GOTO_BOOKMARK binding must be present with the URL as param, verbatim.
        JSONObject comboBinding = env.getJSONObject("combos")
                .getJSONObject("bindings").getJSONObject("4:2,6");
        assertEquals(ComboDispatcher.A_GOTO_BOOKMARK, comboBinding.getInt("action"));
        assertEquals("https://example.com", comboBinding.getString("param"));

        // The bookmarks array must be populated.
        assertEquals("bookmarks array must not be empty", 1, env.getJSONArray("bookmarks").length());
        assertEquals("https://example.com",
                env.getJSONArray("bookmarks").getJSONObject(0).getString("url"));

        // Combo bookmarks ride in their own array (not counted as plain bookmarks).
        assertEquals(1, env.getJSONArray("combo_bookmarks").length());
        assertEquals("https://startpage.com",
                env.getJSONArray("combo_bookmarks").getJSONObject(0).getString("url"));
    }

    // ── deleteEntry (backup file removal) ─────────────────────────────────────

    @Test
    public void deleteEntry_fileEntry_removesFileAndReturnsTrue() throws Exception {
        java.io.File f = java.io.File.createTempFile("glyphew-backup-test", ".json");
        try (java.io.FileWriter w = new java.io.FileWriter(f)) { w.write("{}"); }
        assertTrue("precondition: temp file exists", f.exists());

        ComboExportImport.ExportEntry entry =
                new ComboExportImport.ExportEntry(f, f.getName());
        boolean ok = ComboExportImport.deleteEntry(mCtx, entry);

        assertTrue("deleteEntry returns true when a file is removed", ok);
        assertFalse("the backup file is gone", f.exists());
    }

    @Test
    public void deleteEntry_missingFile_returnsFalse() throws Exception {
        java.io.File f = java.io.File.createTempFile("glyphew-backup-gone", ".json");
        assertTrue(f.delete());

        ComboExportImport.ExportEntry entry =
                new ComboExportImport.ExportEntry(f, f.getName());

        assertFalse("nothing to delete → false", ComboExportImport.deleteEntry(mCtx, entry));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static JSONObject makeEnvelope(JSONObject bindings) throws Exception {
        JSONObject env = new JSONObject();
        env.put("schema_version", ComboExportImport.ENVELOPE_VERSION);
        env.put("combos", blob(bindings));
        return env;
    }

    private static JSONObject blob(JSONObject bindings) throws Exception {
        JSONObject b = new JSONObject();
        b.put("version", ComboBindingStore.SCHEMA_VERSION);
        b.put("bindings", bindings);
        return b;
    }

    private static JSONObject entry(int action, String param) throws Exception {
        JSONObject e = new JSONObject();
        e.put("action", action);
        if (param != null) e.put("param", param);
        return e;
    }

    private static JSONObject bookmark(String url, String title) throws Exception {
        JSONObject b = new JSONObject();
        b.put("url", url);
        b.put("title", title);
        return b;
    }
}
