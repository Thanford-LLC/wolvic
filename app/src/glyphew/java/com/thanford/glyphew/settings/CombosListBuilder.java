/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary Glyphew component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.glyphew.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.UIWidget;
import com.thanford.glyphew.settings.Binding;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 — read-only Combos Settings list builder. Populates a
 * {@link LinearLayout} container with:
 *
 * <ol>
 *   <li>System Gestures section (info-only rows, no interactivity).</li>
 *   <li>Action sections (Navigation, Position, Window, Library, Special) as
 *       4-column grids. Each block shows a {@link ComboPathView} disc at top,
 *       the action label in the middle, and a hover-revealed ✕ / + Create
 *       button at the bottom. Unbound actions show a dim ring.</li>
 * </ol>
 */
public final class CombosListBuilder {

    private CombosListBuilder() {}

    private static final ComboActionCategory[] DISPLAY_ORDER = {
            ComboActionCategory.NAVIGATION,
            ComboActionCategory.POSITION,
            ComboActionCategory.WINDOW,
            ComboActionCategory.LIBRARY,
            ComboActionCategory.SPECIAL
    };

    private static final int[] DISPLAY_HEADERS = {
            R.string.combos_section_navigation,
            R.string.combos_section_position,
            R.string.combos_section_window,
            R.string.combos_section_library,
            R.string.combos_section_special
    };

    private static final int GRID_COLS = 4;

    public static void populate(@NonNull Context ctx,
                                @NonNull LinearLayout container,
                                @NonNull ComboDispatcher dispatcher) {
        container.removeAllViews();
        container.setClipChildren(false);
        container.setClipToPadding(false);
        LayoutInflater inflater = LayoutInflater.from(ctx);

        java.util.Map<String, Binding> fourDirTable = dispatcher.get4DirBindings();
        java.util.Map<String, Binding> activeTable  = dispatcher.getAllBindings();
        boolean is4DirMode = (activeTable == fourDirTable
                || activeTable.equals(fourDirTable));

        addSystemGesturesSection(inflater, container);
        addActionSections(ctx, inflater, container, activeTable, fourDirTable, is4DirMode, dispatcher);
        addComboBookmarksSection(ctx, inflater, container, activeTable, dispatcher);
        addDataSection(ctx, inflater, container, dispatcher);
    }

    // ── System gestures (info-only rows, unchanged) ───────────────────────────

    private static void addSystemGesturesSection(@NonNull LayoutInflater inflater,
                                                 @NonNull LinearLayout container) {
        LinearLayout content = addExpandableSection(inflater, container,
                R.string.combos_section_system, /* startExpanded= */ true);
        addSystemGestureRow(inflater, content,
                R.string.combos_gesture_longpress,
                R.string.combos_gesture_longpress_action);
        addSystemGestureRow(inflater, content,
                R.string.combos_gesture_click_idle,
                R.string.combos_gesture_click_idle_action);
        addSystemGestureRow(inflater, content,
                R.string.combos_gesture_click_midpath,
                R.string.combos_gesture_click_midpath_action);
    }

    // ── Action sections (4-column block grid per category) ───────────────────

    private static void addActionSections(@NonNull Context ctx,
                                          @NonNull LayoutInflater inflater,
                                          @NonNull LinearLayout container,
                                          @NonNull Map<String, Binding> activeBindings,
                                          @NonNull Map<String, Binding> fourDirBindings,
                                          boolean is4DirMode,
                                          @NonNull ComboDispatcher dispatcher) {
        android.util.SparseArray<int[]> primaryPathByAction =
                buildPrimaryPaths(activeBindings, fourDirBindings, is4DirMode);
        int[] actionIds = ComboActionRegistry.knownActions();

        // Main sections: show ALL actions for the category (bound and unbound).
        // Actions with dedicated capture flows (isHiddenFromGenericPicker) are excluded
        // from the grid — they appear in their own explainer section below.
        for (int i = 0; i < DISPLAY_ORDER.length; i++) {
            ComboActionCategory cat = DISPLAY_ORDER[i];
            List<Integer> sectionActions = new ArrayList<>();
            for (int id : actionIds) {
                if (ComboActionRegistry.categoryFor(id) == cat
                        && !ComboActionRegistry.isHiddenFromGenericPicker(id)) {
                    sectionActions.add(id);
                }
            }
            if (sectionActions.isEmpty()) continue;
            LinearLayout content = addExpandableSection(inflater, container,
                    DISPLAY_HEADERS[i], /* startExpanded= */ true);
            addActionsAsGrid(ctx, inflater, content, sectionActions,
                    primaryPathByAction, is4DirMode, dispatcher);
        }

        // Unassigned: actions whose category isn't in DISPLAY_ORDER (edge case).
        List<Integer> unassigned = new ArrayList<>();
        for (int id : actionIds) {
            ComboActionCategory cat = ComboActionRegistry.categoryFor(id);
            boolean inDisplay = false;
            for (ComboActionCategory dc : DISPLAY_ORDER) {
                if (cat == dc) { inDisplay = true; break; }
            }
            if (!inDisplay) unassigned.add(id);
        }
        if (!unassigned.isEmpty()) {
            LinearLayout content = addExpandableSection(inflater, container,
                    R.string.combos_section_unassigned, /* startExpanded= */ false);
            addActionsAsGrid(ctx, inflater, content, unassigned,
                    primaryPathByAction, is4DirMode, dispatcher);
        }
    }

