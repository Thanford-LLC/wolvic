package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.preference.PreferenceManager;

import com.igalia.wolvic.R;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.combo.ComboActionIcons;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;
import com.igalia.wolvic.ui.widgets.combo.ComboTipSelector;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Head-locked HUD showing the in-progress combo path as a circular dial,
 * plus two teaching layers added for Phase 3a:
 *   Layer 1: rotating idle tip strip (ARMED state — grip held, path empty).
 *   Layer 2 base: dashed ghost traces to every legal next node (BUILDING).
 *
 * Animations (breathing, stick-reactive fill, cancellation, handedness CTA)
 * land in Phase 3c and are not drawn here — this phase paints only the
 * static primitives. See /home/hubert/.claude/plans/giggly-crafting-finch.md
 * for the full state table.
 *
 * 8 wedges around a center dot, matching the angle-based direction zones
 * in ComboWindowEngine::NodeFromAxis. Each wedge has three visual states:
 *   1. Idle — dim
 *   2. Preview — joystick is pointing here but not yet confirmed
 *   3. Activated — node confirmed (pushed to max + held)
 *   4. Re-strike — same node hit again (bigger ring effect)
 *
 * Node numbering (numpad-style):
 *   1(UL)  2(U)  3(UR)
 *   4(L)   5(C)  6(R)
 *   7(DL)  8(D)  9(DR)
 *
 * Wedge angles (degrees, 0=right, CCW positive):
 *   6=right(0), 3=upper-right(45), 2=up(90), 1=upper-left(135),
 *   4=left(180), 7=lower-left(225), 8=down(270), 9=lower-right(315)
 */
public class ComboHUDWidget extends UIWidget implements ComboDispatcher.BindingsListener {

    private static final int WIDGET_W = 480;
    // Phase 3a: canvas grows by 60px for the tip strip (6px pad + 48px text + 6px pad).
    private static final int WIDGET_H = 540;
    private static final int TIP_STRIP_H = 60;
    // World-lock Y offset so the dial centroid keeps its current head-lock
    // sweet spot despite the extra 60px of canvas below. 30px is half of the
    // added bottom strip; converting via the widget's world-width/px ratio
    // gives the equivalent vertical meters.
    private static final int DIAL_CENTROID_OFFSET_PX = 30;

    private static final long TIP_ROTATION_INTERVAL_MS = 4000L;
    private static final long TIP_FADE_MS = 150L;

    // Ordered wedge nodes starting from right (0 degrees), going CCW.
    private static final int[] WEDGE_NODES_8 = { 6, 3, 2, 1, 4, 7, 8, 9 };
    // 4-dir layout: cardinals only, 90° wedges.
    private static final int[] WEDGE_NODES_4 = { 6, 2, 4, 8 };
    // Single heavy-up glyph, rotated per-wedge via canvas.rotate. Drawing one
    // glyph at N different angles guarantees identical stroke weight across
    // all directions — font fallbacks for U+2B95 / U+27A1 produce unmatched
    // weights otherwise.
    private static final String ARROW_GLYPH = "\u2B06";

    // Colours — skin-aware, resolved per instance from resources.
    // fd_hud_* lives in values/colors-fd-hud.xml (skin-neutral) except fd_hud_accent
    // which lives per-skin in res-fd-<skin>/values/colors-fd.xml.
    private final int mColorBg;
    private final int mColorBgStroke;
    private final int mColorWedgeIdle;
    private final int mColorWedgePreview;
    private final int mColorWedgeHit;
    private final int mColorAccent;
    private final int mColorCenter;
    private final int mColorTextIdle;
    private final int mColorTextPreview;
    private final int mColorTextHit;
    private final int mColorTextLast;
    // Phase 3a additions
    private final int mColorGhostYellow;
    private final int mColorTipText;
    private final int mColorLabelPill;

    // State
    private final int[] mHitCounts = new int[10]; // index 1-9
    private int mLastNode   = 0;
    private int mPathLength = 0;
    private int mPreviewNode = 0;  // currently highlighted zone (0=none)
    private final int[] mCommittedPath = new int[8];
    private boolean mGripHeld = false;

    // Paint objects — all pre-allocated to honor §5.2 zero-heap rule.
    private final Paint mBgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWedgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGhostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Path mGhostPathScratch = new Path();
    private final DashPathEffect mGhostDash = new DashPathEffect(new float[]{14f, 8f}, 0f);
    private final RectF mPillRect = new RectF();
    // Scratch geometry reused per frame in drawHUD — §5.2 zero-heap on hot path.
    private final Path mWedgePathScratch = new Path();
    private final RectF mOuterRectScratch = new RectF();
    private final RectF mInnerRectScratch = new RectF();

    // Cached 4-dir/8-dir mode flag. Avoids a SharedPreferences read per frame
    // in drawHUD. Updated at attach time and on BindingsChanged (mode flips
    // go through stampBindingChange → onBindingsChanged).
    private boolean mIs4DirMode = true;

