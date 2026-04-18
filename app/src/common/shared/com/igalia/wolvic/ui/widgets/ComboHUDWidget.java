package com.igalia.wolvic.ui.widgets;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;

import com.igalia.wolvic.R;

/**
 * Head-locked HUD showing the in-progress combo path as a circular dial.
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
public class ComboHUDWidget extends UIWidget {

    private static final int WIDGET_PX = 480;  // extra margin outside the dial for confirmation dots

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

    // State
    private final int[] mHitCounts = new int[10]; // index 1-9
    private int mLastNode   = 0;
    private int mPathLength = 0;
    private int mPreviewNode = 0;  // currently highlighted zone (0=none)

    // Paint objects
    private final Paint mBgPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mWedgePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mTextPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLinePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    private View mCanvasView;

    public ComboHUDWidget(Context aContext) {
        super(aContext);
        android.util.Log.e("ComboHUD", "Constructor called, handle=" + getHandle());
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
        mBgPaint.setColor(mColorBg);
        mTextPaint.setTypeface(Typeface.DEFAULT_BOLD);
        mTextPaint.setTextAlign(Paint.Align.CENTER);
        mLinePaint.setColor(0x40606080);
        mLinePaint.setStrokeWidth(1.5f);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setColor(mColorAccent);
        initialize();
    }

    @Override
    public void setSurfaceTexture(android.graphics.SurfaceTexture aTexture, final int aWidth, final int aHeight, Runnable aFirstDrawCallback) {
        android.util.Log.e("ComboHUD", "setSurfaceTexture w=" + aWidth + " h=" + aHeight + " tex=" + aTexture);
        super.setSurfaceTexture(aTexture, aWidth, aHeight, aFirstDrawCallback);
    }

    @Override
    protected void initializeWidgetPlacement(WidgetPlacement p) {
        p.width  = WIDGET_PX;
        p.height = WIDGET_PX;
        p.worldWidth = 0.53f;  // scaled with WIDGET_PX so the dial stays the same physical size
        // World-space placement. y is measured from the reorient base (floor
        // level in Wolvic — eye level is ~1.6m for a standing adult). Place
        // at adult eye level, 1.5m forward.
        p.translationX = 0;
        p.translationY = WidgetPlacement.unitFromMeters(1.5f);
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
        android.util.Log.e("ComboHUD", "show() called, visible=" + mWidgetPlacement.visible + " composited=" + mWidgetPlacement.composited);
        super.show(aShowFlags);
    }

    /** Update with confirmed path (nodes that have been activated). */
    public void updatePath(int[] path, int length) {
        for (int i = 0; i < mHitCounts.length; i++) mHitCounts[i] = 0;
        mLastNode = 0;
        if (is4DirMode()) {
            // Mirror the dispatcher's interpretation so the HUD reflects the
            // path the dispatcher will act on. Snap diagonals to their closer
            // horizontal-or-vertical neighbour by context (if the adjacent
            // flick was cardinal, the diagonal is treated as spurious overshoot
            // of that cardinal); then collapse consecutive duplicates.
            int[] collapsed = collapseFourDir(path, length);
            mPathLength = collapsed.length;
            for (int node : collapsed) {
                if (node >= 1 && node <= 9) {
                    mHitCounts[node]++;
                    mLastNode = node;
                }
            }
        } else {
            mPathLength = length;
            for (int i = 0; i < length; i++) {
                int node = path[i];
                if (node >= 1 && node <= 9) {
                    mHitCounts[node]++;
                    mLastNode = node;
                }
            }
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

    private boolean is4DirMode() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getContext());
        return prefs.getBoolean("fingerdance_combo_4dir_mode", true);
    }

    public void resetPath() {
        for (int i = 0; i < mHitCounts.length; i++) mHitCounts[i] = 0;
        mPathLength = 0;
        mLastNode = 0;
        mPreviewNode = 0;
        if (mCanvasView != null) mCanvasView.invalidate();
    }

    // ── Drawing ───────────────────────────────────────────

    private static int sDrawCount = 0;
    private void drawHUD(Canvas canvas, int w, int h) {
        if (sDrawCount++ % 60 == 0) {
            android.util.Log.e("ComboHUD", "drawHUD #" + sDrawCount + " w=" + w + " h=" + h);
        }
        float cx = w / 2f;
        float cy = h / 2f;
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

        RectF outerRect = new RectF(cx - outerR, cy - outerR, cx + outerR, cy + outerR);
        RectF innerRect = new RectF(cx - innerR, cy - innerR, cx + innerR, cy + innerR);

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

            Path wedge = new Path();
            wedge.arcTo(outerRect, startAngle, sweep, true);
            wedge.arcTo(innerRect, startAngle + sweep, -sweep);
            wedge.close();
            canvas.drawPath(wedge, mWedgePaint);

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
        for (int i = 0; i < count; i++) {
            int node = nodes[i];
            int hits = mHitCounts[node];
            if (hits <= 0) continue;
            float startAngle = -(i * stepDeg) - halfWidth;
            float midAngle = (float) Math.toRadians(startAngle + halfWidth);
            float dx = cx + dotR * (float) Math.cos(midAngle);
            float dy = cy + dotR * (float) Math.sin(midAngle);

            float baseR = outerR * 0.06f;
            float r = baseR + baseR * 0.5f * (Math.min(hits, 5) - 1);  // 1→baseR, 5→3×baseR

            // Filled accent dot
            mWedgePaint.setColor(mColorAccent);
            mWedgePaint.setAlpha(255);
            canvas.drawCircle(dx, dy, r, mWedgePaint);

            // For re-strikes, add concentric rings — one per extra hit, up to 3.
            if (hits > 1) {
                mRingPaint.setStrokeWidth(2.0f);
                mRingPaint.setAlpha(200);
                for (int k = 1; k < Math.min(hits, 4); k++) {
                    canvas.drawCircle(dx, dy, r + k * baseR * 0.45f, mRingPaint);
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
    }
}
