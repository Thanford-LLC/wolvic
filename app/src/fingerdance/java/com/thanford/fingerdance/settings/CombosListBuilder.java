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
import android.text.Spannable;
import android.text.style.ImageSpan;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import android.view.Gravity;

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

        // Phase 7: if user drew an unbound combo and pressed A/X, show a banner
        // at the top prompting them to pick an action to bind the captured path to.
        int[] pendingPath = dispatcher.getPendingCapturePath();
        if (pendingPath != null && pendingPath.length > 0) {
            addFromCaptureBanner(ctx, inflater, container, pendingPath, dispatcher);
        }

        // In 8-dir mode we pass the 4-dir table so the builder can exclude
        // 4-dir-style fallback paths that were mirrored into the 8-dir table
        // (e.g. [6,6,6] → A_NEW_WINDOW). Paths that appear in BOTH tables at the
        // same key+action are "shared" and ARE shown; those mirrored exclusively
        // for the 4-dir fallback are skipped, leaving only the 8-dir-native paths.
        java.util.Map<String, Binding> fourDirTable = dispatcher.get4DirBindings();
        java.util.Map<String, Binding> activeTable  = dispatcher.getAllBindings();
        boolean is4DirMode = (activeTable == fourDirTable
                || activeTable.equals(fourDirTable));

        addSystemGesturesSection(inflater, container);
        addActionSections(ctx, inflater, container, activeTable, fourDirTable, is4DirMode, dispatcher, pendingPath);
    }

    /**
     * Phase 7 FROM_CAPTURE banner: shown at the top of the list when the user
     * drew an unbound combo and pressed A/X while browsing. Tells the user what
     * path was captured and prompts them to tap + Create on any action row below.
     */
    private static void addFromCaptureBanner(@NonNull Context ctx,
                                              @NonNull LayoutInflater inflater,
                                              @NonNull LinearLayout container,
                                              @NonNull int[] capturedPath,
                                              @NonNull ComboDispatcher dispatcher) {
        int chipColor  = ContextCompat.getColor(ctx, R.color.fd_accent);
        int iconSizePx = (int) (20f * ctx.getResources().getDisplayMetrics().scaledDensity);
        android.text.Spannable arrows = com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder
                .renderArrowsOnly(ctx, capturedPath, iconSizePx, chipColor);

        TextView banner = new TextView(ctx);
        banner.setTextSize(14f);
        banner.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text));
        banner.setBackgroundColor(ContextCompat.getColor(ctx, R.color.fd_surface_raised));
        int padPx = (int) (12f * ctx.getResources().getDisplayMetrics().density);
        banner.setPadding(padPx, padPx, padPx, padPx);

        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        sb.append(ctx.getString(R.string.combos_capture_banner_prefix));
        sb.append(" ");
        sb.append(arrows);
        sb.append(" ");
        sb.append(ctx.getString(R.string.combos_capture_banner_suffix));
        banner.setText(sb);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) (8f * ctx.getResources().getDisplayMetrics().density);
        container.addView(banner, lp);
    }

    // S3 fix: mode-escape paths ([2]×8 in 4-dir, [2]×4 in 8-dir) remain wired
    // in the binding tables but are intentionally NOT surfaced here. Keeping
    // them out of the System Gestures section preserves the easter-egg
    // framing called out in CLAUDE.md and the sealed Combos plan.
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

    private static void addActionSections(@NonNull Context ctx,
                                          @NonNull LayoutInflater inflater,
                                          @NonNull LinearLayout container,
                                          @NonNull Map<String, Binding> activeBindings,
                                          @NonNull Map<String, Binding> fourDirBindings,
                                          boolean is4DirMode,
                                          @NonNull ComboDispatcher dispatcher,
                                          @Nullable int[] pendingPath) {
        // Build action → primary path map (one path per action, 8-dir-native preferred).
        SparseArray<int[]> primaryPathByAction = buildPrimaryPaths(
                activeBindings, fourDirBindings, is4DirMode);
        int[] actionIds = ComboActionRegistry.knownActions();

        // FROM_CAPTURE mode: only show unassigned actions so the user can pick a
        // home for the new path without wading through already-bound rows.
        if (pendingPath != null && pendingPath.length > 0) {
            LinearLayout content = addExpandableSection(inflater, container,
                    R.string.combos_section_unassigned, /* startExpanded= */ true);
            for (int actionId : actionIds) {
                if (primaryPathByAction.get(actionId) != null) continue; // already bound
                addActionRow(ctx, inflater, content, actionId, null, dispatcher, pendingPath);
            }
            return;
        }

        // Normal (non-capture) mode: full categorised list.
        List<Integer> unassigned = new ArrayList<>();

        for (int i = 0; i < DISPLAY_ORDER.length; i++) {
            ComboActionCategory cat = DISPLAY_ORDER[i];
            List<Integer> actionIdsForCat = new ArrayList<>();
            for (int actionId : actionIds) {
                if (ComboActionRegistry.categoryFor(actionId) != cat) continue;
                actionIdsForCat.add(actionId);
            }
            if (actionIdsForCat.isEmpty()) continue;
            LinearLayout content = addExpandableSection(inflater, container,
                    DISPLAY_HEADERS[i], /* startExpanded= */ true);
            for (int actionId : actionIdsForCat) {
                int[] primary = primaryPathByAction.get(actionId);
                if (primary == null) {
                    unassigned.add(actionId);
                    continue;
                }
                addActionRow(ctx, inflater, content, actionId, primary, dispatcher, pendingPath);
            }
        }

        // Unassigned section: collapsed by default.
        // Also includes actions whose category didn't match DISPLAY_ORDER (edge case).
        for (int actionId : actionIds) {
            if (primaryPathByAction.get(actionId) == null) {
                ComboActionCategory cat = ComboActionRegistry.categoryFor(actionId);
                boolean alreadyAdded = false;
                for (int displayCat : new int[]{0, 1, 2, 3, 4}) { // DISPLAY_ORDER indices
                    if (cat == DISPLAY_ORDER[displayCat]) { alreadyAdded = true; break; }
                }
                if (!alreadyAdded && !unassigned.contains(actionId)) {
                    unassigned.add(actionId);
                }
            }
        }

        if (!unassigned.isEmpty()) {
            LinearLayout unassignedContent = addExpandableSection(inflater, container,
                    R.string.combos_section_unassigned, /* startExpanded= */ false);
            for (int actionId : unassigned) {
                addActionRow(ctx, inflater, unassignedContent, actionId, null, dispatcher, pendingPath);
            }
        }
    }

    /**
     * Builds a map from action id → the single "primary" path to show in the list.
     *
     * <p>In 4-dir mode: straightforward — one path per action from the active table.
     *
     * <p>In 8-dir mode: the active table contains both 8-dir-native paths (diagonals)
     * AND 4-dir cardinal fallbacks mirrored via {@code putIfAbsent}. To avoid showing
     * two chips for one action ("displaying 8-dir and 4-dir at the same time"), we
     * prefer the 8-dir-native path (not present in the 4-dir table) when one exists;
     * otherwise fall back to the shared path.
     */
    @NonNull
    private static SparseArray<int[]> buildPrimaryPaths(
            @NonNull Map<String, Binding> activeBindings,
            @NonNull Map<String, Binding> fourDirBindings,
            boolean is4DirMode) {
        SparseArray<int[]> result = new SparseArray<>();
        // First pass: collect 8-dir-native paths (in active but NOT in 4-dir table).
        for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
            int actionId = e.getValue().action;
            if (actionId <= 0) continue;
            int[] path = parsePathKey(e.getKey());
            if (path.length == 0) continue;
            if (!is4DirMode && fourDirBindings.containsKey(e.getKey())) {
                // Shared/mirrored path — only use as fallback; don't overwrite a
                // native path already stored, but do record for the second pass.
                if (result.get(actionId) == null) {
                    result.put(actionId, path); // tentative fallback
                }
                continue;
            }
            // Strictly active-mode path (4-dir mode path, or 8-dir-native path).
            result.put(actionId, path);
        }
        // Second pass in 8-dir: replace tentative fallbacks with 8-dir-native paths
        // found later in iteration. (HashMap order is non-deterministic, so iterate
        // a second time to pick up any native paths that weren't seen first.)
        if (!is4DirMode) {
            for (Map.Entry<String, Binding> e : activeBindings.entrySet()) {
                if (fourDirBindings.containsKey(e.getKey())) continue; // skip shared/mirrored
                int actionId = e.getValue().action;
                if (actionId <= 0) continue;
                int[] path = parsePathKey(e.getKey());
                if (path.length == 0) continue;
                result.put(actionId, path); // 8-dir-native: always preferred
            }
        }
        return result;
    }

    /**
     * S10 (2026-04-21) — collapsible section header. Inflates
     * {@code combo_row_section_header} (which now contains a chevron TextView),
     * creates a sibling {@link LinearLayout} content container, and wires the
     * header click to toggle content visibility + chevron glyph (▼/▶).
     *
     * @param startExpanded whether the section content starts visible
     * @return the content container into which callers should add rows
     */
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
        // ▼ glyph is fixed in XML; rotate −90° = pointing right (collapsed), 0° = pointing down (expanded).
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

    private static void addActionRow(@NonNull Context ctx,
                                     @NonNull LayoutInflater inflater,
                                     @NonNull LinearLayout container,
                                     int actionId,
                                     @Nullable int[] primaryPath,
                                     @NonNull ComboDispatcher dispatcher,
                                     @Nullable int[] pendingCapturePath) {
        View row = inflater.inflate(R.layout.combo_row_action, container, false);
        int labelRes = ComboActionRegistry.labelFor(actionId);
        ((TextView) row.findViewById(R.id.action_label))
                .setText(labelRes != 0 ? ctx.getString(labelRes) : "");

        LinearLayout comboCell  = row.findViewById(R.id.combo_cell);
        LinearLayout buttonCell = row.findViewById(R.id.button_cell);

        int chipColor  = ContextCompat.getColor(ctx, R.color.fd_accent);
        int iconSizePx = (int) (20f * ctx.getResources().getDisplayMetrics().scaledDensity);

        final View actionBtn;
        if (primaryPath == null) {
            // Unbound: dim "—" in combo cell; hover-revealed + Create in button cell.
            TextView naLabel = new TextView(ctx);
            naLabel.setText("\u2014"); // em dash
            naLabel.setTextSize(14f);
            naLabel.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text_dim));
            comboCell.addView(naLabel);
            actionBtn = makeCreateButton(ctx, dispatcher, actionId, pendingCapturePath);
        } else {
            // Bound: arrow pill in combo cell; hover-revealed ✕ in button cell.
            comboCell.addView(makeArrowPill(ctx, primaryPath, iconSizePx, chipColor));
            actionBtn = makeDeleteButton(ctx, dispatcher, primaryPath, actionId);
        }

        // Force combo_cell to vertically center itself in the row regardless of
        // whatever gravity the XML inflation produced.
        LinearLayout.LayoutParams comboCellLp =
                (LinearLayout.LayoutParams) comboCell.getLayoutParams();
        if (comboCellLp != null) {
            comboCellLp.gravity = Gravity.CENTER_VERTICAL;
            comboCell.setLayoutParams(comboCellLp);
        }

        // Hover-reveal: button is invisible until the row is activated by the ray cursor.
        actionBtn.setVisibility(View.INVISIBLE);
        buttonCell.addView(actionBtn);

        Handler handler = new Handler(Looper.getMainLooper());
        boolean[] btnHovered = {false};
        Runnable[] hideRunnable = {null};
        Runnable doHide = () -> { if (!btnHovered[0]) actionBtn.setVisibility(View.INVISIBLE); };

        row.setOnHoverListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_HOVER_ENTER:
                    if (hideRunnable[0] != null) { handler.removeCallbacks(hideRunnable[0]); hideRunnable[0] = null; }
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
                    if (hideRunnable[0] != null) { handler.removeCallbacks(hideRunnable[0]); hideRunnable[0] = null; }
                    break;
                case MotionEvent.ACTION_HOVER_EXIT:
                    btnHovered[0] = false;
                    hideRunnable[0] = doHide;
                    handler.postDelayed(doHide, 80);
                    break;
            }
            return false;
        });

        container.addView(row);
    }

    /**
     * S11 — Arrow pill for the combo_cell (middle column). Contains only the
     * path glyph — no X. Separated from the delete button so the button lives
     * in the rightmost button_cell column.
     */
    private static View makeArrowPill(@NonNull Context ctx,
                                      @NonNull int[] path,
                                      int iconSizePx,
                                      int chipColor) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (10f * density);
        int padV = (int) (4f * density);

        TextView arrowsView = new TextView(ctx);
        arrowsView.setBackgroundResource(R.drawable.combo_chip_bg);
        arrowsView.setPadding(padH, padV, padH, padV);
        // R5c — keep TextView text size ≥ image-span size so VR line metrics
        // don't clip the arrow drawables.
        arrowsView.setTextSize(28f);
        arrowsView.setTextColor(ContextCompat.getColor(ctx, R.color.fd_text));
        Spannable arrows = ComboTipBuilder.renderArrowsOnly(ctx, path, iconSizePx, chipColor);
        // ComboTipBuilder uses ALIGN_BASELINE which sits images above the baseline,
        // making them appear at the top of a VR list row. Replace with ALIGN_CENTER
        // (API 29+; Quest runs Android 10+) so arrows sit in the middle of the line.
        for (ImageSpan span : arrows.getSpans(0, arrows.length(), ImageSpan.class)) {
            int start = arrows.getSpanStart(span);
            int end   = arrows.getSpanEnd(span);
            int flags = arrows.getSpanFlags(span);
            arrows.removeSpan(span);
            arrows.setSpan(new ImageSpan(span.getDrawable(), ImageSpan.ALIGN_CENTER),
                    start, end, flags);
        }
        arrowsView.setText(arrows);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.CENTER_VERTICAL;
        arrowsView.setLayoutParams(lp);
        return arrowsView;
    }

    /**
     * S11 — Delete button for the button_cell (right column). Hover-revealed;
     * tapping launches {@link ComboUnbindConfirmDialog}.
     */
    private static View makeDeleteButton(@NonNull Context ctx,
                                         @NonNull ComboDispatcher dispatcher,
                                         @NonNull int[] path,
                                         int actionId) {
        float density = ctx.getResources().getDisplayMetrics().density;
        int padH = (int) (10f * density);
        int padV = (int) (6f * density);

        TextView xView = new TextView(ctx);
        xView.setText("\u2715"); // multiplication X (U+2715)
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

    /**
     * Phase 5 — "+ Create" button for the button_cell (right column).
     * Hover-revealed; launches {@link BindComboView} in FROM_SETTINGS mode.
     */
    private static View makeCreateButton(@NonNull Context ctx,
                                         @NonNull ComboDispatcher dispatcher,
                                         int actionId,
                                         @Nullable int[] pendingCapturePath) {
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
        btn.setOnClickListener(v -> {
            BindComboView view = (pendingCapturePath != null && pendingCapturePath.length > 0)
                    ? BindComboView.forCapture(ctx, dispatcher, actionId, pendingCapturePath)
                    : new BindComboView(ctx, dispatcher, actionId);
            view.show(UIWidget.REQUEST_FOCUS);
        });
        return btn;
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
