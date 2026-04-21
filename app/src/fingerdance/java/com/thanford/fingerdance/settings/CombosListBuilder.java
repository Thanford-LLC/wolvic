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
import android.view.Gravity;
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
import com.igalia.wolvic.ui.widgets.UIWidget;
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
 * <p>Phase 4 wires each bound-combo chip's trailing {@code ✕} to a
 * {@link ComboUnbindConfirmDialog} → {@link ComboDispatcher#removeBinding}.
 * {@code + Create} still lands in Phase 5.
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
        addActionSections(ctx, inflater, container, dispatcher.getAllBindings(), dispatcher);
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
                                          @NonNull Map<String, Binding> bindings,
                                          @NonNull ComboDispatcher dispatcher) {
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
                addActionRow(ctx, inflater, container, actionId, pathsByAction.get(actionId), dispatcher);
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
                                     @Nullable List<int[]> paths,
                                     @NonNull ComboDispatcher dispatcher) {
        View row = inflater.inflate(R.layout.combo_row_action, container, false);
        ((TextView) row.findViewById(R.id.action_label)).setText(ComboActionRegistry.labelFor(actionId));

        LinearLayout chipContainer = row.findViewById(R.id.chip_container);
        int chipColor = ContextCompat.getColor(ctx, R.color.fd_accent);
        int iconSizePx = (int) (24f * ctx.getResources().getDisplayMetrics().density);

        if (paths == null || paths.isEmpty()) {
            chipContainer.addView(makeUnboundChip(chipContainer));
        } else {
            for (int[] path : paths) {
                chipContainer.addView(makePathChip(ctx, chipContainer, path, iconSizePx, chipColor,
                        dispatcher, actionId));
            }
        }
        container.addView(row);
    }

    /**
     * Phase 4 — bound-combo chip as a horizontal {@link LinearLayout} wrapper
     * holding [arrows TextView][X TextView]. Wrapper carries the pill background
     * so the arrows and X sit inside the same capsule; the X has its own
     * onClickListener that launches {@link ComboUnbindConfirmDialog}. Arrows
     * TextView stays non-clickable so tapping the glyphs does not fire the
     * destructive flow by accident.
     */
    private static View makePathChip(@NonNull Context ctx,
                                     @NonNull ViewGroup parent,
                                     @NonNull int[] path,
                                     int iconSizePx,
                                     int chipColor,
                                     @NonNull ComboDispatcher dispatcher,
                                     int actionId) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int marginPx = (int) (4f * density);
        int padH = (int) (10f * density);
        int padV = (int) (4f * density);

        LinearLayout wrap = new LinearLayout(ctx);
        wrap.setOrientation(LinearLayout.HORIZONTAL);
        wrap.setGravity(Gravity.CENTER_VERTICAL);
        wrap.setBackgroundResource(R.drawable.combo_chip_bg);
        wrap.setPadding(padH, padV, padH, padV);
        LinearLayout.LayoutParams wrapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        wrapLp.setMarginStart(marginPx);
        wrapLp.setMarginEnd(marginPx);
        wrap.setLayoutParams(wrapLp);

        TextView arrowsView = new TextView(ctx);
        arrowsView.setTextSize(20f);
        arrowsView.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text));
        Spannable arrows = ComboTipBuilder.renderArrowsOnly(ctx, path, iconSizePx, chipColor);
        arrowsView.setText(arrows);
        wrap.addView(arrowsView);

        TextView xView = new TextView(ctx);
        xView.setText("\u2715"); // multiplication X (U+2715)
        xView.setTextSize(20f);
        xView.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text_muted));
        int xPadLeft = (int) (8f * density);
        int xPadHitbox = (int) (6f * density);
        xView.setPadding(xPadLeft, xPadHitbox, xPadHitbox, xPadHitbox);
        xView.setContentDescription(ctx.getString(R.string.combos_chip_delete_content_desc));
        xView.setClickable(true);
        xView.setFocusable(true);
        xView.setOnClickListener(v ->
                new ComboUnbindConfirmDialog(ctx, dispatcher, path, actionId)
                        .show(UIWidget.REQUEST_FOCUS));
        wrap.addView(xView);

        return wrap;
    }

    private static View makeUnboundChip(@NonNull ViewGroup parent) {
        Context ctx = parent.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        int marginPx = (int) (4f * density);
        int padH = (int) (10f * density);
        int padV = (int) (4f * density);

        TextView chip = new TextView(ctx);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginStart(marginPx);
        lp.setMarginEnd(marginPx);
        chip.setLayoutParams(lp);
        chip.setBackgroundResource(R.drawable.combo_chip_bg);
        chip.setPadding(padH, padV, padH, padV);
        chip.setTextSize(20f);
        chip.setText(R.string.combos_row_unbound);
        chip.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text_dim));
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
