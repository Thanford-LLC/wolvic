/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * FingerDance proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ImageSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.content.res.AppCompatResources;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-logic tip pool + Spannable renderer for the Combo-Tips HUD.
 *
 * <p>Owns three responsibilities:
 * <ul>
 *   <li>{@link #classify(int[])} — bucket every binding path into a
 *       difficulty tier ({@link Difficulty}) for the selector's weighted pick.
 *   <li>{@link #renderBindingTip} / {@link #renderMetaTip} — compose a
 *       Spannable of rotated-arrow spans + action icon (or a plain meta
 *       string) for the HUD to draw.
 *   <li>{@link #buildAll} — enumerate the current dispatcher bindings +
 *       three meta tips into a fully classified pool ready for
 *       {@link ComboTipSelector}.
 * </ul>
 *
 * <p>Binding-tip Spannables are cached by (path, actionInt, iconSizePx, color).
 * Call {@link #clearCache()} when the HUD learns bindings have changed, so
 * new composites pick up the latest arrow/action configuration.
 */
public final class ComboTipBuilder {

    private ComboTipBuilder() {}

    /** Difficulty bucket used by {@link ComboTipSelector} for weighted picks. */
    public enum Difficulty {
        MUST_KNOW, EASY, MIDDLE, HARD
    }

    /**
     * Classified tip with just enough metadata for the selector's recency /
     * ring-buffer checks and the HUD's render step.
     */
    public static final class TipCandidate {
        public final String stableId;
        @Nullable public final int[] path;
        public final int actionInt;
        public final int metaStringRes;
        public final Difficulty difficulty;

        public TipCandidate(@NonNull String stableId,
                            @Nullable int[] path,
                            int actionInt,
                            int metaStringRes,
                            @NonNull Difficulty difficulty) {
            this.stableId = stableId;
            this.path = path;
            this.actionInt = actionInt;
            this.metaStringRes = metaStringRes;
            this.difficulty = difficulty;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof TipCandidate)) return false;
            return stableId.equals(((TipCandidate) o).stableId);
        }

        @Override
        public int hashCode() {
            return stableId.hashCode();
        }
    }

    // ---------------------------------------------------------------------
    // Classification
    // ---------------------------------------------------------------------

    /**
     * Buckets a grid path into a difficulty tier. Pure function — no state.
     *
     * <ul>
     *   <li>Meta tips (null / empty path) → {@link Difficulty#MUST_KNOW}.
     *   <li>Single cardinal / cardinal-double → {@link Difficulty#EASY}.
     *   <li>Single diagonal, cross-cardinals (e.g. {2,4}), triple cardinals
     *       → {@link Difficulty#MIDDLE}.
     *   <li>Length ≥ 4, diagonal-double, or any diagonal at position ≥ 1
     *       → {@link Difficulty#HARD}.
     * </ul>
     */
    public static Difficulty classify(@Nullable int[] path) {
        if (path == null || path.length == 0) return Difficulty.MUST_KNOW;

        int len = path.length;
        if (len >= 4) return Difficulty.HARD;

        // Diagonal-double: 2 nodes, both diagonals, same node.
        if (len == 2 && isDiagonal(path[0]) && isDiagonal(path[1]) && path[0] == path[1]) {
            return Difficulty.HARD;
        }

        // Any diagonal at position ≥ 1 (i.e. mid- or tail-of-multi) → HARD.
        if (len >= 2) {
            for (int i = 1; i < len; i++) {
                if (isDiagonal(path[i])) return Difficulty.HARD;
            }
        }
        // By here: len ∈ {1, 2, 3}, no diagonals at pos ≥ 1, and no
        // diagonal-double. Only path[0] may be diagonal.

        if (len == 1) {
            return isDiagonal(path[0]) ? Difficulty.MIDDLE : Difficulty.EASY;
        }

        // len 2 or 3. If starts with a diagonal (cardinals follow), bump to MIDDLE.
        if (isDiagonal(path[0])) return Difficulty.MIDDLE;

        // All cardinals. EASY if cardinal-double (len 2, same node), else MIDDLE.
        if (len == 2 && path[0] == path[1]) return Difficulty.EASY;
        return Difficulty.MIDDLE;
    }

    private static boolean isDiagonal(int node) {
        return node == 1 || node == 3 || node == 7 || node == 9;
    }

    // ---------------------------------------------------------------------
    // Render — binding tips (arrows + action icon)
    // ---------------------------------------------------------------------

    /** Cache key: "<pathKey>#<actionInt>#<iconSizePx>#<color>". */
    private static final HashMap<String, Spannable> BINDING_TIP_CACHE = new HashMap<>();

    /** Invalidates the binding-tip Spannable cache. Call on bindings changed. */
    public static void clearCache() {
        BINDING_TIP_CACHE.clear();
    }

    @VisibleForTesting
    static int cacheSizeForTest() {
        return BINDING_TIP_CACHE.size();
    }

    /**
     * Composes {rotated-arrow spans}{two spaces}{action-icon span} into a
     * Spannable. Cached by (path, actionInt, iconSizePx, color) — repeat
     * calls with identical args return the same Spannable instance.
     *
     * @param ctx context used to resolve drawable resources.
     * @param path non-null, non-empty grid path (5 omitted).
     * @param actionInt {@code ComboDispatcher.A_*} to render as an action icon.
     * @param iconSizePx target px size for both arrow + action glyphs.
     * @param color ARGB color to tint every glyph.
     */
    public static Spannable renderBindingTip(@NonNull Context ctx,
                                             @NonNull int[] path,
                                             int actionInt,
                                             int iconSizePx,
                                             int color) {
        String cacheKey = Arrays.toString(path) + "#" + actionInt
                + "#" + iconSizePx + "#" + color;
        Spannable cached = BINDING_TIP_CACHE.get(cacheKey);
        if (cached != null) return cached;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int node : path) {
            if (node == 5) continue;
            int start = sb.length();
            sb.append(' ');  // single char placeholder for the ImageSpan.
            Drawable arrow = createRotatedArrow(ctx, node, iconSizePx, color);
            if (arrow != null) {
                sb.setSpan(new ImageSpan(arrow, ImageSpan.ALIGN_BASELINE),
                        start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        // Two literal spaces, then the action icon.
        sb.append("  ");
        int iconStart = sb.length();
        sb.append(' ');
        Drawable actionIcon = createTintedIcon(ctx,
                ComboActionIcons.iconFor(actionInt), iconSizePx, color);
        if (actionIcon != null) {
            sb.setSpan(new ImageSpan(actionIcon, ImageSpan.ALIGN_BASELINE),
                    iconStart, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        BINDING_TIP_CACHE.put(cacheKey, sb);
        return sb;
    }

    /**
     * Loads the arrow drawable, tints it, wraps it in a per-node rotation
     * so the tip reads the stroke direction at a glance. Node layout:
     * <pre>
     *   1 2 3      0°=up (node 2).
     *   4 5 6      Rotations go CW: 3=45°, 6=90°, 9=135°, 8=180°, 7=225°, 4=270°, 1=315°.
     *   7 8 9
     * </pre>
     */
    @Nullable
    private static Drawable createRotatedArrow(Context ctx, int node, int sizePx, int color) {
        Drawable base = AppCompatResources.getDrawable(ctx,
                R.drawable.ic_baseline_arrow_drop_up_24px);
        if (base == null) return null;
        base = base.mutate();
        base.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        base.setBounds(0, 0, sizePx, sizePx);
        float angle = angleForNode(node);
        if (angle == 0f) return base;
        return new RotatingDrawable(base, angle, sizePx);
    }

    @Nullable
    private static Drawable createTintedIcon(Context ctx, int resId, int sizePx, int color) {
        Drawable d = AppCompatResources.getDrawable(ctx, resId);
        if (d == null) return null;
        d = d.mutate();
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        d.setBounds(0, 0, sizePx, sizePx);
        return d;
    }

    private static float angleForNode(int node) {
        switch (node) {
            case 2: return 0f;
            case 3: return 45f;
            case 6: return 90f;
            case 9: return 135f;
            case 8: return 180f;
            case 7: return 225f;
            case 4: return 270f;
            case 1: return 315f;
            default: return 0f;
        }
    }

    /**
     * Drawable wrapper that rotates its child around center during draw.
     * Small and allocation-free once constructed — used per arrow glyph in
     * cached Spannables, so construction cost is amortized.
     */
    private static final class RotatingDrawable extends Drawable {
        private final Drawable mChild;
        private final float mAngleDeg;
        private final int mSize;

        RotatingDrawable(Drawable child, float angleDeg, int size) {
            mChild = child;
            mAngleDeg = angleDeg;
            mSize = size;
            setBounds(0, 0, size, size);
            mChild.setBounds(0, 0, size, size);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            int save = canvas.save();
            canvas.rotate(mAngleDeg, mSize * 0.5f, mSize * 0.5f);
            mChild.draw(canvas);
            canvas.restoreToCount(save);
        }

        @Override public void setAlpha(int alpha) { mChild.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { mChild.setColorFilter(cf); }
        @Override public int getOpacity() { return mChild.getOpacity(); }
        @Override public int getIntrinsicWidth() { return mSize; }
        @Override public int getIntrinsicHeight() { return mSize; }
    }

    // ---------------------------------------------------------------------
    // Render — meta tips
    // ---------------------------------------------------------------------

    /** Returns the plain localized string for a meta tip. */
    public static CharSequence renderMetaTip(int metaStringResId, @NonNull Resources res) {
        return res.getString(metaStringResId);
    }

    // ---------------------------------------------------------------------
    // Pool assembly
    // ---------------------------------------------------------------------

    /**
     * Enumerates the three meta tips followed by every active binding in
     * the dispatcher, each classified by {@link #classify(int[])}.
     *
     * <p>The {@code is4DirMode} argument is accepted for parity with the
     * Phase-3 HUD's mode-aware build call — the bindings come straight
     * from {@code dispatcher.getAllBindings()} which already returns the
     * correct table for the current mode. It's here so callers can pass
     * intent without depending on the dispatcher's internal mode query.
     */
    public static List<TipCandidate> buildAll(@NonNull ComboDispatcher dispatcher,
                                              @NonNull Resources res,
                                              boolean is4DirMode) {
        List<TipCandidate> out = new ArrayList<>();

        // Meta tips first.
        out.add(new TipCandidate(
                "meta:" + R.string.fd_meta_tip_release,
                null, ComboDispatcher.A_NONE,
                R.string.fd_meta_tip_release,
                Difficulty.MUST_KNOW));
        out.add(new TipCandidate(
                "meta:" + R.string.fd_meta_tip_cancel,
                null, ComboDispatcher.A_NONE,
                R.string.fd_meta_tip_cancel,
                Difficulty.MUST_KNOW));
        out.add(new TipCandidate(
                "meta:" + R.string.fd_meta_tip_origin,
                null, ComboDispatcher.A_NONE,
                R.string.fd_meta_tip_origin,
                Difficulty.MUST_KNOW));

        // Binding tips.
        Map<String, Integer> bindings = dispatcher.getAllBindings();
        for (Map.Entry<String, Integer> e : bindings.entrySet()) {
            int[] path = parsePathKey(e.getKey());
            int action = e.getValue();
            out.add(new TipCandidate(
                    "bind:" + Arrays.toString(path),
                    path,
                    action,
                    /* metaStringRes */ 0,
                    classify(path)));
        }

        // is4DirMode is passed for future use (e.g. explicit mode filtering);
        // current implementation trusts dispatcher.getAllBindings() to be
        // mode-consistent.
        if (is4DirMode) {
            // No-op — signature parity with Phase-3 HUD call site.
        }

        return Collections.unmodifiableList(out);
    }

    private static int[] parsePathKey(String k) {
        if (k == null || k.length() < 2) return new int[0];
        String trimmed = k.substring(1, k.length() - 1).trim();
        if (trimmed.isEmpty()) return new int[0];
        String[] parts = trimmed.split(",");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = Integer.parseInt(parts[i].trim());
        }
        return out;
    }
}
