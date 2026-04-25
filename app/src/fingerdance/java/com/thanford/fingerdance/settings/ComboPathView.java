/*
 * Copyright (c) 2026 Thanford. All rights reserved.
 *
 * Proprietary FingerDance component. Not licensed under the MPL 2.0 that
 * covers the surrounding Wolvic files.
 */
package com.thanford.fingerdance.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.igalia.wolvic.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only path disc for combo action blocks in Combos Settings.
 *
 * <p>Draws a circular ring (the joystick sweep area) and overlays:
 * <ul>
 *   <li>Path arcs from node to node along the ring (shorter-arc direction).
 *       Diametrically opposite nodes (e.g. 2-8, 4-6) draw a straight diameter
 *       line through the center instead.</li>
 *   <li>Start node: filled yellow dot; subsequent nodes: hollow ring.</li>
 *   <li>Multi-hit: 240° loop arc centered ON the node position + filled arrowhead
 *       at the arc end + "N×" badge just inside the ring.</li>
 *   <li>Serpentine ABABABAB… paths (length ≥ 4): converging quadratic bezier
 *       curves, opacity 0.14 (oldest) → 1.0 (newest).</li>
 *   <li>Unbound (null path): dim ring only.</li>
 * </ul>
 *
 * <p>All drawing is clipped to the disc boundary.
 *
 * <p>Node → angle (Android canvas, 0° = right, clockwise):
 * <pre>
 *   7(225°) 2(270°) 9(315°)
 *   4(180°)   5     6(  0°)
 *   1(135°) 8( 90°) 3( 45°)
 * </pre>
 * Push up = node 2 = top of disc; push right = node 6 = right of disc.
 */
public final class ComboPathView extends View {

    private static final float[] NODE_ANGLE_DEG = new float[10];
    static {
        NODE_ANGLE_DEG[2] = 270f; // up
        NODE_ANGLE_DEG[4] = 180f; // left
        NODE_ANGLE_DEG[6] =   0f; // right
        NODE_ANGLE_DEG[8] =  90f; // down
        NODE_ANGLE_DEG[1] = 135f; // down-left
        NODE_ANGLE_DEG[3] =  45f; // down-right
        NODE_ANGLE_DEG[7] = 225f; // up-left
        NODE_ANGLE_DEG[9] = 315f; // up-right
    }

    private @Nullable int[] mPath;

    private final Paint mRingPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mArcPaint    = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mHollowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLoopPaint   = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBadgePaint  = new Paint(Paint.ANTI_ALIAS_FLAG);

    public ComboPathView(Context context) {
        super(context);
        init(context);
    }

