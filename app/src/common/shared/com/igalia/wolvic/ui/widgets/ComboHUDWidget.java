package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Layout;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.StaticLayout;
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
import com.igalia.wolvic.VRBrowserActivity;
import com.igalia.wolvic.input.ComboDispatcher;
import com.igalia.wolvic.ui.widgets.combo.ComboActionIcons;
import com.igalia.wolvic.ui.widgets.combo.ComboGhostAnimation;
import com.igalia.wolvic.ui.widgets.combo.ComboPreviewProgressSmoother;
import com.igalia.wolvic.ui.widgets.combo.ComboTipBuilder;
import com.igalia.wolvic.ui.widgets.combo.ComboTipSelector;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Head-locked HUD showing the in-progress combo path as a circular dial,
 * plus teaching layers added across Phase 3:
 *   Layer 1: rotating idle tip strip (ARMED state — grip held, path empty).
 *   Layer 2 base: dashed ghost traces to every legal next node (BUILDING).
 *   Phase 3c: breathing animation, stick-reactive dashed→solid crossfade,
 *             commit-cancellation fade, handedness-reactive dead-end CTA.
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

    // Canvas widened from 480 → 720 for the tip strip. The dial itself is
    // height-constrained (outerR = min(cx, cy) * 0.70 pinned to cy), so the
    // extra horizontal pixels stay transparent around the dial and give the
    // strip room for full-length accent labels like "Release: Scroll to
    // bottom" or "Press A to create combo" without ellipsizing at 28sp.
    // worldWidth scales proportionally so pixels-per-meter stays constant
    // and the dial's physical size in VR is unchanged.
    private static final int WIDGET_W = 720;
    // Phase 3a: canvas grows for the bottom tip strip.
    private static final int WIDGET_H = 608;
    private static final int TIP_STRIP_H = 128;
    // World-lock Y offset so the dial centroid keeps its current head-lock
    // sweet spot despite the extra strip of canvas below. Equals
    // TIP_STRIP_H / 2 (strip is the only asymmetric addition below the dial);
    // converting via the widget's world-width/px ratio gives the equivalent
    // vertical meters.
    private static final int DIAL_CENTROID_OFFSET_PX = 64;

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

    // Pre-allocated digit labels for the path-length center indicator, so the
    // hot draw path never calls String.valueOf(int). mPathLength is clamped to
    // [0, MAX_PATH=8] by the engine, so index 8 must exist.
    private static final String[] PATH_LENGTH_LABEL =
            { "0", "1", "2", "3", "4", "5", "6", "7", "8" };

    // Cardinal max-position marker angles (E, N, W, S in canvas space).
    // Hoisted to static final so the draw loop never allocates a fresh int[].
    private static final int[] CARDINAL_ANGLES = { 0, 90, 180, 270 };

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
    // Whether we're currently in BUILDING state (grip held + path length > 0).
    // Drives the per-frame self-invalidate contract (Step 5).
    private boolean mIsBuilding = false;

    // Phase 3b: continuous live-preview smoother. Populated by
    // updatePreviewProgress from the native engine's throttled signal.
    // Thread model: handleComboPreviewProgress posts via runOnUiThread, so all
    // writes land on the same thread as onDraw / ghost-rebuild. No volatile
    // or synchronisation required.
    private final ComboPreviewProgressSmoother mPreviewSmoother =
            new ComboPreviewProgressSmoother();

    // Phase 3c: ghost-layer animation helpers.
    // mGhostAnim holds the cancellation state machine (written+read on UI thread only).
    private final ComboGhostAnimation mGhostAnim = new ComboGhostAnimation();
    // Frame time (ms) cached once per onDraw so all per-ghost math uses the same clock.
    private long mCurrentFrameTimeMs = 0L;
    // Timestamp when the ghost layer first became visible (path 0→1). Reset to -1 on hide.
    private long mGhostRenderStartMs = -1L;

    // Paint objects — all pre-allocated to honor §5.2 zero-heap rule.
    private final Paint mBgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWedgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    // Phase 3c: separate dashed + solid ghost paints so we never call
    // setPathEffect per-frame on a shared paint (that allocates under the hood).
    private final Paint mDashedGhostPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mSolidGhostPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPillPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mTipPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final DashPathEffect mDash14x8 = new DashPathEffect(new float[]{14f, 8f}, 0f);
    // Phase 3a scratch paths (reused zero-alloc).
    private final Path mGhostPathScratch = new Path();
    private final RectF mPillRect = new RectF();
    // Scratch geometry reused per frame in drawHUD — §5.2 zero-heap on hot path.
    private final Path mWedgePathScratch = new Path();
    private final RectF mOuterRectScratch = new RectF();
    private final RectF mInnerRectScratch = new RectF();
    // Phase 3c: pre-allocated scratch for breathing animation (partial route + re-strike circle).
    private final PathMeasure mPathMeasureScratch = new PathMeasure();
    private final Path mPartialPathScratch = new Path();
    // Scratch path for the near-full dashed circle used by re-strike and
    // arrival-ring strokes. Built via buildDashedCircle (arcTo 0→359.99°).
    private final Path mCircleScratch = new Path();
    private final RectF mArcRectScratch = new RectF();

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

    // Ghost/breathing animation pump.
    //
    // View.postInvalidateOnAnimation() schedules invalidate via Choreographer
    // frame callbacks. Wolvic's UIWidget renders onto an off-screen surface
    // sampled by the VR render thread, and Choreographer callbacks do not
    // fire reliably on that path — so animations either stutter or stop
    // after a single frame. This Handler-driven tick runs every
    // GHOST_TICK_INTERVAL_MS while grip is held, forcing a canvas
    // invalidate that ripples through the VR texture upload. Self-
    // cancelling on grip release; no-op when the view is detached.
    private static final long GHOST_TICK_INTERVAL_MS = 16L;
    private final Runnable mGhostAnimTickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mGripHeld || mDispatcher == null) return;
            if (mCanvasView != null) mCanvasView.invalidate();
            mMainHandler.postDelayed(this, GHOST_TICK_INTERVAL_MS);
        }
    };

    // Commit-preview label cache.
    // Version-gated off mGhostCommittedPathVersion so the fast path (cache hit)
    // allocates zero — no Arrays.copyOf / Arrays.toString per frame.
    // Phase 3c R4: mCommitPreviewHand gates re-build when handedness changes.
    @Nullable private Spannable mCommitPreviewSpannable;
    private int mCommitPreviewPathVersion = -1;
    // Cache key for handedness — invalidates when hand identity changes mid-combo.
    // Stored as ordinal int (NONE=0, LEFT=1, RIGHT=2) to stay primitive.
    private int mCommitPreviewHandOrdinal = -1;
    // Cached accent flag alongside the commit-preview Spannable. Applied on both
    // fast-path and slow-path returns so the strip color stays correct when the
    // pickStripText flow transitions between releaseToFire (accent) and a cached
    // commit-preview (dim binding tip or accent CTA).
    private boolean mCommitPreviewIsAccent = false;

    // Cache for composeReleaseToFireLabel — avoids per-frame Arrays.copyOf +
    // ComboDispatcher key lookup during a sustained preview lean. Key is
    // (previewZone, mGhostCommittedPathVersion); value is the resolved action
    // int (A_NONE means "not bound — return null"). The version field already
    // bumps on: bindings change, commit, and (when path empty) zone change.
    private int mReleaseToFireCacheZone    = -1;
    private int mReleaseToFireCacheVersion = -1;
    private int mReleaseToFireCacheAction  = ComboDispatcher.A_NONE;

    // Tracks whether the current strip text is an accent label (yellow brand
    // emphasis: "Release to fire" or dead-end "Press A to save Combo" CTA) vs.
    // an idle/commit-preview tip (dim). Updated in pickStripText(), consumed in
    // drawTipStrip() to set paint color.
    private boolean mCurrentStripIsAccent = false;

    // Tip-strip StaticLayout cache — avoids per-frame StaticLayout construction
    // on the hot path (§5.2 zero-heap). StaticLayout is the only Canvas-friendly
    // path that actually draws ImageSpan replacement glyphs; canvas.drawText
    // walks characters only and silently skips spans. Cache keys: the
    // CharSequence reference (changes on tip rotation / committed-path change /
    // preview-zone change) and the pixel width.
    @Nullable private StaticLayout mTipStripLayout;
    @Nullable private CharSequence mTipStripLayoutKey;
    private int mTipStripLayoutWidth = -1;

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

        // Phase 3c: dashed ghost paint (no pathEffect mutation per frame).
        mDashedGhostPaint.setStyle(Paint.Style.STROKE);
        mDashedGhostPaint.setStrokeWidth(3f);
        mDashedGhostPaint.setColor(mColorGhostYellow);
        mDashedGhostPaint.setPathEffect(mDash14x8);

        // Phase 3c: solid ghost paint for stick-reactive crossfade target.
        mSolidGhostPaint.setStyle(Paint.Style.STROKE);
        mSolidGhostPaint.setStrokeWidth(3f);
        mSolidGhostPaint.setColor(mColorGhostYellow);
        // No path effect → solid line.

        mPillPaint.setStyle(Paint.Style.FILL);
        mPillPaint.setColor(mColorLabelPill);

        mTipPaint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        // Paint.Align.LEFT (default) is required: StaticLayout uses
        // Layout.Alignment.ALIGN_CENTER to center lines within the layout
        // width, and when the backing Paint is set to Align.CENTER instead,
        // StaticLayout shifts each line's origin by half the line width —
        // the visible text landed hugging the left edge of the strip
        // instead of centered under the dial.
        mTipPaint.setTextAlign(Paint.Align.LEFT);
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
        p.worldWidth = 0.795f;  // 0.53 * (720/480); keeps pixels-per-meter identical so the dial's physical size is unchanged.
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
     * Call from VRBrowserActivity once the dispatcher is constructed.
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
        // Path changed → tip strip swaps regimes. Invalidate caches.
        invalidateCommitPreviewCache();
        boolean wasBuilding = mIsBuilding;
        mIsBuilding = mGripHeld && mPathLength > 0;
        if (mPathLength > 0) {
            if (!wasBuilding) {
                // Transition 0→1: start ghost clock.
                mGhostRenderStartMs = -1L; // will be set on first drawGhostLayer call
            }
            stopTipRotation();
        } else if (mGripHeld) {
            startTipRotation();
            mGhostRenderStartMs = -1L;
        }
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    // 4-dir canonicalisation. Diagonals 1/3/7/9 expand to two cardinals.
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
        int[] tmp = new int[length];
        boolean[] tmpDiag = new boolean[length];
        int out = 0;
        for (int i = 0; i < length; i++) {
            int v = snapped[i];
            if (v == 0) continue;
            if (out > 0 && tmp[out - 1] == v && (tmpDiag[out - 1] || fromDiagonal[i])) {
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
     * Phase 3b: receive the continuous preview-progress signal from the native
     * engine. {@code progressRaw} is clamped [0, 1] by the engine; the smoother
     * applies EMA within the same zone and snaps across zone boundaries.
     */
    public void updatePreviewProgress(int zone, float progressRaw) {
        int prevZone = mPreviewSmoother.zone();
        mPreviewSmoother.accept(zone, progressRaw);
        // When the path is empty, the preview zone acts as a virtual-committed
        // anchor for the ghost layer — zone changes must rebuild ghosts so the
        // teaching arrows follow the user's current lean.
        if (mPathLength == 0 && zone != prevZone) {
            mGhostCommittedPathVersion++;
            if (mCanvasView != null) mCanvasView.invalidate();
        } else if (mCanvasView != null) {
            mCanvasView.invalidate();
        }
    }

    /**
     * Called from VRBrowserActivity when the grip-hold state flips.
     */
    public void updateGripState(boolean gripHeld) {
        if (mGripHeld == gripHeld) return;
        mGripHeld = gripHeld;
        boolean wasBuilding = mIsBuilding;
        mIsBuilding = gripHeld && mPathLength > 0;
        if (gripHeld) {
            if (mPathLength == 0) startTipRotation();
            startGhostAnimTick();
        } else {
            stopTipRotation();
            stopGhostAnimTick();
            mGhostRenderStartMs = -1L;
            mGhostAnim.resetCancel();
            mIsBuilding = false;
        }
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    private void startGhostAnimTick() {
        mMainHandler.removeCallbacks(mGhostAnimTickRunnable);
        mMainHandler.postDelayed(mGhostAnimTickRunnable, GHOST_TICK_INTERVAL_MS);
    }

    private void stopGhostAnimTick() {
        mMainHandler.removeCallbacks(mGhostAnimTickRunnable);
    }

    private boolean is4DirMode() {
        return mIs4DirMode;
    }

    public void resetPath() {
        for (int i = 0; i < mHitCounts.length; i++) mHitCounts[i] = 0;
        mPathLength = 0;
        mLastNode = 0;
        mPreviewNode = 0;
        mIsBuilding = false;
        mGhostRenderStartMs = -1L;
        mGhostAnim.resetCancel();
        invalidateCommitPreviewCache();
        if (mGripHeld) startTipRotation();
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    // ── Tip rotation (ARMED state) ────────────────────────

    private void rebuildTipPool() {
        if (mDispatcher == null) return;
        mTipPool = ComboTipBuilder.buildAll(mDispatcher, getContext().getResources());
        ComboTipBuilder.clearCache();
        if (mDispatcher.getLastChangedPathId() != null) {
            mTipSelector.notifyBindingChanged(
                    "bind:" + mDispatcher.getLastChangedPathId(),
                    System.currentTimeMillis());
        }
    }

    @Override
    public void onBindingsChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshModeFlag();
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

    // ── Commit-preview cache (R1 + R4 handedness CTA) ────

    private void invalidateCommitPreviewCache() {
        mCommitPreviewSpannable = null;
        // Ghost entries are keyed off the committed path too — bump the version
        // so drawGhostLayer rebuilds on next draw (no allocation per frame).
        mGhostCommittedPathVersion++;
        // New node committed → reset any stale cancel winner so competitor ghosts
        // on the new path don't appear pre-faded for a frame (Phase 3 review Fix 3).
        mGhostAnim.resetCancel();
    }

    @Nullable
    private Spannable composeCommitPreview(int[] path, int length) {
        if (mDispatcher == null || length == 0) return null;

        // Determine handedness for R4 CTA (ordinal to avoid boxing).
        int handOrdinal = resolveActiveHandOrdinal();

        // Fast path: committed path + bindings + hand all unchanged → zero alloc.
        if (mCommitPreviewPathVersion == mGhostCommittedPathVersion
                && mCommitPreviewHandOrdinal == handOrdinal) {
            mCurrentStripIsAccent = mCommitPreviewIsAccent;
            return mCommitPreviewSpannable;
        }

        // Slow path: rebuild. Only runs when committed path, bindings, or hand changed.
        int[] snapshot = Arrays.copyOf(path, length);
        int action = mDispatcher.getActionForExactPath(snapshot);
        Spannable out;
        boolean accent;
        if (action != ComboDispatcher.A_NONE) {
            // Path is itself a binding → render arrows + action-icon tip (dim).
            accent = false;
            out = ComboTipBuilder.renderBindingTip(
                    getContext(), snapshot, action,
                    (int) Math.round(mTipPaint.getTextSize()),
                    mColorTipText);
        } else {
            // Prefix-only → null (blank strip). Dead-end → handedness CTA.
            Set<Integer> nexts = mDispatcher.getLegalNextNodes(snapshot);
            if (nexts.isEmpty()) {
                // R4: handedness-reactive CTA. Accent-yellow brand emphasis.
                String buttonGlyph = handOrdinalToGlyph(handOrdinal);
                String cta = getContext().getString(R.string.fd_meta_tip_create_combo, buttonGlyph);
                accent = true;
                out = new SpannableString(cta);
            } else {
                accent = false;
                out = null;
            }
        }
        mCommitPreviewSpannable   = out;
        mCommitPreviewPathVersion = mGhostCommittedPathVersion;
        mCommitPreviewHandOrdinal = handOrdinal;
        mCommitPreviewIsAccent    = accent;
        mCurrentStripIsAccent     = accent;
        return out;
    }

    /**
     * Resolve the active combo controller hand as an ordinal int without
     * allocating a VRBrowserActivity.ComboHand enum reference per call.
     * Returns: 0=NONE, 1=LEFT, 2=RIGHT.
     *
     * Accesses VRBrowserActivity via the widgetManager context. If the cast
     * fails (e.g. test context), defaults to RIGHT (2).
     */
    private int resolveActiveHandOrdinal() {
        Context ctx = getContext();
        if (ctx instanceof VRBrowserActivity) {
            VRBrowserActivity.ComboHand hand =
                    ((VRBrowserActivity) ctx).getActiveComboControllerHand();
            if (hand == VRBrowserActivity.ComboHand.LEFT)  return 1;
            if (hand == VRBrowserActivity.ComboHand.RIGHT) return 2;
            return 0; // NONE
        }
        return 2; // default to RIGHT
    }

    private static String handOrdinalToGlyph(int ordinal) {
        // ordinal: 0=NONE→"A", 1=LEFT→"X", 2=RIGHT→"A"
        return (ordinal == 1) ? "X" : "A";
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

    private void drawIconPill(Canvas canvas, float cx, float cy, @DrawableRes int iconRes, int alpha) {
        // 44×44 rounded-square pill, corner 8px. Axis-aligned.
        mPillRect.set(cx - 22f, cy - 22f, cx + 22f, cy + 22f);
        mPillPaint.setColor(mColorLabelPill);
        mPillPaint.setAlpha(alpha);
        canvas.drawRoundRect(mPillRect, 8f, 8f, mPillPaint);
        Drawable icon = getTintedIcon(iconRes, 0xFFFDDE0A /* fd-yellow full alpha */);
        if (icon == null || alpha <= 0) return;
        // The cached drawable carries a PorterDuff.Mode.SRC_IN color filter
        // baked with full-alpha yellow, which on hardware-accelerated canvas
        // dominates Drawable.setAlpha — the icon stayed pinned at 255 while
        // the pill background breathed. saveLayerAlpha renders the icon
        // into an offscreen layer and composites the layer back at the
        // requested alpha, bypassing the filter-dominance quirk.
        int layerSave = canvas.saveLayerAlpha(
                cx - 16f, cy - 16f, cx + 16f, cy + 16f, alpha);
        canvas.translate(cx, cy);
        icon.draw(canvas);
        canvas.restoreToCount(layerSave);
    }

    // ── Lifecycle ────────────────────────────────────────

    @Override
    public void releaseWidget() {
        stopTipRotation();
        stopGhostAnimTick();
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
        stopGhostAnimTick();
        if (mDispatcher != null) {
            mDispatcher.removeBindingsListener(this);
        }
        mTintedIconCache.clear();
        super.onDetachedFromWindow();
    }

    // ── Drawing ───────────────────────────────────────────

    private void drawHUD(Canvas canvas, int w, int h) {
        // Cache frame time once at top of onDraw for all per-ghost animation math.
        mCurrentFrameTimeMs = System.nanoTime() / 1_000_000L;
        android.util.Log.d("FingerDance", "drawHUD w=" + w + " h=" + h
                + " grip=" + mGripHeld + " building=" + mIsBuilding
                + " pathLen=" + mPathLength + " ghosts=" + mGhostCount
                + " lastNode=" + mLastNode);

        int tipStripPx = Math.round(TIP_STRIP_H * (h / (float) WIDGET_H));
        int dialH = h - tipStripPx;
        float cx = w / 2f;
        float cy = dialH / 2f;
        float outerR = Math.min(cx, cy) * 0.70f;
        float innerR = outerR * 0.30f;
        float labelR = (outerR + innerR) / 2f;

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

        final boolean fourDir = is4DirMode();
        final int[] nodes   = fourDir ? WEDGE_NODES_4  : WEDGE_NODES_8;
        final int count    = nodes.length;
        final float stepDeg = 360f / count;
        final float halfWidth = stepDeg / 2f;

        mOuterRectScratch.set(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        mInnerRectScratch.set(cx - innerR, cy - innerR, cx + innerR, cy + innerR);

        // Pass 1: wedge fills + separator lines. Arrow glyphs are deferred
        // to Pass 2 below so they render ABOVE the ghost layer (round-8
        // feedback: "arrows should be above ghost routes").
        for (int i = 0; i < count; i++) {
            int node = nodes[i];
            boolean isPreview = (node == mPreviewNode);

            float startAngle = -(i * stepDeg) - halfWidth;
            float sweep = halfWidth * 2f;

            mWedgePaint.setColor(isPreview ? mColorWedgePreview : mColorWedgeIdle);

            mWedgePathScratch.reset();
            mWedgePathScratch.arcTo(mOuterRectScratch, startAngle, sweep, true);
            mWedgePathScratch.arcTo(mInnerRectScratch, startAngle + sweep, -sweep);
            mWedgePathScratch.close();
            canvas.drawPath(mWedgePathScratch, mWedgePaint);

            float lineAngle = (float) Math.toRadians(startAngle);
            canvas.drawLine(
                    cx + innerR * (float) Math.cos(lineAngle),
                    cy + innerR * (float) Math.sin(lineAngle),
                    cx + outerR * (float) Math.cos(lineAngle),
                    cy + outerR * (float) Math.sin(lineAngle),
                    mLinePaint);
        }

        float dotR = outerR * 1.15f;
        float dotBaseR = outerR * 0.06f;
        mRingPaint.setStrokeWidth(2.0f);
        mRingPaint.setAlpha(200);
        for (int i = 0; i < count; i++) {
            int node = nodes[i];
            int hits = mHitCounts[node];
            if (hits <= 0) continue;
            float startAngle = -(i * stepDeg) - halfWidth;
            float midAngle = (float) Math.toRadians(startAngle + halfWidth);
            float dx = cx + dotR * (float) Math.cos(midAngle);
            float dy = cy + dotR * (float) Math.sin(midAngle);

            float r = dotBaseR + dotBaseR * 0.5f * (Math.min(hits, 5) - 1);

            mWedgePaint.setColor(mColorAccent);
            mWedgePaint.setAlpha(255);
            canvas.drawCircle(dx, dy, r, mWedgePaint);

            if (hits > 1) {
                for (int k = 1; k < Math.min(hits, 4); k++) {
                    canvas.drawCircle(dx, dy, r + k * dotBaseR * 0.45f, mRingPaint);
                }
            }

            if (node == mLastNode) {
                mWedgePaint.setColor(mColorTextLast);
                mWedgePaint.setAlpha(255);
                canvas.drawCircle(dx, dy, r * 0.45f, mWedgePaint);
            }
        }

        mWedgePaint.setColor(mColorCenter);
        mWedgePaint.setAlpha(255);
        canvas.drawCircle(cx, cy, innerR - 2f, mWedgePaint);

        if (mPathLength > 0) {
            mTextPaint.setTextSize(innerR * 0.7f);
            mTextPaint.setColor(mColorAccent);
            float textY = cy - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
            canvas.drawText(PATH_LENGTH_LABEL[mPathLength], cx, textY, mTextPaint);
        } else {
            mWedgePaint.setColor(mColorAccent);
            mWedgePaint.setAlpha(150);
            canvas.drawCircle(cx, cy, 6f, mWedgePaint);
        }

        // ── Phase 3c: ghost traces (armed + lean) ─────
        // Ghosts are the "legal next moves" teaching layer. Three sub-states:
        //  • ARMED idle (grip held, path empty, no lean): dashed straight lines
        //    radiate from the dial center to each bound starter direction.
        //  • ARMED lean (grip held, path empty, stick leaning): dashed lines
        //    bow from the lean node's dot to each node that extends the lean
        //    into a bound 2-node combo. This teaches "where can I go next?".
        //  • BUILDING (grip held, path committed): dashed lines bow from the
        //    last committed node to each legal next node.
        // Ghosts hide when grip is released or when the recognizer is disabled.
        boolean hasGhostAnchor = mGripHeld && mDispatcher != null;
        if (hasGhostAnchor) {
            drawGhostLayer(canvas, cx, cy, outerR, dotR, dotBaseR,
                    nodes, count, stepDeg, halfWidth);
        }

        // Pass 2: arrow glyphs, rendered ABOVE the ghost layer so the
        // direction cues are never obscured by dashed ghost routes.
        mTextPaint.setTextSize(outerR * 0.22f);
        mTextPaint.setShadowLayer(6f, 0f, 3f, 0xB3000000);
        for (int i = 0; i < count; i++) {
            int node = nodes[i];
            boolean isPreview = (node == mPreviewNode);
            float startAngle = -(i * stepDeg) - halfWidth;
            float midDeg = startAngle + halfWidth;
            float midAngle = (float) Math.toRadians(midDeg);
            float lx = cx + labelR * (float) Math.cos(midAngle);
            float ly = cy + labelR * (float) Math.sin(midAngle);
            mTextPaint.setColor(isPreview ? mColorTextPreview : mColorTextIdle);
            float textY = ly - (mTextPaint.descent() + mTextPaint.ascent()) / 2f;
            canvas.save();
            canvas.rotate(midDeg + 90f, lx, ly);
            canvas.drawText(ARROW_GLYPH, lx, textY, mTextPaint);
            canvas.restore();
        }
        mTextPaint.clearShadowLayer();

        // ── Phase 3a: bottom tip strip ────────────────────
        drawTipStrip(canvas, w, h, dialH, tipStripPx);

        // Per-frame invalidate is driven by mGhostAnimTickRunnable on the main
        // Handler (see startGhostAnimTick). postInvalidateOnAnimation() was
        // tried first and does not fire reliably on Wolvic's UIWidget off-
        // screen surface path, which caused the breathing animation to stop
        // after a single frame.
    }

    /**
     * Draws animated dashed ghost curves from {@code lastNode} to every legal
     * next node, with breathing + stick-reactive crossfade + cancel fading.
     *
     * Per-frame allocation is zero: ghost entries rebuilt only on version bump,
     * drawn here by iterating a preallocated fixed-capacity array.
     */
    private void drawGhostLayer(Canvas canvas, float cx, float cy,
                                 float outerR, float dotR, float dotBaseR,
                                 int[] nodes, int count, float stepDeg, float halfWidth) {
        if (mLastGhostVersion != mGhostCommittedPathVersion) {
            rebuildGhostEntries();
            mLastGhostVersion = mGhostCommittedPathVersion;
        }
        if (mGhostCount == 0) return;

        // Start ghost clock on first visible frame.
        if (mGhostRenderStartMs < 0) {
            mGhostRenderStartMs = mCurrentFrameTimeMs;
        }

        // Update cancellation state machine once per frame.
        //
        // Cancel-fade was designed for the pre-commit arm phase ("you're
        // leaning past threshold toward firing this one, so dim the others").
        // Once a node is committed and the stick is still held, the same lean
        // is the user pausing at the committed node — NOT an intent to kill
        // the other legal-next options. So disable cancel during BUILDING
        // state: the aimed-ghost solid fill is still the "about to fire" cue,
        // and the breathing ghosts stay visible so the user can see every
        // legal next move the committed path offers.
        if (mPathLength > 0) {
            mGhostAnim.resetCancel();
        } else {
            mGhostAnim.updateCancellationState(mPreviewSmoother, mCurrentFrameTimeMs);
        }

        int aimedZone   = mPreviewSmoother.zone();
        float nodeProgress = mPreviewSmoother.progress();

        // Anchor selection:
        //   path non-empty            → last committed node on the dot ring
        //   path empty + active lean  → preview zone on the dot ring
        //   path empty + no lean      → dial center (idle teaching lines)
        int lastNode;
        float fromX, fromY, fromMid;
        boolean centerAnchor = (mPathLength == 0 && aimedZone <= 0);
        if (centerAnchor) {
            lastNode = 0;
            fromX = cx;
            fromY = cy;
            fromMid = 0f;
        } else {
            lastNode = (mPathLength > 0)
                    ? mCommittedPath[mPathLength - 1]
                    : aimedZone;
            if (lastNode <= 0) return;
            int idxFrom = indexOfNode(nodes, count, lastNode);
            if (idxFrom < 0) return;
            fromMid = (float) Math.toRadians(-(idxFrom * stepDeg));
            fromX = cx + dotR * (float) Math.cos(fromMid);
            fromY = cy + dotR * (float) Math.sin(fromMid);
        }

        int numGhosts = mGhostCount;

        for (int i = 0; i < numGhosts; i++) {
            GhostEntry g = mGhostScratch[i];
            int idxTo = indexOfNode(nodes, count, g.nextNode);
            if (idxTo < 0) continue;

            float toMid = (float) Math.toRadians(-(idxTo * stepDeg));
            float toX   = cx + dotR * (float) Math.cos(toMid);
            float toY   = cy + dotR * (float) Math.sin(toMid);

            // Sync every ghost to phase 0. On-device testing showed that the
            // staggered fan (originally intended as a rhythmic flourish in
            // BUILDING state, where ghosts radiate from distinct nodes) reads
            // as chaotic flicker, not rhythm — even when origins are visually
            // distinct. Users consistently preferred unified breathing for
            // every state. One schedule, shared pulse.
            long phaseOffsetMs = 0L;
            long elapsed = mCurrentFrameTimeMs - mGhostRenderStartMs;
            long localPhaseMs = (elapsed + phaseOffsetMs) % ComboGhostAnimation.PERIOD_MS;

            // Cancel alpha — competitors of the winner fade out.
            float cancelAlpha = mGhostAnim.ghostCancelAlpha(g.nextNode, mCurrentFrameTimeMs);

            // Is this the aimed ghost? Re-strike ghosts (self-loop, nextNode ==
            // lastNode) never go aimed: while the user is holding the stick at
            // the just-committed node, the engine legitimately still reports
            // that zone as the live direction, but the [lastNode, lastNode]
            // combo does not actually fire until the stick returns to center
            // and pushes out again. Treating the hold as "aimed" crossfaded the
            // re-strike circle to solid yellow and froze its icon at 100% —
            // users read that as "broken, not dashing, not breathing". Keep
            // re-strike visuals on the breathing schedule regardless of live
            // preview state.
            boolean isAimed = (g.nextNode == aimedZone
                    && nodeProgress > 0f
                    && g.nextNode != lastNode);

            if (isAimed) {
                // R3: Stick-reactive crossfade — pauses breathing, uses nodeProgress.
                drawAimedGhost(canvas, cx, cy, fromX, fromY, toX, toY,
                        fromMid, toMid, centerAnchor,
                        g.nextNode, lastNode, dotBaseR, g.actionInt,
                        dotR, nodeProgress, cancelAlpha);
            } else {
                // R2: Breathing animation.
                drawBreathingGhost(canvas, cx, cy, fromX, fromY, toX, toY,
                        fromMid, toMid, centerAnchor,
                        g.nextNode, lastNode, dotBaseR, g.actionInt,
                        dotR, localPhaseMs, cancelAlpha);
            }
        }
    }

    /**
     * Build the ghost route as an actual {@link Path} ready to stroke. Writes
     * into {@code target}; always resets {@code target} first.
     *
     * <p><b>Adjacent cardinals / diagonals (non-opposite, non-self-loop):</b>
     * a true circular arc on the HUD-centered dot ring. Both endpoints lie on
     * that ring, so the arc is literally the 1/4-circle (4-dir) or 1/8-circle
     * (8-dir) that the user physically traces with the joystick. No Bezier
     * approximation — the visual IS the geometry.
     *
     * <p><b>Opposite-cardinal pair</b> (|delta| ≈ π, e.g. 2↔8 or 4↔6): straight
     * line through dial center. Matches the actual joystick sweep, which for
     * diametrically-opposite dots literally passes through center.
     *
     * <p><b>Center anchor mode</b> (centerAnchor=true): straight line from
     * dial center to the toNode — the idle "here are your starter moves"
     * dashed radial spokes.
     *
     * <p>Angle convention: {@code fromMid}/{@code toMid} are in radians using
     * Android's canvas-space angle convention (0 = east, positive = CW in
     * screen coords under Y-down). {@link Path#arcTo} uses the same convention
     * in degrees, so radians→degrees is a direct conversion.
     *
     * <p>Zero allocation on the hot path — shares the widget's
     * {@code mArcRectScratch}.
     */
    private void buildGhostRoute(Path target,
                                  float fromX, float fromY,
                                  float toX, float toY,
                                  float fromMid, float toMid,
                                  float cx, float cy, float dotR,
                                  boolean centerAnchor) {
        target.reset();
        target.moveTo(fromX, fromY);
        if (centerAnchor) {
            target.lineTo(toX, toY);
            return;
        }
        float delta = toMid - fromMid;
        while (delta > (float) Math.PI)  delta -= (float) (2.0 * Math.PI);
        while (delta < -(float) Math.PI) delta += (float) (2.0 * Math.PI);
        // Opposite cardinal (|delta| ≈ π): straight line through center.
        if (Math.abs(Math.abs(delta) - (float) Math.PI) < 1e-3f) {
            target.lineTo(toX, toY);
            return;
        }
        // Circular arc on the dot ring centered at dial center. Using the
        // existing dot ring as the arc radius guarantees both endpoints sit
        // exactly on the arc with no seam at node positions.
        mArcRectScratch.set(cx - dotR, cy - dotR, cx + dotR, cy + dotR);
        float startDeg = (float) Math.toDegrees(fromMid);
        float sweepDeg = (float) Math.toDegrees(delta);
        target.arcTo(mArcRectScratch, startDeg, sweepDeg, false);
    }

    // Build an almost-closed 359.99° arc into target. Required because
    // DashPathEffect is unreliable on closed contours (Path.addCircle /
    // canvas.drawCircle) under hardware acceleration — the dashes silently
    // drop out. An open near-full arc renders dashes reliably.
    private void buildDashedCircle(Path target, float cx, float cy, float r) {
        target.reset();
        mArcRectScratch.set(cx - r, cy - r, cx + r, cy + r);
        target.arcTo(mArcRectScratch, 0f, 359.99f, true);
    }

    /**
     * Draw a breathing ghost (R2). Alpha-only pulse — route extends in the
     * first half of the breath cycle, then everything fades. Re-strike circles
     * stay fully formed and breathe on alpha (no arc-sweep "spinner" effect).
     */
    private void drawBreathingGhost(Canvas canvas, float cx, float cy,
                                     float fromX, float fromY, float toX, float toY,
                                     float fromMid, float toMid, boolean centerAnchor,
                                     int nextNode, int lastNode,
                                     float dotBaseR, int actionInt,
                                     float dotR,
                                     long localPhaseMs, float cancelAlpha) {
        float breathUnit = ComboGhostAnimation.breathAlphaUnit(localPhaseMs,
                ComboGhostAnimation.PERIOD_MS);
        boolean inExtension = localPhaseMs < ComboGhostAnimation.HALF_PERIOD_MS;

        // Route alpha: extension ramps 0→178, fade stays at 178 but alpha decreases.
        int routeAlpha = Math.round(178f * breathUnit * cancelAlpha);
        // Arrival ring alpha: same schedule.
        int ringAlpha  = Math.round(178f * breathUnit * cancelAlpha);
        // Icon pill alpha: ramps 0→255 extension, 255→0 fade.
        int pillAlpha  = Math.round(255f * breathUnit * cancelAlpha);

        if (nextNode == lastNode) {
            // Re-strike: always-full dashed circle, alpha breathes.
            if (routeAlpha > 0) {
                mDashedGhostPaint.setAlpha(routeAlpha);
                buildDashedCircle(mCircleScratch, fromX, fromY, dotBaseR * 1.8f);
                canvas.drawPath(mCircleScratch, mDashedGhostPaint);
            }
        } else {
            // Route arc (or straight line for opposite / center-anchor).
            if (routeAlpha > 0) {
                mDashedGhostPaint.setAlpha(routeAlpha);
                if (inExtension && breathUnit < 0.99f) {
                    // Partial extension — measure and clip.
                    buildGhostRoute(mGhostPathScratch, fromX, fromY, toX, toY,
                            fromMid, toMid, cx, cy, dotR, centerAnchor);
                    mPathMeasureScratch.setPath(mGhostPathScratch, false);
                    float len = mPathMeasureScratch.getLength();
                    mPartialPathScratch.reset();
                    mPathMeasureScratch.getSegment(0f, len * breathUnit,
                            mPartialPathScratch, true);
                    canvas.drawPath(mPartialPathScratch, mDashedGhostPaint);
                } else {
                    // Full route (extension at 1.0 or fade phase).
                    buildGhostRoute(mGhostPathScratch, fromX, fromY, toX, toY,
                            fromMid, toMid, cx, cy, dotR, centerAnchor);
                    canvas.drawPath(mGhostPathScratch, mDashedGhostPaint);
                }
            }

            // Arrival ring (dashed stroke around the toNode dot).
            if (ringAlpha > 0) {
                mDashedGhostPaint.setAlpha(ringAlpha);
                buildDashedCircle(mCircleScratch, toX, toY, dotBaseR * 1.2f);
                canvas.drawPath(mCircleScratch, mDashedGhostPaint);
            }
        }

        // Icon pill.
        if (pillAlpha > 0) {
            float pillR = dotR + 48f;
            float pillX = cx + pillR * (float) Math.cos(toMid);
            float pillY = cy + pillR * (float) Math.sin(toMid);
            drawIconPill(canvas, pillX, pillY, ComboActionIcons.iconFor(actionInt), pillAlpha);
        }
    }

    /**
     * Draw the aimed ghost (R3). Pauses breathing: full route extent at nodeProgress
     * crossfade between dashed and solid. AimedZone ghost is unaffected by cancel
     * (cancelAlpha = 1.0 for the winner).
     */
    private void drawAimedGhost(Canvas canvas, float cx, float cy,
                                  float fromX, float fromY, float toX, float toY,
                                  float fromMid, float toMid, boolean centerAnchor,
                                  int nextNode, int lastNode,
                                  float dotBaseR, int actionInt,
                                  float dotR,
                                  float nodeProgress, float cancelAlpha) {
        // Crossfade: dashed at (1-progress), solid at progress.
        int dashedAlpha = Math.round(255f * 0.70f * (1f - nodeProgress) * cancelAlpha);
        int solidAlpha  = Math.round(255f * nodeProgress * cancelAlpha);
        int pillAlpha   = Math.round(255f * cancelAlpha);

        if (nextNode == lastNode) {
            // Re-strike: full dashed circle fading to solid circle.
            if (dashedAlpha > 0) {
                mDashedGhostPaint.setAlpha(dashedAlpha);
                buildDashedCircle(mCircleScratch, fromX, fromY, dotBaseR * 1.8f);
                canvas.drawPath(mCircleScratch, mDashedGhostPaint);
            }
            if (solidAlpha > 0) {
                mSolidGhostPaint.setAlpha(solidAlpha);
                canvas.drawCircle(fromX, fromY, dotBaseR * 1.8f, mSolidGhostPaint);
            }
        } else {
            buildGhostRoute(mGhostPathScratch, fromX, fromY, toX, toY,
                    fromMid, toMid, cx, cy, dotR, centerAnchor);

            if (dashedAlpha > 0) {
                mDashedGhostPaint.setAlpha(dashedAlpha);
                canvas.drawPath(mGhostPathScratch, mDashedGhostPaint);
            }
            if (solidAlpha > 0) {
                mSolidGhostPaint.setAlpha(solidAlpha);
                canvas.drawPath(mGhostPathScratch, mSolidGhostPaint);
            }

            // Arrival ring crossfade — dashed path, solid drawCircle.
            int dashedRingAlpha = Math.round(255f * 0.70f * (1f - nodeProgress) * cancelAlpha);
            int solidRingAlpha  = Math.round(255f * nodeProgress * cancelAlpha);
            if (dashedRingAlpha > 0) {
                mDashedGhostPaint.setAlpha(dashedRingAlpha);
                buildDashedCircle(mCircleScratch, toX, toY, dotBaseR * 1.2f);
                canvas.drawPath(mCircleScratch, mDashedGhostPaint);
            }
            if (solidRingAlpha > 0) {
                mSolidGhostPaint.setAlpha(solidRingAlpha);
                canvas.drawCircle(toX, toY, dotBaseR * 1.2f, mSolidGhostPaint);
            }
        }

        // Icon pill stays full while aimed.
        if (pillAlpha > 0) {
            float pillR = dotR + 48f;
            float pillX = cx + pillR * (float) Math.cos(toMid);
            float pillY = cy + pillR * (float) Math.sin(toMid);
            drawIconPill(canvas, pillX, pillY, ComboActionIcons.iconFor(actionInt), pillAlpha);
        }
    }

    /**
     * Rebuild ghost entries from the current committed path + dispatcher
     * bindings. Called only when mGhostCommittedPathVersion bumps.
     */
    private void rebuildGhostEntries() {
        mGhostCount = 0;
        if (mDispatcher == null) return;

        // Anchor selection (mirrors drawGhostLayer):
        //   path non-empty            → committed last node
        //   path empty + active lean  → preview zone (teaching-during-lean)
        //   path empty + no lean      → empty path (idle starter teaching)
        int lastNode;
        int anchorLen;
        if (mPathLength > 0) {
            lastNode = mCommittedPath[mPathLength - 1];
            System.arraycopy(mCommittedPath, 0, mExtendedPathScratch, 0, mPathLength);
            anchorLen = mPathLength;
        } else {
            int preview = mPreviewSmoother.zone();
            if (preview > 0) {
                lastNode = preview;
                mExtendedPathScratch[0] = preview;
                anchorLen = 1;
            } else {
                lastNode = 0;
                anchorLen = 0;
            }
        }
        int extLen = anchorLen + 1;

        int[] anchorPath = Arrays.copyOf(mExtendedPathScratch, anchorLen);
        Set<Integer> legalNexts = mDispatcher.getLegalNextNodes(anchorPath);
        for (Integer boxedNext : legalNexts) {
            int nextNode = boxedNext;
            if (nextNode == 5) continue;
            mExtendedPathScratch[anchorLen] = nextNode;
            int[] extended = Arrays.copyOf(mExtendedPathScratch, extLen);
            int action = mDispatcher.getActionForExactPath(extended);
            if (action == ComboDispatcher.A_NONE) continue;
            insertGhostSorted(nextNode, extLen, action);
        }

        // Re-strike fallback: only meaningful when we have a real anchor node
        // (anchorLen > 0). In center-anchor idle mode, lastNode == 0 so there
        // is no re-strike semantics to preserve.
        if (anchorLen > 0
                && !legalNexts.contains(lastNode)
                && !containsGhostForNode(lastNode)) {
            mExtendedPathScratch[anchorLen] = lastNode;
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

    private void insertGhostSorted(int nextNode, int pathLength, int actionInt) {
        int idx = 0;
        while (idx < mGhostCount) {
            GhostEntry e = mGhostScratch[idx];
            int c = Integer.compare(pathLength, e.pathLength);
            if (c == 0) c = Integer.compare(actionInt, e.actionInt);
            if (c < 0) break;
            idx++;
        }
        if (idx >= GHOST_CAP) return;
        int end = Math.min(mGhostCount, GHOST_CAP - 1);
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
     * In BUILDING state: commit-preview tip (arrows + action icon), dead-end CTA,
     * or blank on prefix.
     */
    private void drawTipStrip(Canvas canvas, int w, int h, int dialH, int tipStripPx) {
        CharSequence cs = pickStripText();
        if (cs == null || cs.length() == 0) return;
        int availWidth = Math.max(0, w - 16);

        // Swap text color based on strip role: yellow when actively signaling
        // "release to fire", dim otherwise. StaticLayout reads mTipPaint's
        // color at draw time, so a plain setColor on the shared paint is
        // enough — no layout rebuild needed for the text portion.
        int desiredColor = mCurrentStripIsAccent ? mColorGhostYellow : mColorTipText;
        if (mTipPaint.getColor() != desiredColor) {
            mTipPaint.setColor(desiredColor);
        }

        // Rebuild the StaticLayout only when the text identity or width changes.
        // The composeReleaseToFireTip / composeCommitPreview / rendering helpers
        // upstream return cached Spannables for stable inputs, so the reference
        // compare here acts as a real cache key.
        if (cs != mTipStripLayoutKey || availWidth != mTipStripLayoutWidth) {
            mTipStripLayout = new StaticLayout(
                    cs, mTipPaint, availWidth,
                    Layout.Alignment.ALIGN_CENTER,
                    1f, 0f, false);
            mTipStripLayoutKey = cs;
            mTipStripLayoutWidth = availWidth;
        }
        if (mTipStripLayout == null) return;

        // Center the layout vertically inside the 60px strip (dialH .. dialH+tipStripPx).
        float yOffset = dialH + (tipStripPx - mTipStripLayout.getHeight()) * 0.5f;
        int save = canvas.save();
        canvas.translate(8f, yOffset);
        mTipStripLayout.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Nullable
    private CharSequence pickStripText() {
        if (!mGripHeld) return null;

        // Highest priority: "Release to fire: [icon] [name]" whenever the user
        // is leaning toward a zone that, combined with the committed path,
        // forms an exact bound action. Covers both fresh combos (path empty,
        // leaning toward a single-tap binding like [2]=Scroll-Up) and combo
        // extensions (path [2], leaning back to 2 for the [2,2]=Scroll-Top
        // re-strike).
        CharSequence releaseToFire = composeReleaseToFireLabel();
        if (releaseToFire != null) {
            mCurrentStripIsAccent = true;
            return releaseToFire;
        }

        if (mPathLength == 0) {
            mCurrentStripIsAccent = false;
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
        // BUILDING without active lean. Commit preview label (R1 + R4).
        // The dead-end CTA branch inside composeCommitPreview sets the accent
        // flag; the binding-tip / prefix-blank branches reset it.
        return composeCommitPreview(mCommittedPath, mPathLength);
    }

    /**
     * When the stick is actively leaned into a zone that completes a bound
     * combo (either alone or appended to the committed path), return the
     * "Release to fire: [icon] [action name]" Spannable. Returns {@code null}
     * otherwise.
     *
     * <p>Zero allocation on the hot path: delegates to
     * {@link ComboTipBuilder#renderReleaseToFireTip} which caches by
     * {@code (actionInt, size, color)} — repeated frames during a sustained
     * lean hit the cache.
     */
    @Nullable
    private CharSequence composeReleaseToFireLabel() {
        if (mDispatcher == null) return null;
        int previewZone = mPreviewSmoother.zone();

        // Resolution priority:
        //   1. If the committed path is itself a bound combo, its action is
        //      what releasing grip right now will actually fire. Show that —
        //      even if the user is leaning further (the lean has not yet
        //      crossed activation, so no new commit has happened). This is
        //      the fix for the "committed at zone 4, label says Back" bug:
        //      previously we always appended previewZone to the committed
        //      path, computing [4,4] = A_BACK. Now committed [4] = A_SCROLL_
        //      LEFT wins.
        //   2. Otherwise, if the stick is leaned into a zone that extends
        //      the (possibly empty) committed path into a bound combo,
        //      show that virtual action — an anticipatory "if you push past
        //      activation this will fire" cue. Covers the fresh-combo case
        //      (path empty, leaning toward [2] = Scroll Up) and the prefix-
        //      extension case (path [2], leaning toward [2,8] = Refresh
        //      when [2] itself is unbound).
        //   3. Otherwise return null — caller falls through to idle-tip or
        //      commit-preview rendering.
        //
        // Zero-allocation cache keyed on (previewZone, committed-version).
        // The version field bumps on bindings changes, commits, and zone-
        // change-while-path-empty, so this key is sufficient.
        int action;
        if (mReleaseToFireCacheZone == previewZone
                && mReleaseToFireCacheVersion == mGhostCommittedPathVersion) {
            action = mReleaseToFireCacheAction;
        } else {
            action = ComboDispatcher.A_NONE;
            // 1. Committed-path action takes priority when path is non-empty.
            if (mPathLength > 0) {
                int[] committed = Arrays.copyOf(mCommittedPath, mPathLength);
                action = mDispatcher.getActionForExactPath(committed);
            }
            // 2. Virtual-extended fallback — only when committed path is
            //    unbound/empty and the user is actively leaning.
            if (action == ComboDispatcher.A_NONE
                    && previewZone > 0 && previewZone != 5) {
                int virtualLen = mPathLength + 1;
                if (virtualLen <= mExtendedPathScratch.length) {
                    System.arraycopy(mCommittedPath, 0,
                            mExtendedPathScratch, 0, mPathLength);
                    mExtendedPathScratch[mPathLength] = previewZone;
                    int[] virtualPath = Arrays.copyOf(
                            mExtendedPathScratch, virtualLen);
                    action = mDispatcher.getActionForExactPath(virtualPath);
                }
            }
            mReleaseToFireCacheZone    = previewZone;
            mReleaseToFireCacheVersion = mGhostCommittedPathVersion;
            mReleaseToFireCacheAction  = action;
        }
        if (action == ComboDispatcher.A_NONE) return null;

        // Text-only label ("Release: {Action Name}"). The inline ImageSpan
        // experiment from round 4/5 never read cleanly in VR — icon
        // alignment on the 28sp line was fragile and the icon is already
        // shown in the ghost pill for the aimed zone, so the strip doesn't
        // need to repeat it.
        return ComboTipBuilder.renderReleaseToFireTip(getContext(), action);
    }
}
