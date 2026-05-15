/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.igalia.wolvic.input.ComboDispatcher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.Arrays;
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
}
