/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.UIWidget;

import java.util.ArrayList;
import java.util.Arrays;
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
        LayoutInflater inflater = LayoutInflater.from(ctx);

        java.util.Map<String, Binding> fourDirTable = dispatcher.get4DirBindings();
        java.util.Map<String, Binding> activeTable  = dispatcher.getAllBindings();
        boolean is4DirMode = (activeTable == fourDirTable
                || activeTable.equals(fourDirTable));

        addSystemGesturesSection(inflater, container);
        addActionSections(ctx, inflater, container, activeTable, fourDirTable, is4DirMode, dispatcher);
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
        for (int i = 0; i < DISPLAY_ORDER.length; i++) {
            ComboActionCategory cat = DISPLAY_ORDER[i];
            List<Integer> sectionActions = new ArrayList<>();
            for (int id : actionIds) {
                if (ComboActionRegistry.categoryFor(id) == cat) sectionActions.add(id);
            }
            if (sectionActions.isEmpty()) continue;
            LinearLayout content = addExpandableSection(inflater, container,
                    DISPLAY_HEADERS[i], /* startExpanded= */ true);
            addActionsAsGrid(ctx, inflater, content, sectionActions,
                    primaryPathByAction, dispatcher);
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
                    primaryPathByAction, dispatcher);
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
                                         @NonNull ComboDispatcher dispatcher) {
        int i = 0;
        while (i < actionIds.size()) {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));

            for (int col = 0; col < GRID_COLS; col++) {
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                if (i + col < actionIds.size()) {
                    int id = actionIds.get(i + col);
                    View block = makeActionBlock(ctx, inflater, id,
                            primaryPaths.get(id), dispatcher);
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
                                        @NonNull ComboDispatcher dispatcher) {
        View block = inflater.inflate(R.layout.combo_block_action, null, false);

        // Path disc
        ((ComboPathView) block.findViewById(R.id.path_disc)).setPath(primaryPath);

        // Action label
        int labelRes = ComboActionRegistry.labelFor(actionId);
        ((TextView) block.findViewById(R.id.action_label))
                .setText(labelRes != 0 ? ctx.getString(labelRes) : "");

        // Hover-revealed ✕ or + Create button
        LinearLayout buttonCell = block.findViewById(R.id.button_cell);
        View actionBtn = (primaryPath == null)
                ? makeCreateButton(ctx, dispatcher, actionId)
                : makeDeleteButton(ctx, dispatcher, primaryPath, actionId);

        actionBtn.setVisibility(View.INVISIBLE);
        buttonCell.addView(actionBtn);

        // Hover reveal with 80 ms grace period to reach the button.
        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] btnHovered = {false};
        Runnable[] hideRunnable = {null};
        Runnable doHide = () -> { if (!btnHovered[0]) actionBtn.setVisibility(View.INVISIBLE); };

        block.setOnHoverListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    if (hideRunnable[0] != null) {
                        handler.removeCallbacks(hideRunnable[0]);
                        hideRunnable[0] = null;
                    }
                    actionBtn.setVisibility(View.VISIBLE);
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

    // ── Button factories (unchanged from Phase 4/5) ───────────────────────────

    private static View makeDeleteButton(@NonNull Context ctx,
                                         @NonNull ComboDispatcher dispatcher,
                                         @NonNull int[] path,
                                         int actionId) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (10f * density);
        int padV = (int) (6f * density);

        TextView xView = new TextView(ctx);
        xView.setText("✕");
        xView.setTextSize(20f);
        xView.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text_muted));
        xView.setPadding(padH, padV, padH, padV);
        xView.setContentDescription(ctx.getString(R.string.combos_chip_delete_content_desc));
        xView.setClickable(true);
        xView.setFocusable(true);
        xView.setOnClickListener(v ->
                new ComboUnbindConfirmDialog(ctx, dispatcher, path, actionId)
                        .show(UIWidget.REQUEST_FOCUS));
        return xView;
    }

    private static View makeCreateButton(@NonNull Context ctx,
                                         @NonNull ComboDispatcher dispatcher,
                                         int actionId) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (10f * density);
        int padV = (int) (4f * density);

        TextView btn = new TextView(ctx);
        btn.setBackgroundResource(R.drawable.combo_chip_bg);
        btn.setPadding(padH, padV, padH, padV);
        btn.setTextSize(14f);
        btn.setText(R.string.combos_row_create_button);
        btn.setTextColor(ContextCompat.getColor(ctx, R.color.fd_accent));
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
        for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
            int actionId = e.getValue().action;
            if (actionId <= 0) continue;
            int[] path = parsePathKey(e.getKey());
            if (path.length == 0) continue;
            if (!is4DirMode && fourDirBindings.containsKey(e.getKey())) {
                if (result.get(actionId) == null) result.put(actionId, path);
                continue;
            }
            result.put(actionId, path);
        }
        if (!is4DirMode) {
            for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
                if (fourDirBindings.containsKey(e.getKey())) continue;
                int actionId = e.getValue().action;
                if (actionId <= 0) continue;
                int[] path = parsePathKey(e.getKey());
                if (path.length == 0) continue;
                result.put(actionId, path);
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