    // Ghost-layer rebuild-on-change cache. The ghost entries depend only on
    // (committed path, dispatcher bindings); both are invalidated via version
    // bumps. drawGhostLayer reads mGhostScratch[0..mGhostCount) per frame with
    // zero allocation.
    private static final int GHOST_CAP = 5;
    private final GhostEntry[] mGhostScratch = new GhostEntry[GHOST_CAP];
    private int mGhostCount = 0;
    private int mGhostCommittedPathVersion = 0;
    private int mLastGhostVersion = -1;
    // Reusable scratch for building "committed path + nextNode" lookups during
    // ghost rebuild. Committed path caps at 8 (mCommittedPath.length), so the
    // extended path caps at 9. Arrays.copyOf still allocates fresh per entry
    // because the dispatcher key() hashes the full array — but that happens
    // only on rebuild, not per frame.
    private final int[] mExtendedPathScratch = new int[9];

    // Cached tinted icon drawables. Keyed by drawable resource id; every entry
    // has been .mutate()'d before setColorFilter() to prevent cross-contamination
    // with Wolvic's toolbar drawables via shared ConstantState.
    private final SparseArray<Drawable> mTintedIconCache = new SparseArray<>();

    // Tip pool + selector. Pool rebuilds on BindingsChanged (subscribed via
    // dispatcher). Selector holds the warm-up / recency / ring-buffer state.
    @Nullable private ComboDispatcher mDispatcher;
    @NonNull private List<ComboTipBuilder.TipCandidate> mTipPool = Collections.emptyList();
    private final ComboTipSelector mTipSelector = new ComboTipSelector();
    @Nullable private ComboTipBuilder.TipCandidate mCurrentTip;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final Runnable mRotateTipRunnable = this::advanceTip;

    // Commit-preview label cache (R1, minus handedness CTA which is Phase 3c).
    @Nullable private Spannable mCommitPreviewSpannable;
    @Nullable private String mCommitPreviewPathKey;
    private int mCommitPreviewActionKey = ComboDispatcher.A_NONE;

    private View mCanvasView;

    public ComboHUDWidget(Context aContext) {
        super(aContext);
        mColorBg           = aContext.getColor(R.color.fd_hud_bg);
        mColorBgStroke     = aContext.getColor(R.color.fd_hud_bg_stroke);
        mColorWedgeIdle    = aContext.getColor(R.color.fd_hud_wedge_idle);
        mColorWedgePreview = aContext.getColor(R.color.fd_hud_wedge_preview);
        mColorWedgeHit     = aContext.getColor(R.color.fd_hud_wedge_hit);
        mColorAccent       = aContext.getColor(R.color.fd_hud_accent);
        mColorCenter       = aContext.getColor(R.color.fd_hud_center);
        mColorTextIdle     = aContext.getColor(R.color.fd_hud_text_idle);
        mColorTextPreview  = aContext.getColor(R.color.fd_hud_text_preview);
        mColorTextHit      = aContext.getColor(R.color.fd_hud_text_hit);
        mColorTextLast     = aContext.getColor(R.color.fd_hud_text_last);
        mColorGhostYellow  = aContext.getColor(R.color.fd_hud_ghost_yellow);
        mColorTipText      = aContext.getColor(R.color.fd_hud_tip_text);
        mColorLabelPill    = aContext.getColor(R.color.fd_hud_label_pill);
        mBgPaint.setColor(mColorBg);
        mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mLinePaint.setColor(0x40606080);
        mLinePaint.setStrokeWidth(1.5f);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setColor(mColorAccent);

        // Ghost paint: dashed yellow 70% alpha, 3px stroke.
        mGhostPaint.setStyle(Paint.Style.STROKE);
        mGhostPaint.setStrokeWidth(3f);
        mGhostPaint.setColor(mColorGhostYellow);
        mGhostPaint.setPathEffect(mGhostDash);

        mPillPaint.setStyle(Paint.Style.FILL);
        mPillPaint.setColor(mColorLabelPill);

        mTipPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mTipPaint.setTextAlign(Paint.Align.CENTER);
        mTipPaint.setColor(mColorTipText);
        // 28sp floor per CLAUDE.md §5.3. Convert sp → px via resource density.
        float sp = aContext.getResources().getDisplayMetrics().scaledDensity;
        mTipPaint.setTextSize(28f * sp);

        initialize();
    }

    @Override
    public void setSurfaceTexture(android.graphics.SurfaceTexture aTexture, final int aWidth, final int aHeight, Runnable aFirstDrawCallback) {
        super.setSurfaceTexture(aTexture, aWidth, aHeight, aFirstDrawCallback);
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement p) {
        p.width  = WIDGET_W;
        p.height = WIDGET_H;
        p.worldWidth = 0.53f;  // scaled with WIDGET_W so the dial stays the same physical size
        // World-space placement. y is measured from the reorient base (floor
        // level in Wolvic — eye level is ~1.6m for a standing adult). Place
        // at adult eye level, 1.5m forward. Add a small upward bump so the
        // dial centroid keeps its old head-lock sweet spot despite the
        // taller canvas (the extra 60px of strip lives below the dial).
        float dialOffsetMeters = (DIAL_CENTROID_OFFSET_PX / (float) WIDGET_W) * p.worldWidth;
        p.translationX = 0;
        p.translationY = WidgetPlacement.unitFromMeters(1.5f + dialOffsetMeters);
        p.translationZ = WidgetPlacement.unitFromMeters(-1.5f);
        p.anchorX = 0.5f;
        p.anchorY = 0.5f;
        p.cylinder = false;
        p.visible = false;
        p.layer = false;
        // clearColor with non-zero alpha makes IsReadyForComposition() return true
        // in the native renderer, which is needed for the widget to be visible.
        // Do NOT set composited=true here — the firstDrawCallback mechanism in
        // dispatchCreateWidget sets it after the first draw, triggering the
        // necessary UpdateSurface() with the correct surface texture shader.
        p.clearColor = 0x01000000;  // almost transparent black, just enough for alpha > 0
    }

