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

import com.igalia.wolvic.input.ComboDispatcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.IntStream;

@RunWith(RobolectricTestRunner.class)
public class ComboActionRegistryTest {

    // ── TDD Cycle 1: A_GOTO_BOOKMARK registration ────────────────────────────

    @Test
    public void knownActions_includesGotoBookmark() {
        int[] known = ComboActionRegistry.knownActions();
        boolean found = IntStream.of(known)
                .anyMatch(a -> a == ComboDispatcher.A_GOTO_BOOKMARK);
        assertTrue("A_GOTO_BOOKMARK must appear in knownActions() after Phase 8b registration",
                found);
    }

    @Test
    public void categoryFor_gotoBookmark_returnsLibraryCategory() {
        ComboActionCategory cat = ComboActionRegistry.categoryFor(ComboDispatcher.A_GOTO_BOOKMARK);
        assertTrue("A_GOTO_BOOKMARK must be categorised as LIBRARY",
                cat == ComboActionCategory.LIBRARY);
    }

    @Test
    public void gotoBookmark_isHiddenFromGenericPicker() {
        assertTrue("A_GOTO_BOOKMARK must be hidden from ActionPickerView generic list",
                ComboActionRegistry.isHiddenFromGenericPicker(ComboDispatcher.A_GOTO_BOOKMARK));
    }

    @Test
    public void standardActions_areNotHiddenFromGenericPicker() {
        assertFalse("A_BACK must NOT be hidden from the generic picker",
                ComboActionRegistry.isHiddenFromGenericPicker(ComboDispatcher.A_BACK));
    }

    // ── Registration completeness ─────────────────────────────────────────────

    @Test
    public void knownActions_returnsExpectedCount() {
        // REMEMBER: bump this count when adding a new A_* constant to CATEGORY_FOR_ACTION.
        // Current breakdown: 6 NAVIGATION + 6 POSITION + 6 WINDOW + 4 LIBRARY + 5 SPECIAL = 27.
        assertEquals("knownActions() count must equal the number of entries in CATEGORY_FOR_ACTION",
                27, ComboActionRegistry.knownActions().length);
    }

    @Test
    public void everyKnownAction_hasNonZeroLabel() {
        Set<ComboActionCategory> inUse = EnumSet.of(
                ComboActionCategory.NAVIGATION, ComboActionCategory.POSITION,
                ComboActionCategory.WINDOW,     ComboActionCategory.LIBRARY,
                ComboActionCategory.SPECIAL);
        for (int id : ComboActionRegistry.knownActions()) {
            int label = ComboActionRegistry.labelFor(id);
            assertTrue("labelFor(" + id + ") must be a non-zero string resource", label != 0);
            ComboActionCategory cat = ComboActionRegistry.categoryFor(id);
            assertNotNull("categoryFor(" + id + ") must not be null", cat);
            assertTrue("categoryFor(" + id + ") = " + cat + " is not in the active category set",
                    inUse.contains(cat));
        }
    }

    // ── Per-category membership ───────────────────────────────────────────────

    @Test
    public void categoryNavigation_containsExpectedActions() {
        int[] nav = {ComboDispatcher.A_BACK, ComboDispatcher.A_FORWARD,
                     ComboDispatcher.A_REFRESH, ComboDispatcher.A_STOP,
                     ComboDispatcher.A_FIND_IN_PAGE, ComboDispatcher.A_URL_BAR};
        for (int a : nav) {
            assertEquals("action " + a + " must be categorised as NAVIGATION",
                    ComboActionCategory.NAVIGATION, ComboActionRegistry.categoryFor(a));
        }
    }

    @Test
    public void categoryPosition_containsExpectedActions() {
        int[] pos = {ComboDispatcher.A_SCROLL_UP,    ComboDispatcher.A_SCROLL_DOWN,
                     ComboDispatcher.A_SCROLL_LEFT,   ComboDispatcher.A_SCROLL_RIGHT,
                     ComboDispatcher.A_SCROLL_TOP,    ComboDispatcher.A_SCROLL_BOTTOM};
        for (int a : pos) {
            assertEquals("action " + a + " must be categorised as POSITION",
                    ComboActionCategory.POSITION, ComboActionRegistry.categoryFor(a));
        }
    }

