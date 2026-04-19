/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import android.util.SparseIntArray;

import androidx.annotation.DrawableRes;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;

/**
 * Maps every ComboDispatcher action integer to a drawable resource identifier.
 * Used by the HUD tip strip and ghost-trace overlay to render action icons
 * without requiring localized text strings.
 *
 * <p>A_NONE (0) is intentionally omitted — it is the sentinel for "no binding".
 */
public final class ComboActionIcons {

    private ComboActionIcons() {}

    public static final SparseIntArray ICON_FOR_ACTION;

    static {
        ICON_FOR_ACTION = new SparseIntArray(22);
        ICON_FOR_ACTION.put(ComboDispatcher.A_SCROLL_UP,      R.drawable.ic_baseline_arrow_drop_up_24px);
        ICON_FOR_ACTION.put(ComboDispatcher.A_SCROLL_DOWN,    R.drawable.ic_baseline_arrow_drop_down_24px);
        ICON_FOR_ACTION.put(ComboDispatcher.A_SCROLL_LEFT,    R.drawable.fd_action_scroll_left_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_SCROLL_RIGHT,   R.drawable.fd_action_scroll_right_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_SCROLL_TOP,     R.drawable.fd_action_scroll_top_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_SCROLL_BOTTOM,  R.drawable.fd_action_scroll_bottom_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_BACK,           R.drawable.ic_icon_back);
        ICON_FOR_ACTION.put(ComboDispatcher.A_FORWARD,        R.drawable.ic_icon_forward);
        ICON_FOR_ACTION.put(ComboDispatcher.A_REFRESH,        R.drawable.ic_icon_reload);
        ICON_FOR_ACTION.put(ComboDispatcher.A_FIND_IN_PAGE,   R.drawable.ic_icon_search);
        ICON_FOR_ACTION.put(ComboDispatcher.A_STOP,           R.drawable.fd_action_stop_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_NEW_WINDOW,     R.drawable.ic_icon_tray_newwindow);
        ICON_FOR_ACTION.put(ComboDispatcher.A_CLOSE_WINDOW,   R.drawable.ic_icon_window_exit);
        ICON_FOR_ACTION.put(ComboDispatcher.A_DUPLICATE,      R.drawable.fd_action_duplicate_window_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_NEXT_WINDOW,    R.drawable.ic_icon_window_right);
        ICON_FOR_ACTION.put(ComboDispatcher.A_PREV_WINDOW,    R.drawable.ic_icon_window_left);
        ICON_FOR_ACTION.put(ComboDispatcher.A_URL_BAR,        R.drawable.ic_icon_globe);
        ICON_FOR_ACTION.put(ComboDispatcher.A_OPEN_BOOKMARKS, R.drawable.ic_icon_bookmarks);
        ICON_FOR_ACTION.put(ComboDispatcher.A_ADD_BOOKMARK,   R.drawable.ic_icon_bookmark);
        ICON_FOR_ACTION.put(ComboDispatcher.A_HISTORY,        R.drawable.ic_icon_history);
        ICON_FOR_ACTION.put(ComboDispatcher.A_READER_MODE,    R.drawable.fd_action_reader_mode_24dp);
        ICON_FOR_ACTION.put(ComboDispatcher.A_PRIVATE_WINDOW, R.drawable.ic_icon_tray_private_browsing_on_v2);
    }

    /**
     * Returns the drawable resource for the given action integer, or the generic
     * "unknown action" drawable if the action is not mapped.
     *
     * @param actionInt a {@code ComboDispatcher.A_*} constant
     * @return a {@link DrawableRes} drawable resource ID
     */
    public static @DrawableRes int iconFor(int actionInt) {
        return ICON_FOR_ACTION.get(actionInt, R.drawable.fd_action_unknown_24dp);
    }
}