    public ComboPathView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    private void init(Context ctx) {
        int accent = ContextCompat.getColor(ctx, R.color.fd_accent);

        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setColor(accent);

        mArcPaint.setStyle(Paint.Style.STROKE);
        mArcPaint.setColor(accent);
        mArcPaint.setStrokeCap(Paint.Cap.ROUND);

        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(accent);

        mHollowPaint.setStyle(Paint.Style.STROKE);
        mHollowPaint.setColor(accent);

        mLoopPaint.setStyle(Paint.Style.STROKE);
        mLoopPaint.setColor(accent);
        mLoopPaint.setStrokeCap(Paint.Cap.ROUND);

        mBadgePaint.setStyle(Paint.Style.FILL);
        mBadgePaint.setColor(accent);
        mBadgePaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setPath(@Nullable int[] path) {
        mPath = (path != null && path.length > 0) ? path : null;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int w = MeasureSpec.getSize(widthSpec);
        int h = MeasureSpec.getSize(heightSpec);
        int s = (w > 0 && h > 0)
                ? Math.min(w, h)
                : (int) (72f * getResources().getDisplayMetrics().density);
        setMeasuredDimension(s, s);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float size  = (float) Math.min(getWidth(), getHeight());
        float cx    = size * 0.5f;
        float cy    = size * 0.5f;
        float ringR = size * 0.36f;

        float ringStroke = Math.max(1f,   size * 0.018f);
        float arcStroke  = Math.max(1.5f, size * 0.025f);
        float nodeR      = size * 0.056f;
        float nodeStroke = Math.max(1f,   size * 0.018f);
        float loopR      = size * 0.10f;
        float loopStroke = Math.max(1.5f, size * 0.022f);
        float badgeSp    = size * 0.11f;

        // Clip to disc boundary.
        int saveCount = canvas.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, size * 0.5f - 1f, Path.Direction.CW);
        canvas.clipPath(clip);

        // Dim guide ring.
        mRingPaint.setStrokeWidth(ringStroke);
        mRingPaint.setAlpha(55);
        canvas.drawCircle(cx, cy, ringR, mRingPaint);

        if (mPath != null) {
            RectF ringRect = new RectF(cx - ringR, cy - ringR, cx + ringR, cy + ringR);
            int[]   multiHit   = buildMultiHitCounts(mPath);
            boolean serpentine = isSerpentinePattern(mPath);
            int[]   deduped    = deduplicateConsecutive(mPath);

            // --- Arc segments ---
            mArcPaint.setStrokeWidth(arcStroke);
            if (serpentine) {
                drawSerpentineArcs(canvas, mPath, cx, cy, ringR, arcStroke);
            } else {
                mArcPaint.setAlpha(255);
                for (int i = 0; i + 1 < deduped.length; i++) {
                    drawPathArc(canvas, deduped[i], deduped[i + 1],
                                cx, cy, ringR, ringRect, mArcPaint);
                }
            }

            // --- Multi-hit loop arcs + arrowheads + badges ---
            mLoopPaint.setStrokeWidth(loopStroke);
            mLoopPaint.setAlpha(255);
            for (int n = 1; n <= 9; n++) {
                if (n == 5 || multiHit[n] < 2) continue;
                drawLoopArc(canvas, n, cx, cy, ringR, loopR);
                drawBadge(canvas, n, cx, cy, ringR, loopR, badgeSp, multiHit[n]);
            }

            // --- Node dots (start = filled, others = hollow) ---
            mFillPaint.setAlpha(255);
            mHollowPaint.setStrokeWidth(nodeStroke);
            mHollowPaint.setAlpha(255);
            boolean isFirst = true;
            boolean[] drawn = new boolean[10];
            for (int n : deduped) {
                if (!validNode(n) || drawn[n]) continue;
                drawn[n] = true;
                PointF p = nodePoint(n, cx, cy, ringR);
                canvas.drawCircle(p.x, p.y, nodeR, isFirst ? mFillPaint : mHollowPaint);
                isFirst = false;
            }
        }

        canvas.restoreToCount(saveCount);
    }

    // ── Drawing ──────────────────────────────────────────────────────────────────

    private void drawPathArc(Canvas canvas, int from, int to,
                             float cx, float cy, float ringR,
                             RectF ring, Paint paint) {
        if (!validNode(from) || !validNode(to)) return;
        float start = NODE_ANGLE_DEG[from];
        float diff  = ((NODE_ANGLE_DEG[to] - start) % 360f + 360f) % 360f;

        if (Math.abs(diff - 180f) < 0.5f) {
            // Diametrically opposite nodes (2-8, 4-6, 1-9, 3-7):
            // draw a straight diameter line through the center.
            PointF p1 = nodePoint(from, cx, cy, ringR);
            PointF p2 = nodePoint(to,   cx, cy, ringR);
            canvas.drawLine(p1.x, p1.y, p2.x, p2.y, paint);
            return;
        }

        float sweep = diff <= 180f ? diff : diff - 360f; // shorter arc
        canvas.drawArc(ring, start, sweep, false, paint);
    }

    private void drawLoopArc(Canvas canvas, int node,
                             float cx, float cy, float ringR, float loopR) {
        float nodeRad = (float) Math.toRadians(NODE_ANGLE_DEG[node]);
        // Loop circle centered ON the node position (on the ring).
        float lcx = cx + ringR * (float) Math.cos(nodeRad);
        float lcy = cy + ringR * (float) Math.sin(nodeRad);

        // Gap faces the disc center (inward); arc sweeps 240° outward.
        float inward   = (NODE_ANGLE_DEG[node] + 180f) % 360f;
        float arcStart = (inward + 60f) % 360f;
        float arcEnd   = (arcStart + 240f) % 360f;

        RectF rect = new RectF(lcx - loopR, lcy - loopR, lcx + loopR, lcy + loopR);
        canvas.drawArc(rect, arcStart, 240f, false, mLoopPaint);

        // Arrowhead at arc end, pointing in the direction of CW travel.
        float arcEndRad = (float) Math.toRadians(arcEnd);
        float tipX = lcx + loopR * (float) Math.cos(arcEndRad);
        float tipY = lcy + loopR * (float) Math.sin(arcEndRad);
        // CW tangent at arcEndRad: (-sin, cos) in screen-coord (y-down) space.
        float tanX = -(float) Math.sin(arcEndRad);
        float tanY =  (float) Math.cos(arcEndRad);
        // Radial (outward) at arcEndRad.
        float radX = (float) Math.cos(arcEndRad);
        float radY = (float) Math.sin(arcEndRad);
        float arrowLen  = loopR * 0.55f;
        float arrowHalf = loopR * 0.32f;
        float bx = tipX - tanX * arrowLen;
        float by = tipY - tanY * arrowLen;

        Path arrow = new Path();
        arrow.moveTo(tipX, tipY);
        arrow.lineTo(bx + radX * arrowHalf, by + radY * arrowHalf);
        arrow.lineTo(bx - radX * arrowHalf, by - radY * arrowHalf);
        arrow.close();
        mFillPaint.setAlpha(255);
        canvas.drawPath(arrow, mFillPaint);
    }