    private void initialize() {
        mCanvasView = new View(getContext()) {
            @Override
            protected void onDraw(Canvas canvas) {
                drawHUD(canvas, getWidth(), getHeight());
            }
        };
        mCanvasView.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        addView(mCanvasView);
    }

    // ── Public API ────────────────────────────────────────

    @Override
    public void show(@ShowFlags int aShowFlags) {
        super.show(aShowFlags);
    }

    /**
     * Bind to the dispatcher so the tip pool rebuilds on rebinding + mode flip.
     * Call from VRBrowserActivity once the dispatcher is constructed. Phase 3b
     * will add the grip-state JNI wiring; this one is safe to call now.
     */
    public void attachDispatcher(@NonNull ComboDispatcher dispatcher) {
        if (mDispatcher == dispatcher) return;
        if (mDispatcher != null) mDispatcher.removeBindingsListener(this);
        mDispatcher = dispatcher;
        mDispatcher.addBindingsListener(this);
        refreshModeFlag();
        rebuildTipPool();
    }

    /**
     * Refresh the cached 4-dir/8-dir flag from SharedPreferences. Called at
     * attach time and on binding changes (mode flips route through
     * stampBindingChange → onBindingsChanged).
     */
    private void refreshModeFlag() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        boolean nowFourDir = prefs.getBoolean("fingerdance_combo_4dir_mode", true);
        if (mIs4DirMode != nowFourDir) {
            mIs4DirMode = nowFourDir;
            // Mode flip changes which node is a legal next step → ghost cache stale.
            mGhostCommittedPathVersion++;
        }
    }

    /** Update with confirmed path (nodes that have been activated). */
    public void updatePath(int[] path, int length) {
        for (int i = 0; i < mHitCounts.length; i++) mHitCounts[i] = 0;
        mLastNode = 0;
        int[] canonical;
        if (is4DirMode()) {
            // Mirror the dispatcher's interpretation so the HUD reflects the
            // path the dispatcher will act on. Snap diagonals to their closer
            // horizontal-or-vertical neighbour by context (if the adjacent
            // flick was cardinal, the diagonal is treated as spurious overshoot
            // of that cardinal); then collapse consecutive duplicates.
            canonical = collapseFourDir(path, length);
            mPathLength = canonical.length;
        } else {
            canonical = Arrays.copyOf(path, length);
            mPathLength = length;
        }
        for (int i = 0; i < canonical.length; i++) {
            int node = canonical[i];
            if (node >= 1 && node <= 9) {
                mHitCounts[node]++;
                mLastNode = node;
            }
            if (i < mCommittedPath.length) mCommittedPath[i] = node;
        }
        // Path changed → tip strip swaps regimes. Invalidate caches so the
        // commit-preview Spannable rebuilds, and stop the rotating-tip timer
        // since we're not ARMED anymore.
        invalidateCommitPreviewCache();
        if (mPathLength > 0) {
            stopTipRotation();
        } else if (mGripHeld) {
            startTipRotation();
        }
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    // 4-dir canonicalisation. Diagonals 1/3/7/9 expand to two cardinals. Pick
    // the one that matches an adjacent cardinal neighbour in the path so the
    // diagonal merges with it (spurious mid-arc activation). Fall back to the
    // horizontal cardinal when both neighbours are diagonals/absent. Then
    // collapse runs of the same cardinal.
    private static int[] collapseFourDir(int[] path, int length) {
        int[] snapped = new int[length];
        boolean[] fromDiagonal = new boolean[length];
        for (int i = 0; i < length; i++) {
            int n = path[i];
            if (isCardinal(n)) { snapped[i] = n; continue; }
            int[] options = diagonalCardinals(n);
            int prev = i > 0 ? path[i - 1] : 0;
            int next = i + 1 < length ? path[i + 1] : 0;
            int pick = 0;
            for (int o : options) {
                if (o == prev || o == next) { pick = o; break; }
            }
            snapped[i] = pick != 0 ? pick : (options.length > 0 ? options[0] : 0);
            fromDiagonal[i] = true;
        }
        // Collapse a duplicate only when the pair includes a diagonal-snapped
        // entry — that's a spurious mid-arc activation. Genuine cardinal
        // repeats (6-6-6, 8-8-8) are preserved.
        int[] tmp = new int[length];
        boolean[] tmpDiag = new boolean[length];
        int out = 0;
        for (int i = 0; i < length; i++) {
            int v = snapped[i];
            if (v == 0) continue;
            if (out > 0 && tmp[out - 1] == v && (tmpDiag[out - 1] || fromDiagonal[i])) {
                // Merge: prefer the cardinal-original (not diagonal-snapped) entry
                if (tmpDiag[out - 1] && !fromDiagonal[i]) tmpDiag[out - 1] = false;
                continue;
            }
            tmp[out] = v;
            tmpDiag[out] = fromDiagonal[i];
            out++;
        }
        int[] result = new int[out];
        System.arraycopy(tmp, 0, result, 0, out);
        return result;
    }

    private static boolean isCardinal(int n) {
        return n == 2 || n == 4 || n == 6 || n == 8;
    }

    private static int[] diagonalCardinals(int n) {
        switch (n) {
            case 1: return new int[]{2, 4};
            case 3: return new int[]{2, 6};
            case 7: return new int[]{4, 8};
            case 9: return new int[]{6, 8};
            default: return new int[0];
        }
    }

    /** Update preview highlight — which wedge the joystick is currently pointing at. */
    public void updatePreview(int previewNode) {
        if (mPreviewNode != previewNode) {
            mPreviewNode = previewNode;
            if (mCanvasView != null) mCanvasView.invalidate();
        }
    }

    /**
     * Called from VRBrowserActivity when the grip-hold state flips.
     * Starts/stops the rotating tip rhythm + triggers the ARMED / BUILDING /
     * hidden state transitions. Phase 3b wires this to the native grip
     * signal; for now it's a public entry point that tests / other code
     * paths can drive.
     */
    public void updateGripState(boolean gripHeld) {
        if (mGripHeld == gripHeld) return;
        mGripHeld = gripHeld;
        if (gripHeld) {
            if (mPathLength == 0) startTipRotation();
        } else {
            stopTipRotation();
        }
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    private boolean is4DirMode() {
        return mIs4DirMode;
    }

    public void resetPath() {
        for (int i = 0; i < mHitCounts.length; i++) mHitCounts[i] = 0;
        mPathLength = 0;
        mLastNode = 0;
        mPreviewNode = 0;
        invalidateCommitPreviewCache();
        if (mGripHeld) startTipRotation();
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    // ── Tip rotation (ARMED state) ────────────────────────

    private void rebuildTipPool() {
        if (mDispatcher == null) return;
        mTipPool = ComboTipBuilder.buildAll(mDispatcher, getContext().getResources());
        // Composed Spannables need flushing so arrow sequences rebuild with
        // whatever new bindings the user just installed.
        ComboTipBuilder.clearCache();
        // Stamp the selector with the changed-path id so it surfaces within
        // the next 1-2 cycles.
        if (mDispatcher.getLastChangedPathId() != null) {
            mTipSelector.notifyBindingChanged(
                    "bind:" + mDispatcher.getLastChangedPathId(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public void onBindingsChanged() {
        // Dispatcher may fire from any thread; hop to main before touching view state.
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshModeFlag();
            // Bindings mutated → legal-nexts + action-for-path answers differ → ghost cache stale.
            mGhostCommittedPathVersion++;
            rebuildTipPool();
            if (mCanvasView != null) mCanvasView.invalidate();
        } else {
            mMainHandler.post(() -> {
                refreshModeFlag();
                mGhostCommittedPathVersion++;
                rebuildTipPool();
                if (mCanvasView != null) mCanvasView.invalidate();
            });
        }
    }

    private void startTipRotation() {
        stopTipRotation();
        if (mTipPool.isEmpty() && mDispatcher != null) rebuildTipPool();
        advanceTip();
    }

    private void stopTipRotation() {
        mMainHandler.removeCallbacks(mRotateTipRunnable);
    }

    private void advanceTip() {
        if (mTipPool.isEmpty()) return;
        mCurrentTip = mTipSelector.pickNext(mTipPool, System.currentTimeMillis());
        if (mCanvasView != null) mCanvasView.invalidate();
        mMainHandler.postDelayed(mRotateTipRunnable, TIP_ROTATION_INTERVAL_MS);
    }

    // ── Commit-preview cache (R1 base, no handedness) ────

    private void invalidateCommitPreviewCache() {
        mCommitPreviewSpannable = null;
        mCommitPreviewPathKey = null;
        mCommitPreviewActionKey = ComboDispatcher.A_NONE;
        // Ghost entries are keyed off the committed path too — bump the version
        // so drawGhostLayer rebuilds on next draw (no allocation per frame).
        mGhostCommittedPathVersion++;
    }

    @Nullable
    private Spannable composeCommitPreview(int[] path, int length) {
        if (mDispatcher == null || length == 0) return null;
        int[] snapshot = Arrays.copyOf(path, length);
        int action = mDispatcher.getActionForExactPath(snapshot);
        String pathKey = Arrays.toString(snapshot);
        // Cache hit — identical path + action combo already composed.
        if (action == mCommitPreviewActionKey
                && pathKey.equals(mCommitPreviewPathKey)
                && mCommitPreviewSpannable != null) {
            return mCommitPreviewSpannable;
        }
        Spannable out;
        if (action != ComboDispatcher.A_NONE) {
            // Path is itself a binding → render arrows + action-icon tip.
            out = ComboTipBuilder.renderBindingTip(
                    getContext(), snapshot, action,
                    (int) Math.round(mTipPaint.getTextSize()),
                    mColorTipText);
        } else {
            // Prefix-only → null (blank strip). Dead-end → stub CTA.
            Set<Integer> nexts = mDispatcher.getLegalNextNodes(snapshot);
            if (nexts.isEmpty()) {
                CharSequence cs = getContext().getString(R.string.fd_meta_tip_dead_end_stub);
                out = new android.text.SpannableString(cs);
            } else {
                out = null;
            }
        }
        mCommitPreviewSpannable = out;
        mCommitPreviewPathKey = pathKey;
        mCommitPreviewActionKey = action;
        return out;
    }

    // ── Icon pill cache + draw ───────────────────────────

    private Drawable getTintedIcon(@DrawableRes int iconRes, int color) {
        Drawable cached = mTintedIconCache.get(iconRes);
        if (cached != null) return cached;
        Drawable d = AppCompatResources.getDrawable(getContext(), iconRes);
        if (d == null) return null;
        // .mutate() is mandatory: Wolvic's toolbar consumes the same drawables
        // via shared ConstantState; without mutate() the yellow filter would
        // bleed into unrelated toolbar icons.
        d = d.mutate();
        d.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        d.setBounds(-16, -16, 16, 16);
        mTintedIconCache.put(iconRes, d);
        return d;
    }

    private void drawIconPill(Canvas canvas, float cx, float cy, @DrawableRes int iconRes) {
        // 44×44 rounded-square pill, corner 8px. Axis-aligned.
        mPillRect.set(cx - 22f, cy - 22f, cx + 22f, cy + 22f);
        // Pill fill (fd_hud_label_pill — navy @ 80% alpha).
        mPillPaint.setColor(mColorLabelPill);
        canvas.drawRoundRect(mPillRect, 8f, 8f, mPillPaint);
        Drawable icon = getTintedIcon(iconRes, 0xFFFDDE0A /* fd-yellow full alpha */);
        if (icon == null) return;
        int save = canvas.save();
        canvas.translate(cx, cy);
        icon.draw(canvas);
        canvas.restoreToCount(save);
    }

    // ── Lifecycle ────────────────────────────────────────

    @Override
    public void releaseWidget() {
        stopTipRotation();
        if (mDispatcher != null) {
            mDispatcher.removeBindingsListener(this);
            mDispatcher = null;
        }
        mTintedIconCache.clear();
        super.releaseWidget();
    }

    @Override
    protected void onDetachedFromWindow() {
        stopTipRotation();
        if (mDispatcher != null) {
            mDispatcher.removeBindingsListener(this);
        }
        mTintedIconCache.clear();
        super.onDetachedFromWindow();
    }

    // ── Drawing ───────────────────────────────────────────

    private void drawHUD(Canvas canvas, int w, int h) {
        // Scale the dial drawing to the top (w × (h - tipStrip)) band so the
        // extra 60px canvas growth does not shrink the dial itself.
        int tipStripPx = Math.round(TIP_STRIP_H * (h / (float) WIDGET_H));
        int dialH = h - tipStripPx;
        float cx = w / 2f;
        float cy = dialH / 2f;
        // Reserve ~28% of the widget radius as an outer margin so confirmation
        // dots can sit beyond the dial — the dot is the "joystick pushed past
        // the rim" metaphor.
        float outerR = Math.min(cx, cy) * 0.70f;
        float innerR = outerR * 0.30f;           // center circle radius
        float labelR = (outerR + innerR) / 2f;   // radius for text placement

        // Opaque background disc with a bright accent stroke so the HUD stays
        // unambiguously separated from web content (dark or light pages).
        mBgPaint.setColor(mColorBg);
        mBgPaint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, outerR + 4f, mBgPaint);
        mBgPaint.setStyle(Paint.Style.STROKE);
        mBgPaint.setStrokeWidth(4f);
        mBgPaint.setColor(mColorBgStroke);
        mBgPaint.setAlpha(255);
        canvas.drawCircle(cx, cy, outerR + 4f, mBgPaint);
        mBgPaint.setStyle(Paint.Style.FILL);
        mBgPaint.setAlpha(255);

        // Mode-dependent layout.
        final boolean fourDir = is4DirMode();
        final int[] nodes   = fourDir ? WEDGE_NODES_4  : WEDGE_NODES_8;
        final int count    = nodes.length;
        final float stepDeg = 360f / count;
        final float halfWidth = stepDeg / 2f;

        mOuterRectScratch.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        mInnerRectScratch.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);

        // Pass 1: wedges + labels + dividers. Wedge = joystick direction
        // preview only (no confirmed state — confirmation is the outer dot).
        for (int i = 0; i < count; i++) {
            int node = nodes[i];
            boolean isPreview = (node == mPreviewNode);

            // Android canvas: 0 degrees = 3 o'clock, positive = clockwise.
            // World center angle (CCW) = i*stepDeg; canvas center = -(i*stepDeg).
            float startAngle = -(i * stepDeg) - halfWidth;
            float sweep = halfWidth * 2f;

            // setColor already carries the resource's alpha channel; calling
            // setAlpha here would clobber it and force every wedge to 100%.
            mWedgePaint.setColor(isPreview ? mColorWedgePreview : mColorWedgeIdle);

            mWedgePathScratch.reset();
            mWedgePathScratch.arcTo(mOuterRectScratch, startAngle, sweep, true);
            mWedgePathScratch.arcTo(mInnerRectScratch, startAngle + sweep, -sweep);
            mWedgePathScratch.close();
            canvas.drawPath(mWedgePathScratch, mWedgePaint);

            // Divider line between wedges
            float lineAngle = (float) Math.toRadians(startAngle);
            canvas.drawLine(
                    cx + innerR * (float) Math.cos(lineAngle),
                    cy + innerR * (float) Math.sin(lineAngle),
                    cx + outerR * (float) Math.cos(lineAngle),
                    cy + outerR * (float) Math.sin(lineAngle),
                    mLinePaint);

            // Label — single up-arrow glyph rotated to point outward along
            // this wedge's midline. midDeg (canvas CW) = -(i * stepDeg); the
            // glyph points UP (-Y) natively, so rotate by midDeg + 90.
            float midDeg = startAngle + halfWidth;
            float midAngle = (float) Math.toRadians(midDeg);
            float lx = cx + labelR * (float) Math.cos(midAngle);
            float ly = cy + labelR * (float) Math.sin(midAngle);
            mTextPaint.setTextSize(outerR * 0.22f);
            mTextPaint.setColor(isPreview ? mColorTextPreview : mColorTextIdle);
            // Drop shadow gives the arrow glyph depth — reads as a real
            // embossed UI element rather than a flat overlay.
            mTextPaint.setShadowLayer(6f, 0f, 3f, 0xB3000000);
            float textY = ly - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
            canvas.save();
            canvas.rotate(midDeg + 90f, lx, ly);
            canvas.drawText(ARROW_GLYPH, lx, textY, mTextPaint);
            canvas.restore();
        }
        mTextPaint.clearShadowLayer();

        // Cardinal max-position markers: small navy dots on the yellow outer
        // ring at 0/90/180/270 degrees. Always visible (not activation-gated),
        // so the user always knows where the "max reach" slots are.
        float markerR = outerR + 4f;
        float markerDotR = outerR * 0.08f;
        int[] cardinalAngles = { 0, 90, 180, 270 };  // E, N, W, S in canvas space
        mWedgePaint.setColor(mColorBg);
        mWedgePaint.setAlpha(255);
        for (int a : cardinalAngles) {
            double rad = Math.toRadians(-a);  // canvas: CCW in world = negative in canvas
            float mx = cx + markerR * (float) Math.cos(rad);
            float my = cy + markerR * (float) Math.sin(rad);
            canvas.drawCircle(mx, my, markerDotR, mWedgePaint);
        }

        // Pass 2: confirmation dots at the outer edge of each activated wedge.
        // Size grows with hit count; last-activated node gets a white center
        // so re-strike progress is visible at a glance.
        // Dots sit in the outer margin, beyond the dial rim — the "joystick
        // pushed past max" metaphor, and visually separate from the direction
        // preview inside the dial.
        float dotR = outerR * 1.15f;
        float dotBaseR = outerR * 0.06f;
        for (int i = 0; i < count; i++) {
            int node = nodes[i];
            int hits = mHitCounts[node];
            if (hits <= 0) continue;
            float startAngle = -(i * stepDeg) - halfWidth;
            float midAngle = (float) Math.toRadians(startAngle + halfWidth);
            float dx = cx + dotR * (float) Math.cos(midAngle);
            float dy = cy + dotR * (float) Math.sin(midAngle);

            float r = dotBaseR + dotBaseR * 0.5f * (Math.min(hits, 5) - 1);  // 1→baseR, 5→3×baseR

            // Filled accent dot
            mWedgePaint.setColor(mColorAccent);
            mWedgePaint.setAlpha(255);
            canvas.drawCircle(dx, dy, r, mWedgePaint);

            // For re-strikes, add concentric rings — one per extra hit, up to 3.
            if (hits > 1) {
                mRingPaint.setStrokeWidth(2.0f);
                mRingPaint.setAlpha(200);
                for (int k = 1; k < Math.min(hits, 4); k++) {
                    canvas.drawCircle(dx, dy, r + k * dotBaseR * 0.45f, mRingPaint);
                }
            }

            // Highlight the most-recent node with a white core pip.
            if (node == mLastNode) {
                mWedgePaint.setColor(mColorTextLast);
                mWedgePaint.setAlpha(255);
                canvas.drawCircle(dx, dy, r * 0.45f, mWedgePaint);
            }
        }

        // Center circle
        mWedgePaint.setColor(mColorCenter);
        mWedgePaint.setAlpha(255);
        canvas.drawCircle(cx, cy, innerR - 2f, mWedgePaint);

        // Center dot or path length indicator
        if (mPathLength > 0) {
            mTextPaint.setTextSize(innerR * 0.7f);
            mTextPaint.setColor(mColorAccent);
            float textY = cy - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
            canvas.drawText(String.valueOf(mPathLength), cx, textY, mTextPaint);
        } else {
            mWedgePaint.setColor(mColorAccent);
            mWedgePaint.setAlpha(150);
            canvas.drawCircle(cx, cy, 6f, mWedgePaint);
        }

        // ── Phase 3a: ghost traces (BUILDING) ─────────────
        if (mGripHeld && mPathLength > 0 && mDispatcher != null) {
            drawGhostLayer(canvas, cx, cy, dotR, dotBaseR, nodes, count, stepDeg, halfWidth);
        }

        // ── Phase 3a: bottom tip strip ────────────────────
        drawTipStrip(canvas, w, h, dialH, tipStripPx);
    }

    /**
     * Draws dashed ghost curves from {@code lastNode} to every legal next
     * node, each with an action-icon pill offset radially. Static only —
     * breathing / stick-reactive fill is Phase 3c.
     *
     * Per-frame allocation is zero: the ghost entries are rebuilt only when
     * the committed-path / bindings version changes (rebuildGhostEntries()),
     * and drawn here by iterating a preallocated fixed-capacity array.
     */
    private void drawGhostLayer(Canvas canvas, float cx, float cy,
                                 float dotR, float dotBaseR,
                                 int[] nodes, int count, float stepDeg, float halfWidth) {
        if (mLastGhostVersion != mGhostCommittedPathVersion) {
            rebuildGhostEntries();
            mLastGhostVersion = mGhostCommittedPathVersion;
        }
        if (mGhostCount == 0) return;

        int lastNode = mCommittedPath[mPathLength - 1];
        int idxFrom = indexOfNode(nodes, count, lastNode);
        if (idxFrom < 0) return;
        float fromMid = (float) Math.toRadians(-(idxFrom * stepDeg));
        float fromX = cx + dotR * (float) Math.cos(fromMid);
        float fromY = cy + dotR * (float) Math.sin(fromMid);

        // mGhostPaint is fully configured in the constructor (style/stroke/color/
        // pathEffect). Nothing to reset per entry.
        for (int i = 0; i < mGhostCount; i++) {
            GhostEntry g = mGhostScratch[i];
            int idxTo = indexOfNode(nodes, count, g.nextNode);
            if (idxTo < 0) continue;

            float toMid = (float) Math.toRadians(-(idxTo * stepDeg));
            float toX   = cx + dotR * (float) Math.cos(toMid);
            float toY   = cy + dotR * (float) Math.sin(toMid);

            // Re-strike: same-node extension (e.g. 2→2). Draw a dashed ring
            // around the last-node's confirmation dot instead of a curve.
            if (g.nextNode == lastNode) {
                canvas.drawCircle(fromX, fromY, dotBaseR * 1.8f, mGhostPaint);
            } else {
                mGhostPathScratch.reset();
                mGhostPathScratch.moveTo(fromX, fromY);
                // Cubic Bezier with both controls at the dial center — the plan
                // asks for a curve that bows through center. Using cx/cy for
                // both control points produces a consistent arc for cardinal
                // pairs and adjacent diagonals.
                mGhostPathScratch.cubicTo(cx, cy, cx, cy, toX, toY);
                canvas.drawPath(mGhostPathScratch, mGhostPaint);
            }

            // Icon pill: 48px radially outward from dot ring at the nextNode
            // angle. Axis-aligned so icons read upright.
            float pillR = dotR + 48f;
            float pillX = cx + pillR * (float) Math.cos(toMid);
            float pillY = cy + pillR * (float) Math.sin(toMid);
            drawIconPill(canvas, pillX, pillY, ComboActionIcons.iconFor(g.actionInt));
        }
    }

    /**
     * Rebuild ghost entries from the current committed path + dispatcher
     * bindings. Called only when mGhostCommittedPathVersion bumps (committed
     * path mutated, bindings changed, or mode flipped). The per-entry
     * allocations here are bounded: O(legalNexts) int[] lookups capped at 8
     * legal nodes; final entry set capped at GHOST_CAP=5.
     *
     * Writes into mGhostScratch[0..mGhostCount). Entries are kept in sort
     * order (length asc, actionInt asc) via insertion at each add — the array
     * is at most 5 long so this is effectively free.
     */
    private void rebuildGhostEntries() {
        mGhostCount = 0;
        if (mDispatcher == null || mPathLength == 0) return;
        int lastNode = mCommittedPath[mPathLength - 1];

        // Copy committed path into the head of mExtendedPathScratch once; the
        // trailing slot [mPathLength] gets mutated per candidate. The API call
        // itself still allocates a fresh int[] via Arrays.copyOf (see the dispatcher's
        // key() hash), but only during rebuild — not per frame.
        System.arraycopy(mCommittedPath, 0, mExtendedPathScratch, 0, mPathLength);
        int extLen = mPathLength + 1;

        Set<Integer> legalNexts = mDispatcher.getLegalNextNodes(
                Arrays.copyOf(mCommittedPath, mPathLength));
        for (Integer boxedNext : legalNexts) {
            int nextNode = boxedNext;
            if (nextNode == 5) continue;
            mExtendedPathScratch[mPathLength] = nextNode;
            int[] extended = Arrays.copyOf(mExtendedPathScratch, extLen);
            int action = mDispatcher.getActionForExactPath(extended);
            if (action == ComboDispatcher.A_NONE) continue;
            insertGhostSorted(nextNode, extLen, action);
        }

        // Re-strike fallback: if lastNode is absent from legalNexts but
        // [...path, lastNode] is still a binding, surface it. (Edge case where
        // the precomputed next-node index missed it.)
        if (!legalNexts.contains(lastNode) && !containsGhostForNode(lastNode)) {
            mExtendedPathScratch[mPathLength] = lastNode;
            int[] extended = Arrays.copyOf(mExtendedPathScratch, extLen);
            int action = mDispatcher.getActionForExactPath(extended);
            if (action != ComboDispatcher.A_NONE) {
                insertGhostSorted(lastNode, extLen, action);
            }
        }
    }

    private boolean containsGhostForNode(int node) {
        for (int i = 0; i < mGhostCount; i++) {
            if (mGhostScratch[i].nextNode == node) return true;
        }
        return false;
    }

    /**
     * Insert into mGhostScratch preserving (pathLength asc, actionInt asc)
     * order. Capacity capped at GHOST_CAP — drops larger/higher-actionInt
     * entries past the cap. Entries are mutated in place (no allocation in
     * steady state — only the first few calls allocate a GhostEntry, and the
     * array reuses those slots on subsequent rebuilds).
     */
    private void insertGhostSorted(int nextNode, int pathLength, int actionInt) {
        // Find insertion index.
        int idx = 0;
        while (idx < mGhostCount) {
            GhostEntry e = mGhostScratch[idx];
            int c = Integer.compare(pathLength, e.pathLength);
            if (c == 0) c = Integer.compare(actionInt, e.actionInt);
            if (c < 0) break;
            idx++;
        }
        if (idx >= GHOST_CAP) return;  // worse than every existing entry and full.
        // Shift tail right (drop last if full).
        int end = Math.min(mGhostCount, GHOST_CAP - 1);
        // We'll shift [idx..end) → [idx+1..end+1), but swap-in-place using
        // existing GhostEntry slots so we don't allocate. Since GhostEntry is
        // a tiny object held directly in the array, we cascade its fields.
        for (int j = end; j > idx; j--) {
            GhostEntry dst = getOrCreateGhostSlot(j);
            GhostEntry src = mGhostScratch[j - 1];
            dst.nextNode = src.nextNode;
            dst.pathLength = src.pathLength;
            dst.actionInt = src.actionInt;
        }
        GhostEntry slot = getOrCreateGhostSlot(idx);
        slot.nextNode = nextNode;
        slot.pathLength = pathLength;
        slot.actionInt = actionInt;
        if (mGhostCount < GHOST_CAP) mGhostCount++;
    }

    private GhostEntry getOrCreateGhostSlot(int i) {
        GhostEntry e = mGhostScratch[i];
        if (e == null) {
            e = new GhostEntry();
            mGhostScratch[i] = e;
        }
        return e;
    }

    private static int indexOfNode(int[] nodes, int count, int node) {
        for (int i = 0; i < count; i++) {
            if (nodes[i] == node) return i;
        }
        return -1;
    }

    /** Small mutable holder for ghost render entries, reused across rebuilds. */
    private static final class GhostEntry {
        int nextNode;
        int pathLength;
        int actionInt;
    }

    /**
     * Draws the 60px bottom strip. In ARMED state: a single rotating tip.
     * In BUILDING state: the commit-preview tip (arrows + action icon) when
     * the committed path is itself a binding; blank on prefix; dead-end stub
     * otherwise. No animation here — Phase 3c layers cross-fade on top.
     */
    private void drawTipStrip(Canvas canvas, int w, int h, int dialH, int tipStripPx) {
        CharSequence cs = pickStripText();
        if (cs == null || cs.length() == 0) return;
        // Truncate at strip width (author tips ≤26 chars so truncation
        // effectively never fires, but the safety net matches plan §Layer 1).
        float stripCenterY = dialH + tipStripPx * 0.5f;
        float baseline = stripCenterY - (mTipPaint.descent() + mTipPaint.ascent()) / 2f;
        CharSequence rendered = TextUtils.ellipsize(cs, mTipPaint,
                w - 16f, TextUtils.TruncateAt.END);
        canvas.drawText(rendered, 0, rendered.length(), w / 2f, baseline, mTipPaint);
    }

    @Nullable
    private CharSequence pickStripText() {
        if (!mGripHeld) return null;
        if (mPathLength == 0) {
            // ARMED. Prefer a full rendered binding tip (arrows + icon);
            // Meta tips render as plain strings.
            if (mCurrentTip == null) return null;
            if (mCurrentTip.metaStringRes != 0) {
                return ComboTipBuilder.renderMetaTip(
                        mCurrentTip.metaStringRes, getContext().getResources());
            }
            if (mCurrentTip.path != null) {
                return ComboTipBuilder.renderBindingTip(
                        getContext(), mCurrentTip.path, mCurrentTip.actionInt,
                        (int) Math.round(mTipPaint.getTextSize()),
                        mColorTipText);
            }
            return null;
        }
        // BUILDING. Commit preview label (R1 base).
        return composeCommitPreview(mCommittedPath, mPathLength);
    }
}
