/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.util.SparseArray;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.combo.ComboActionNames;

/**
 * Central lookup for action int → {label string resource, category}. Delegates
 * to {@link ComboActionNames} for the localized label; adds category metadata
 * for Combos Settings grouping. Phase 1 ships the 22 existing actions; future
 * phases add A_TOGGLE_HUD, A_TOGGLE_MODE, A_TOGGLE_CURVE_WINDOW, A_GOTO_BOOKMARK.
 */
public final class ComboActionRegistry {

    private ComboActionRegistry() {}

    private static final SparseArray<ComboActionCategory> CATEGORY_FOR_ACTION;

    static {
        CATEGORY_FOR_ACTION = new SparseArray<>(23);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_BACK,           ComboActionCategory.NAVIGATION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_FORWARD,        ComboActionCategory.NAVIGATION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_REFRESH,        ComboActionCategory.NAVIGATION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_STOP,           ComboActionCategory.NAVIGATION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_FIND_IN_PAGE,   ComboActionCategory.NAVIGATION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_URL_BAR,        ComboActionCategory.NAVIGATION);

        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_SCROLL_UP,      ComboActionCategory.POSITION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_SCROLL_DOWN,    ComboActionCategory.POSITION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_SCROLL_LEFT,    ComboActionCategory.POSITION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_SCROLL_RIGHT,   ComboActionCategory.POSITION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_SCROLL_TOP,     ComboActionCategory.POSITION);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_SCROLL_BOTTOM,  ComboActionCategory.POSITION);

        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_NEW_WINDOW,     ComboActionCategory.WINDOW);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_CLOSE_WINDOW,   ComboActionCategory.WINDOW);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_DUPLICATE,      ComboActionCategory.WINDOW);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_NEXT_WINDOW,    ComboActionCategory.WINDOW);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_PREV_WINDOW,    ComboActionCategory.WINDOW);

        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_OPEN_BOOKMARKS, ComboActionCategory.LIBRARY);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_ADD_BOOKMARK,   ComboActionCategory.LIBRARY);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_HISTORY,        ComboActionCategory.LIBRARY);

        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_READER_MODE,    ComboActionCategory.SPECIAL);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_PRIVATE_WINDOW, ComboActionCategory.SPECIAL);

        // Phase 8a: curved-window toggle — user-bindable, ships unbound.
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_TOGGLE_CURVE_WINDOW, ComboActionCategory.WINDOW);

        // Phase 8a (feedback): HUD + mode-toggle + ghost-routes toggle registered
        // so they appear in the Settings list. A_TOGGLE_HUD and A_TOGGLE_GHOST_ROUTES
        // ship unbound → appear in Unassigned until user binds them.
        // A_TOGGLE_MODE ships with the asymmetric escape-path defaults → appears in
        // Special while bound; moves to Unassigned if user deletes the binding.
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_TOGGLE_HUD,          ComboActionCategory.SPECIAL);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_TOGGLE_MODE,         ComboActionCategory.SPECIAL);
        CATEGORY_FOR_ACTION.put(ComboDispatcher.A_TOGGLE_GHOST_ROUTES, ComboActionCategory.SPECIAL);
    }

    @StringRes
    public static int labelFor(int actionInt) {
        return ComboActionNames.nameFor(actionInt);
    }

    @Nullable
    public static ComboActionCategory categoryFor(int actionInt) {
        return CATEGORY_FOR_ACTION.get(actionInt);
    }

    @NonNull
    public static int[] knownActions() {
        int[] ids = new int[CATEGORY_FOR_ACTION.size()];
        for (int i = 0; i < CATEGORY_FOR_ACTION.size(); i++) {
            ids[i] = CATEGORY_FOR_ACTION.keyAt(i);
        }
        return ids;
    }
}
