/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.text.Spannable;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Phase 3 — read-only Combos Settings list builder. Populates a
 * {@link LinearLayout} container with:
 *
 * <ol>
 *   <li>System Gestures section (info-only rows, current-mode toggle hint
 *       per sealed decision T1)</li>
 *   <li>Action sections in display order (Navigation, Position, Window,
 *       Library, Special) with one row per known action, listing every
 *       bound path as a chip. Unbound actions render a "Not bound" chip.</li>
 * </ol>
 *
 * <p>No interactions in Phase 3 — chip X-delete lands Phase 4,
 * {@code + Create} lands Phase 5.
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

    public static void populate(@NonNull Context ctx,
                                @NonNull LinearLayout container,
                                @NonNull ComboDispatcher dispatcher) {
        container.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(ctx);

        addSystemGesturesSection(inflater, container, dispatcher.isCombo4DirMode());
        addActionSections(ctx, inflater, container, dispatcher.getAllBindings());
    }

    private static void addSystemGesturesSection(@NonNull LayoutInflater inflater,
                                                 @NonNull LinearLayout container,
                                                 boolean fourDir) {
        addSectionHeader(inflater, container, R.string.combos_section_system);
        addSystemGestureRow(inflater, container,
                R.string.combos_gesture_longpress,
                R.string.combos_gesture_longpress_action);
        addSystemGestureRow(inflater, container,
                R.string.combos_gesture_click_idle,
                R.string.combos_gesture_click_idle_action);
        addSystemGestureRow(inflater, container,
                R.string.combos_gesture_click_midpath,
                R.string.combos_gesture_click_midpath_action);
        if (fourDir) {
            addSystemGestureRow(inflater, container,
                    R.string.combos_gesture_mode_4dir_escape,
                    R.string.combos_gesture_mode_4dir_escape_action);
        } else {
            addSystemGestureRow(inflater, container,
                    R.string.combos_gesture_mode_8dir_escape,
                    R.string.combos_gesture_mode_8dir_escape_action);
        }
    }

    private static void addActionSections(@NonNull Context ctx,
                                          @NonNull LayoutInflater inflater,
                                          @NonNull LinearLayout container,
                                          @NonNull Map<String, Binding> bindings) {
        SparseArray<List<int[]>> pathsByAction = invertBindings(bindings);
        int[] actionIds = ComboActionRegistry.knownActions();

        for (int i = 0; i < DISPLAY_ORDER.length; i++) {
            ComboActionCategory cat = DISPLAY_ORDER[i];
            boolean headerAdded = false;
            for (int actionId : actionIds) {
                if (ComboActionRegistry.categoryFor(actionId) != cat) continue;
                if (!headerAdded) {
                    addSectionHeader(inflater, container, DISPLAY_HEADERS[i]);
                    headerAdded = true;
                }
                addActionRow(ctx, inflater, container, actionId, pathsByAction.get(actionId));
            }
        }
    }

    private static SparseArray<List<int[]>> invertBindings(@NonNull Map<String, Binding> bindings) {
        SparseArray<List<int[]>> out = new SparseArray<>();
        for (Map.Entry<String, Binding> e : bindings.entrySet()) {
            int[] path = parsePathKey(e.getKey());
            if (path.length == 0) continue;
            int action = e.getValue().action;
            List<int[]> list = out.get(action);
            if (list == null) {
                list = new ArrayList<>(2);
                out.put(action, list);
            }
            list.add(path);
        }
        return out;
    }

    private static void addSectionHeader(@NonNull LayoutInflater inflater,
                                         @NonNull LinearLayout container,
                                         @StringRes int titleRes) {
        View row = inflater.inflate(R.layout.combo_row_section_header, container, false);
        ((TextView) row.findViewById(R.id.section_title)).setText(titleRes);
        container.addView(row);
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

    private static void addActionRow(@NonNull Context ctx,
                                     @NonNull LayoutInflater inflater,
                                     @NonNull LinearLayout container,
                                     int actionId,
                                     @Nullable List<int[]> paths) {
        View row = inflater.inflate(R.layout.combo_row_action, container, false);
        ((TextView) row.findViewById(R.id.action_label)).setText(ComboActionRegistry.labelFor(actionId));

        LinearLayout chipContainer = row.findViewById(R.id.chip_container);
        int chipColor = ContextCompat.getColor(ctx, R.color.fd_accent);
        int iconSizePx = (int) (24f * ctx.getResources().getDisplayMetrics().density);

        if (paths == null || paths.isEmpty()) {
            chipContainer.addView(makeUnboundChip(inflater, chipContainer));
        } else {
            for (int[] path : paths) {
                chipContainer.addView(makePathChip(ctx, inflater, chipContainer, path, iconSizePx, chipColor));
            }
        }
        container.addView(row);
    }

    private static TextView makePathChip(@NonNull Context ctx,
                                         @NonNull LayoutInflater inflater,
                                         @NonNull ViewGroup parent,
                                         @NonNull int[] path,
                                         int iconSizePx,
                                         int chipColor) {
        TextView chip = makeBaseChip(ctx, parent);
        Spannable arrows = ComboTipBuilder.renderArrowsOnly(ctx, path, iconSizePx, chipColor);
        chip.setText(arrows);
        return chip;
    }

    private static TextView makeUnboundChip(@NonNull LayoutInflater inflater,
                                            @NonNull ViewGroup parent) {
        TextView chip = makeBaseChip(parent.getContext(), parent);
        chip.setText(R.string.combos_row_unbound);
        chip.setTextColor(ContextCompat.getColor(parent.getContext(), R.color.fd_text_dim));
        return chip;
    }

    private static TextView makeBaseChip(@NonNull Context ctx, @NonNull ViewGroup parent) {
        TextView chip = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        int marginPx = (int) (4f * ctx.getResources().getDisplayMetrics().density);
        lp.setMarginStart(marginPx);
        lp.setMarginEnd(marginPx);
        chip.setLayoutParams(lp);
        chip.setBackgroundResource(R.drawable.combo_chip_bg);
        int padH = (int) (10f * ctx.getResources().getDisplayMetrics().density);
        int padV = (int) (4f * ctx.getResources().getDisplayMetrics().density);
        chip.setPadding(padH, padV, padH, padV);
        chip.setTextSize(20f);
        chip.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text));
        return chip;
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