    @Test
    public void categoryWindow_containsExpectedActions() {
        int[] win = {ComboDispatcher.A_NEW_WINDOW,          ComboDispatcher.A_CLOSE_WINDOW,
                     ComboDispatcher.A_DUPLICATE,            ComboDispatcher.A_NEXT_WINDOW,
                     ComboDispatcher.A_PREV_WINDOW,          ComboDispatcher.A_TOGGLE_CURVE_WINDOW};
        for (int a : win) {
            assertEquals("action " + a + " must be categorised as WINDOW",
                    ComboActionCategory.WINDOW, ComboActionRegistry.categoryFor(a));
        }
    }

    @Test
    public void categoryLibrary_containsExpectedActions() {
        int[] lib = {ComboDispatcher.A_OPEN_BOOKMARKS, ComboDispatcher.A_ADD_BOOKMARK,
                     ComboDispatcher.A_HISTORY,         ComboDispatcher.A_GOTO_BOOKMARK};
        for (int a : lib) {
            assertEquals("action " + a + " must be categorised as LIBRARY",
                    ComboActionCategory.LIBRARY, ComboActionRegistry.categoryFor(a));
        }
    }

    @Test
    public void categorySpecial_containsExpectedActions() {
        int[] special = {ComboDispatcher.A_READER_MODE,        ComboDispatcher.A_PRIVATE_WINDOW,
                         ComboDispatcher.A_TOGGLE_HUD,          ComboDispatcher.A_TOGGLE_MODE,
                         ComboDispatcher.A_TOGGLE_GHOST_ROUTES};
        for (int a : special) {
            assertEquals("action " + a + " must be categorised as SPECIAL",
                    ComboActionCategory.SPECIAL, ComboActionRegistry.categoryFor(a));
        }
    }

    // ── Sentinel handling ─────────────────────────────────────────────────────

    @Test
    public void sentinels_areNotRegistered() {
        // A_NONE (0) and A_REMOVED (-1) must not appear in the registry; they are
        // internal dispatcher sentinels, not user-visible actions.
        assertEquals("labelFor(A_NONE) must return 0 (no resource)", 0,
                ComboActionRegistry.labelFor(ComboDispatcher.A_NONE));
        assertEquals("labelFor(A_REMOVED) must return 0 (no resource)", 0,
                ComboActionRegistry.labelFor(ComboDispatcher.A_REMOVED));
        assertNull("categoryFor(A_NONE) must return null",
                ComboActionRegistry.categoryFor(ComboDispatcher.A_NONE));
        assertNull("categoryFor(A_REMOVED) must return null",
                ComboActionRegistry.categoryFor(ComboDispatcher.A_REMOVED));

        int[] known = ComboActionRegistry.knownActions();
        assertFalse("A_NONE must not be in knownActions()",
                IntStream.of(known).anyMatch(a -> a == ComboDispatcher.A_NONE));
        assertFalse("A_REMOVED must not be in knownActions()",
                IntStream.of(known).anyMatch(a -> a == ComboDispatcher.A_REMOVED));
    }

    // ── Picker-visibility completeness ────────────────────────────────────────

    @Test
    public void onlyGotoBookmark_isHiddenFromGenericPicker() {
        int hiddenCount = 0;
        int lastHiddenId = -999;
        for (int id : ComboActionRegistry.knownActions()) {
            if (ComboActionRegistry.isHiddenFromGenericPicker(id)) {
                hiddenCount++;
                lastHiddenId = id;
            }
        }
        assertEquals("exactly one action must be hidden from the generic picker", 1, hiddenCount);
        assertEquals("the only hidden action must be A_GOTO_BOOKMARK",
                ComboDispatcher.A_GOTO_BOOKMARK, lastHiddenId);
    }
}
