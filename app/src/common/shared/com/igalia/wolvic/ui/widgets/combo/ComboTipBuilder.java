/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Glyphew proprietary — not part of the Wolvic MPL 2.0 codebase.
 */
package com.igalia.wolvic.ui.widgets.combo;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
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
import java.util.LinkedHashMap;
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
 *       meta tips into a fully classified pool ready for
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

    /**
     * Cache key: "&lt;pathKey&gt;#&lt;actionInt&gt;#&lt;iconSizePx&gt;#&lt;color&gt;".
     *
     * <p>Bounded LRU (access-order). Realistic working set is ~34 bindings ×
     * 1-2 icon sizes × 1-2 colors ≈ 100 entries, so 128 gives comfortable
     * headroom while capping worst-case memory if a caller ever sweeps sizes
     * or colors. Single-threaded by contract: {@link #renderBindingTip} is
     * called from the render thread only. Wrap in
     * {@link Collections#synchronizedMap(Map)} if that contract ever widens.
     */
    private static final int BINDING_TIP_CACHE_MAX = 128;
    private static final Map<String, Spannable> BINDING_TIP_CACHE =
            new LinkedHashMap<String, Spannable>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Spannable> eldest) {
                    return size() > BINDING_TIP_CACHE_MAX;
                }
            };

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
     * Like {@link #renderBindingTip} but arrows only — no trailing action icon.
     * Used by the Combos Settings panel where the action label appears as row
     * chrome and repeating the action icon inside the chip would duplicate it.
     * Cached separately under an {@code "arrows#"} key so the binding-tip and
     * chip renders don't evict each other.
     */
    public static Spannable renderArrowsOnly(@NonNull Context ctx,
                                             @NonNull int[] path,
                                             int iconSizePx,
                                             int color) {
        String cacheKey = "arrows#" + Arrays.toString(path)
                + "#" + iconSizePx + "#" + color;
        Spannable cached = BINDING_TIP_CACHE.get(cacheKey);
        if (cached != null) return cached;

        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (int node : path) {
            if (node == 5) continue;
            int start = sb.length();
            sb.append(' ');
            Drawable arrow = createRotatedArrow(ctx, node, iconSizePx, color);
            if (arrow != null) {
                sb.setSpan(new ImageSpan(arrow, ImageSpan.ALIGN_BASELINE),
                        start, sb.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        BINDING_TIP_CACHE.put(cacheKey, sb);
        return sb;
    }

    /**
     * Compose the preview-fire tip shown in the bottom strip while the user is
     * actively leaning toward a bound combo. Text-only: {@code "Release:
     * {action name}"}. The inline ImageSpan variant (rounds 4-5) never sat
     * cleanly on the 28sp VR line — ALIGN_BASELINE trailed below x-height,
     * ALIGN_CENTER read as a trailing footnote — and the ghost pill on the
     * aimed zone already shows the icon, so repeating it in the strip added
     * no information. Cached by {@code actionInt} alone.
     */
    public static Spannable renderReleaseToFireTip(@NonNull Context ctx, int actionInt) {
        String cacheKey = "RTF#" + actionInt;
        Spannable cached = BINDING_TIP_CACHE.get(cacheKey);
        if (cached != null) return cached;

        int nameRes = ComboActionNames.nameFor(actionInt);
        String actionName = (nameRes != 0) ? ctx.getString(nameRes) : "";
        SpannableStringBuilder sb = new SpannableStringBuilder(
                ctx.getString(R.string.fd_tip_release_to_fire, actionName));
        BINDING_TIP_CACHE.put(cacheKey, sb);
        return sb;
    }

    /**
     * Unicode heavy-up arrow. Same glyph the HUD wedge dial uses — renders
     * with a full stem, not a chevron. Tips + chips share the wedge asset
     * so the whole Combos surface reads as one visual family. Drawing one
     * glyph at eight rotations guarantees identical stroke weight across
     * all directions, which font-fallback arrows like U+27A1 do not.
     */
    private static final String ARROW_GLYPH = "\u2B06";

    /**
     * Builds a rotated heavy-up arrow matching the HUD wedge dial. Was the
     * material-triangle vector ({@code ic_baseline_arrow_drop_up_24px}) until
     * S6 (2026-04-21) — Quest 3 feedback flagged that chips and HUD wedges
     * rendered different shapes; now both ride the same Unicode glyph.
     * Node layout:
     * <pre>
     *   1 2 3      0°=up (node 2).
     *   4 5 6      Rotations go CW: 3=45°, 6=90°, 9=135°, 8=180°, 7=225°, 4=270°, 1=315°.
     *   7 8 9
     * </pre>
     */
    @Nullable
    private static Drawable createRotatedArrow(Context ctx, int node, int sizePx, int color) {
        Drawable base = new GlyphArrowDrawable(sizePx, color);
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

    /**
     * Drawable that paints {@link #ARROW_GLYPH} centered in a square bound.
     * Same TextPaint pipeline the HUD wedge dial uses so chips, tip strip,
     * and dial all render the same shape at the same stroke weight.
     */
    private static final class GlyphArrowDrawable extends Drawable {
        private final int mSize;
        private final android.text.TextPaint mPaint;
        private final float mBaselineY;

        GlyphArrowDrawable(int size, int color) {
            mSize = size;
            mPaint = new android.text.TextPaint(Paint.ANTI_ALIAS_FLAG);
            mPaint.setColor(color);
            mPaint.setTextAlign(Paint.Align.CENTER);
            mPaint.setTextSize(size * 0.9f);
            // S6 fix (2026-04-21): U+2B06 (heavy up arrow) has nearly zero
            // descent in most fonts — the stem does not fill to the descent line.
            // FontMetrics-based centering (ascent + descent) places the glyph
            // above the square's midpoint, and at 180° rotation (down arrow) the
            // clipped stem makes it appear shorter. Use getTextBounds() on the
            // actual glyph to measure its ink box and center on that instead.
            android.graphics.Rect bounds = new android.graphics.Rect();
            mPaint.getTextBounds(ARROW_GLYPH, 0, ARROW_GLYPH.length(), bounds);
            // bounds.top < 0 (above baseline), bounds.bottom >= 0 (below baseline).
            // baseline = size/2 - (bounds.top + bounds.bottom)/2 centers ink box.
            mBaselineY = size * 0.5f - (bounds.top + bounds.bottom) * 0.5f;
            setBounds(0, 0, size, size);
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            canvas.drawText(ARROW_GLYPH, mSize * 0.5f, mBaselineY, mPaint);
        }

        @Override public void setAlpha(int alpha) { mPaint.setAlpha(alpha); }
        @Override public void setColorFilter(@Nullable ColorFilter cf) { mPaint.setColorFilter(cf); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
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
     * Enumerates the meta tips followed by every active binding in
     * the dispatcher, each classified by {@link #classify(int[])}.
     *
     * <p>Mode (4-dir vs 8-dir) is implicit: {@code dispatcher.getAllBindings()}
     * already returns the table for the active mode. Callers that need to
     * branch on mode explicitly can query {@code dispatcher.is4DirMode()}.
     */
    public static List<TipCandidate> buildAll(@NonNull ComboDispatcher dispatcher,
                                              @NonNull Resources res) {
        List<TipCandidate> out = new ArrayList<>();

        // Meta tips first.
        out.add(new TipCandidate(
                "meta:" + R.string.gw_meta_tip_release,
                null, ComboDispatcher.A_NONE,
                R.string.gw_meta_tip_release,
                Difficulty.MUST_KNOW));
        out.add(new TipCandidate(
                "meta:" + R.string.gw_meta_tip_cancel,
                null, ComboDispatcher.A_NONE,
                R.string.gw_meta_tip_cancel,
                Difficulty.MUST_KNOW));
        out.add(new TipCandidate(
                "meta:" + R.string.gw_meta_tip_origin,
                null, ComboDispatcher.A_NONE,
                R.string.gw_meta_tip_origin,
                Difficulty.MUST_KNOW));
        out.add(new TipCandidate(
                "meta:" + R.string.gw_meta_tip_long_press_settings,
                null, ComboDispatcher.A_NONE,
                R.string.gw_meta_tip_long_press_settings,
                Difficulty.MUST_KNOW));

        // Binding tips intentionally omitted: rendered as
        // {arrow-glyphs}+{action-icon}, which read as an untranslated icon
        // soup at 1.5m in VR. Round-8 user feedback: "some tips are just
        // some icons, without any meaning, please remove them." The
        // preview-fire label already teaches bindings in context when the
        // user actually commits a node, so we rely on that surface for
        // binding discovery instead of the idle rotation.
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