    private void drawBadge(Canvas canvas, int node,
                           float cx, float cy, float ringR, float loopR,
                           float sp, int count) {
        float nodeRad = (float) Math.toRadians(NODE_ANGLE_DEG[node]);
        // Badge slightly inside the ring (in the gap of the loop arc).
        float dist = ringR - loopR * 0.55f;
        float bx = cx + dist * (float) Math.cos(nodeRad);
        float by = cy + dist * (float) Math.sin(nodeRad);
        mBadgePaint.setTextSize(sp);
        mBadgePaint.setAlpha(255);
        canvas.drawText(count + "×", bx, by + sp * 0.38f, mBadgePaint);
    }

    private void drawSerpentineArcs(Canvas canvas, int[] path,
                                    float cx, float cy, float ringR, float strokeW) {
        PointF pa = nodePoint(path[0], cx, cy, ringR);
        PointF pb = nodePoint(path[1], cx, cy, ringR);
        float mx = (pa.x + pb.x) * 0.5f;
        float my = (pa.y + pb.y) * 0.5f;
        float dx = pb.x - pa.x, dy = pb.y - pa.y;
        float len = (float) Math.sqrt(dx * dx + dy * dy);
        float px = (len > 0f) ? -dy / len : 0f;
        float py = (len > 0f) ?  dx / len : 1f;

        int nArcs = path.length - 1;
        for (int i = 0; i < nArcs; i++) {
            float t      = nArcs > 1 ? (float) i / (nArcs - 1) : 1f;
            float alpha  = 0.14f + 0.86f * t;
            float offset = ringR * (0.55f - 0.40f * t);
            int   side   = (i % 2 == 0) ? 1 : -1;

            PointF pf = nodePoint(path[i],     cx, cy, ringR);
            PointF pt = nodePoint(path[i + 1], cx, cy, ringR);

            Path bezier = new Path();
            bezier.moveTo(pf.x, pf.y);
            bezier.quadTo(mx + px * offset * side,
                          my + py * offset * side,
                          pt.x, pt.y);

            mArcPaint.setAlpha((int) (255 * alpha));
            mArcPaint.setStrokeWidth(strokeW);
            canvas.drawPath(bezier, mArcPaint);
        }
        mArcPaint.setAlpha(255);
    }

    // ── Analysis ─────────────────────────────────────────────────────────────────

    private static int[] buildMultiHitCounts(int[] path) {
        int[] counts = new int[10];
        int i = 0;
        while (i < path.length) {
            int n = path[i];
            if (n >= 1 && n <= 9) {
                int j = i + 1;
                while (j < path.length && path[j] == n) j++;
                if (j - i > counts[n]) counts[n] = j - i;
                i = j;
            } else {
                i++;
            }
        }
        return counts;
    }

    private static boolean isSerpentinePattern(int[] path) {
        if (path.length < 4) return false;
        int a = path[0], b = path[1];
        if (a == b) return false;
        for (int i = 2; i < path.length; i++) {
            if (path[i] != (i % 2 == 0 ? a : b)) return false;
        }
        return true;
    }

    @NonNull
    private static int[] deduplicateConsecutive(int[] path) {
        if (path.length == 0) return path;
        List<Integer> out = new ArrayList<>();
        out.add(path[0]);
        for (int i = 1; i < path.length; i++) {
            if (path[i] != path[i - 1]) out.add(path[i]);
        }
        int[] arr = new int[out.size()];
        for (int i = 0; i < out.size(); i++) arr[i] = out.get(i);
        return arr;
    }

    private static PointF nodePoint(int node, float cx, float cy, float r) {
        float rad = (float) Math.toRadians(NODE_ANGLE_DEG[node]);
        return new PointF(cx + r * (float) Math.cos(rad),
                          cy + r * (float) Math.sin(rad));
    }

    private static boolean validNode(int n) {
        return n >= 1 && n <= 9 && n != 5;
    }
}
