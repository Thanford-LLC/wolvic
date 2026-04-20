/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import android.util.SparseIntArray;

import androidx.annotation.StringRes;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;

/**
 * Maps every ComboDispatcher action integer to a localized action-name string
 * resource. Companion to {@link ComboActionIcons}: the icons map provides the
 * glyph, this map provides the human-readable label, and the HUD tip strip's
 * preview-fire affordance composes them into
 * "Release to fire: [icon] [action name]".
 *
 * <p>Ships English-only in v1.0 (22 strings × ~23 locales ≈ 506 translations
 * deferred to a post-launch patch). Non-English locales fall back to the
 * English string automatically via Android's resource resolver.
 *
 * <p>A_NONE (0) is intentionally omitted — it is the sentinel for "no binding".
 */
public final class ComboActionNames {

    private ComboActionNames() {}

    public static final SparseIntArray NAME_FOR_ACTION;

    static {
        NAME_FOR_ACTION = new SparseIntArray(22);
        NAME_FOR_ACTION.put(ComboDispatcher.A_SCROLL_UP,      R.string.fd_action_name_scroll_up);
        NAME_FOR_ACTION.put(ComboDispatcher.A_SCROLL_DOWN,    R.string.fd_action_name_scroll_down);
        NAME_FOR_ACTION.put(ComboDispatcher.A_SCROLL_LEFT,    R.string.fd_action_name_scroll_left);
        NAME_FOR_ACTION.put(ComboDispatcher.A_SCROLL_RIGHT,   R.string.fd_action_name_scroll_right);
        NAME_FOR_ACTION.put(ComboDispatcher.A_SCROLL_TOP,     R.string.fd_action_name_scroll_top);
        NAME_FOR_ACTION.put(ComboDispatcher.A_SCROLL_BOTTOM,  R.string.fd_action_name_scroll_bottom);
        NAME_FOR_ACTION.put(ComboDispatcher.A_BACK,           R.string.fd_action_name_back);
        NAME_FOR_ACTION.put(ComboDispatcher.A_FORWARD,        R.string.fd_action_name_forward);
        NAME_FOR_ACTION.put(ComboDispatcher.A_REFRESH,        R.string.fd_action_name_refresh);
        NAME_FOR_ACTION.put(ComboDispatcher.A_FIND_IN_PAGE,   R.string.fd_action_name_find_in_page);
        NAME_FOR_ACTION.put(ComboDispatcher.A_STOP,           R.string.fd_action_name_stop);
        NAME_FOR_ACTION.put(ComboDispatcher.A_NEW_WINDOW,     R.string.fd_action_name_new_window);
        NAME_FOR_ACTION.put(ComboDispatcher.A_CLOSE_WINDOW,   R.string.fd_action_name_close_window);
        NAME_FOR_ACTION.put(ComboDispatcher.A_DUPLICATE,      R.string.fd_action_name_duplicate);
        NAME_FOR_ACTION.put(ComboDispatcher.A_NEXT_WINDOW,    R.string.fd_action_name_next_window);
        NAME_FOR_ACTION.put(ComboDispatcher.A_PREV_WINDOW,    R.string.fd_action_name_prev_window);
        NAME_FOR_ACTION.put(ComboDispatcher.A_URL_BAR,        R.string.fd_action_name_url_bar);
        NAME_FOR_ACTION.put(ComboDispatcher.A_OPEN_BOOKMARKS, R.string.fd_action_name_open_bookmarks);
        NAME_FOR_ACTION.put(ComboDispatcher.A_ADD_BOOKMARK,   R.string.fd_action_name_add_bookmark);
        NAME_FOR_ACTION.put(ComboDispatcher.A_HISTORY,        R.string.fd_action_name_history);
        NAME_FOR_ACTION.put(ComboDispatcher.A_READER_MODE,    R.string.fd_action_name_reader_mode);
        NAME_FOR_ACTION.put(ComboDispatcher.A_PRIVATE_WINDOW, R.string.fd_action_name_private_window);
    }

    /**
     * Returns the string resource for the given action integer, or 0 if the
     * action is not mapped (caller must guard against 0 — there is no generic
     * "unknown action" fallback string, unlike the icon map).
     *
     * @param actionInt a {@code ComboDispatcher.A_*} constant
     * @return a {@link StringRes} string resource ID, or 0 if unmapped
     */
    public static @StringRes int nameFor(int actionInt) {
        return NAME_FOR_ACTION.get(actionInt, 0);
    }
}