    /**
     * Lays out {@code actionIds} in rows of {@link #GRID_COLS} equal-width blocks.
     * Empty slots in the last row are filled with invisible spacers to keep column widths.
     */
    private static void addActionsAsGrid(@NonNull Context ctx,
                                         @NonNull LayoutInflater inflater,
                                         @NonNull LinearLayout content,
                                         @NonNull List<Integer> actionIds,
                                         @NonNull android.util.SparseArray<int[]> primaryPaths,
                                         boolean is4DirMode,
                                         @NonNull ComboDispatcher dispatcher) {
        content.setClipChildren(false);
        content.setClipToPadding(false);
        int i = 0;
        while (i < actionIds.size()) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            row.setClipToPadding(false);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            for (int col = 0; col < GRID_COLS; col++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (i + col < actionIds.size()) {
                    int id = actionIds.get(i + col);
                    View block = makeActionBlock(ctx, inflater, id,
                            primaryPaths.get(id), is4DirMode, dispatcher);
                    block.setLayoutParams(lp);
                    row.addView(block);
                } else {
                    // Invisible spacer to maintain column widths.
                    View spacer = new View(ctx);
                    spacer.setLayoutParams(lp);
                    row.addView(spacer);
                }
            }
            content.addView(row);
            i += GRID_COLS;
        }
    }

    /**
     * Inflates {@code combo_block_action} and wires up the path disc, label,
     * and hover-revealed action button.
     */
    private static View makeActionBlock(@NonNull Context ctx,
                                        @NonNull LayoutInflater inflater,
                                        int actionId,
                                        @Nullable int[] primaryPath,
                                        boolean is4DirMode,
                                        @NonNull ComboDispatcher dispatcher) {
        View block = inflater.inflate(R.layout.combo_block_action, null, false);
        View blockContent = block.findViewById(R.id.block_content);

        // Path disc
        ComboPathView pathDisc = block.findViewById(R.id.path_disc);
        pathDisc.set8DirMode(!is4DirMode);
        pathDisc.setPath(primaryPath);

        // Action label
        int labelRes = ComboActionRegistry.labelFor(actionId);
        ((TextView) block.findViewById(R.id.action_label))
                .setText(labelRes != 0 ? ctx.getString(labelRes) : "");

        // Hover tip: combo path as directional arrows on hover.
        TextView pathTip = block.findViewById(R.id.path_tip);
        if (primaryPath != null) {
            pathTip.setText(pathToArrows(primaryPath));
        }

        // Hover-revealed ✕ or + Create button
        LinearLayout buttonCell = block.findViewById(R.id.button_cell);
        View actionBtn = (primaryPath == null)
                ? makeCreateButton(ctx, dispatcher, actionId)
                : makeDeleteButton(ctx, dispatcher, primaryPath, actionId);

        actionBtn.setVisibility(View.INVISIBLE);
        buttonCell.addView(actionBtn);

        // Hover reveal: show tip + button, 80 ms grace period before hiding.
        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] btnHovered = {false};
        Runnable[] hideRunnable = {null};
        Runnable doHide = () -> {
            if (!btnHovered[0]) {
                if (primaryPath != null) pathTip.setVisibility(View.INVISIBLE);
                actionBtn.setVisibility(View.INVISIBLE);
                blockContent.setBackgroundColor(0xFF0c1140);
            }
        };

        block.setOnHoverListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    if (hideRunnable[0] != null) {
                        handler.removeCallbacks(hideRunnable[0]);
                        hideRunnable[0] = null;
                    }
                    if (primaryPath != null) pathTip.setVisibility(View.VISIBLE);
                    actionBtn.setVisibility(View.VISIBLE);
                    blockContent.setBackgroundColor(0xFF0e1450);
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    hideRunnable[0] = doHide;
                    handler.postDelayed(doHide, 80);
                    break;
            }
            return false;
        });
        actionBtn.setOnHoverListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    btnHovered[0] = true;
                    if (hideRunnable[0] != null) {
                        handler.removeCallbacks(hideRunnable[0]);
                        hideRunnable[0] = null;
                    }
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    btnHovered[0] = false;
                    hideRunnable[0] = doHide;
                    handler.postDelayed(doHide, 80);
                    break;
            }
            return false;
        });

        return block;
    }

    // ── Tooltip content ───────────────────────────────────────────────────────

    /** Converts an int[] path to a directional arrow string, e.g. [2,4] → "↑←". */
    @NonNull
    static String pathToArrows(@NonNull int[] path) {
        StringBuilder sb = new StringBuilder();
        for (int n : path) {
            switch (n) {
                case 2: sb.append('↑'); break;
                case 8: sb.append('↓'); break;
                case 4: sb.append('←'); break;
                case 6: sb.append('→'); break;
                case 3: sb.append('↗'); break;
                case 9: sb.append('↘'); break;
                case 1: sb.append('↖'); break;
                case 7: sb.append('↙'); break;
            }
        }
        return sb.toString();
    }

    // ── Button factories (unchanged from Phase 4/5) ───────────────────────────

    private static View makeDeleteButton(@NonNull Context ctx,
                                         @NonNull ComboDispatcher dispatcher,
                                         @NonNull int[] path,
                                         int actionId) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (10f * density);
        int padV = (int) (5f * density);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setStroke((int) density, 0x30FDDE0A);
        bg.setCornerRadius(4f * density);

        TextView xView = new TextView(ctx);
        xView.setBackground(bg);
        xView.setText("✕  Unbind");
        xView.setTextSize(11f);
        xView.setLetterSpacing(0.08f);
        xView.setTextColor(ContextCompat.getColor(ctx, R.color.gw_accent));
        xView.setPadding(padH, padV, padH, padV);
        xView.setContentDescription(ctx.getString(R.string.combos_chip_delete_content_desc));
        xView.setClickable(true);
        xView.setFocusable(true);
        xView.setOnClickListener(v -> {
            // For A_GOTO_BOOKMARK, pass the bookmark URL so the dialog can delete
            // the associated bookmark on confirm (inverse cascade — plan §6).
            Binding binding = dispatcher.getBindingForPath(path);
            String bookmarkUrl = (binding != null && binding.action == ComboDispatcher.A_GOTO_BOOKMARK)
                    ? binding.param : null;
            new ComboUnbindConfirmDialog(ctx, dispatcher, path, actionId, bookmarkUrl)
                    .show(UIWidget.REQUEST_FOCUS);
        });
        return xView;
    }

    private static View makeCreateButton(@NonNull Context ctx,
                                         @NonNull ComboDispatcher dispatcher,
                                         int actionId) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (10f * density);
        int padV = (int) (5f * density);

        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        bg.setColor(android.graphics.Color.TRANSPARENT);
        bg.setStroke((int) density, 0x14FDDE0A);
        bg.setCornerRadius(4f * density);

        TextView btn = new TextView(ctx);
        btn.setBackground(bg);
        btn.setPadding(padH, padV, padH, padV);
        btn.setTextSize(11f);
        btn.setLetterSpacing(0.08f);
        btn.setText("+ Bind");
        btn.setTextColor(0x44FDDE0A); // dim yellow, matches HTML .combo-btn.assign
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(v -> new BindComboView(ctx, dispatcher, actionId)
                .show(UIWidget.REQUEST_FOCUS));
        return btn;
    }

    // ── Section / row helpers ─────────────────────────────────────────────────

    @NonNull
    private static LinearLayout addExpandableSection(@NonNull LayoutInflater inflater,
                                                     @NonNull LinearLayout container,
                                                     @StringRes int titleRes,
                                                     boolean startExpanded) {
        View header = inflater.inflate(R.layout.combo_row_section_header, container, false);
        ((TextView) header.findViewById(R.id.section_title)).setText(titleRes);
        TextView chevron = header.findViewById(R.id.section_chevron);
        container.addView(header);

        LinearLayout content = new LinearLayout(inflater.getContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        content.setVisibility(startExpanded ? View.VISIBLE : View.GONE);
        chevron.setRotation(startExpanded ? 0f : -90f);
        container.addView(content);

        header.setOnClickListener(v -> {
            boolean nowExpanded = content.getVisibility() != View.VISIBLE;
            content.setVisibility(nowExpanded ? View.VISIBLE : View.GONE);
            chevron.animate().rotation(nowExpanded ? 0f : -90f).setDuration(150).start();
        });
        return content;
    }

    // ── Data section (Export / Import) ────────────────────────────────────────

    private static void addDataSection(@NonNull Context ctx,
                                       @NonNull LayoutInflater inflater,
                                       @NonNull LinearLayout container,
                                       @NonNull ComboDispatcher dispatcher) {
        LinearLayout content = addExpandableSection(inflater, container,
                R.string.gw_combos_data_section, /* startExpanded= */ true);

        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (16f * density);
        int padV = (int) (10f * density);
        int btnMargin = (int) (6f * density);
        int sectionPad = (int) (8f * density);

        content.setPadding(0, sectionPad, 0, sectionPad);

        // Export — combos + bookmarks combined.
        // Notification is in-panel (button text changes) since VR compositor
        // doesn't surface Android system Toasts into the headset lens view.
        String exportLabel = ctx.getString(R.string.gw_backup_export_button);
        content.addView(makeDataButton(ctx, exportLabel, padH, padV, density, btnMargin, v -> {
            TextView btn = (TextView) v;
            btn.setClickable(false);
            btn.setText(R.string.gw_backup_exporting);
            ComboExportImport.exportAsync(ctx, dispatcher, (displayName, clipOk) -> {
                if ("".equals(displayName)) {
                    btn.setText(R.string.gw_backup_export_uptodate);
                } else if (displayName != null) {
                    btn.setText(R.string.gw_backup_export_saved);
                } else {
                    btn.setText(R.string.gw_backup_export_clipboard);
                }
                btn.postDelayed(() -> {
                    btn.setText(exportLabel);
                    btn.setClickable(true);
                }, 2500);
            });
        }));

        // Import — opens combined backup picker.
        content.addView(makeDataButton(ctx,
                ctx.getString(R.string.gw_backup_import_button),
                padH, padV, density, btnMargin, v ->
                    new ComboImportPickerView(ctx, dispatcher).showWithFocus()));
    }

    private static View makeDataButton(@NonNull Context ctx,
                                       @NonNull String label,
                                       int padH, int padV, float density, int bottomMargin,
                                       View.OnClickListener listener) {
        android.graphics.drawable.GradientDrawable normalBg =
                new android.graphics.drawable.GradientDrawable();
        normalBg.setColor(0xFF1C1F7E);
        normalBg.setStroke((int) density, 0xFF32369E);
        normalBg.setCornerRadius(6f * density);

        android.graphics.drawable.GradientDrawable focusedBg =
                new android.graphics.drawable.GradientDrawable();
        focusedBg.setColor(0xFF252AA0);
        focusedBg.setStroke((int) (2f * density), 0xCCFDDE0A);
        focusedBg.setCornerRadius(6f * density);

        android.graphics.drawable.StateListDrawable sld =
                new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{ android.R.attr.state_focused }, focusedBg);
        sld.addState(new int[]{ android.R.attr.state_hovered }, focusedBg);
        sld.addState(new int[]{}, normalBg);

        TextView btn = new TextView(ctx);
        btn.setBackground(sld);
        btn.setPadding(padH, padV, padH, padV);
        btn.setTextSize(14f);
        btn.setText(label);
        btn.setTextColor(ContextCompat.getColor(ctx, R.color.fog));
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(listener);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, bottomMargin);
        btn.setLayoutParams(lp);
        return btn;
    }

    // ── Combo Bookmarks section ──────────────────────────────────────────────

    private static void addComboBookmarksSection(@NonNull Context ctx,
                                                 @NonNull LayoutInflater inflater,
                                                 @NonNull LinearLayout container,
                                                 @NonNull Map<String, Binding> activeBindings,
                                                 @NonNull ComboDispatcher dispatcher) {
        LinearLayout content = addExpandableSection(inflater, container,
                R.string.gw_combos_combo_bookmarks_header, /* startExpanded= */ true);
        // Explainer copy at the top (reuses the system-gesture row layout).
        addSystemGestureRow(inflater, content,
                R.string.gw_combos_combo_bookmarks_header,
                R.string.gw_combos_combo_bookmarks_body);

        // List each bound combo bookmark: path arrows → site host, with an unbind button.
        // binding.param is the bookmark URL, so the host is shown directly with no async lookup.
        for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
            Binding b = e.getValue();
            if (b.action != ComboDispatcher.A_GOTO_BOOKMARK || b.param == null) continue;
            int[] path = parsePathKey(e.getKey());
            if (path.length == 0) continue;
            content.addView(makeComboBookmarkRow(ctx, dispatcher, path, b.param));
        }
    }

    /**
     * Row showing "↑→  example.com" plus an Unbind button for one combo bookmark.
     * Styling follows DESIGN.md tokens: accent-yellow combo arrows (matching the grid
     * path_tip), gw_text host label at settings body size, gw_* spacing dimens.
     */
    private static View makeComboBookmarkRow(@NonNull Context ctx,
                                             @NonNull ComboDispatcher dispatcher,
                                             @NonNull int[] path,
                                             @NonNull String url) {
        android.content.res.Resources res = ctx.getResources();
        int padH = res.getDimensionPixelSize(R.dimen.gw_pad_row_h);
        int padV = res.getDimensionPixelSize(R.dimen.gw_pad_row_v_system);
        int gap  = res.getDimensionPixelSize(R.dimen.gw_pad_gesture_action_start);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(padH, padV, padH, padV);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        // Combo path arrows — accent yellow, matching the grid's path_tip (18sp).
        TextView arrows = new TextView(ctx);
        arrows.setText(pathToArrows(path));
        arrows.setTextColor(ContextCompat.getColor(ctx, R.color.gw_accent));
        arrows.setTextSize(18f);
        arrows.setIncludeFontPadding(false);
        LinearLayout.LayoutParams arrowsLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        arrowsLp.setMarginEnd(gap);
        arrows.setLayoutParams(arrowsLp);
        row.addView(arrows);

        // Site host — default text color at settings body size.
        String host;
        try {
            android.net.Uri u = android.net.Uri.parse(url);
            host = (u.getHost() != null) ? u.getHost() : url;
        } catch (Exception ex) {
            host = url;
        }
        TextView label = new TextView(ctx);
        label.setText(host);
        label.setTextColor(ContextCompat.getColor(ctx, R.color.gw_text));
        label.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                res.getDimension(R.dimen.settings_text_size));
        LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelLp);
        row.addView(label);

        row.addView(makeDeleteButton(ctx, dispatcher, path, ComboDispatcher.A_GOTO_BOOKMARK));
        return row;
    }

    private static void addSystemGestureRow(@NonNull LayoutInflater inflater,
                                            @NonNull LinearLayout container,
                                            @StringRes int labelRes,
                                            @StringRes int actionRes) {
        View row = inflater.inflate(R.layout.combo_row_system_gesture, container, false);
        ((TextView) row.findViewById(R.id.system_gesture_label)).setText(labelRes);
        ((TextView) row.findViewById(R.id.system_gesture_action)).setText(actionRes);
        container.addView(row);
    }

    // ── Path resolution (unchanged) ───────────────────────────────────────────

    @NonNull
    private static android.util.SparseArray<int[]> buildPrimaryPaths(
            @NonNull Map<String, Binding> activeBindings,
            @NonNull Map<String, Binding> fourDirBindings,
            boolean is4DirMode) {
        android.util.SparseArray<int[]> result = new android.util.SparseArray<>();
        if (is4DirMode) {
            // 4-dir: each action has exactly one path; iterate once.
            for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
                int actionId = e.getValue().action;
                if (actionId <= 0) continue;
                int[] path = parsePathKey(e.getKey());
                if (path.length > 0) result.put(actionId, path);
            }
        } else {
            // 8-dir: two explicit passes so the 8-dir-specific path always wins,
            // regardless of HashMap iteration order.
            // Pass 1 — 8-dir specific entries (key absent from the 4-dir table): always preferred.
            for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
                if (fourDirBindings.containsKey(e.getKey())) continue;
                int actionId = e.getValue().action;
                if (actionId <= 0) continue;
                int[] path = parsePathKey(e.getKey());
                if (path.length > 0) result.put(actionId, path);
            }
            // Pass 2 — 4-dir fallback paths: only fill actions with no 8-dir-specific path yet.
            for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
                if (!fourDirBindings.containsKey(e.getKey())) continue;
                int actionId = e.getValue().action;
                if (actionId <= 0) continue;
                if (result.get(actionId) != null) continue;
                int[] path = parsePathKey(e.getKey());
                if (path.length > 0) result.put(actionId, path);
            }
        }
        return result;
    }

    /** Parses Arrays.toString format: "[]", "[2]", "[2, 4, 6]" → int[]. */
    @NonNull
    private static int[] parsePathKey(@Nullable String rawKey) {
        if (rawKey == null || rawKey.length() < 2) return new int[0];
        String trimmed = rawKey.substring(1, rawKey.length() - 1).trim();
        if (trimmed.isEmpty()) return new int[0];
        String[] parts = trimmed.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                return new int[0];
            }
        }
        return out;
    }
}
